package com.example.myapplication.userinterface.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.vision.detector.Detection
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

    private enum class ModelArchitecture {
        SSD_POST_PROCESS, // Modelo de 4 tensores de saída (SSD MobileNet)
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
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Índices e dimensões para SSD (4 saídas)
    private var ssdNumDetections = 10
    private var ssdBoxIndex = 0
    private var ssdClassIndex = 1
    private var ssdScoreIndex = 2
    private var ssdCountIndex = 3

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
            // Inicializa Interpreter TFLite puro com 4 threads na CPU para máxima estabilidade universal
            val options = Interpreter.Options().apply {
                setNumThreads(4)
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
                architecture = ModelArchitecture.SSD_POST_PROCESS
                identifySsdOutputs()
                Log.d("Detector", "Arquitetura identificada: SSD (boxes=$ssdBoxIndex, classes=$ssdClassIndex, scores=$ssdScoreIndex, count=$ssdCountIndex, maxDets=$ssdNumDetections)")
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
                    if (name.contains("score")) {
                        ssdScoreIndex = i
                    } else if (name.contains("class") || name.contains("category")) {
                        ssdClassIndex = i
                    } else {
                        if (i == 1) ssdClassIndex = i
                        if (i == 2) ssdScoreIndex = i
                    }
                }
            }
        }
    }

    private fun buildQuantLutIfNeeded(
        type: DataType,
        quant: org.tensorflow.lite.Tensor.QuantizationParams
    ): ByteArray? {
        if (type != DataType.UINT8 && type != DataType.INT8) return null
        val scale = if (quant.scale > 0f) quant.scale else 1f
        val zp = quant.zeroPoint
        val identityUint8 = (type == DataType.UINT8 && scale in 0.99f..1.01f && zp == 0)
        return ByteArray(256) { v ->
            val q = when {
                identityUint8 -> v
                type == DataType.UINT8 -> ((v / 255f) / scale + zp).roundToInt().coerceIn(0, 255)
                else -> ((v / 255f) / scale + zp).roundToInt().coerceIn(-128, 127)
            }
            q.toByte()
        }
    }

    fun obterLabelsModelo(): List<String> = synchronized(lock) { modelLabels.toList() }

    private fun extractLabelsFromModelFile(file: File) {
        modelLabels.clear()
        modelLabels.addAll(extractLabelsFromFile(file))
    }

    fun detect(image: Bitmap) {
        val resultado = executarInferencia(image) ?: return
        val listener = synchronized(lock) { detectorListener }
        listener?.onResults(resultado)
    }

    /**
     * Executa inferência de forma síncrona diretamente sobre um Bitmap (ex: foto estática em alta resolução na Tela de Revisão).
     * É thread-safe e reutiliza a calibração geométrica isomórfica (Letterbox) e NMS.
     */
    fun detectarSincrono(image: Bitmap): ResultadoDeteccao? {
        return executarInferencia(image)
    }

    private fun executarInferencia(image: Bitmap): ResultadoDeteccao? = synchronized(lock) {
        if (isClosed) return null

        val imgW = image.width.toFloat()
        val imgH = image.height.toFloat()
        if (imgW <= 0f || imgH <= 0f) return null
        lastBitmapWidth = imgW
        lastBitmapHeight = imgH

        val interp = interpreter ?: run { setup(); interpreter ?: return null }
        val finalDetections = mutableListOf<Detection>()

        try {
            // 1. Prepara buffer de entrada via Letterbox Isomórfico (mantém proporção original do fruto sem achatamento)
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
            val destRect = RectF(padX, padY, padX + newW, padY + newH)
            canvas.drawBitmap(image, null, destRect, letterboxPaint)

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
            } else {
                buf.rewind()
            }

            if (lut != null) {
                for (px in pixels) {
                    buf.put(lut[(px shr 16) and 0xFF])
                    buf.put(lut[(px shr 8)  and 0xFF])
                    buf.put(lut[px          and 0xFF])
                }
            } else {
                for (px in pixels) {
                    buf.putFloat(((px shr 16) and 0xFF) / 255f)
                    buf.putFloat(((px shr 8)  and 0xFF) / 255f)
                    buf.putFloat((px          and 0xFF) / 255f)
                }
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

                for (i in 0 until numDetected) {
                    val score = scores[0][i]
                    if (score < scoreThreshold) continue

                    val classIdx = classes[0][i].toInt()
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
        if (outputDataType == DataType.UINT8 || outputDataType == DataType.INT8) {
            val byteOut = Array(1) { Array(dim1) { ByteArray(dim2) } }
            interp.run(inputBuf, byteOut)
            val isUint8 = outputDataType == DataType.UINT8
            return Array(dim1) { r ->
                FloatArray(dim2) { c ->
                    val q = if (isUint8) (byteOut[0][r][c].toInt() and 0xFF) else byteOut[0][r][c].toInt()
                    (q - outQuantZeroPoint) * outQuantScale
                }
            }
        }
        val floatOut = Array(1) { Array(dim1) { FloatArray(dim2) } }
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
                    y2Tensor = y2Tensor
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
        interpreter?.close()
        interpreter = null
        loadedPath = null
        quantByteLut = null
        letterboxBitmap?.recycle()
        letterboxBitmap = null
        letterboxPixels = null
        inputByteBuffer = null
    }

    data class ResultadoDeteccao(
        val deteccoes: List<Detection>,
        val larguraImagem: Float,
        val alturaImagem: Float
    )

    interface DetectorListener {
        fun onResults(resultado: ResultadoDeteccao)
        fun onError(mensagem: String) {}
    }

    companion object {
        val DEFAULT_4CLASS_DOENCA_LABELS = listOf("PodridaoParda", "Moniliase", "Sadio", "VassouraBruxa")

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
            modelLabels.getOrNull(classIdx)?.takeIf { it.isNotBlank() }?.let { return it }

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
                    candidates.add(floatArrayOf(normCx, normCy, normW, normH, prob, bestClass.toFloat(), probClass0))
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
            y2Tensor: Float
        ): Int {
            if (classIdx != 0 && !(classIdx == 2 && probBest < 0.72f && probClass0 >= 0.20f)) {
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

            return if (classIdx == 0) {
                val isColdGrayDry = rbDiff < 0.145f && satMean < 0.265f && valMean < 0.68f
                val isDarkBrownDry = valMean < 0.46f && satMean < 0.45f && rbDiff < 0.24f
                val isPredominantlyNecroticMatte = darkMatteRatio >= 0.45f && valMean < 0.62f && rbDiff < 0.22f
                if (isColdGrayDry || isDarkBrownDry || isPredominantlyNecroticMatte) 3 else 0
            } else {
                if ((rbDiff < 0.10f && satMean < 0.20f && valMean < 0.58f) ||
                    (darkMatteRatio >= 0.55f && valMean < 0.52f && rbDiff < 0.18f)
                ) 3 else classIdx
            }
        }

        /**
         * Extrai rótulos (labelmap.txt / labels.txt) eventualmente embutidos nos metadados ZIP do .tflite.
         */
        fun extractLabelsFromFile(file: File): List<String> {
            val labels = mutableListOf<String>()
            try {
                file.inputStream().use { fis ->
                    val zis = ZipInputStream(fis)
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name.contains("label", ignoreCase = true)) {
                            zis.bufferedReader().lineSequence().forEach { line ->
                                val trimmed = line.trim()
                                if (trimmed.isNotEmpty()) labels.add(trimmed)
                            }
                            if (labels.isNotEmpty()) break
                        }
                        entry = zis.nextEntry
                    }
                }
            } catch (_: Exception) {
                // Metadados opcionais
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
