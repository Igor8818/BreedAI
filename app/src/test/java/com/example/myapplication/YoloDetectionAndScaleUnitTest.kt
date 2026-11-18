package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Testes unitários para a lógica de inspeção de tensores, escalas e NMS do YOLOv11/v8.
 */
class YoloDetectionAndScaleUnitTest {

    @Test
    fun testCoordsArePixels_MultiAnchorCheck_PaddingEdgeDoesNotFail() {
        val yoloNumAnchors = 8400
        val lastIdx = yoloNumAnchors - 1
        val midIdx = yoloNumAnchors / 2

        // Simula tensor transposto [1, 5, 8400] onde a âncora 0 está na borda e tem cx < 2.0f
        val out = Array(1) { Array(5) { FloatArray(yoloNumAnchors) } }
        out[0][0][0] = 1.2f // Âncora 0 (borda de padding 4px / stride 8)
        out[0][0][midIdx] = 320.0f // Âncora central (320px)
        out[0][0][lastIdx] = 624.0f // Âncora final (624px)
        out[0][2][lastIdx] = 85.0f // w final

        // Antiga lógica (cheque APENAS na âncora 0):
        val sampleCxOld = out[0][0][0]
        val sampleWOld = out[0][2][0]
        val coordsArePixelsOld = (sampleCxOld > 2.0f || sampleWOld > 2.0f)
        // A lógica antiga falhava e dava false!
        assertFalse("Lógica antiga falhava na âncora 0", coordsArePixelsOld)

        // Nova lógica multi-âncora implementada no SDD-002:
        val maxSampleCoord = maxOf(out[0][0][0], out[0][0][midIdx], out[0][0][lastIdx], out[0][2][lastIdx])
        val coordsArePixelsNew = maxSampleCoord > 2.0f

        assertTrue("Nova lógica multi-âncora identifica corretamente escala em pixels", coordsArePixelsNew)
        val scaleX = if (coordsArePixelsNew) (1f / 640f) else 1f
        assertEquals(1f / 640f, scaleX, 0.00001f)
    }

    @Test
    fun testCoordsArePixels_WhenTrulyNormalized() {
        val yoloNumAnchors = 8400
        val lastIdx = yoloNumAnchors - 1
        val midIdx = yoloNumAnchors / 2

        // Modelo cujas saídas já vêm normalizadas entre 0.0 e 1.0
        val out = Array(1) { Array(5) { FloatArray(yoloNumAnchors) } }
        out[0][0][0] = 0.05f
        out[0][0][midIdx] = 0.50f
        out[0][0][lastIdx] = 0.95f
        out[0][2][lastIdx] = 0.30f

        val maxSampleCoord = maxOf(out[0][0][0], out[0][0][midIdx], out[0][0][lastIdx], out[0][2][lastIdx])
        val coordsArePixels = maxSampleCoord > 2.0f

        assertFalse("Modelo normalizado não deve ativar coordsArePixels", coordsArePixels)
        val scaleX = if (coordsArePixels) (1f / 640f) else 1f
        assertEquals(1.0f, scaleX, 0.00001f)
    }

    @Test
    fun testNeedsSigmoid_Detection() {
        val yoloNumAnchors = 8400
        val lastIdx = yoloNumAnchors - 1
        val midIdx = yoloNumAnchors / 2

        // Caso 1: Modelo exportado com scores brutos (logits [-10..+10])
        val rawOut = Array(1) { Array(5) { FloatArray(yoloNumAnchors) } }
        rawOut[0][4][0] = -3.5f
        rawOut[0][4][midIdx] = 2.1f
        rawOut[0][4][lastIdx] = 5.8f

        val minSampleRaw = minOf(rawOut[0][4][0], rawOut[0][4][midIdx], rawOut[0][4][lastIdx])
        val maxSampleRaw = maxOf(rawOut[0][4][0], rawOut[0][4][midIdx], rawOut[0][4][lastIdx])
        val needsSigmoidRaw = (minSampleRaw < -0.001f || maxSampleRaw > 1.001f)
        assertTrue("Logits brutos exigem aplicação de sigmoid", needsSigmoidRaw)

        // Caso 2: detector.tflite com Sigmoid já embutido no grafo (OP[321])
        val postSigmoidOut = Array(1) { Array(5) { FloatArray(yoloNumAnchors) } }
        postSigmoidOut[0][4][0] = 0.01f
        postSigmoidOut[0][4][midIdx] = 0.65f
        postSigmoidOut[0][4][lastIdx] = 0.92f

        val minSamplePost = minOf(postSigmoidOut[0][4][0], postSigmoidOut[0][4][midIdx], postSigmoidOut[0][4][lastIdx])
        val maxSamplePost = maxOf(postSigmoidOut[0][4][0], postSigmoidOut[0][4][midIdx], postSigmoidOut[0][4][lastIdx])
        val needsSigmoidPost = (minSamplePost < -0.001f || maxSamplePost > 1.001f)
        assertFalse("Scores já em [0..1] não devem sofrer sigmoid duplo", needsSigmoidPost)
    }

    @Test
    fun testNMS_SuppressesDuplicatesOnSameFruit() {
        // [normCx, normCy, normW, normH, prob, classIdx]
        val candidate1 = floatArrayOf(0.40f, 0.50f, 0.20f, 0.30f, 0.92f, 0f) // Melhor caixa
        val candidate2 = floatArrayOf(0.41f, 0.51f, 0.21f, 0.29f, 0.81f, 0f) // Duplicata com IoU alto
        val candidate3 = floatArrayOf(0.42f, 0.49f, 0.19f, 0.31f, 0.65f, 0f) // Outra duplicata
        val candidateSeparateFruit = floatArrayOf(0.75f, 0.25f, 0.18f, 0.25f, 0.88f, 0f) // Fruto diferente distante

        val candidates = listOf(candidate1, candidate2, candidate3, candidateSeparateFruit)
        val result = nms(candidates, iouThresh = 0.45f)

        // Deve manter exatamente 2 caixas: candidate1 (melhor do fruto 1) e candidateSeparateFruit (fruto 2)
        assertEquals(2, result.size)
        assertEquals(0.92f, result[0][4], 0.001f)
        assertEquals(0.88f, result[1][4], 0.001f)
    }

    @Test
    fun testDetectorTfliteAsset_BinaryIntegrityAndTensorContract() {
        val candidates = listOf(
            java.io.File("src/main/assets/detector.tflite"),
            java.io.File("app/src/main/assets/detector.tflite")
        )
        val modelFile = candidates.firstOrNull { it.exists() }
        assertNotNull("O modelo detector.tflite deve existir na pasta assets do projeto", modelFile)
        assertTrue("O modelo detector.tflite deve ter mais de 10 MB (YOLOv8 Float32)", modelFile!!.length() > 10_000_000L)

        // Verifica Magic Header FlatBuffer TFLite ("TFL3" nos bytes 4..7)
        val header = ByteArray(16)
        modelFile.inputStream().use { it.read(header) }
        val magic = String(header, 4, 4, Charsets.US_ASCII)
        assertEquals("Cabeçalho FlatBuffer TFLite válido (TFL3)", "TFL3", magic)

        // Inspeciona o rodapé de metadados do grafo para confirmar tensor de entrada (inputs_0)
        val tailSize = 200_000.coerceAtMost(modelFile.length().toInt())
        val tailBytes = ByteArray(tailSize)
        java.io.RandomAccessFile(modelFile, "r").use { raf ->
            raf.seek(modelFile.length() - tailSize)
            raf.readFully(tailBytes)
        }
        val tailAscii = String(tailBytes, Charsets.US_ASCII)
        assertTrue("Grafo TFLite deve conter tensor de entrada 'inputs_0'", tailAscii.contains("inputs_0"))
    }

    @Test
    fun testDetectorDoencaTfliteAsset_BinaryIntegrityAndTensorContract() {
        val candidates = listOf(
            java.io.File("src/main/assets/detector_doenca.tflite"),
            java.io.File("app/src/main/assets/detector_doenca.tflite")
        )
        val modelFile = candidates.firstOrNull { it.exists() }
        assertNotNull("O modelo detector_doenca.tflite deve existir na pasta assets do projeto", modelFile)
        assertTrue("O modelo detector_doenca.tflite deve ter mais de 10 MB (YOLO11n Float32)", modelFile!!.length() > 10_000_000L)

        val header = ByteArray(16)
        modelFile.inputStream().use { it.read(header) }
        val magic = String(header, 4, 4, Charsets.US_ASCII)
        assertEquals("Cabeçalho FlatBuffer TFLite válido (TFL3)", "TFL3", magic)

        val tailSize = 250_000.coerceAtMost(modelFile.length().toInt())
        val tailBytes = ByteArray(tailSize)
        java.io.RandomAccessFile(modelFile, "r").use { raf ->
            raf.seek(modelFile.length() - tailSize)
            raf.readFully(tailBytes)
        }
        val tailAscii = String(tailBytes, Charsets.US_ASCII)
        assertTrue(
            "Grafo TFLite do modelo de doenças deve conter tensores 'images' ou 'Identity'",
            tailAscii.contains("images") || tailAscii.contains("Identity")
        )
    }

    @Test
    fun testFundirContagemEDiagnosticoBreedAI_UpgradesGenericLabelsAndKeepsCount() {
        val contagem = listOf(
            com.example.myapplication.userinterface.DetEstilizada(0.10f, 0.10f, 0.30f, 0.40f, 0.90f, "cacau"),
            com.example.myapplication.userinterface.DetEstilizada(0.50f, 0.20f, 0.70f, 0.50f, 0.86f, "cacau")
        )
        val secundario = listOf(
            com.example.myapplication.userinterface.DetEstilizada(0.11f, 0.11f, 0.29f, 0.39f, 0.79f, "VassouraBruxa"),
            com.example.myapplication.userinterface.DetEstilizada(0.20f, 0.65f, 0.40f, 0.90f, 0.72f, "PodridaoParda")
        )

        val resultado = com.example.myapplication.userinterface.fundirContagemEDiagnosticoBreedAI(contagem, secundario)

        assertEquals(3, resultado.size)
        assertEquals("VassouraBruxa", resultado[0].rotulo)
        assertEquals("cacau", resultado[1].rotulo)
        assertEquals("PodridaoParda", resultado[2].rotulo)
    }

    private fun nms(boxes: List<FloatArray>, iouThresh: Float = 0.45f): List<FloatArray> {
        val sorted = boxes.sortedByDescending { it[4] }.toMutableList()
        val result = mutableListOf<FloatArray>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best, it) > iouThresh }
        }
        return result
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ax1 = a[0] - a[2] / 2f; val ay1 = a[1] - a[3] / 2f
        val ax2 = a[0] + a[2] / 2f; val ay2 = a[1] + a[3] / 2f
        val bx1 = b[0] - b[2] / 2f; val by1 = b[1] - b[3] / 2f
        val bx2 = b[0] + b[2] / 2f; val by2 = b[1] + b[3] / 2f
        val inter = max(0f, min(ax2, bx2) - max(ax1, bx1)) * max(0f, min(ay2, by2) - max(ay1, by1))
        val union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
        return if (union <= 0f) 0f else inter / union
    }

    @Test
    fun `testExtractUltralyticsMetadataLabels_ParsesNamesDictionary`() {
        val rawMetadata = """
            task: detect
            stride: 32
            names: {0: 'Verde', 1: 'Maduro', 2: "Podre", 3: 'Vassoura'}
        """.trimIndent()

        val labels = com.example.myapplication.userinterface.util.ObjectDetectorHelper
            .extrairLabelsMetadadosUltralytics(rawMetadata)

        assertEquals(listOf("Verde", "Maduro", "Podre", "Vassoura"), labels)
    }

    @Test
    fun `testYoloEnd2EndNmsDecoding`() {
        // Modelo End2End [1, N, 6]: cada linha = [x1, y1, x2, y2, score, classId]
        val rows = arrayOf(
            floatArrayOf(0.10f, 0.20f, 0.30f, 0.40f, 0.92f, 2f),
            floatArrayOf(0.50f, 0.50f, 0.70f, 0.70f, 0.15f, 0f), // abaixo do limiar
            floatArrayOf(0.40f, 0.40f, 0.60f, 0.80f, 0.85f, 1f)
        )

        val decoded = com.example.myapplication.userinterface.util.ObjectDetectorHelper
            .decodeYoloEnd2EndNms(
                out = rows,
                numRows = rows.size,
                inputWidth = 640,
                inputHeight = 640,
                scoreThreshold = 0.25f
            )

        assertEquals(2, decoded.size)
        assertEquals(0.92f, decoded[0][4], 0.001f)
        assertEquals(2f, decoded[0][5], 0.001f)
        // [x1=0.10, x2=0.30] -> cx = 0.20 (ou 0.20 * 640 = 128), w = 0.20 (ou 128)
        assertEquals(0.20f, if (decoded[0][0] > 1f) decoded[0][0] / 640f else decoded[0][0], 0.001f)
        assertEquals(0.20f, if (decoded[0][2] > 1f) decoded[0][2] / 640f else decoded[0][2], 0.001f)

        // NMS multi-classe deve preservar detecções de classes diferentes com sobreposição moderada
        val boxesMulti = listOf(
            floatArrayOf(0.50f, 0.50f, 0.20f, 0.20f, 0.90f, 0f),
            floatArrayOf(0.52f, 0.52f, 0.20f, 0.20f, 0.80f, 1f), // outra classe, IoU ~0.68 (<0.85) -> mantida
            floatArrayOf(0.51f, 0.51f, 0.20f, 0.20f, 0.70f, 0f)  // mesma classe, IoU >0.45 -> suprimida
        )
        val nmsRes = com.example.myapplication.userinterface.util.ObjectDetectorHelper
            .nmsMultiClasse(boxesMulti, iouMesmaClasse = 0.45f, iouEntreClasses = 0.85f)
        assertEquals(2, nmsRes.size)
        assertEquals(0f, nmsRes[0][5], 0.001f)
        assertEquals(1f, nmsRes[1][5], 0.001f)
    }

    @Test
    fun `testFundirContagemEDiagnosticoBreedAI_LowIouAndCenterProximityPairsDiseaseToPod`() {
        // Caixa grande de contagem (detector.tflite) cobrindo o fruto inteiro
        val contagem = listOf(
            com.example.myapplication.userinterface.DetEstilizada(0.20f, 0.20f, 0.60f, 0.80f, 0.91f, "cacau")
        )
        // Caixa menor de lesão/doença centrada dentro do mesmo fruto (IoU < 0.25, mas centro próximo)
        val secundario = listOf(
            com.example.myapplication.userinterface.DetEstilizada(0.32f, 0.36f, 0.48f, 0.60f, 0.74f, "VassouraBruxa")
        )

        val resultado = com.example.myapplication.userinterface.fundirContagemEDiagnosticoBreedAI(contagem, secundario)

        assertEquals(1, resultado.size)
        assertEquals("VassouraBruxa", resultado[0].rotulo)
        assertEquals(0.20f, resultado[0].normLeft, 0.001f)
    }

    @Test
    fun `testDecodeYoloRawCandidates_4ClassPreservesAllProbs`() {
        val out = Array(1) { FloatArray(8) } // [1, 4+4] não-transposto: cx, cy, w, h, p0, p1, p2, p3
        out[0] = floatArrayOf(0.50f, 0.50f, 0.30f, 0.40f, 0.22f, 0.12f, 0.61f, 0.18f)

        val candidates = com.example.myapplication.userinterface.util.ObjectDetectorHelper.decodeYoloRawCandidates(
            out = out,
            numAnchors = 1,
            numClasses = 4,
            transposed = false,
            inputWidth = 640,
            inputHeight = 640,
            scoreThreshold = 0.25f
        )

        assertEquals(1, candidates.size)
        assertEquals(10, candidates[0].size)
        assertEquals(0.61f, candidates[0][4], 0.001f)
        assertEquals(2f, candidates[0][5], 0.001f)
        assertEquals(0.22f, candidates[0][6], 0.001f)
        assertEquals(0.12f, candidates[0][7], 0.001f)
        assertEquals(0.61f, candidates[0][8], 0.001f)
        assertEquals(0.18f, candidates[0][9], 0.001f)
    }

    @Test
    fun `testDecodeYoloV5WithObjectness_SingleClassDoesNotCreateGhostClass`() {
        val out = arrayOf(
            floatArrayOf(0.5f, 0.5f, 0.3f, 0.4f, 0.90f, 0.80f)
        )
        val candidates = com.example.myapplication.userinterface.util.ObjectDetectorHelper.decodeYoloRawCandidates(
            out = out,
            numAnchors = 1,
            numClasses = 1,
            transposed = false,
            inputWidth = 640,
            inputHeight = 640,
            scoreThreshold = 0.25f,
            hasObjectness = true
        )

        assertEquals(1, candidates.size)
        assertEquals(0f, candidates[0][5], 0.001f)
        assertEquals(0.72f, candidates[0][4], 0.001f)

        val lowObjOut = arrayOf(
            floatArrayOf(0.5f, 0.5f, 0.3f, 0.4f, 0.10f, 0.95f)
        )
        val lowObjCandidates = com.example.myapplication.userinterface.util.ObjectDetectorHelper.decodeYoloRawCandidates(
            out = lowObjOut,
            numAnchors = 1,
            numClasses = 1,
            transposed = false,
            inputWidth = 640,
            inputHeight = 640,
            scoreThreshold = 0.25f,
            hasObjectness = true
        )
        assertTrue(lowObjCandidates.isEmpty())
    }

    @Test
    fun `testParseLabelsDeTextoOuYaml_SupportsRoboflowYamlAndTxt`() {
        val roboflowYaml = """
            train: ../train/images
            val: ../valid/images
            nc: 3
            names: ['Ferrugem', 'Cercospora', 'Sadio']
        """.trimIndent()
        assertEquals(
            listOf("Ferrugem", "Cercospora", "Sadio"),
            com.example.myapplication.userinterface.util.ObjectDetectorHelper.parseLabelsDeTextoOuYaml(
                roboflowYaml,
                "data.yaml"
            )
        )

        val ultralyticsYaml = """
            names:
              0: Sadio
              1: Vassoura
              2: Podridao
        """.trimIndent()
        assertEquals(
            listOf("Sadio", "Vassoura", "Podridao"),
            com.example.myapplication.userinterface.util.ObjectDetectorHelper.parseLabelsDeTextoOuYaml(
                ultralyticsYaml,
                "metadata.yaml"
            )
        )

        val txt = "# classes\nEspiga\nFolha\n"
        assertEquals(
            listOf("Espiga", "Folha"),
            com.example.myapplication.userinterface.util.ObjectDetectorHelper.parseLabelsDeTextoOuYaml(
                txt,
                "classes.txt"
            )
        )
    }

    @Test
    fun `testMultiAnchor64Sampling_DetectsLogitsWhenCornersAreZero`() {
        val out = Array(100) { FloatArray(5) }
        out[20] = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, -4.0f)
        out[37] = floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, 3.0f)

        val candidates = com.example.myapplication.userinterface.util.ObjectDetectorHelper.decodeYoloRawCandidates(
            out = out,
            numAnchors = 100,
            numClasses = 1,
            transposed = false,
            inputWidth = 640,
            inputHeight = 640,
            scoreThreshold = 0.25f,
            hasObjectness = false
        )

        assertEquals(1, candidates.size)
        assertEquals(0.95257f, candidates[0][4], 0.002f)
    }

    @Test
    fun `testIsTwoOutputBoxAndScoresDetector_DistinguishesEfficientDetFromYoloSeg`() {
        assertTrue(
            com.example.myapplication.userinterface.util.ObjectDetectorHelper.isTwoOutputBoxAndScoresDetector(
                intArrayOf(1, 19206, 90),
                intArrayOf(1, 19206, 4)
            )
        )
        assertFalse(
            com.example.myapplication.userinterface.util.ObjectDetectorHelper.isTwoOutputBoxAndScoresDetector(
                intArrayOf(1, 37, 8400),
                intArrayOf(1, 160, 160, 32)
            )
        )
    }

    @Test
    fun `testDecodeEfficientDetTwoOutputs_Generates19206AnchorsAndDecodesBananaAndApple`() {
        val anchors = com.example.myapplication.userinterface.util.ObjectDetectorHelper
            .generateEfficientDetAnchors(320, 320)
        assertEquals(19206, anchors.size)
        assertEquals(0.075f, anchors[0][0], 0.0002f)
        assertEquals(0.075f, anchors[0][1], 0.0002f)
        assertEquals(0.0125f, anchors[0][2], 0.0002f)
        assertEquals(0.0125f, anchors[0][3], 0.0002f)

        val scores = Array(19206) { FloatArray(90) { -10f } }
        val boxes = Array(19206) { FloatArray(4) { 0f } }
        // Isca de classe não permitida ("person" = 0) com confiança mais alta
        scores[100][0] = 4.0f
        // Classe alvo "banana" = 51 na âncora interior 7380 (y=20, x=20 no nível 3)
        scores[7380][51] = 2.5f

        val decoded = com.example.myapplication.userinterface.util.ObjectDetectorHelper
            .decodeEfficientDetTwoOutputs(
                scores = scores,
                boxes = boxes,
                anchors = anchors,
                numClasses = 90,
                scoreThreshold = 0.20f,
                allowedClassIndices = setOf(51)
            )

        assertEquals(1, decoded.size)
        assertEquals(51f, decoded[0][5], 0.001f)
        assertEquals(anchors[7380][3], decoded[0][0], 0.002f)
        assertEquals(anchors[7380][2], decoded[0][1], 0.002f)
    }
}
