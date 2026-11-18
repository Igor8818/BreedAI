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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        SSD_RAW_ANCHORS,  // Modelo SSD/EfficientDet de 2 tensores de saída (scores + anchor deltas)
        YOLO_RAW,         // Modelo de saída única com âncoras brutas
        YOLO_END2END_NMS  // Modelo YOLO exportado com nms=True ([1, N, 6])
    }

    private var architecture = ModelArchitecture.YOLO_RAW

    // Configuração de entrada
    private var inputWidth = 640
    private var inputHeight = 640
    private var inputDataType = DataType.FLOAT32
    private var isInputChannelFirst = false
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
    private var reusableExtraOutputBuf: ByteBuffer? = null
    private val letterboxMatrix = Matrix()
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Índices e dimensões para SSD (4 saídas)
    private var ssdNumDetections = 10
    private var ssdBoxIndex = 0
    private var ssdClassIndex = 1
    private var ssdScoreIndex = 2
    private var ssdCountIndex = 3
    private var ssdAmbiguousShape2Pair: Pair<Int, Int>? = null

    // Índices, dimensões e buffers reutilizáveis para SSD_RAW_ANCHORS (2 saídas: scores + box deltas)
    private var rawBoxTensorIdx = 1
    private var rawScoreTensorIdx = 0
    private var rawAnchorCount = 0
    private var rawClassCount = 0
    private var rawBoxOutputType = DataType.FLOAT32
    private var rawBoxQuantScale = 1f
    private var rawBoxQuantZeroPoint = 0
    private var rawScoreOutputType = DataType.FLOAT32
    private var rawScoreQuantScale = 1f
    private var rawScoreQuantZeroPoint = 0
    private var cachedAnchorsW = 0
    private var cachedAnchorsH = 0
    private var cachedEfficientDetAnchors: Array<FloatArray>? = null
    private var reusableRawScoresFloat: Array<Array<FloatArray>>? = null
    private var reusableRawBoxesFloat: Array<Array<FloatArray>>? = null
    private var reusableRawScoresByte: Array<Array<ByteArray>>? = null
    private var reusableRawBoxesByte: Array<Array<ByteArray>>? = null
    private var reusableRawScoresDeq: Array<FloatArray>? = null
    private var reusableRawBoxesDeq: Array<FloatArray>? = null

    // Configurações para YOLO
    private var yoloNumAnchors = 8400
    private var yoloNumClasses = 1
    private var yoloTransposed = true
    private var yoloHasObjectness = false
    private var yoloExtraChannels = 0
    private var modelTask = "detect"

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

            // Inspeciona tensor de entrada (suporta NHWC [1,H,W,3] e NCHW [1,3,H,W])
            val inTensor = interpreter!!.getInputTensor(0)
            val inShape = inTensor.shape()
            inputDataType = inTensor.dataType()
            quantByteLut = buildQuantLutIfNeeded(inputDataType, inTensor.quantizationParams())
            isInputChannelFirst = (inShape.size == 4 && inShape[1] == 3 && inShape[3] > 3)
            if (isInputChannelFirst) {
                inputHeight = if (inShape[2] > 0) inShape[2] else 640
                inputWidth = if (inShape[3] > 0) inShape[3] else 640
            } else {
                inputHeight = if (inShape.size >= 2 && inShape[1] > 0) inShape[1] else 640
                inputWidth = if (inShape.size >= 3 && inShape[2] > 0) inShape[2] else 640
            }

            val numOutputs = interpreter!!.outputTensorCount
            Log.d("Detector", "Modelo carregado: input=${inShape.toList()} nchw=$isInputChannelFirst type=$inputDataType outputs=$numOutputs size=${file.length()}")

            // Tenta extrair rótulos embutidos (ZIP Metadata ou FlatBuffer Ultralytics) e task
            extractLabelsFromModelFile(file)
            modelTask = extrairTaskDeArquivo(file) ?: "detect"

            val isTwoOutSsd = numOutputs == 2 && isTwoOutputBoxAndScoresDetector(
                interpreter!!.getOutputTensor(0).shape(),
                interpreter!!.getOutputTensor(1).shape()
            )

            if (numOutputs >= 4 || isTwoOutSsd) {
                if (numOutputs >= 4) {
                    identifySsdOutputs()
                } else {
                    configureTwoOutputSsdIndices()
                }
                val allowList = if (customLabels.isNotEmpty() && modelLabels.isNotEmpty()) {
                    modelLabels.filter { m ->
                        m.isNotBlank() && m != "???" &&
                            customLabels.any { c -> labelEquivalente(c, m) }
                    }.distinct()
                } else {
                    emptyList()
                }
                val tvDet = try {
                    val tvOptsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setNumThreads(3).build())
                        .setScoreThreshold(min(scoreThreshold, 0.10f))
                        .setMaxResults(max(maxResults, 25))
                    if (allowList.isNotEmpty()) {
                        tvOptsBuilder.setLabelAllowList(allowList)
                    }
                    ObjectDetector.createFromFileAndOptions(file, tvOptsBuilder.build())
                } catch (t: Throwable) {
                    if (allowList.isNotEmpty()) {
                        try {
                            val fallbackOpts = ObjectDetector.ObjectDetectorOptions.builder()
                                .setBaseOptions(BaseOptions.builder().setNumThreads(3).build())
                                .setScoreThreshold(min(scoreThreshold, 0.10f))
                                .setMaxResults(max(maxResults, 25))
                                .build()
                            ObjectDetector.createFromFileAndOptions(file, fallbackOpts)
                        } catch (t2: Throwable) {
                            Log.w("Detector", "TaskVision indisponível (${t2.message}), usando Interpreter fallback")
                            null
                        }
                    } else {
                        Log.w("Detector", "TaskVision indisponível (${t.message}), usando Interpreter fallback")
                        null
                    }
                }
                if (tvDet != null) {
                    taskObjectDetector = tvDet
                    architecture = ModelArchitecture.SSD_TASK_VISION
                    Log.d("Detector", "Arquitetura identificada: SSD TaskVision Metadata ($inputWidth x $inputHeight, allowList=$allowList)")
                } else if (isTwoOutSsd) {
                    architecture = ModelArchitecture.SSD_RAW_ANCHORS
                    Log.d("Detector", "Arquitetura identificada: SSD Raw Anchors (anchors=$rawAnchorCount, classes=$rawClassCount, scoreIdx=$rawScoreTensorIdx, boxIdx=$rawBoxTensorIdx)")
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
                Log.d("Detector", "YOLO outShape=${outShape.toList()} outType=$outputDataType scale=$outQuantScale zp=$outQuantZeroPoint task=$modelTask")

                var maskChannels = 0
                if (numOutputs == 2) {
                    val outShape1 = interpreter!!.getOutputTensor(1).shape()
                    if (outShape1.size == 4) {
                        maskChannels = when {
                            outShape1[3] in 1..128 && outShape1[1] > 8 -> outShape1[3]
                            outShape1[1] in 1..128 && outShape1[3] > 8 -> outShape1[1]
                            else -> 32
                        }
                    }
                }

                if (numOutputs == 1 && outShape.size == 3 && outShape[2] == 6 && outShape[1] in 1..1000) {
                    architecture = ModelArchitecture.YOLO_END2END_NMS
                    yoloTransposed = false
                    yoloHasObjectness = false
                    yoloExtraChannels = 0
                    yoloNumAnchors = outShape[1]
                    yoloNumClasses = max(1, modelLabels.size.coerceAtLeast(customLabels.size))
                } else {
                    architecture = ModelArchitecture.YOLO_RAW
                    val totalChannels: Int
                    when {
                        // [1, canais, anchors] (ex: YOLOv8 padrão 1x5x8400 ou YOLO-seg 1x37x8400)
                        outShape.size == 3 && outShape[1] in 5..400 && outShape[2] > outShape[1] -> {
                            yoloTransposed = true
                            totalChannels = outShape[1]
                            yoloNumAnchors = outShape[2]
                        }
                        // [1, anchors, canais] (ex: YOLOv5 1x25200x6)
                        outShape.size == 3 && outShape[2] in 5..400 && outShape[1] > outShape[2] -> {
                            yoloTransposed = false
                            yoloNumAnchors = outShape[1]
                            totalChannels = outShape[2]
                        }
                        else -> {
                            yoloTransposed = true
                            yoloNumAnchors = if (outShape.size >= 3) outShape[2] else 8400
                            totalChannels = if (outShape.size >= 2) outShape[1] else 5
                        }
                    }

                    yoloExtraChannels = when {
                        maskChannels > 0 && totalChannels > 4 + maskChannels -> maskChannels
                        modelTask == "obb" && totalChannels > 5 -> 1
                        else -> 0
                    }
                    yoloHasObjectness = yoloExtraChannels == 0 && modelTask != "obb" && (
                        (modelLabels.isNotEmpty() && totalChannels == 5 + modelLabels.size) ||
                        (yoloNumAnchors in setOf(25200, 6300, 10647, 15120) && modelLabels.isEmpty() && totalChannels == 6)
                    )
                    val headerCols = if (yoloHasObjectness) 5 else 4
                    yoloNumClasses = (totalChannels - headerCols - yoloExtraChannels).coerceAtLeast(1)
                }
                Log.d("Detector", "YOLO configurado ($architecture): anchors=$yoloNumAnchors, classes=$yoloNumClasses, transposto=$yoloTransposed, obj=$yoloHasObjectness, extra=$yoloExtraChannels")
            }

            loadedPath = file.absolutePath
            ultimoErro = null

        } catch (e: Exception) {
            ultimoErro = "Erro ao carregar modelo: ${e.message?.take(200)}"
            Log.e("Detector", ultimoErro!!, e)
            detectorListener?.onError(ultimoErro!!)
        }
    }

    private fun configureTwoOutputSsdIndices() {
        val interp = interpreter ?: return
        if (interp.outputTensorCount < 2) return
        val t0 = interp.getOutputTensor(0)
        val t1 = interp.getOutputTensor(1)
        val s0 = t0.shape()
        val s1 = t1.shape()
        when {
            s1.size == 3 && s1[2] == 4 && s0.size == 3 && s0[2] != 4 -> {
                rawScoreTensorIdx = 0
                rawBoxTensorIdx = 1
            }
            s0.size == 3 && s0[2] == 4 && s1.size == 3 && s1[2] != 4 -> {
                rawBoxTensorIdx = 0
                rawScoreTensorIdx = 1
            }
            else -> {
                val n0 = t0.name().lowercase()
                val n1 = t1.name().lowercase()
                if (n0.contains("box") || n0.contains("loc") || n1.contains("score")) {
                    rawBoxTensorIdx = 0
                    rawScoreTensorIdx = 1
                } else {
                    rawScoreTensorIdx = 0
                    rawBoxTensorIdx = 1
                }
            }
        }
        val scoreT = interp.getOutputTensor(rawScoreTensorIdx)
        val boxT = interp.getOutputTensor(rawBoxTensorIdx)
        rawAnchorCount = scoreT.shape().getOrNull(1)?.coerceAtLeast(1) ?: 19206
        rawClassCount = scoreT.shape().getOrNull(2)?.coerceAtLeast(1) ?: 1
        rawScoreOutputType = scoreT.dataType()
        rawScoreQuantScale = scoreT.quantizationParams().scale.let { if (it > 0f) it else 1f }
        rawScoreQuantZeroPoint = scoreT.quantizationParams().zeroPoint
        rawBoxOutputType = boxT.dataType()
        rawBoxQuantScale = boxT.quantizationParams().scale.let { if (it > 0f) it else 1f }
        rawBoxQuantZeroPoint = boxT.quantizationParams().zeroPoint
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
                    architecture = if (interp.outputTensorCount >= 4) {
                        ModelArchitecture.SSD_POST_PROCESS
                    } else {
                        configureTwoOutputSsdIndices()
                        ModelArchitecture.SSD_RAW_ANCHORS
                    }
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
                if (isInputChannelFirst) {
                    val planeG = totalPixels
                    val planeB = totalPixels * 2
                    for (idx in pixels.indices) {
                        val px = pixels[idx]
                        bArr[idx]          = lut[(px shr 16) and 0xFF]
                        bArr[planeG + idx] = lut[(px shr 8)  and 0xFF]
                        bArr[planeB + idx] = lut[px          and 0xFF]
                    }
                } else {
                    var outIdx = 0
                    for (px in pixels) {
                        bArr[outIdx++] = lut[(px shr 16) and 0xFF]
                        bArr[outIdx++] = lut[(px shr 8)  and 0xFF]
                        bArr[outIdx++] = lut[px          and 0xFF]
                    }
                }
                buf.put(bArr)
                buf.rewind()
            } else {
                var fArr = inputFloatArray
                if (fArr == null || fArr.size != neededChannels) {
                    fArr = FloatArray(neededChannels)
                    inputFloatArray = fArr
                }
                if (isInputChannelFirst) {
                    val planeG = totalPixels
                    val planeB = totalPixels * 2
                    for (idx in pixels.indices) {
                        val px = pixels[idx]
                        fArr[idx]          = FLOAT_NORM_LUT[(px shr 16) and 0xFF]
                        fArr[planeG + idx] = FLOAT_NORM_LUT[(px shr 8)  and 0xFF]
                        fArr[planeB + idx] = FLOAT_NORM_LUT[px          and 0xFF]
                    }
                } else {
                    var outIdx = 0
                    for (px in pixels) {
                        fArr[outIdx++] = FLOAT_NORM_LUT[(px shr 16) and 0xFF]
                        fArr[outIdx++] = FLOAT_NORM_LUT[(px shr 8)  and 0xFF]
                        fArr[outIdx++] = FLOAT_NORM_LUT[px          and 0xFF]
                    }
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

            } else if (architecture == ModelArchitecture.SSD_RAW_ANCHORS) {
                val (scoresFloat, boxesFloat) = runTwoOutputTensorMatrix(
                    interp = interp,
                    inputBuf = buf,
                    scoreIdx = rawScoreTensorIdx,
                    boxIdx = rawBoxTensorIdx,
                    numAnchors = rawAnchorCount,
                    numClasses = rawClassCount
                )
                var anchors = cachedEfficientDetAnchors
                if (anchors == null || cachedAnchorsW != inputWidth || cachedAnchorsH != inputHeight || anchors.size != rawAnchorCount) {
                    anchors = generateEfficientDetAnchors(inputWidth, inputHeight)
                    cachedEfficientDetAnchors = anchors
                    cachedAnchorsW = inputWidth
                    cachedAnchorsH = inputHeight
                }

                val allowedIndices = if (customLabels.isNotEmpty() && modelLabels.isNotEmpty()) {
                    (0 until rawClassCount).filter { idx ->
                        val resolved = resolverRotuloClasse(idx)
                        customLabels.any { c -> labelEquivalente(c, resolved) }
                    }.toSet()
                } else {
                    emptySet()
                }

                val decoded = decodeEfficientDetTwoOutputs(
                    scores = scoresFloat,
                    boxes = boxesFloat,
                    anchors = anchors,
                    numClasses = rawClassCount,
                    scoreThreshold = scoreThreshold,
                    allowedClassIndices = allowedIndices
                )
                val nmsResult = nmsMultiClasse(decoded).take(maxResults)
                for (box in nmsResult) {
                    val x1Tensor = (box[0] - box[2] / 2f) * inputWidth
                    val y1Tensor = (box[1] - box[3] / 2f) * inputHeight
                    val x2Tensor = (box[0] + box[2] / 2f) * inputWidth
                    val y2Tensor = (box[1] + box[3] / 2f) * inputHeight

                    val left   = ((x1Tensor - padX) / scale).coerceIn(0f, imgW)
                    val top    = ((y1Tensor - padY) / scale).coerceIn(0f, imgH)
                    val right  = ((x2Tensor - padX) / scale).coerceIn(0f, imgW)
                    val bottom = ((y2Tensor - padY) / scale).coerceIn(0f, imgH)
                    if (right <= left || bottom <= top) continue

                    val label = resolverRotuloClasse(box[5].toInt())
                    finalDetections.add(
                        Detection.create(
                            RectF(left, top, right, bottom),
                            listOf(Category.create(label, label, box[4]))
                        )
                    )
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
                // Inferência YOLO raw (FLOAT32, UINT8 ou INT8, incluindo YOLOv5, YOLO-Seg e YOLO-OBB)
                val headerCols = if (yoloHasObjectness) 5 else 4
                val totalChannels = headerCols + yoloNumClasses + yoloExtraChannels
                val dim1 = if (yoloTransposed) totalChannels else yoloNumAnchors
                val dim2 = if (yoloTransposed) yoloNumAnchors else totalChannels
                val matrix = runYoloOutputMatrix(interp, buf, dim1, dim2)

                val candidates = decodeYoloRawCandidates(
                    out = matrix,
                    numAnchors = yoloNumAnchors,
                    numClasses = yoloNumClasses,
                    transposed = yoloTransposed,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    scoreThreshold = scoreThreshold,
                    hasObjectness = yoloHasObjectness
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

    private fun runTwoOutputTensorMatrix(
        interp: Interpreter,
        inputBuf: ByteBuffer,
        scoreIdx: Int,
        boxIdx: Int,
        numAnchors: Int,
        numClasses: Int
    ): Pair<Array<FloatArray>, Array<FloatArray>> {
        val scoresQuant = rawScoreOutputType == DataType.UINT8 || rawScoreOutputType == DataType.INT8
        val boxesQuant = rawBoxOutputType == DataType.UINT8 || rawBoxOutputType == DataType.INT8

        val scoreTarget: Any
        val scoresResult: Array<FloatArray>
        if (scoresQuant) {
            var bOut = reusableRawScoresByte
            var dOut = reusableRawScoresDeq
            if (bOut == null || dOut == null || bOut[0].size != numAnchors || bOut[0].firstOrNull()?.size != numClasses) {
                bOut = Array(1) { Array(numAnchors) { ByteArray(numClasses) } }
                dOut = Array(numAnchors) { FloatArray(numClasses) }
                reusableRawScoresByte = bOut
                reusableRawScoresDeq = dOut
                reusableRawScoresFloat = null
            }
            scoreTarget = bOut
            scoresResult = dOut
        } else {
            var fOut = reusableRawScoresFloat
            if (fOut == null || fOut[0].size != numAnchors || fOut[0].firstOrNull()?.size != numClasses) {
                fOut = Array(1) { Array(numAnchors) { FloatArray(numClasses) } }
                reusableRawScoresFloat = fOut
                reusableRawScoresByte = null
                reusableRawScoresDeq = null
            }
            scoreTarget = fOut
            scoresResult = fOut[0]
        }

        val boxTarget: Any
        val boxesResult: Array<FloatArray>
        if (boxesQuant) {
            var bOut = reusableRawBoxesByte
            var dOut = reusableRawBoxesDeq
            if (bOut == null || dOut == null || bOut[0].size != numAnchors || bOut[0].firstOrNull()?.size != 4) {
                bOut = Array(1) { Array(numAnchors) { ByteArray(4) } }
                dOut = Array(numAnchors) { FloatArray(4) }
                reusableRawBoxesByte = bOut
                reusableRawBoxesDeq = dOut
                reusableRawBoxesFloat = null
            }
            boxTarget = bOut
            boxesResult = dOut
        } else {
            var fOut = reusableRawBoxesFloat
            if (fOut == null || fOut[0].size != numAnchors || fOut[0].firstOrNull()?.size != 4) {
                fOut = Array(1) { Array(numAnchors) { FloatArray(4) } }
                reusableRawBoxesFloat = fOut
                reusableRawBoxesByte = null
                reusableRawBoxesDeq = null
            }
            boxTarget = fOut
            boxesResult = fOut[0]
        }

        val outMap = HashMap<Int, Any>(2)
        outMap[scoreIdx] = scoreTarget
        outMap[boxIdx] = boxTarget
        interp.runForMultipleInputsOutputs(arrayOf(inputBuf), outMap)

        if (scoresQuant) {
            val isUint8 = rawScoreOutputType == DataType.UINT8
            val zp = rawScoreQuantZeroPoint
            val scale = rawScoreQuantScale
            val src = reusableRawScoresByte!![0]
            for (r in 0 until numAnchors) {
                val sr = src[r]
                val dr = scoresResult[r]
                for (c in 0 until numClasses) {
                    val q = if (isUint8) (sr[c].toInt() and 0xFF) else sr[c].toInt()
                    dr[c] = (q - zp) * scale
                }
            }
        }
        if (boxesQuant) {
            val isUint8 = rawBoxOutputType == DataType.UINT8
            val zp = rawBoxQuantZeroPoint
            val scale = rawBoxQuantScale
            val src = reusableRawBoxesByte!![0]
            for (r in 0 until numAnchors) {
                val sr = src[r]
                val dr = boxesResult[r]
                for (c in 0 until 4) {
                    val q = if (isUint8) (sr[c].toInt() and 0xFF) else sr[c].toInt()
                    dr[c] = (q - zp) * scale
                }
            }
        }
        return scoresResult to boxesResult
    }

    private fun runYoloOutputMatrix(
        interp: Interpreter,
        inputBuf: ByteBuffer,
        dim1: Int,
        dim2: Int
    ): Array<FloatArray> {
        fun execTarget(target: Any) {
            val count = interp.outputTensorCount
            if (count <= 1) {
                interp.run(inputBuf, target)
            } else {
                val outMap = HashMap<Int, Any>(count)
                outMap[0] = target
                val nb1 = interp.getOutputTensor(1).numBytes().coerceAtLeast(4)
                var extraBuf = reusableExtraOutputBuf
                if (extraBuf == null || extraBuf.capacity() != nb1) {
                    extraBuf = ByteBuffer.allocateDirect(nb1).order(ByteOrder.nativeOrder())
                    reusableExtraOutputBuf = extraBuf
                } else {
                    extraBuf.rewind()
                }
                outMap[1] = extraBuf
                for (i in 2 until count) {
                    val nb = interp.getOutputTensor(i).numBytes().coerceAtLeast(4)
                    outMap[i] = ByteBuffer.allocateDirect(nb).order(ByteOrder.nativeOrder())
                }
                interp.runForMultipleInputsOutputs(arrayOf(inputBuf), outMap)
            }
        }

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
            execTarget(byteOut)
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
        execTarget(floatOut)
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
        reusableExtraOutputBuf = null
        cachedAnchorsW = 0
        cachedAnchorsH = 0
        cachedEfficientDetAnchors = null
        reusableRawScoresFloat = null
        reusableRawBoxesFloat = null
        reusableRawScoresByte = null
        reusableRawBoxesByte = null
        reusableRawScoresDeq = null
        reusableRawBoxesDeq = null
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
            val taskExtraida = extrairTaskDeArquivo(file)
            if (taskExtraida == "pose") {
                return ModelInspectionReport(
                    compativel = false,
                    arquiteturaResumo = "Modelo de pontos-chave (Pose)",
                    detalhesTecnicos = "Task pose",
                    avisoUsuario = "Este modelo detecta pontos-chave (pose) e não caixas de objetos. Exporte um modelo de detecção ou segmentação.",
                    numClasses = 0,
                    labelsExtraidas = labelsExtraidas
                )
            }
            var interp: Interpreter? = null
            return try {
                interp = Interpreter(file, Interpreter.Options().apply { setNumThreads(2) })
                val inTensor = interp.getInputTensor(0)
                val inShape = inTensor.shape()
                val inType = inTensor.dataType()
                val ehNhwc = inShape.size == 4 && inShape[3] == 3
                val ehNchw = inShape.size == 4 && inShape[1] == 3 && inShape[3] > 3
                if ((!ehNhwc && !ehNchw) || (inType != DataType.FLOAT32 && inType != DataType.UINT8 && inType != DataType.INT8)) {
                    return ModelInspectionReport(
                        compativel = false,
                        arquiteturaResumo = "Entrada incompatível",
                        detalhesTecnicos = "Input ${inShape.toList()} $inType",
                        avisoUsuario = "Modelo incompatível com câmera RGB: entrada esperada [1, H, W, 3] ou [1, 3, H, W] FLOAT32/UINT8/INT8, mas o modelo espera ${inShape.toList()} ($inType).",
                        numClasses = 0,
                        labelsExtraidas = labelsExtraidas
                    )
                }

                val numOutputs = interp.outputTensorCount
                val inResumo = if (ehNchw) "${inShape[2]}x${inShape[3]} NCHW $inType" else "${inShape[1]}x${inShape[2]} $inType"
                val isTwoOutSsd = numOutputs == 2 && isTwoOutputBoxAndScoresDetector(
                    interp.getOutputTensor(0).shape(),
                    interp.getOutputTensor(1).shape()
                )
                if (numOutputs >= 4 || isTwoOutSsd) {
                    val labelsValidas = labelsExtraidas.filter { it != "???" && it.isNotBlank() }
                    val prefixoArch = if (isTwoOutSsd) "SSD / EfficientDet" else "SSD"
                    return ModelInspectionReport(
                        compativel = true,
                        arquiteturaResumo = "Modelo pronto para uso",
                        detalhesTecnicos = "$prefixoArch ($inResumo) • ${labelsValidas.size} classes nos metadados",
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

                var segMaskChannels = 0
                if (numOutputs == 2) {
                    val outShape1 = interp.getOutputTensor(1).shape()
                    if (outShape1.size == 4) {
                        segMaskChannels = when {
                            outShape1[3] in 1..128 && outShape1[1] > 8 -> outShape1[3]
                            outShape1[1] in 1..128 && outShape1[3] > 8 -> outShape1[1]
                            else -> 32
                        }
                    }
                }

                val isEnd2End = numOutputs == 1 && outShape.size == 3 && outShape[2] == 6 && outShape[1] in 1..1000
                val isYoloRawTransposed = outShape.size == 3 && outShape[1] in 5..400 && outShape[2] > outShape[1]
                val isYoloRawNormal = outShape.size == 3 && outShape[2] in 5..400 && outShape[1] > outShape[2]

                if (!isEnd2End && !isYoloRawTransposed && !isYoloRawNormal) {
                    return ModelInspectionReport(
                        compativel = false,
                        arquiteturaResumo = "Formato não compatível",
                        detalhesTecnicos = "Saída ${outShape.toList()} ($outType)",
                        avisoUsuario = "Este modelo usa um formato que não marca caixas na câmera (ex.: saída incompatível).",
                        numClasses = 0,
                        labelsExtraidas = labelsExtraidas
                    )
                }

                val totalChannels = when {
                    isYoloRawTransposed -> outShape[1]
                    isYoloRawNormal -> outShape[2]
                    else -> 0
                }
                val numAnchors = when {
                    isYoloRawTransposed -> outShape[2]
                    isYoloRawNormal -> outShape[1]
                    else -> outShape[1]
                }
                val extraChannels = when {
                    segMaskChannels > 0 && totalChannels > 4 + segMaskChannels -> segMaskChannels
                    taskExtraida == "obb" && totalChannels > 5 -> 1
                    else -> 0
                }
                val hasObj = !isEnd2End && extraChannels == 0 && taskExtraida != "obb" && (
                    (labelsExtraidas.isNotEmpty() && totalChannels == 5 + labelsExtraidas.size) ||
                    (numAnchors in setOf(25200, 6300, 10647, 15120) && labelsExtraidas.isEmpty() && totalChannels == 6)
                )

                val numClasses = when {
                    isEnd2End -> labelsExtraidas.size.coerceAtLeast(1)
                    else -> (totalChannels - (if (hasObj) 5 else 4) - extraChannels).coerceAtLeast(1)
                }
                val archNome = when {
                    isEnd2End -> "YOLO End2End NMS"
                    segMaskChannels > 0 -> "YOLO Segmentação (Caixas)"
                    taskExtraida == "obb" -> "YOLO OBB"
                    hasObj -> "YOLOv5 (Objectness)"
                    else -> "YOLO Raw"
                }
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
            scoreThreshold: Float,
            hasObjectness: Boolean = false,
            minBoxNorm: Float = 0.004f
        ): List<FloatArray> {
            if (numAnchors <= 0 || numClasses <= 0) return emptyList()
            val lastIdx = numAnchors - 1
            val midIdx = numAnchors / 2
            val classOffset = if (hasObjectness) 5 else 4

            fun readVal(coordOrClass: Int, anchor: Int): Float =
                if (transposed) out[coordOrClass][anchor] else out[anchor][coordOrClass]

            val sampleIndices = LinkedHashSet<Int>(68).apply {
                add(0)
                add(midIdx)
                add(lastIdx)
                val step = max(1, numAnchors / 64)
                var idx = 0
                while (idx < numAnchors) {
                    add(idx)
                    idx += step
                }
            }

            var maxSampleCoord = 0f
            var minSampleScore = Float.MAX_VALUE
            var maxSampleScore = -Float.MAX_VALUE
            for (idx in sampleIndices) {
                for (coord in 0..3) {
                    val cv = readVal(coord, idx)
                    if (cv > maxSampleCoord) maxSampleCoord = cv
                }
                if (hasObjectness) {
                    val ov = readVal(4, idx)
                    if (ov < minSampleScore) minSampleScore = ov
                    if (ov > maxSampleScore) maxSampleScore = ov
                }
                for (c in 0 until numClasses) {
                    val v = readVal(classOffset + c, idx)
                    if (v < minSampleScore) minSampleScore = v
                    if (v > maxSampleScore) maxSampleScore = v
                }
            }
            val coordsArePixels = maxSampleCoord > 2.0f
            val scaleX = if (coordsArePixels) 1f / inputWidth.coerceAtLeast(1).toFloat() else 1f
            val scaleY = if (coordsArePixels) 1f / inputHeight.coerceAtLeast(1).toFloat() else 1f
            val needsSigmoid = minSampleScore < -0.001f || maxSampleScore > 1.001f

            val candidates = mutableListOf<FloatArray>()
            for (i in 0 until numAnchors) {
                val prob: Float
                var bestClass = 0
                val probClass0: Float

                if (hasObjectness) {
                    val rawObj = readVal(4, i)
                    val objProb = if (needsSigmoid) sigmoidFn(rawObj) else rawObj.coerceIn(0f, 1f)
                    if (objProb < scoreThreshold) continue

                    var bestClsRaw = -Float.MAX_VALUE
                    for (c in 0 until numClasses) {
                        val s = readVal(5 + c, i)
                        if (s > bestClsRaw) {
                            bestClsRaw = s
                            bestClass = c
                        }
                    }
                    val clsProb = if (needsSigmoid) sigmoidFn(bestClsRaw) else bestClsRaw.coerceIn(0f, 1f)
                    prob = (objProb * clsProb).coerceIn(0f, 1f)
                    if (prob < scoreThreshold) continue
                    val rawC0 = readVal(5, i)
                    val c0Prob = if (needsSigmoid) sigmoidFn(rawC0) else rawC0.coerceIn(0f, 1f)
                    probClass0 = (objProb * c0Prob).coerceIn(0f, 1f)
                } else {
                    var rawScore = -Float.MAX_VALUE
                    val rawClass0 = readVal(4, i)
                    for (c in 0 until numClasses) {
                        val s = readVal(4 + c, i)
                        if (s > rawScore) {
                            rawScore = s
                            bestClass = c
                        }
                    }
                    prob = if (needsSigmoid) sigmoidFn(rawScore) else rawScore.coerceIn(0f, 1f)
                    if (prob < scoreThreshold) continue
                    probClass0 = if (needsSigmoid) sigmoidFn(rawClass0) else rawClass0.coerceIn(0f, 1f)
                }

                val normCx = readVal(0, i) * scaleX
                val normCy = readVal(1, i) * scaleY
                val normW = readVal(2, i) * scaleX
                val normH = readVal(3, i) * scaleY

                if (normW > minBoxNorm && normH > minBoxNorm &&
                    normW < 0.98f && normH < 0.98f &&
                    normCx > 0.002f && normCy > 0.002f &&
                    normCx < 0.998f && normCy < 0.998f
                ) {
                    if (numClasses == 4 && !hasObjectness) {
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
            var maxSampleCoord = maxOf(
                out[0][2], out[0][3],
                out[mid][2], out[mid][3],
                out[last][2], out[last][3]
            )
            for (i in 0 until numRows) {
                if (out[i][4] > 0.005f) {
                    maxSampleCoord = maxOf(maxSampleCoord, out[i][2], out[i][3])
                }
            }
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
                if (score >= scoreThreshold && w > 0.004f && h > 0.004f && w < 0.99f && h < 0.99f) {
                    val cx = (x1 + x2) / 2f
                    val cy = (y1 + y2) / 2f
                    candidates.add(floatArrayOf(cx, cy, w, h, score.coerceIn(0f, 1f), cls.toFloat(), score))
                }
            }
            return candidates
        }

        private fun normalizarChaveLabel(s: String): String {
            val nfd = java.text.Normalizer.normalize(
                s.trim().lowercase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD
            )
            return nfd.replace(Regex("\\p{M}+"), "")
        }

        internal fun labelEquivalente(a: String, b: String): Boolean {
            val na = normalizarChaveLabel(a)
            val nb = normalizarChaveLabel(b)
            if (na.isEmpty() || nb.isEmpty()) return false
            if (na == nb) return true
            val aliases = mapOf(
                "maca" to "apple",
                "apple" to "maca",
                "laranja" to "orange",
                "orange" to "laranja"
            )
            return aliases[na] == nb
        }

        internal fun isTwoOutputBoxAndScoresDetector(shape0: IntArray, shape1: IntArray): Boolean {
            if (shape0.size != 3 || shape1.size != 3) return false
            if (shape0[0] != 1 || shape1[0] != 1) return false
            if (shape0[1] != shape1[1] || shape0[1] < 100) return false
            return (shape0[2] == 4 && shape1[2] >= 1) || (shape1[2] == 4 && shape0[2] >= 1)
        }

        internal fun generateEfficientDetAnchors(inputWidth: Int, inputHeight: Int): Array<FloatArray> {
            val wIn = inputWidth.coerceAtLeast(32).toFloat()
            val hIn = inputHeight.coerceAtLeast(32).toFloat()
            val sq2 = sqrt(2.0).toFloat()
            val invSq2 = (1.0 / sqrt(2.0)).toFloat()
            val aspects = arrayOf(
                1.0f to 1.0f,
                sq2 to invSq2,
                invSq2 to sq2
            )
            val list = ArrayList<FloatArray>(20000)
            for (level in 3..7) {
                val stride = 1 shl level
                val gridH = (inputHeight + stride - 1) / stride
                val gridW = (inputWidth + stride - 1) / stride
                val baseSize = 3.0f * stride
                for (y in 0 until gridH) {
                    val yc = (y + 0.5f) * stride / hIn
                    for (x in 0 until gridW) {
                        val xc = (x + 0.5f) * stride / wIn
                        for (o in 0..2) {
                            val octScale = 2.0.pow(o / 3.0).toFloat()
                            val s = baseSize * octScale
                            for ((arW, arH) in aspects) {
                                val ah = (s * arH) / hIn
                                val aw = (s * arW) / wIn
                                list.add(floatArrayOf(ah, aw, yc, xc))
                            }
                        }
                    }
                }
            }
            return list.toTypedArray()
        }

        internal fun decodeEfficientDetTwoOutputs(
            scores: Array<FloatArray>,
            boxes: Array<FloatArray>,
            anchors: Array<FloatArray>,
            numClasses: Int,
            scoreThreshold: Float,
            allowedClassIndices: Set<Int> = emptySet()
        ): List<FloatArray> {
            val n = min(scores.size, min(boxes.size, anchors.size))
            if (n <= 0 || numClasses <= 0) return emptyList()

            val indicesToCheck = if (allowedClassIndices.isNotEmpty()) {
                allowedClassIndices.filter { it in 0 until numClasses }.toIntArray()
            } else {
                IntArray(numClasses) { it }
            }
            if (indicesToCheck.isEmpty()) return emptyList()

            var minSampleScore = Float.MAX_VALUE
            var maxSampleScore = -Float.MAX_VALUE
            val step = max(1, n / 64)
            var sIdx = 0
            while (sIdx < n) {
                val row = scores[sIdx]
                val cols = min(numClasses, row.size)
                for (c in 0 until cols) {
                    val v = row[c]
                    if (v < minSampleScore) minSampleScore = v
                    if (v > maxSampleScore) maxSampleScore = v
                }
                sIdx += step
            }
            val needsSigmoid = minSampleScore < -0.001f || maxSampleScore > 1.001f
            val rawMin = if (needsSigmoid) {
                -ln((1.0 / scoreThreshold.coerceIn(0.01f, 0.99f)) - 1.0).toFloat()
            } else {
                0f
            }

            val candidates = mutableListOf<FloatArray>()
            for (i in 0 until n) {
                val sRow = scores[i]
                var bestClass = indicesToCheck[0]
                var bestRaw = -Float.MAX_VALUE
                for (c in indicesToCheck) {
                    if (c < sRow.size) {
                        val v = sRow[c]
                        if (v > bestRaw) {
                            bestRaw = v
                            bestClass = c
                        }
                    }
                }
                if (needsSigmoid && bestRaw < rawMin) continue
                val prob = if (needsSigmoid) sigmoidFn(bestRaw) else bestRaw.coerceIn(0f, 1f)
                if (prob < scoreThreshold) continue

                val a = anchors[i]
                val b = boxes[i]
                val yc = (b[0] / 10f) * a[0] + a[2]
                val xc = (b[1] / 10f) * a[1] + a[3]
                val h = exp((b[2] / 5f).coerceIn(-10f, 10f).toDouble()).toFloat() * a[0]
                val w = exp((b[3] / 5f).coerceIn(-10f, 10f).toDouble()).toFloat() * a[1]

                val x1 = (xc - w / 2f).coerceIn(0f, 1f)
                val y1 = (yc - h / 2f).coerceIn(0f, 1f)
                val x2 = (xc + w / 2f).coerceIn(0f, 1f)
                val y2 = (yc + h / 2f).coerceIn(0f, 1f)
                val cw = x2 - x1
                val ch = y2 - y1
                if (cw > 0.004f && ch > 0.004f) {
                    candidates.add(floatArrayOf((x1 + x2) / 2f, (y1 + y2) / 2f, cw, ch, prob, bestClass.toFloat(), prob))
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

        internal fun extrairTaskMetadadosUltralytics(textoAscii: String): String? {
            val match = Regex("""(?im)\btask\s*:\s*['"]?(detect|segment|obb|classify|pose)['"]?""").find(textoAscii)
            return match?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)
        }

        internal fun extrairTaskDeArquivo(file: File): String? {
            return try {
                val tamanho = file.length()
                val bytesLer = min(tamanho, 262_144L).toInt()
                if (bytesLer <= 16) return null
                val tail = ByteArray(bytesLer)
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(tamanho - bytesLer)
                    raf.readFully(tail)
                }
                extrairTaskMetadadosUltralytics(String(tail, Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }

        internal fun parseLabelsDeTextoOuYaml(conteudo: String, nomeArquivo: String = ""): List<String> {
            val texto = conteudo.trim()
            if (texto.isEmpty()) return emptyList()

            val ehYaml = nomeArquivo.endsWith(".yaml", ignoreCase = true) ||
                nomeArquivo.endsWith(".yml", ignoreCase = true) ||
                Regex("""(?m)^\s*names\s*:""").containsMatchIn(texto)

            if (ehYaml) {
                val dictLabels = extrairLabelsMetadadosUltralytics(texto)
                if (dictLabels.isNotEmpty()) return dictLabels

                val inlineListMatch = Regex("""(?m)^\s*names\s*:\s*\[([^\]]+)\]""").find(texto)
                if (inlineListMatch != null) {
                    val itens = inlineListMatch.groupValues[1]
                        .split(",")
                        .map { it.trim().trim('\'', '"').trim() }
                        .filter { it.isNotEmpty() }
                    if (itens.isNotEmpty()) return itens
                }

                val linhas = texto.lines()
                val idxNames = linhas.indexOfFirst { Regex("""^\s*names\s*:\s*(#.*)?$""").matches(it) }
                if (idxNames >= 0) {
                    val pares = linkedMapOf<Int, String>()
                    val listaSeq = mutableListOf<String>()
                    for (i in (idxNames + 1) until linhas.size) {
                        val raw = linhas[i]
                        val trim = raw.trim()
                        if (trim.isEmpty() || trim.startsWith("#")) continue
                        if (!raw.startsWith(" ") && !raw.startsWith("\t") && !trim.startsWith("-")) break

                        val mMap = Regex("""^\s*(\d{1,3})\s*:\s*['"]?([^'"#\r\n]+?)['"]?\s*(?:#.*)?$""").find(raw)
                        if (mMap != null) {
                            val idx = mMap.groupValues[1].toIntOrNull()
                            val nome = mMap.groupValues[2].trim()
                            if (idx != null && nome.isNotEmpty()) pares[idx] = nome
                            continue
                        }
                        val mItem = Regex("""^\s*-\s*['"]?([^'"#\r\n]+?)['"]?\s*(?:#.*)?$""").find(raw)
                        if (mItem != null) {
                            val nome = mItem.groupValues[1].trim()
                            if (nome.isNotEmpty()) listaSeq.add(nome)
                        }
                    }
                    if (pares.isNotEmpty()) {
                        val maxIdx = pares.keys.maxOrNull() ?: 0
                        return (0..maxIdx).mapNotNull { pares[it] }
                    }
                    if (listaSeq.isNotEmpty()) return listaSeq
                }
            }

            return texto.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }

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
            if (labels.isEmpty() && file.name.equals("detector_doenca.tflite", ignoreCase = true)) {
                labels.addAll(DEFAULT_4CLASS_DOENCA_LABELS)
            }
            return labels
        }
    }
}
