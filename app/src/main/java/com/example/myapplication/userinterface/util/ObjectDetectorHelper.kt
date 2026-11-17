package com.example.myapplication.userinterface.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Helper de detecção universal que usa APENAS o Interpreter direto do TFLite.
 * Suporta automaticamente:
 * 1. Modelos YOLO raw (`[1, 4+C, anchors]` ou `[1, anchors, 4+C]`) — YOLOv5/v8/v11/v12.
 * 2. Modelos YOLO End-to-End com NMS embutido (`[1, N, 6]` = `[x1, y1, x2, y2, score, classId]`).
 * 3. Modelos SSD MobileNet / TFLite PostProcess (4 tensores de saída).
 * 4. Tensores FLOAT32, UINT8 e INT8 com dequantização automática e extração de labels Ultralytics.
 */
class ObjectDetectorHelper(
    val context: Context,
    var detectorListener: DetectorListener?,
    var modelSource: ModelSource = ModelSource.Asset("detector.tflite"),
    var scoreThreshold: Float = 0.30f,
    var maxResults: Int = 25,
    var defaultLabel: String = "objeto",
    var customLabels: List<String> = emptyList()
) {
    sealed class ModelSource {
        data class Asset(val fileName: String) : ModelSource()
        data class External(val uri: Uri, val displayName: String) : ModelSource()
        data class InternalFile(val path: String, val displayName: String) : ModelSource()
    }

    private val lock = Any()
    private var isClosed = false
    private var interpreter: Interpreter? = null
    private var taskObjectDetector: ObjectDetector? = null

    private enum class ModelArchitecture {
        SSD_TASK_VISION,  // Modelo SSD/EfficientDet com metadados oficiais (Google Task Vision)
        SSD_POST_PROCESS, // Modelo de 4 tensores de saída via Interpreter direto
        YOLO_RAW,         // Modelo de saída única com âncoras brutas
        YOLO_END2END_NMS  // Modelo YOLO exportado com nms=True ([1, N, 6])
    }

    private var architecture = ModelArchitecture.YOLO_RAW

    // Configuração de entrada
    private var inputWidth = 640
    private var inputHeight = 640
    private var inputDataType = DataType.FLOAT32
    private var quantByteLut: ByteArray? = null

    // Configuração de saída para YOLO
    private var outputDataType = DataType.FLOAT32
    private var outQuantScale = 1f
    private var outQuantZeroPoint = 0

    // Buffers reutilizáveis para zerar GC churn por frame
    private var letterboxBitmap: Bitmap? = null
    private var letterboxPixels: IntArray? = null
    private var inputByteBuffer: ByteBuffer? = null
    private var inputFloatView: java.nio.FloatBuffer? = null
    private var inputFloatArray: FloatArray? = null
    private var inputByteArray: ByteArray? = null
    private var cachedOutDim1 = 0
    private var cachedOutDim2 = 0
    private var cachedOutIsQuant = false
    private var reusableFloatOut: Array<Array<FloatArray>>? = null
    private var reusableByteOut: Array<Array<ByteArray>>? = null
    private var reusableDeqOut: Array<FloatArray>? = null
    private val letterboxMatrix = Matrix()
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Índices e dimensões para SSD (4 saídas)
    private var ssdNumDetections = 10
    private var ssdBoxIndex = 0
    private var ssdClassIndex = 1
    private var ssdScoreIndex = 2
    private var ssdCountIndex = 3
    private var ssdAmbiguousShape2Pair: Pair<Int, Int>? = null

    // Configurações para YOLO
    private var yoloNumAnchors = 8400
    private var yoloNumClasses = 1
    private var yoloTransposed = true

    // Rótulos do modelo
    private val modelLabels = mutableListOf<String>()

    var ultimoErro: String? = null
        private set

    @Volatile
    var lastBitmapWidth: Float = 1f
        private set
    @Volatile
    var lastBitmapHeight: Float = 1f
        private set

    private var loadedPath: String? = null

    init {
        setup()
    }

    fun setListener(l: DetectorListener?) {
        synchronized(lock) {
            detectorListener = l
        }
    }

    fun reloadModel(source: ModelSource, threshold: Float = scoreThreshold, maxRes: Int = maxResults) {
        synchronized(lock) {
            close()
            isClosed = false
            modelSource = source
            scoreThreshold = threshold
            maxResults = maxRes
            ultimoErro = null
            loadedPath = null
            setup()
        }
    }

    fun fechar() = synchronized(lock) {
        isClosed = true
        close()
    }

    private fun setup() {
        val file = resolveFile() ?: return
        if (file.absolutePath == loadedPath && interpreter != null) return

        close()

        try {
            // 3 threads + XNNPACK entregam inferência rápida mantendo núcleos livres para câmera 60 FPS e UI
            val options = Interpreter.Options().apply {
                setNumThreads(3)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(file, options)

            // Inspeciona tensor de entrada
            val inTensor = interpreter!!.getInputTensor(0)
            val inShape = inTensor.shape()
            inputDataType = inTensor.dataType()
            quantByteLut = buildQuantLutIfNeeded(inputDataType, inTensor.quantizationParams())
            inputHeight = if (inShape.size >= 2 && inShape[1] > 0) inShape[1] else 640
            inputWidth = if (inShape.size >= 3 && inShape[2] > 0) inShape[2] else 640

            val numOutputs = interpreter!!.outputTensorCount
            Log.d("Detector", "Modelo carregado: input=${inShape.toList()} type=$inputDataType outputs=$numOutputs size=${file.length()}")

            // Tenta extrair rótulos embutidos (ZIP Metadata ou FlatBuffer Ultralytics)
            extractLabelsFromModelFile(file)

            if (numOutputs >= 4) {
                identifySsdOutputs()
                val tvDet = try {
                    val tvOpts = ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setNumThreads(3).build())
                        .setScoreThreshold(min(scoreThreshold, 0.15f))
                        .setMaxResults(max(maxResults, 25))
                        .build()
                    ObjectDetector.createFromFileAndOptions(file, tvOpts)
                } catch (t: Throwable) {
                    Log.w("Detector", "TaskVision indisponível (${t.message}), usando Interpreter fallback")
                    null
                }
                if (tvDet != null) {
                    taskObjectDetector = tvDet
                    architecture = ModelArchitecture.SSD_TASK_VISION
                    Log.d("Detector", "Arquitetura identificada: SSD TaskVision Metadata ($inputWidth x $inputHeight)")
                } else {
                    architecture = ModelArchitecture.SSD_POST_PROCESS
                    Log.d("Detector", "Arquitetura identificada: SSD (boxes=$ssdBoxIndex, classes=$ssdClassIndex, scores=$ssdScoreIndex, count=$ssdCountIndex, maxDets=$ssdNumDetections)")
                }
            } else {
                val outTensor = interpreter!!.getOutputTensor(0)
                val outShape = outTensor.shape()
                outputDataType = outTensor.dataType()
                val outQuant = outTensor.quantizationParams()
                outQuantScale = if (outQuant.scale > 0f) outQuant.scale else 1f
                outQuantZeroPoint = outQuant.zeroPoint
                Log.d("Detector", "YOLO outShape=${outShape.toList()} outType=$outputDataType scale=$outQuantScale zp=$outQuantZeroPoint")

                if (outShape.size == 3 && outShape[2] == 6 && outShape[1] in 1..1000) {
                    architecture = ModelArchitecture.YOLO_END2END_NMS
                    yoloTransposed = false
                    yoloNumAnchors = outShape[1]
                    yoloNumClasses = max(1, modelLabels.size.coerceAtLeast(customLabels.size))
                } else {
                    architecture = ModelArchitecture.YOLO_RAW
                    when {
                        // [1, 4+C, anchors] (ex: YOLOv8 padrão 1x5x8400)
                        outShape.size == 3 && outShape[1] in 5..300 && outShape[2] > outShape[1] -> {
                            yoloTransposed = true
                            yoloNumClasses = outShape[1] - 4
                            yoloNumAnchors = outShape[2]
                        }
                        // [1, anchors, 4+C]
                        outShape.size == 3 && outShape[2] in 5..300 && outShape[1] > outShape[2] -> {
                            yoloTransposed = false
                            yoloNumAnchors = outShape[1]
                            yoloNumClasses = outShape[2] - 4
                        }
                        else -> {
                            yoloTransposed = true
                            yoloNumAnchors = if (outShape.size >= 3) outShape[2] else 8400
                            yoloNumClasses = if (outShape.size >= 2) max(1, outShape[1] - 4) else 1
                        }
                    }
                }
                Log.d("Detector", "YOLO configurado ($architecture): anchors=$yoloNumAnchors, classes=$yoloNumClasses, transposto=$yoloTransposed")
            }

            loadedPath = file.absolutePath
            ultimoErro = null

        } catch (e: Exception) {
            ultimoErro = "Erro ao carregar modelo: ${e.message?.take(200)}"
            Log.e("Detector", ultimoErro!!, e)
            detectorListener?.onError(ultimoErro!!)
        }
    }

    private fun identifySsdOutputs() {
        val count = interpreter!!.outputTensorCount
        val shape2Indices = mutableListOf<Int>()
        var scoreByName = -1
        var classByName = -1
        var classByInt32 = -1
        ssdAmbiguousShape2Pair = null

        for (i in 0 until count) {
            val t = interpreter!!.getOutputTensor(i)
            val shape = t.shape()
            val name = t.name().lowercase()

            when {
                // Locations: [1, N, 4]
                shape.size == 3 && shape[2] == 4 -> {
                    ssdBoxIndex = i
                    ssdNumDetections = shape[1]
                }
                // Locations transposto: [1, 4, N]
                shape.size == 3 && shape[1] == 4 -> {
                    ssdBoxIndex = i
                    ssdNumDetections = shape[2]
                }
                // Contagem total de detecções: [1]
                shape.size == 1 && shape[0] == 1 -> {
                    ssdCountIndex = i
                }
                // Classes ou Scores: [1, N]
                shape.size == 2 -> {
                    shape2Indices.add(i)
                    if (name.contains("score")) {
                        scoreByName = i
                    } else if (name.contains("class") || name.contains("category")) {
                        classByName = i
                    } else if (t.dataType() == DataType.INT32) {
                        classByInt32 = i
                    }
                }
            }
        }

        when {
            scoreByName >= 0 && classByName >= 0 -> {
                ssdScoreIndex = scoreByName
                ssdClassIndex = classByName
            }
            classByInt32 >= 0 && shape2Indices.size == 2 -> {
                ssdClassIndex = classByInt32
                ssdScoreIndex = shape2Indices.first { it != classByInt32 }
            }
            shape2Indices.size == 2 -> {
                // Ordem típica de StatefulPartitionedCall (EfficientDet) = [scores, classes]; verificada dinamicamente na 1ª detecção
                ssdScoreIndex = shape2Indices[0]
                ssdClassIndex = shape2Indices[1]
                ssdAmbiguousShape2Pair = shape2Indices[0] to shape2Indices[1]
            }
        }
    }

    private fun buildQuantLutIfNeeded(
        type: DataType,
        quant: org.tensorflow.lite.Tensor.QuantizationParams
    ): ByteArray? = buildQuantLutForParams(type, quant.scale, quant.zeroPoint)

    fun obterLabelsModelo(): List<String> = synchronized(lock) { modelLabels.toList() }

    private fun extractLabelsFromModelFile(file: File) {
        modelLabels.clear()
        modelLabels.addAll(extractLabelsFromFile(file))
    }

    fun detect(image: Bitmap, rotationDegrees: Int = 0) {
        val resultado = executarInferencia(image, rotationDegrees) ?: return
        val listener = synchronized(lock) { detectorListener }
        listener?.onResults(resultado)
    }

    /**
     * Executa inferência de forma síncrona diretamente sobre um Bitmap (ex: foto estática em alta resolução na Tela de Revisão).
     * É thread-safe e reutiliza a calibração geométrica isomórfica (Letterbox) e NMS.
     */
    fun detectarSincrono(image: Bitmap, rotationDegrees: Int = 0): ResultadoDeteccao? {
        return executarInferencia(image, rotationDegrees)
    }

    private fun executarInferencia(image: Bitmap, rotationDegrees: Int = 0): ResultadoDeteccao? = synchronized(lock) {
        if (isClosed) return null

        val rawW = image.width.toFloat()
        val rawH = image.height.toFloat()
        if (rawW <= 0f || rawH <= 0f) return null

        val normRot = ((rotationDegrees % 360) + 360) % 360
        val imgW = if (normRot == 90 || normRot == 270) rawH else rawW
        val imgH = if (normRot == 90 || normRot == 270) rawW else rawH
        lastBitmapWidth = imgW
        lastBitmapHeight = imgH

        val interp = interpreter ?: run { setup(); interpreter ?: return null }
        val finalDetections = mutableListOf<Detection>()

        try {
            // 1. Prepara buffer de entrada via Letterbox Isomórfico (e rotação em 1 único passo sem copiar bitmap)
            val scale = min(inputWidth.toFloat() / imgW, inputHeight.toFloat() / imgH)
            val newW = kotlin.math.round(imgW * scale).toInt().coerceAtLeast(1)
            val newH = kotlin.math.round(imgH * scale).toInt().coerceAtLeast(1)
            val padX = (inputWidth - newW) / 2f
            val padY = (inputHeight - newH) / 2f

            var lbBitmap = letterboxBitmap
            if (lbBitmap == null || lbBitmap.width != inputWidth || lbBitmap.height != inputHeight) {
                lbBitmap?.recycle()
                lbBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
                letterboxBitmap = lbBitmap
            }

            val canvas = Canvas(lbBitmap)
            // Cor padrão cinza neutro do Ultralytics YOLO: RGB(114, 114, 114)
            canvas.drawColor(Color.rgb(114, 114, 114))
            if (normRot == 0) {
                val destRect = RectF(padX, padY, padX + newW, padY + newH)
                canvas.drawBitmap(image, null, destRect, letterboxPaint)
            } else {
                letterboxMatrix.reset()
                letterboxMatrix.postTranslate(-rawW / 2f, -rawH / 2f)
                letterboxMatrix.postRotate(normRot.toFloat())
                letterboxMatrix.postTranslate(imgW / 2f, imgH / 2f)
                letterboxMatrix.postScale(scale, scale)
                letterboxMatrix.postTranslate(padX, padY)
                canvas.drawBitmap(image, letterboxMatrix, letterboxPaint)
            }

            if (architecture == ModelArchitecture.SSD_TASK_VISION && taskObjectDetector != null) {
                try {
                    val tvResults = taskObjectDetector!!.detect(TensorImage.fromBitmap(lbBitmap))
                    for (tvDet in tvResults) {
                        val cat = tvDet.categories.firstOrNull() ?: continue
                        val score = cat.score
                        if (score < scoreThreshold) continue
                        val r = tvDet.boundingBox
                        val left   = ((r.left - padX) / scale).coerceIn(0f, imgW)
                        val top    = ((r.top - padY) / scale).coerceIn(0f, imgH)
                        val right  = ((r.right - padX) / scale).coerceIn(0f, imgW)
                        val bottom = ((r.bottom - padY) / scale).coerceIn(0f, imgH)
                        if (right <= left || bottom <= top) continue
                        val label = cat.label.trim().takeIf { it.isNotBlank() && it != "???" }
                            ?: resolverRotuloClasse(cat.index)
                        finalDetections.add(
                            Detection.create(
                                RectF(left, top, right, bottom),
                                listOf(Category.create(label, label, score))
                            )
                        )
                        if (finalDetections.size >= maxResults) break
                    }
                    Log.d("Detector", "Detecções TaskVision encontradas: ${finalDetections.size}")
                    return@synchronized ResultadoDeteccao(finalDetections, imgW, imgH)
                } catch (t: Throwable) {
                    Log.w("Detector", "Falha TaskVision em frame, alternando para Interpreter: ${t.message}")
                    try { taskObjectDetector?.close() } catch (_: Exception) {}
                    taskObjectDetector = null
                    architecture = ModelArchitecture.SSD_POST_PROCESS
                }
            }

            var pixels = letterboxPixels
            val totalPixels = inputWidth * inputHeight
            if (pixels == null || pixels.size != totalPixels) {
                pixels = IntArray(totalPixels)
                letterboxPixels = pixels
            }
            lbBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

            val lut = quantByteLut
            val bytesPerChannel = if (lut != null) 1 else 4
            val bufferCapacity = totalPixels * 3 * bytesPerChannel
            var buf = inputByteBuffer
            if (buf == null || buf.capacity() != bufferCapacity) {
                buf = ByteBuffer.allocateDirect(bufferCapacity).order(ByteOrder.nativeOrder())
                inputByteBuffer = buf
                inputFloatView = if (lut == null) buf.asFloatBuffer() else null
            } else {
                buf.rewind()
            }

            val neededChannels = totalPixels * 3
            if (lut != null) {
                var bArr = inputByteArray
                if (bArr == null || bArr.size != neededChannels) {
                    bArr = ByteArray(neededChannels)
                    inputByteArray = bArr
                }
                var outIdx = 0
                for (px in pixels) {
                    bArr[outIdx++] = lut[(px shr 16) and 0xFF]
                    bArr[outIdx++] = lut[(px shr 8)  and 0xFF]
                    bArr[outIdx++] = lut[px          and 0xFF]
                }
                buf.put(bArr)
                buf.rewind()
            } else {
                var fArr = inputFloatArray
                if (fArr == null || fArr.size != neededChannels) {
                    fArr = FloatArray(neededChannels)
                    inputFloatArray = fArr
                }
                var outIdx = 0
                for (px in pixels) {
                    fArr[outIdx++] = FLOAT_NORM_LUT[(px shr 16) and 0xFF]
                    fArr[outIdx++] = FLOAT_NORM_LUT[(px shr 8)  and 0xFF]
                    fArr[outIdx++] = FLOAT_NORM_LUT[px          and 0xFF]
                }
                val fView = inputFloatView ?: buf.asFloatBuffer().also { inputFloatView = it }
                fView.rewind()
                fView.put(fArr)
                buf.rewind()
            }

            // 2. Executa inferência de acordo com a arquitetura
            if (architecture == ModelArchitecture.SSD_POST_PROCESS) {
                val maxDets = ssdNumDetections.coerceAtLeast(1)
                val locations = Array(1) { Array(maxDets) { FloatArray(4) } }
                val classes = Array(1) { FloatArray(maxDets) }
                val scores = Array(1) { FloatArray(maxDets) }
                val count = FloatArray(1)

                val outputMap = HashMap<Int, Any>()
                outputMap[ssdBoxIndex] = locations
                outputMap[ssdClassIndex] = classes
                outputMap[ssdScoreIndex] = scores
                outputMap[ssdCountIndex] = count

                interp.runForMultipleInputsOutputs(arrayOf(buf), outputMap)

                val numDetected = min(count[0].toInt(), maxDets).coerceAtLeast(0)
                var activeScores = scores[0]
                var activeClasses = classes[0]
                if (ssdAmbiguousShape2Pair != null && numDetected > 0) {
                    val sOverflowOrInt = (0 until numDetected).any { activeScores[it] > 1.001f }
                    val cHasFraction = (0 until numDetected).any {
                        val v = activeClasses[it]
                        v > 0.001f && v < 0.999f
                    }
                    if (sOverflowOrInt || cHasFraction) {
                        val tmpIdx = ssdScoreIndex
                        ssdScoreIndex = ssdClassIndex
                        ssdClassIndex = tmpIdx
                        activeScores = classes[0]
                        activeClasses = scores[0]
                    }
                    ssdAmbiguousShape2Pair = null
                }

                for (i in 0 until numDetected) {
                    val score = activeScores[i]
                    if (score < scoreThreshold) continue

                    val classIdx = activeClasses[i].toInt()
                    val ymin = locations[0][i][0]
                    val xmin = locations[0][i][1]
                    val ymax = locations[0][i][2]
                    val xmax = locations[0][i][3]

                    val x1Tensor = xmin * inputWidth
                    val y1Tensor = ymin * inputHeight
                    val x2Tensor = xmax * inputWidth
                    val y2Tensor = ymax * inputHeight

                    val left   = ((x1Tensor - padX) / scale).coerceIn(0f, imgW)
                    val top    = ((y1Tensor - padY) / scale).coerceIn(0f, imgH)
                    val right  = ((x2Tensor - padX) / scale).coerceIn(0f, imgW)
                    val bottom = ((y2Tensor - padY) / scale).coerceIn(0f, imgH)

                    if (right <= left || bottom <= top) continue

                    val label = resolverRotuloClasse(classIdx)

                    finalDetections.add(
                        Detection.create(
                            RectF(left, top, right, bottom),
                            listOf(Category.create(label, label, score))
                        )
                    )

                    if (finalDetections.size >= maxResults) break
                }

            } else if (architecture == ModelArchitecture.YOLO_END2END_NMS) {
                val matrix = runYoloOutputMatrix(interp, buf, yoloNumAnchors, 6)
                val decoded = decodeYoloEnd2EndNms(
                    out = matrix,
                    numRows = yoloNumAnchors,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    scoreThreshold = scoreThreshold
                )
                val nmsResult = nmsMultiClasse(decoded).take(maxResults)
                finalDetections.addAll(
                    buildYoloDetections(
                        boxes = nmsResult,
                        pixels = pixels,
                        padX = padX,
                        padY = padY,
                        scale = scale,
                        imgW = imgW,
                        imgH = imgH
                    )
                )
            } else {
                // Inferência YOLO raw (FLOAT32, UINT8 ou INT8)
                val dim1 = if (yoloTransposed) 4 + yoloNumClasses else yoloNumAnchors
                val dim2 = if (yoloTransposed) yoloNumAnchors else 4 + yoloNumClasses
                val matrix = runYoloOutputMatrix(interp, buf, dim1, dim2)

                val candidates = decodeYoloRawCandidates(
                    out = matrix,
                    numAnchors = yoloNumAnchors,
                    numClasses = yoloNumClasses,
                    transposed = yoloTransposed,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    scoreThreshold = scoreThreshold
                )

                val nmsResult = nmsMultiClasse(candidates).take(maxResults)
                finalDetections.addAll(
                    buildYoloDetections(
                        boxes = nmsResult,
                        pixels = pixels,
                        padX = padX,
                        padY = padY,
                        scale = scale,
                        imgW = imgW,
                        imgH = imgH
                    )
                )
            }

            Log.d("Detector", "Detecções encontradas: ${finalDetections.size}")
            ResultadoDeteccao(finalDetections, imgW, imgH)

        } catch (e: Exception) {
            Log.e("Detector", "Erro na detecção: ${e.message}", e)
            ResultadoDeteccao(emptyList(), imgW, imgH)
        }
    }

    private fun runYoloOutputMatrix(
        interp: Interpreter,
        inputBuf: ByteBuffer,
        dim1: Int,
        dim2: Int
    ): Array<FloatArray> {
        val isQuant = outputDataType == DataType.UINT8 || outputDataType == DataType.INT8
        if (isQuant) {
            var byteOut = reusableByteOut
            var deqOut = reusableDeqOut
            if (byteOut == null || deqOut == null || !cachedOutIsQuant || cachedOutDim1 != dim1 || cachedOutDim2 != dim2) {
                byteOut = Array(1) { Array(dim1) { ByteArray(dim2) } }
                deqOut = Array(dim1) { FloatArray(dim2) }
                reusableByteOut = byteOut
                reusableDeqOut = deqOut
                reusableFloatOut = null
                cachedOutIsQuant = true
                cachedOutDim1 = dim1
                cachedOutDim2 = dim2
            }
            interp.run(inputBuf, byteOut)
            val isUint8 = outputDataType == DataType.UINT8
            val zp = outQuantZeroPoint
            val scale = outQuantScale
            val src = byteOut[0]
            for (r in 0 until dim1) {
                val srcRow = src[r]
                val dstRow = deqOut[r]
                for (c in 0 until dim2) {
                    val q = if (isUint8) (srcRow[c].toInt() and 0xFF) else srcRow[c].toInt()
                    dstRow[c] = (q - zp) * scale
                }
            }
            return deqOut
        }
        var floatOut = reusableFloatOut
        if (floatOut == null || cachedOutIsQuant || cachedOutDim1 != dim1 || cachedOutDim2 != dim2) {
            floatOut = Array(1) { Array(dim1) { FloatArray(dim2) } }
            reusableFloatOut = floatOut
            reusableByteOut = null
            reusableDeqOut = null
            cachedOutIsQuant = false
            cachedOutDim1 = dim1
            cachedOutDim2 = dim2
        }
        interp.run(inputBuf, floatOut)
        return floatOut[0]
    }

    private fun buildYoloDetections(
        boxes: List<FloatArray>,
        pixels: IntArray,
        padX: Float,
        padY: Float,
        scale: Float,
        imgW: Float,
        imgH: Float
    ): List<Detection> {
        val modelPathAtual = loadedPath ?: ""
        val aplicarHeuristicaCacau = yoloNumClasses == 4 &&
                isModeloDoencaCacau(modelPathAtual, modelLabels, customLabels)
        val detections = mutableListOf<Detection>()

        for (box in boxes) {
            val x1Tensor = (box[0] - box[2] / 2f) * inputWidth
            val y1Tensor = (box[1] - box[3] / 2f) * inputHeight
            val x2Tensor = (box[0] + box[2] / 2f) * inputWidth
            val y2Tensor = (box[1] + box[3] / 2f) * inputHeight

            val left   = ((x1Tensor - padX) / scale).coerceIn(0f, imgW)
            val top    = ((y1Tensor - padY) / scale).coerceIn(0f, imgH)
            val right  = ((x2Tensor - padX) / scale).coerceIn(0f, imgW)
            val bottom = ((y2Tensor - padY) / scale).coerceIn(0f, imgH)

            if (right <= left || bottom <= top) continue

            val rawClassIdx = box[5].toInt()
            val classIdx = if (aplicarHeuristicaCacau) {
                refinarClasseDoencaPorMorfologiaCor(
                    classIdx = rawClassIdx,
                    probBest = box[4],
                    probClass0 = if (box.size > 6) box[6] else 0f,
                    pixels = pixels,
                    width = inputWidth,
                    height = inputHeight,
                    x1Tensor = x1Tensor,
                    y1Tensor = y1Tensor,
                    x2Tensor = x2Tensor,
                    y2Tensor = y2Tensor,
                    probClass1 = if (box.size > 7) box[7] else 0f,
                    probClass3 = if (box.size > 9) box[9] else 0f
                )
            } else {
                rawClassIdx
            }
            val label = resolverRotuloClasse(classIdx)

            detections.add(
                Detection.create(
                    RectF(left, top, right, bottom),
                    listOf(Category.create(label, label, box[4]))
                )
            )
        }
        return detections
    }

    private fun resolverRotuloClasse(classIdx: Int): String = resolverRotuloPorIndice(
        classIdx = classIdx,
        numClasses = if (architecture == ModelArchitecture.SSD_POST_PROCESS) modelLabels.size else yoloNumClasses,
        modelLabels = modelLabels,
        customLabels = customLabels,
        fallbackDefault = defaultLabel,
        modelPath = loadedPath ?: ""
    )

    private fun resolveFile(): File? = when (val s = modelSource) {
        is ModelSource.Asset -> try {
            val targetFile = File(context.cacheDir, s.fileName)
            val assetFd = try { context.assets.openFd(s.fileName) } catch (_: Exception) { null }
            val assetLen = assetFd?.length ?: -1L
            assetFd?.close()

            // Se o arquivo em cache não existe ou tem tamanho diferente do asset, copia o novo
            if (!targetFile.exists() || (assetLen > 0L && targetFile.length() != assetLen)) {
                context.assets.open(s.fileName).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            targetFile
        } catch (e: Exception) {
            ultimoErro = "Modelo '${s.fileName}' não encontrado nos assets."
            detectorListener?.onError(ultimoErro!!)
            null
        }
        is ModelSource.InternalFile -> File(s.path).takeIf { it.exists() } ?: run {
            ultimoErro = "Arquivo do modelo não encontrado. Importe novamente."
            detectorListener?.onError(ultimoErro!!)
            null
        }
        is ModelSource.External -> try {
            File(context.cacheDir, "custom_model_cache.tflite").also { f ->
                context.contentResolver.openInputStream(s.uri)?.use { i ->
                    FileOutputStream(f).use { i.copyTo(it) }
                }
            }
        } catch (e: Exception) {
            ultimoErro = "Não foi possível acessar o arquivo. Selecione novamente."
            detectorListener?.onError(ultimoErro!!)
            null
        }
    }

    private fun close() {
        try { taskObjectDetector?.close() } catch (_: Exception) {}
        taskObjectDetector = null
        ssdAmbiguousShape2Pair = null
        interpreter?.close()
        interpreter = null
        loadedPath = null
        quantByteLut = null
        letterboxBitmap?.recycle()
        letterboxBitmap = null
        letterboxPixels = null
        inputByteBuffer = null
        inputFloatView = null
        inputFloatArray = null
        inputByteArray = null
        cachedOutDim1 = 0
        cachedOutDim2 = 0
        reusableFloatOut = null
        reusableByteOut = null
        reusableDeqOut = null
    }

    data class ResultadoDeteccao(
        val deteccoes: List<Detection>,
        val larguraImagem: Float,
        val alturaImagem: Float
    )

    data class ModelInspectionReport(
        val compativel: Boolean,
        val arquiteturaResumo: String,
        val detalhesTecnicos: String,
        val avisoUsuario: String,
        val numClasses: Int,
        val labelsExtraidas: List<String>
    )

    interface DetectorListener {
        fun onResults(resultado: ResultadoDeteccao)
        fun onError(mensagem: String) {}
    }

    companion object {
        private val FLOAT_NORM_LUT = FloatArray(256) { it / 255f }
        val DEFAULT_4CLASS_DOENCA_LABELS = listOf("PodridaoParda", "Moniliase", "Sadio", "VassouraBruxa")

        fun isTfliteFlatBuffer(bytes: ByteArray): Boolean {
            return bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "TFL3"
        }

        internal fun buildQuantLutForParams(
            type: DataType,
            rawScale: Float,
            zp: Int
        ): ByteArray? {
            if (type != DataType.UINT8 && type != DataType.INT8) return null
            val qMin = if (type == DataType.UINT8) 0 else -128
            val qMax = if (type == DataType.UINT8) 255 else 127

            if (rawScale <= 0f || (type == DataType.UINT8 && rawScale in 0.99f..1.01f && zp == 0)) {
                return ByteArray(256) { v ->
                    val q = if (type == DataType.UINT8) v else (v - 128)
                    q.toByte()
                }
            }

            val minFloat = (qMin - zp) * rawScale
            val maxFloat = (qMax - zp) * rawScale
            val isSignedNormalized = minFloat < -0.25f && maxFloat <= 2.5f
            val isRaw255Range = !isSignedNormalized && maxFloat > 10f

            return ByteArray(256) { v ->
                val real = when {
                    isSignedNormalized -> (v / 127.5f) - 1.0f
                    isRaw255Range -> v.toFloat()
                    else -> v / 255f
                }
                (real / rawScale + zp).roundToInt().coerceIn(qMin, qMax).toByte()
            }
        }

        fun extrairLabelsDeAsset(context: Context, assetName: String): List<String> {
            return try {
                val targetFile = File(context.cacheDir, assetName)
                val assetFd = try { context.assets.openFd(assetName) } catch (_: Exception) { null }
                val assetLen = assetFd?.length ?: -1L
                assetFd?.close()
                if (!targetFile.exists() || (assetLen > 0L && targetFile.length() != assetLen)) {
                    context.assets.open(assetName).use { input ->
                        FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                    }
                }
                extractLabelsFromFile(targetFile)
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun inspecionarModeloArquivo(file: File): ModelInspectionReport {
            if (!file.exists() || file.length() < 16L) {
                return ModelInspectionReport(
                    compativel = false,
                    arquiteturaResumo = "Arquivo inválido",
                    detalhesTecnicos = "Arquivo vazio ou muito pequeno.",
                    avisoUsuario = "Este arquivo não contém um modelo TensorFlow Lite válido.",
                    numClasses = 0,
                    labelsExtraidas = emptyList()
                )
            }
            val header = ByteArray(8)
            try {
                RandomAccessFile(file, "r").use { it.readFully(header) }
            } catch (_: Exception) {
                return ModelInspectionReport(
                    compativel = false,
                    arquiteturaResumo = "Erro de leitura",
                    detalhesTecnicos = "Falha ao ler cabeçalho.",
                    avisoUsuario = "Não foi possível ler o arquivo selecionado.",
                    numClasses = 0,
                    labelsExtraidas = emptyList()
                )
            }
            if (!isTfliteFlatBuffer(header)) {
                return ModelInspectionReport(
                    compativel = false,
                    arquiteturaResumo = "Formato inválido",
                    detalhesTecnicos = "Assinatura TFL3 ausente.",
                    avisoUsuario = "O arquivo não é um modelo TensorFlow Lite (.tflite) válido (assinatura TFL3 ausente).",
                    numClasses = 0,
                    labelsExtraidas = emptyList()
                )
            }

            val labelsExtraidas = extractLabelsFromFile(file)
            var interp: Interpreter? = null
            return try {
                interp = Interpreter(file, Interpreter.Options().apply { setNumThreads(2) })
                val inTensor = interp.getInputTensor(0)
                val inShape = inTensor.shape()
                val inType = inTensor.dataType()
                if (inShape.size != 4 || inShape[3] != 3 || (inType != DataType.FLOAT32 && inType != DataType.UINT8 && inType != DataType.INT8)) {
                    return ModelInspectionReport(
                        compativel = false,
                        arquiteturaResumo = "Entrada incompatível",
                        detalhesTecnicos = "Input ${inShape.toList()} $inType",
                        avisoUsuario = "Modelo incompatível com câmera RGB: entrada esperada [1, H, W, 3] FLOAT32/UINT8/INT8, mas o modelo espera ${inShape.toList()} ($inType).",
                        numClasses = 0,
                        labelsExtraidas = labelsExtraidas
                    )
                }

                val numOutputs = interp.outputTensorCount
                val inResumo = "${inShape[1]}x${inShape[2]} $inType"
                if (numOutputs >= 4) {
                    val labelsValidas = labelsExtraidas.filter { it != "???" && it.isNotBlank() }
                    return ModelInspectionReport(
                        compativel = true,
                        arquiteturaResumo = "Modelo pronto para uso",
                        detalhesTecnicos = "SSD ($inResumo) • ${labelsValidas.size} classes nos metadados",
                        avisoUsuario = if (labelsValidas.isEmpty()) {
                            "Compatível com a câmera, mas não trouxe nomes prontos. Escreva abaixo o que deseja contar."
                        } else {
                            "Compatível! Encontramos ${labelsValidas.size} tipos de itens que você pode marcar/desmarcar abaixo."
                        },
                        numClasses = labelsValidas.size.coerceAtLeast(1),
                        labelsExtraidas = labelsExtraidas
                    )
                }

                val outShape = interp.getOutputTensor(0).shape()
                val outType = interp.getOutputTensor(0).dataType()
                if (outShape.size == 2) {
                    return ModelInspectionReport(
                        compativel = false,
                        arquiteturaResumo = "Modelo incompatível para contagem",
                        detalhesTecnicos = "Classificador puro ${outShape.toList()}",
                        avisoUsuario = "Este arquivo apenas dá um nome para a foto inteira e não consegue localizar nem contar frutos separados. Envie um modelo de detecção de objetos.",
                        numClasses = outShape[1],
                        labelsExtraidas = labelsExtraidas
                    )
                }

                val isEnd2End = outShape.size == 3 && outShape[2] == 6 && outShape[1] in 1..1000
                val isYoloRawTransposed = outShape.size == 3 && outShape[1] in 5..300 && outShape[2] > outShape[1]
                val isYoloRawNormal = outShape.size == 3 && outShape[2] in 5..300 && outShape[1] > outShape[2]

                if (!isEnd2End && !isYoloRawTransposed && !isYoloRawNormal) {
                    return ModelInspectionReport(
                        compativel = false,
                        arquiteturaResumo = "Formato não compatível",
                        detalhesTecnicos = "Saída ${outShape.toList()} ($outType)",
                        avisoUsuario = "Este modelo usa um formato que não marca caixas na câmera (ex.: segmentação ou outro tipo de rede).",
                        numClasses = 0,
                        labelsExtraidas = labelsExtraidas
                    )
                }

                val numClasses = when {
                    isEnd2End -> labelsExtraidas.size.coerceAtLeast(1)
                    isYoloRawTransposed -> (outShape[1] - 4).coerceAtLeast(1)
                    else -> (outShape[2] - 4).coerceAtLeast(1)
                }
                val archNome = if (isEnd2End) "YOLO End2End NMS" else "YOLO Raw"
                val avisoLabels = when {
                    labelsExtraidas.isEmpty() && numClasses > 1 ->
                        "Modelo compatível com $numClasses categorias, mas sem os nomes salvos no arquivo — informe os $numClasses nomes abaixo para separar na planilha."
                    labelsExtraidas.isEmpty() ->
                        "Modelo pronto para contar (1 categoria). Escreva abaixo o nome do objeto (ex: fruto, espiga, planta)."
                    else ->
                        "Compatível e pronto para uso! Identificamos ${labelsExtraidas.size} categoria(s) automaticamente."
                }
                ModelInspectionReport(
                    compativel = true,
                    arquiteturaResumo = "Modelo pronto para uso",
                    detalhesTecnicos = "$archNome • Entrada $inResumo • Saída $outType • $numClasses classe(s)",
                    avisoUsuario = avisoLabels,
                    numClasses = numClasses,
                    labelsExtraidas = labelsExtraidas
                )
            } catch (e: Exception) {
                ModelInspectionReport(
                    compativel = false,
                    arquiteturaResumo = "Falha ao inicializar modelo",
                    detalhesTecnicos = e.message?.take(140) ?: "Erro desconhecido",
                    avisoUsuario = "Não foi possível abrir o modelo no interpretador TFLite: ${e.message?.take(120) ?: "arquivo incompatível"}",
                    numClasses = 0,
                    labelsExtraidas = labelsExtraidas
                )
            } finally {
                interp?.close()
            }
        }

        internal fun isModeloDoencaCacau(
            modelPath: String,
            modelLabels: List<String>,
            customLabels: List<String>
        ): Boolean {
            if (modelPath.contains("detector_doenca", ignoreCase = true)) return true
            val todas = (modelLabels + customLabels).map { it.trim().lowercase() }
            return todas.any { it == "vassourabruxa" || it == "podridaoparda" || it == "moniliase" }
        }

        fun resolverRotuloPorIndice(
            classIdx: Int,
            numClasses: Int,
            modelLabels: List<String>,
            customLabels: List<String>,
            fallbackDefault: String,
            modelPath: String = ""
        ): String {
            if (modelLabels.isNotEmpty()) {
                val hasLeadingPlaceholder = modelLabels.firstOrNull() == "???"
                val efetivoIdx = if (hasLeadingPlaceholder) classIdx + 1 else classIdx
                val candidato = modelLabels.getOrNull(efetivoIdx)?.trim()
                if (!candidato.isNullOrEmpty() && candidato != "???") {
                    return candidato
                }
            }

            val ehDoencaCacau = numClasses == 4 && isModeloDoencaCacau(modelPath, modelLabels, customLabels)
            if (ehDoencaCacau) {
                val especificos = customLabels.filter { lbl ->
                    val low = lbl.trim().lowercase()
                    low.isNotEmpty() && low != "cacau" && low != "fruto" && low != "objeto"
                }
                if (especificos.size >= 4) {
                    especificos.getOrNull(classIdx)?.let { return it }
                }
                DEFAULT_4CLASS_DOENCA_LABELS.getOrNull(classIdx)?.let { return it }
            }

            if (numClasses > 1) {
                customLabels.getOrNull(classIdx)?.takeIf { it.isNotBlank() }?.let { return it }
                return "classe_$classIdx"
            }

            customLabels.firstOrNull { it.isNotBlank() }?.let { return it }
            return fallbackDefault.ifBlank { "objeto" }
        }

        internal fun decodeYoloRawCandidates(
            out: Array<FloatArray>,
            numAnchors: Int,
            numClasses: Int,
            transposed: Boolean,
            inputWidth: Int,
            inputHeight: Int,
            scoreThreshold: Float
        ): List<FloatArray> {
            if (numAnchors <= 0 || numClasses <= 0) return emptyList()
            val lastIdx = numAnchors - 1
            val midIdx = numAnchors / 2

            fun readVal(coordOrClass: Int, anchor: Int): Float =
                if (transposed) out[coordOrClass][anchor] else out[anchor][coordOrClass]

            val maxSampleCoord = maxOf(
                readVal(0, 0), readVal(0, midIdx), readVal(0, lastIdx), readVal(2, lastIdx)
            )
            val coordsArePixels = maxSampleCoord > 2.0f
            val scaleX = if (coordsArePixels) 1f / inputWidth.coerceAtLeast(1).toFloat() else 1f
            val scaleY = if (coordsArePixels) 1f / inputHeight.coerceAtLeast(1).toFloat() else 1f

            var minSampleScore = Float.MAX_VALUE
            var maxSampleScore = -Float.MAX_VALUE
            for (c in 0 until numClasses) {
                for (idx in intArrayOf(0, midIdx, lastIdx)) {
                    val v = readVal(4 + c, idx)
                    if (v < minSampleScore) minSampleScore = v
                    if (v > maxSampleScore) maxSampleScore = v
                }
            }
            val needsSigmoid = minSampleScore < -0.001f || maxSampleScore > 1.001f

            val candidates = mutableListOf<FloatArray>()
            for (i in 0 until numAnchors) {
                val normCx = readVal(0, i) * scaleX
                val normCy = readVal(1, i) * scaleY
                val normW = readVal(2, i) * scaleX
                val normH = readVal(3, i) * scaleY

                var rawScore = -Float.MAX_VALUE
                var bestClass = 0
                val rawClass0 = readVal(4, i)
                for (c in 0 until numClasses) {
                    val s = readVal(4 + c, i)
                    if (s > rawScore) {
                        rawScore = s
                        bestClass = c
                    }
                }

                val prob = if (needsSigmoid) sigmoidFn(rawScore) else rawScore.coerceIn(0f, 1f)
                val probClass0 = if (needsSigmoid) sigmoidFn(rawClass0) else rawClass0.coerceIn(0f, 1f)

                if (prob >= scoreThreshold &&
                    normW > 0.015f && normH > 0.015f &&
                    normW < 0.98f && normH < 0.98f &&
                    normCx > 0.005f && normCy > 0.005f &&
                    normCx < 0.995f && normCy < 0.995f
                ) {
                    if (numClasses == 4) {
                        val p0 = if (needsSigmoid) sigmoidFn(readVal(4, i)) else readVal(4, i).coerceIn(0f, 1f)
                        val p1 = if (needsSigmoid) sigmoidFn(readVal(5, i)) else readVal(5, i).coerceIn(0f, 1f)
                        val p2 = if (needsSigmoid) sigmoidFn(readVal(6, i)) else readVal(6, i).coerceIn(0f, 1f)
                        val p3 = if (needsSigmoid) sigmoidFn(readVal(7, i)) else readVal(7, i).coerceIn(0f, 1f)
                        candidates.add(floatArrayOf(normCx, normCy, normW, normH, prob, bestClass.toFloat(), p0, p1, p2, p3))
                    } else {
                        candidates.add(floatArrayOf(normCx, normCy, normW, normH, prob, bestClass.toFloat(), probClass0))
                    }
                }
            }
            return candidates
        }

        internal fun decodeYoloEnd2EndNms(
            out: Array<FloatArray>,
            numRows: Int,
            inputWidth: Int,
            inputHeight: Int,
            scoreThreshold: Float
        ): List<FloatArray> {
            if (numRows <= 0) return emptyList()
            val mid = numRows / 2
            val last = numRows - 1
            val maxSampleCoord = maxOf(
                out[0][2], out[0][3],
                out[mid][2], out[mid][3],
                out[last][2], out[last][3]
            )
            val coordsArePixels = maxSampleCoord > 2.0f
            val scaleX = if (coordsArePixels) 1f / inputWidth.coerceAtLeast(1).toFloat() else 1f
            val scaleY = if (coordsArePixels) 1f / inputHeight.coerceAtLeast(1).toFloat() else 1f

            val candidates = mutableListOf<FloatArray>()
            for (i in 0 until numRows) {
                val x1 = (out[i][0] * scaleX).coerceIn(0f, 1f)
                val y1 = (out[i][1] * scaleY).coerceIn(0f, 1f)
                val x2 = (out[i][2] * scaleX).coerceIn(0f, 1f)
                val y2 = (out[i][3] * scaleY).coerceIn(0f, 1f)
                val score = out[i][4]
                val cls = out[i][5].toInt().coerceAtLeast(0)

                val w = x2 - x1
                val h = y2 - y1
                if (score >= scoreThreshold && w > 0.01f && h > 0.01f && w < 0.99f && h < 0.99f) {
                    val cx = (x1 + x2) / 2f
                    val cy = (y1 + y2) / 2f
                    candidates.add(floatArrayOf(cx, cy, w, h, score.coerceIn(0f, 1f), cls.toFloat(), score))
                }
            }
            return candidates
        }

        internal fun nmsMultiClasse(
            boxes: List<FloatArray>,
            iouMesmaClasse: Float = 0.45f,
            iouEntreClasses: Float = 0.85f
        ): List<FloatArray> {
            val sorted = boxes.sortedByDescending { it[4] }.toMutableList()
            val result = mutableListOf<FloatArray>()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                result.add(best)
                val bestCls = best[5].toInt()
                sorted.removeAll { other ->
                    val thresh = if (other[5].toInt() == bestCls) iouMesmaClasse else iouEntreClasses
                    iouCaixas(best, other) > thresh
                }
            }
            return result
        }

        private fun iouCaixas(a: FloatArray, b: FloatArray): Float {
            val ax1 = a[0] - a[2] / 2f; val ay1 = a[1] - a[3] / 2f
            val ax2 = a[0] + a[2] / 2f; val ay2 = a[1] + a[3] / 2f
            val bx1 = b[0] - b[2] / 2f; val by1 = b[1] - b[3] / 2f
            val bx2 = b[0] + b[2] / 2f; val by2 = b[1] + b[3] / 2f
            val inter = max(0f, min(ax2, bx2) - max(ax1, bx1)) * max(0f, min(ay2, by2) - max(ay1, by1))
            val union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
            return if (union <= 0f) 0f else inter / union
        }

        private fun sigmoidFn(x: Float) = (1f / (1f + exp(-x.toDouble()))).toFloat()

        internal fun extrairLabelsMetadadosUltralytics(textoAscii: String): List<String> {
            val idxNames = textoAscii.indexOf("names")
            val janela = if (idxNames >= 0) {
                textoAscii.substring(idxNames, min(textoAscii.length, idxNames + 8192))
            } else {
                textoAscii
            }
            val regexBloco = Regex("""\{([^{}]{3,4096})\}""")
            val parRegex = Regex("""['"]?(\d{1,3})['"]?\s*:\s*['"]([^'"]{1,80})['"]""")

            for (match in regexBloco.findAll(janela)) {
                val pares = mutableMapOf<Int, String>()
                parRegex.findAll(match.groupValues[1]).forEach { m ->
                    val idx = m.groupValues[1].toIntOrNull()
                    val nome = m.groupValues[2].trim()
                    if (idx != null && idx in 0..200 && nome.isNotEmpty() && nome.none { it.isISOControl() }) {
                        pares[idx] = nome
                    }
                }
                if (pares.isNotEmpty()) {
                    val maxIdx = pares.keys.maxOrNull() ?: continue
                    if (pares.size == maxIdx + 1 && pares.containsKey(0)) {
                        return (0..maxIdx).map { pares[it]!! }
                    }
                }
            }
            return emptyList()
        }

        internal fun refinarClasseDoencaPorMorfologiaCor(
            classIdx: Int,
            probBest: Float,
            probClass0: Float,
            pixels: IntArray,
            width: Int,
            height: Int,
            x1Tensor: Float,
            y1Tensor: Float,
            x2Tensor: Float,
            y2Tensor: Float,
            probClass1: Float = 0f,
            probClass3: Float = 0f
        ): Int {
            val sadioComCompeticao = classIdx == 2 && (probBest < 0.78f && (probClass0 >= 0.16f || probClass1 >= 0.16f || probClass3 >= 0.14f))
            if (classIdx != 0 && classIdx != 1 && !sadioComCompeticao) {
                return classIdx
            }
            val bw = x2Tensor - x1Tensor
            val bh = y2Tensor - y1Tensor
            if (bw < 4f || bh < 4f) return classIdx

            val startX = (x1Tensor + bw * 0.15f).toInt().coerceIn(0, width - 1)
            val endX = (x2Tensor - bw * 0.15f).toInt().coerceIn(startX + 1, width)
            val startY = (y1Tensor + bh * 0.15f).toInt().coerceIn(0, height - 1)
            val endY = (y2Tensor - bh * 0.15f).toInt().coerceIn(startY + 1, height)

            var rSum = 0f
            var gSum = 0f
            var bSum = 0f
            var satSum = 0f
            var valSum = 0f
            var darkMatteCount = 0
            var rustCount = 0
            var whiteSporeCount = 0
            var count = 0

            val stepY = ((endY - startY) / 24).coerceAtLeast(1)
            val stepX = ((endX - startX) / 24).coerceAtLeast(1)

            var y = startY
            while (y < endY) {
                val rowOffset = y * width
                var x = startX
                while (x < endX) {
                    val px = pixels[rowOffset + x]
                    val r = ((px shr 16) and 0xFF) / 255f
                    val g = ((px shr 8) and 0xFF) / 255f
                    val b = (px and 0xFF) / 255f
                    val cmax = maxOf(r, g, b)
                    val cmin = minOf(r, g, b)
                    val sat = if (cmax > 0.001f) (cmax - cmin) / cmax else 0f
                    val rb = r - b
                    if (cmax < 0.42f || (cmax < 0.58f && sat < 0.38f && rb < 0.22f)) {
                        darkMatteCount++
                    }
                    if (r > g * 1.15f && cmax in 0.22f..0.62f && sat >= 0.30f && rb >= 0.14f) {
                        rustCount++
                    }
                    if (cmax > 0.72f && sat < 0.22f) {
                        whiteSporeCount++
                    }
                    rSum += r
                    gSum += g
                    bSum += b
                    satSum += sat
                    valSum += cmax
                    count++
                    x += stepX
                }
                y += stepY
            }

            if (count == 0) return classIdx
            val rMean = rSum / count
            val bMean = bSum / count
            val satMean = satSum / count
            val valMean = valSum / count
            val rbDiff = rMean - bMean
            val darkMatteRatio = darkMatteCount.toFloat() / count.toFloat()
            val rustRatio = rustCount.toFloat() / count.toFloat()
            val whiteSporeRatio = whiteSporeCount.toFloat() / count.toFloat()

            // Assinatura de Monilíase: esporulação pulverulenta branco/creme extensa sobre tecido lesionado
            if (whiteSporeRatio >= 0.24f && darkMatteRatio >= 0.12f && (classIdx == 0 || classIdx == 1 || probClass1 >= 0.15f)) {
                return 1
            }

            // Assinatura de Vassoura de Bruxa: necrose seca escura/fosca ou manchas ferrugíneas mistas
            val isColdGrayDry = rbDiff < 0.145f && satMean < 0.265f && valMean < 0.68f
            val isDarkBrownDry = valMean < 0.46f && satMean < 0.45f && rbDiff < 0.24f
            val isPredominantlyNecroticMatte = darkMatteRatio >= 0.45f && valMean < 0.62f && rbDiff < 0.22f
            val isRustMummified = rustRatio >= 0.26f && darkMatteRatio >= 0.24f && valMean < 0.62f

            return when (classIdx) {
                0 -> if (isColdGrayDry || isDarkBrownDry || isPredominantlyNecroticMatte || isRustMummified) 3 else 0
                1 -> if ((isColdGrayDry || isPredominantlyNecroticMatte) && whiteSporeRatio < 0.12f && probBest < 0.65f) 3 else 1
                else -> {
                    if ((rbDiff < 0.10f && satMean < 0.20f && valMean < 0.58f) ||
                        (darkMatteRatio >= 0.55f && valMean < 0.52f && rbDiff < 0.18f) ||
                        (isRustMummified && probClass3 >= 0.14f)
                    ) 3 else classIdx
                }
            }
        }

        /**
         * Classificação assistida por recorte para caixas vindas do contador primário
         * quando o detector secundário de doenças não conseguiu parear no cacho denso.
         */
        fun classificarRecorteDoencaCacau(bmp: Bitmap, rect: RectF): String {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return "Sadio"
            val x1 = rect.left.coerceIn(0f, (w - 1).toFloat())
            val y1 = rect.top.coerceIn(0f, (h - 1).toFloat())
            val x2 = rect.right.coerceIn(x1 + 1f, w.toFloat())
            val y2 = rect.bottom.coerceIn(y1 + 1f, h.toFloat())
            val bw = x2 - x1
            val bh = y2 - y1
            if (bw < 4f || bh < 4f) return "Sadio"

            val startX = (x1 + bw * 0.2f).toInt().coerceIn(0, w - 1)
            val endX = (x2 - bw * 0.2f).toInt().coerceIn(startX + 1, w)
            val startY = (y1 + bh * 0.2f).toInt().coerceIn(0, h - 1)
            val endY = (y2 - bh * 0.2f).toInt().coerceIn(startY + 1, h)
            val stepY = ((endY - startY) / 16).coerceAtLeast(1)
            val stepX = ((endX - startX) / 16).coerceAtLeast(1)

            var darkCount = 0
            var whiteSporeCount = 0
            var brownCount = 0
            var count = 0

            var y = startY
            while (y < endY) {
                var x = startX
                while (x < endX) {
                    val px = bmp.getPixel(x, y)
                    val r = ((px shr 16) and 0xFF) / 255f
                    val g = ((px shr 8) and 0xFF) / 255f
                    val b = (px and 0xFF) / 255f
                    val cmax = maxOf(r, g, b)
                    val cmin = minOf(r, g, b)
                    val sat = if (cmax > 0.001f) (cmax - cmin) / cmax else 0f
                    val rb = r - b

                    if (cmax < 0.40f || (cmax < 0.56f && sat < 0.36f && rb < 0.22f)) darkCount++
                    if (cmax > 0.72f && sat < 0.22f) whiteSporeCount++
                    if (r > g * 1.15f && cmax in 0.25f..0.75f && rb > 0.18f) brownCount++
                    count++
                    x += stepX
                }
                y += stepY
            }
            if (count == 0) return "Sadio"
            val darkRatio = darkCount.toFloat() / count
            val sporeRatio = whiteSporeCount.toFloat() / count
            val brownRatio = brownCount.toFloat() / count

            return when {
                sporeRatio >= 0.24f && (darkRatio >= 0.12f || brownRatio >= 0.12f) -> "Moniliase"
                darkRatio >= 0.46f -> "VassouraBruxa"
                brownRatio >= 0.48f && darkRatio < 0.30f -> "PodridaoParda"
                else -> "Sadio"
            }
        }

        /**
         * Extrai rótulos (labelmap.txt / labels.txt) eventualmente embutidos nos metadados ZIP do .tflite.
         */
        fun extractLabelsFromFile(file: File): List<String> {
            val labels = mutableListOf<String>()
            try {
                java.util.zip.ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory &&
                            ImportadorPlanilhaUniversal.isSafeZipEntryName(entry.name) &&
                            entry.name.contains("label", ignoreCase = true)
                        ) {
                            zip.getInputStream(entry).use { entryStream ->
                                val bytes = ImportadorPlanilhaUniversal.readZipEntryBounded(entryStream, 512 * 1024)
                                if (bytes != null) {
                                    String(bytes, Charsets.UTF_8).lineSequence().forEach { line ->
                                        val trimmed = line.trim()
                                        if (trimmed.isNotEmpty()) labels.add(trimmed)
                                    }
                                }
                            }
                            if (labels.isNotEmpty()) break
                        }
                    }
                }
            } catch (_: Exception) {
                // Metadados opcionais (nem todo .tflite traz bloco ZIP anexado)
            }
            if (labels.isEmpty()) {
                try {
                    val tamanho = file.length()
                    val bytesLer = min(tamanho, 262_144L).toInt()
                    if (bytesLer > 32) {
                        val tail = ByteArray(bytesLer)
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(tamanho - bytesLer)
                            raf.readFully(tail)
                        }
                        val tailText = String(tail, Charsets.UTF_8)
                        labels.addAll(extrairLabelsMetadadosUltralytics(tailText))
                    }
                } catch (_: Exception) {
                    // Metadados opcionais
                }
            }
            return labels
        }
    }
}
