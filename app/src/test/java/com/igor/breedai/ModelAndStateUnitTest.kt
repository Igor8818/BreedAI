package com.igor.breedai

import android.net.Uri
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.LABELS_DOENCA_CACAU
import com.igor.breedai.data.MODELOS_EMBUTIDOS
import com.igor.breedai.data.MOTIVOS_NAO_AVALIAVEL
import com.igor.breedai.data.ModelConfig
import com.igor.breedai.data.PresetRendimento
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.data.TipoVariavelExtra
import com.igor.breedai.data.VariavelExtra
import com.igor.breedai.data.calcularRankingTratamentos
import com.igor.breedai.data.calcularRendimentoColeta
import com.igor.breedai.data.calcularResumoAgronomico
import com.igor.breedai.data.gerarResumoTextoColeta
import com.igor.breedai.data.variaveisExtrasIniciais
import com.igor.breedai.userinterface.CaixaAuditavel
import com.igor.breedai.userinterface.DetEstilizada
import com.igor.breedai.userinterface.deveUsarParDetectoresSecundario
import com.igor.breedai.userinterface.fundirContagemEDiagnosticoBreedAI
import com.igor.breedai.userinterface.resolverClassesFallbackRevisao
import com.igor.breedai.userinterface.rotuloPassaFiltroModelo
import com.igor.breedai.userinterface.util.ImportadorPlanilhaUniversal
import com.igor.breedai.userinterface.util.ObjectDetectorHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.tensorflow.lite.DataType
import java.io.File

class ModelAndStateUnitTest {

    @Test
    fun `modelos embutidos devem trazer modelo de contagem geral como padrao e modelo de doencas`() {
        assertEquals(4, MODELOS_EMBUTIDOS.size)
        val contagemModel = MODELOS_EMBUTIDOS.first()
        assertEquals("detector.tflite", contagemModel.nomeArquivo)
        assertTrue(contagemModel.labelsDefault.contains("cacau"))

        val doencaModel = MODELOS_EMBUTIDOS[1]
        assertEquals("detector_doenca.tflite", doencaModel.nomeArquivo)
        assertEquals("Cacau Doenças (Pré-carregado)", doencaModel.nomeExibicao)
        assertEquals(listOf("PodridaoParda", "Moniliase", "Sadio", "VassouraBruxa"), doencaModel.labelsDefault)

        val bananaModel = MODELOS_EMBUTIDOS[2]
        assertEquals("detector_frutas_coco.tflite", bananaModel.nomeArquivo)
        assertEquals(listOf("banana"), bananaModel.labelsDefault)

        val macaModel = MODELOS_EMBUTIDOS[3]
        assertEquals("detector_frutas_coco.tflite", macaModel.nomeArquivo)
        assertEquals(listOf("apple"), macaModel.labelsDefault)

        val defaultCfg = ModelConfig()
        assertTrue(defaultCfg.labelsFiltro.containsAll(contagemModel.labelsDefault))
    }

    @Test
    fun `testGeneric4ClassModel_DoesNotApplyCocoaColorHeuristic`() {
        val genericLabels = listOf("Verde", "Maduro", "Passado", "Defeito")
        assertFalse(
            com.igor.breedai.userinterface.util.ObjectDetectorHelper.isModeloDoencaCacau(
                modelPath = "tomate_v1.tflite",
                modelLabels = genericLabels,
                customLabels = emptyList()
            )
        )
        assertEquals(
            "Maduro",
            com.igor.breedai.userinterface.util.ObjectDetectorHelper.resolverRotuloPorIndice(
                classIdx = 1,
                numClasses = 4,
                modelLabels = genericLabels,
                customLabels = emptyList(),
                fallbackDefault = "objeto",
                modelPath = "tomate_v1.tflite"
            )
        )
        assertEquals(
            "Defeito",
            com.igor.breedai.userinterface.util.ObjectDetectorHelper.resolverRotuloPorIndice(
                classIdx = 3,
                numClasses = 4,
                modelLabels = genericLabels,
                customLabels = emptyList(),
                fallbackDefault = "objeto",
                modelPath = "tomate_v1.tflite"
            )
        )
        assertTrue(
            com.igor.breedai.userinterface.util.ObjectDetectorHelper.isModeloDoencaCacau(
                modelPath = "detector_doenca.tflite",
                modelLabels = emptyList(),
                customLabels = emptyList()
            )
        )
    }

    @Test
    fun `cores semanticas e resolucao multi-classe devem mapear doencas de cacau`() {
        val corSadio = com.igor.breedai.userinterface.corParaLabel("Sadio")
        val corVassoura = com.igor.breedai.userinterface.corParaLabel("VassouraBruxa")
        val corPodridao = com.igor.breedai.userinterface.corParaLabel("PodridaoParda")
        val corMoniliase = com.igor.breedai.userinterface.corParaLabel("Moniliase")

        assertNotEquals(corSadio, corVassoura)
        assertNotEquals(corVassoura, corPodridao)
        assertNotEquals(corPodridao, corMoniliase)
        assertEquals("Podridão", com.igor.breedai.userinterface.rotuloAmigavel("PodridaoParda"))
        assertEquals("Monilíase", com.igor.breedai.userinterface.rotuloAmigavel("Moniliase"))
        assertEquals("Vassoura", com.igor.breedai.userinterface.rotuloAmigavel("VassouraBruxa"))

        val resolved = com.igor.breedai.userinterface.util.ObjectDetectorHelper.resolverRotuloPorIndice(
            classIdx = 2,
            numClasses = 4,
            modelLabels = emptyList(),
            customLabels = listOf("PodridaoParda", "Moniliase", "Sadio", "VassouraBruxa", "cacau", "fruto"),
            fallbackDefault = "objeto"
        )
        assertEquals("Sadio", resolved)
    }

    @Test
    fun `configuracao de modelo deve serializar e restaurar manifesto JSON para zip`() {
        val cfg = ModelConfig(
            modeloSelecionado = MODELOS_EMBUTIDOS.first(),
            modeloInternoPath = "/data/user/0/com.igor.breedai/files/modelos_ia/soja_v3.tflite",
            modeloExternoNome = "Soja Vagem v3",
            labelsFiltro = listOf("vagem", "grao"),
            threshold = 0.42f,
            maxResultados = 30,
            usarModeloSecundario = true,
            modeloSecundarioArquivo = "detector_doenca.tflite"
        )
        assertEquals("Soja Vagem v3", cfg.nomeExibicao)

        val json = cfg.toManifestJson()
        val parsed = ModelConfig.fromManifestJson(json)
        assertNotNull(parsed)
        assertEquals("Soja Vagem v3", parsed!!.nomeExibicao)
        assertEquals(listOf("vagem", "grao"), parsed.labelsFiltro)
        assertEquals(0.42f, parsed.threshold, 0.001f)
        assertEquals(30, parsed.maxResultados)
        assertTrue(parsed.usarModeloSecundario)
        assertEquals("detector_doenca.tflite", parsed.modeloSecundarioArquivo)
    }

    @Test
    fun `situacao da planta e motivos nao avaliavel devem refletir padrao Field Book`() {
        assertEquals(listOf("Normal", "Sem Frutos", "Não avaliável"), SituacaoPlanta.entries.map { it.rotulo })
        assertEquals(listOf("Pequena", "Morta", "Errada", "Cavalo"), MOTIVOS_NAO_AVALIAVEL)
    }

    @Test
    fun `variaveis extras iniciais devem vir pre-cadastradas para toggle por coleta`() {
        val iniciais = variaveisExtrasIniciais()
        assertEquals(3, iniciais.size)
        assertTrue(iniciais.all { !it.ativa })
        assertEquals("Cor do Fruto", iniciais[0].nome)
        assertEquals(TipoVariavelExtra.LISTA_OPCOES, iniciais[0].tipo)
        assertEquals(listOf("Verde", "Amarelo", "Roxo", "Vermelho"), iniciais[0].opcoes)
        assertEquals("Nota de Vírus", iniciais[1].nome)
        assertEquals("Diâmetro do Tronco (cm)", iniciais[2].nome)
        assertEquals(TipoVariavelExtra.NUMERICO, iniciais[2].tipo)
    }

    @Test
    fun `caixa auditavel e concordancia de registro por situacao`() {
        val mockUri = Mockito.mock(Uri::class.java)
        val caixas = listOf(
            CaixaAuditavel(0.10f, 0.10f, 0.30f, 0.40f, rotulo = "cacau"),
            CaixaAuditavel(0.50f, 0.20f, 0.70f, 0.50f, rotulo = "cacau", manual = true)
        )

        val regNormalValido = RegistroColeta(
            uri = mockUri,
            idCompleto = "C01_T1_P1_1",
            coletaNome = "Ensaio_Soja",
            tratamento = "T1",
            parcela = "1",
            planta = "1",
            situacaoPlanta = SituacaoPlanta.NORMAL,
            diagnostico = "IA: 2",
            data = "2026-11-15 10:00",
            lat = -14.79,
            lng = -39.23,
            totalObjetos = 2,
            variaveisExtras = mapOf("Diâmetro do Tronco (cm)" to "14.2"),
            caixas = caixas
        )
        assertTrue(regNormalValido.isConcordante())

        val regSemFrutosValido = RegistroColeta(
            uri = mockUri,
            idCompleto = "C01_T1_P1_2",
            coletaNome = "Ensaio_Soja",
            tratamento = "T1",
            parcela = "1",
            planta = "2",
            situacaoPlanta = SituacaoPlanta.SEM_FRUTOS,
            diagnostico = "Sem Frutos (foto comprobatória)",
            data = "2026-11-15 10:01",
            lat = 0.0,
            lng = 0.0,
            totalObjetos = 0,
            caixas = emptyList()
        )
        assertTrue(regSemFrutosValido.isConcordante())

        val regNaoAvaliavelInvalido = RegistroColeta(
            uri = mockUri,
            idCompleto = "C01_T1_P1_3",
            coletaNome = "Ensaio_Soja",
            tratamento = "T1",
            parcela = "1",
            planta = "3",
            situacaoPlanta = SituacaoPlanta.NAO_AVALIAVEL,
            motivoNaoAvaliavel = "Morta",
            diagnostico = "Não avaliável: Morta",
            data = "2026-11-15 10:02",
            lat = 0.0,
            lng = 0.0,
            totalObjetos = 1,
            caixas = emptyList()
        )
        assertFalse(regNaoAvaliavelInvalido.isConcordante())
    }

    @Test
    fun `refinarClasseDoencaPorMorfologiaCor distingue VassouraBruxa seca escura de PodridaoParda quente`() {
        val w = 100
        val h = 100
        val pxVassoura = (0xFF shl 24) or (115 shl 16) or (105 shl 8) or 102
        val gridVassoura = IntArray(w * h) { pxVassoura }
        val clsVassoura = ObjectDetectorHelper.refinarClasseDoencaPorMorfologiaCor(
            classIdx = 0,
            probBest = 0.88f,
            probClass0 = 0.88f,
            pixels = gridVassoura,
            width = w,
            height = h,
            x1Tensor = 10f,
            y1Tensor = 10f,
            x2Tensor = 90f,
            y2Tensor = 90f
        )
        assertEquals(3, clsVassoura)

        val pxPodridao = (0xFF shl 24) or (215 shl 16) or (190 shl 8) or 140
        val gridPodridao = IntArray(w * h) { pxPodridao }
        val clsPodridao = ObjectDetectorHelper.refinarClasseDoencaPorMorfologiaCor(
            classIdx = 0,
            probBest = 0.90f,
            probClass0 = 0.90f,
            pixels = gridPodridao,
            width = w,
            height = h,
            x1Tensor = 10f,
            y1Tensor = 10f,
            x2Tensor = 90f,
            y2Tensor = 90f
        )
        assertEquals(0, clsPodridao)

        for ((r, g, b) in listOf(Triple(95, 72, 58), Triple(130, 105, 85))) {
            val pxSecoEscuro = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            val gridSecoEscuro = IntArray(w * h) { pxSecoEscuro }
            val clsSeco = ObjectDetectorHelper.refinarClasseDoencaPorMorfologiaCor(
                classIdx = 0,
                probBest = 0.85f,
                probClass0 = 0.85f,
                pixels = gridSecoEscuro,
                width = w,
                height = h,
                x1Tensor = 10f,
                y1Tensor = 10f,
                x2Tensor = 90f,
                y2Tensor = 90f
            )
            assertEquals("Esperado VassouraBruxa (3) para RGB($r,$g,$b)", 3, clsSeco)
        }
    }

    @Test
    fun `testCocoFruitModel_ExtractsEmbeddedLabelsAndResolvesAppleBananaOrange`() {
        val cocoAsset = listOf(
            File("app/src/main/assets/detector_frutas_coco.tflite"),
            File("src/main/assets/detector_frutas_coco.tflite")
        ).firstOrNull { it.exists() }
        assertNotNull("Asset detector_frutas_coco.tflite deve existir", cocoAsset)

        val header = ByteArray(16)
        cocoAsset!!.inputStream().use { it.read(header) }
        assertTrue("Header TFL3 deve ser reconhecido", ObjectDetectorHelper.isTfliteFlatBuffer(header))

        val labels = ObjectDetectorHelper.extractLabelsFromFile(cocoAsset)
        assertTrue("Labelmap embutido deve ter >= 85 entradas (obtido ${labels.size})", labels.size >= 85)
        assertEquals("person", labels.first())

        val banana = ObjectDetectorHelper.resolverRotuloPorIndice(
            classIdx = 51,
            numClasses = labels.size,
            modelLabels = labels,
            customLabels = emptyList(),
            fallbackDefault = "objeto"
        )
        val apple = ObjectDetectorHelper.resolverRotuloPorIndice(
            classIdx = 52,
            numClasses = labels.size,
            modelLabels = labels,
            customLabels = emptyList(),
            fallbackDefault = "objeto"
        )
        val orange = ObjectDetectorHelper.resolverRotuloPorIndice(
            classIdx = 54,
            numClasses = labels.size,
            modelLabels = labels,
            customLabels = emptyList(),
            fallbackDefault = "objeto"
        )
        assertEquals("banana", banana)
        assertEquals("apple", apple)
        assertEquals("orange", orange)
        assertEquals("Banana", com.igor.breedai.userinterface.rotuloAmigavel(banana))
        assertEquals("Maçã", com.igor.breedai.userinterface.rotuloAmigavel(apple))
        assertEquals("Laranja", com.igor.breedai.userinterface.rotuloAmigavel(orange))
    }

    @Test
    fun `testDiseaseRefinement_DetectsMoniliaseWhiteSporeClusterAndSadioWithCompetingDisease`() {
        val w = 100
        val h = 100
        val pxSpore = (0xFF shl 24) or (230 shl 16) or (226 shl 8) or 214
        val pxNecrosis = (0xFF shl 24) or (72 shl 16) or (62 shl 8) or 56
        val grid = IntArray(w * h) { idx ->
            val x = idx % w
            if (x < w / 2) pxSpore else pxNecrosis
        }
        val refinedFromSadio = ObjectDetectorHelper.refinarClasseDoencaPorMorfologiaCor(
            classIdx = 2,
            probBest = 0.52f,
            probClass0 = 0.14f,
            pixels = grid,
            width = w,
            height = h,
            x1Tensor = 10f,
            y1Tensor = 10f,
            x2Tensor = 90f,
            y2Tensor = 90f,
            probClass1 = 0.28f,
            probClass3 = 0.06f
        )
        assertEquals("Sadio incerto com esporulação branca e necrose deve virar Moniliase (1)", 1, refinedFromSadio)
    }

    @Test
    fun `testBuildQuantLut_HandlesSignedNormalizedUint8AndInt8`() {
        val lutSignedUint8 = ObjectDetectorHelper.buildQuantLutForParams(
            type = DataType.UINT8,
            rawScale = 1f / 128f,
            zp = 128
        )!!
        assertEquals(256, lutSignedUint8.size)
        assertEquals(0, lutSignedUint8[0].toInt() and 0xFF)
        assertEquals(255, lutSignedUint8[255].toInt() and 0xFF)

        val lutUint8Norm01 = ObjectDetectorHelper.buildQuantLutForParams(
            type = DataType.UINT8,
            rawScale = 1f / 255f,
            zp = 0
        )!!
        assertEquals(0, lutUint8Norm01[0].toInt() and 0xFF)
        assertEquals(255, lutUint8Norm01[255].toInt() and 0xFF)

        val lutInt8Norm = ObjectDetectorHelper.buildQuantLutForParams(
            type = DataType.INT8,
            rawScale = 1f / 255f,
            zp = -128
        )!!
        assertEquals(-128, lutInt8Norm[0].toInt())
        assertEquals(127, lutInt8Norm[255].toInt())
    }

    @Test
    fun `testModelValidation_RejectsInvalidFileAndZipSlip`() {
        assertFalse(ObjectDetectorHelper.isTfliteFlatBuffer("NOT_A_TFLITE_MODEL".toByteArray()))
        assertTrue(ObjectDetectorHelper.isTfliteFlatBuffer(byteArrayOf(0x1C, 0, 0, 0, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())))

        val tempBad = File.createTempFile("bad_model", ".tflite")
        try {
            tempBad.writeBytes("corrupted-bytes-1234567890".toByteArray())
            val report = ObjectDetectorHelper.inspecionarModeloArquivo(tempBad)
            assertFalse("Arquivo corrompido deve ser rejeitado", report.compativel)
        } finally {
            tempBad.delete()
        }

        assertFalse(ImportadorPlanilhaUniversal.isSafeZipEntryName("../evil.tflite"))
        assertFalse(ImportadorPlanilhaUniversal.isSafeZipEntryName("/abs/path.tflite"))
        assertFalse(ImportadorPlanilhaUniversal.isSafeZipEntryName("C:\\win\\path.tflite"))
        assertTrue(ImportadorPlanilhaUniversal.isSafeZipEntryName("weights/best_float32.tflite"))
    }

    @Test
    fun `testChaveModeloPersistente_SeparaModelosBananaMacaECustomizados`() {
        val chaveBanana = ModelConfig(modeloSelecionado = MODELOS_EMBUTIDOS[2]).chaveModeloPersistente()
        val chaveMaca = ModelConfig(modeloSelecionado = MODELOS_EMBUTIDOS[3]).chaveModeloPersistente()
        val chaveDoenca = ModelConfig(modeloSelecionado = MODELOS_EMBUTIDOS[1]).chaveModeloPersistente()
        val chaveCustom = ModelConfig(modeloInternoPath = "/data/user/0/modelos_ia/sigatoka_v2.tflite").chaveModeloPersistente()

        assertNotEquals(chaveBanana, chaveMaca)
        assertNotEquals(chaveBanana, chaveDoenca)
        assertNotEquals(chaveBanana, chaveCustom)
        assertEquals("custom_sigatoka_v2.tflite", chaveCustom)
    }

    @Test
    fun `testResumoAgronomicoExcluiNaoAvaliavelDaMedia`() {
        val mockUri = Uri.EMPTY ?: Mockito.mock(Uri::class.java)
        val registros = listOf(
            RegistroColeta(
                uri = mockUri,
                idCompleto = "T1_P1_Pl1",
                coletaNome = "Ensaio_1",
                tratamento = "T1",
                parcela = "1",
                planta = "1",
                situacaoPlanta = SituacaoPlanta.NORMAL,
                diagnostico = "10",
                data = "2026-11-17 09:00:00",
                lat = 0.0,
                lng = 0.0,
                totalObjetos = 10,
                variaveisExtras = emptyMap()
            ),
            RegistroColeta(
                uri = mockUri,
                idCompleto = "T1_P1_Pl2",
                coletaNome = "Ensaio_1",
                tratamento = "T1",
                parcela = "1",
                planta = "2",
                situacaoPlanta = SituacaoPlanta.SEM_FRUTOS,
                diagnostico = "0",
                data = "2026-11-17 09:05:00",
                lat = 0.0,
                lng = 0.0,
                totalObjetos = 0,
                variaveisExtras = emptyMap()
            ),
            RegistroColeta(
                uri = mockUri,
                idCompleto = "T1_P1_Pl3",
                coletaNome = "Ensaio_1",
                tratamento = "T1",
                parcela = "1",
                planta = "3",
                situacaoPlanta = SituacaoPlanta.NAO_AVALIAVEL,
                motivoNaoAvaliavel = "Planta morta",
                diagnostico = "Não avaliável",
                data = "2026-11-17 09:10:00",
                lat = 0.0,
                lng = 0.0,
                totalObjetos = 0,
                variaveisExtras = emptyMap()
            )
        )

        val resumo = calcularResumoAgronomico(registros)
        assertEquals(3, resumo.totalRegistros)
        assertEquals(2, resumo.plantasValidas)
        assertEquals(1, resumo.plantasNaoAvaliaveis)
        assertEquals(10, resumo.totalObjetos)
        assertEquals(5.0, resumo.mediaPorPlantaValida, 0.001)
        assertEquals(66.666, resumo.estandePercentual, 0.05)
    }

    @Test
    fun `testMaxResultadosSuportaAltaDensidadeAte300`() {
        val cfg = ModelConfig(
            modeloExternoNome = "Espigas Milho",
            maxResultados = 250,
            incluirPercentualClassesCsv = true
        )
        val json = cfg.toManifestJson()
        val parsed = ModelConfig.fromManifestJson(json)

        assertNotNull(parsed)
        assertEquals(250, parsed!!.maxResultados)
        assertTrue(parsed.incluirPercentualClassesCsv)

        val clamped = ModelConfig.fromManifestJson("""{"maxResultados": 999}""")
        assertNotNull(clamped)
        assertEquals(300, clamped!!.maxResultados)
    }

    @Test
    fun `testCacauDoencasRunsDirectlyInLiveCameraAndMatchesAccentedFilters`() {
        assertFalse(
            deveUsarParDetectoresSecundario(
                ModelConfig(modeloSelecionado = MODELOS_EMBUTIDOS[1], usarModeloSecundario = false)
            )
        )
        assertTrue(
            deveUsarParDetectoresSecundario(
                ModelConfig(modeloSelecionado = MODELOS_EMBUTIDOS[1], usarModeloSecundario = true)
            )
        )
        assertTrue(
            rotuloPassaFiltroModelo("PodridaoParda", listOf("Podridão"), LABELS_DOENCA_CACAU)
        )
        assertTrue(
            rotuloPassaFiltroModelo("apple", listOf("Maçã"), listOf("person", "banana", "apple"))
        )
    }

    @Test
    fun `testFundirContagemEDiagnostico_KeepsUnpairedDiseasedPodAt026Confidence`() {
        val contagem = listOf(
            DetEstilizada(0.05f, 0.05f, 0.20f, 0.25f, 0.88f, "cacau")
        )
        val secundario = listOf(
            DetEstilizada(0.60f, 0.55f, 0.85f, 0.90f, 0.26f, "PodridaoParda")
        )

        val result = fundirContagemEDiagnosticoBreedAI(contagem, secundario)
        assertEquals(2, result.size)
        assertTrue(result.any { it.rotulo == "PodridaoParda" && kotlin.math.abs(it.confianca - 0.26f) < 0.001f })
    }

    private fun registroTeste(
        tratamento: String,
        parcela: String,
        planta: String,
        situacao: SituacaoPlanta,
        totalObjetos: Int,
        caixas: List<CaixaAuditavel> = emptyList(),
        coletaNome: String = "Coleta",
        motivoNaoAvaliavel: String = ""
    ): RegistroColeta = RegistroColeta(
        uri = Mockito.mock(Uri::class.java),
        idCompleto = "${tratamento}_P${parcela}_Pl$planta",
        coletaNome = coletaNome,
        tratamento = tratamento,
        parcela = parcela,
        planta = planta,
        situacaoPlanta = situacao,
        motivoNaoAvaliavel = motivoNaoAvaliavel,
        diagnostico = "",
        data = "2026-11-19 10:00:00",
        lat = 0.0,
        lng = 0.0,
        totalObjetos = totalObjetos,
        caixas = caixas
    )

    @Test
    fun `testCalcularRendimentoColeta_CacauDescontaDoencasECalculaArrobasPorHa`() {
        val config = ConfigConversaoRendimento.dePreset(PresetRendimento.CACAU_ARROBA)

        val caixasPl1 = List(20) {
            CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "Sadio", manual = false, confianca = 0.9f)
        } + List(5) {
            CaixaAuditavel(0.3f, 0.3f, 0.4f, 0.4f, rotulo = "Vassoura", manual = false, confianca = 0.8f)
        }
        val caixasPl2 = List(10) {
            CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "Sadio", manual = false, confianca = 0.9f)
        } + List(3) {
            CaixaAuditavel(0.3f, 0.3f, 0.4f, 0.4f, rotulo = "Podridão", manual = false, confianca = 0.8f)
        }

        val registros = listOf(
            registroTeste("T1", "1", "1", SituacaoPlanta.NORMAL, 25, caixas = caixasPl1),
            registroTeste("T1", "1", "2", SituacaoPlanta.NORMAL, 13, caixas = caixasPl2),
            registroTeste("T1", "1", "3", SituacaoPlanta.SEM_FRUTOS, 0),
            registroTeste("T1", "1", "4", SituacaoPlanta.NAO_AVALIAVEL, 0, motivoNaoAvaliavel = "Planta morta")
        )

        val resumo = calcularRendimentoColeta(registros, config)
        assertEquals(30, resumo.totalAproveitaveis)
        assertEquals(8, resumo.totalDoentes)
        assertEquals(75.0, resumo.estandePercentual, 0.001)
        assertEquals(10.0, resumo.mediaAproveitaveisPlantaViva, 0.001)
        assertEquals(30.0 / 22.0, resumo.kgAcumulado, 0.001)
        assertEquals((30.0 / 22.0) / 15.0, resumo.unidadesAcumuladas, 0.0001)
        assertEquals(505.0, resumo.potencialKgHa, 0.001)
        assertEquals(378.75, resumo.realKgHa, 0.001)
        assertEquals(25.25, resumo.realUnidadesHa, 0.001)
        assertTrue(resumo.temClassificacaoDoenca)

        val resumoSemDesconto = calcularRendimentoColeta(registros, config.copy(descontarDoencas = false))
        assertEquals(38, resumoSemDesconto.totalAproveitaveis)
        assertEquals(0, resumoSemDesconto.totalDoentes)
    }

    @Test
    fun `testCalcularRankingTratamentos_OrdenaPorMediaSadiosEGeraResumoWhatsApp`() {
        val config = ConfigConversaoRendimento.dePreset(PresetRendimento.CACAU_ARROBA)

        val caixasTB = List(40) {
            CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "Sadio", manual = false, confianca = 0.9f)
        }
        val caixasTA1 = List(15) {
            CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "Sadio", manual = false, confianca = 0.9f)
        } + List(5) {
            CaixaAuditavel(0.4f, 0.4f, 0.5f, 0.5f, rotulo = "Vassoura", manual = false, confianca = 0.85f)
        }
        val caixasTA2 = List(15) {
            CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "Sadio", manual = false, confianca = 0.9f)
        }

        val registros = listOf(
            registroTeste("T-A", "1", "1", SituacaoPlanta.NORMAL, 20, caixas = caixasTA1, coletaNome = "Ensaio Sul"),
            registroTeste("T-A", "1", "2", SituacaoPlanta.NORMAL, 15, caixas = caixasTA2, coletaNome = "Ensaio Sul"),
            registroTeste("T-B", "1", "1", SituacaoPlanta.NORMAL, 40, caixas = caixasTB, coletaNome = "Ensaio Sul")
        )

        val ranking = calcularRankingTratamentos(registros, config)
        assertEquals(2, ranking.size)
        assertEquals("T-B", ranking[0].tratamento)
        assertEquals(40.0, ranking[0].mediaAproveitaveis, 0.001)
        assertEquals("T-A", ranking[1].tratamento)
        assertEquals(15.0, ranking[1].mediaAproveitaveis, 0.001)
        assertEquals(5, ranking[1].totalDoentes)
        assertEquals((5.0 * 100.0) / 35.0, ranking[1].taxaDoencaPercentual, 0.01)

        val texto = gerarResumoTextoColeta(
            coletaNome = "Ensaio Sul",
            labelTratamento = "Clone",
            registros = registros,
            config = config,
            templateCultura = TemplateCultura.FRUTICULTURA
        )
        assertTrue(texto.contains("*BreedAI • Resumo da Coleta*"))
        assertTrue(texto.contains("Coleta: Ensaio Sul"))
        assertTrue(texto.contains("Rendimento líquido (sadios):"))
        assertTrue(texto.contains("0,21 @"))
        assertTrue(texto.contains("@/ha"))
        assertTrue(texto.contains("Perdas por doenças: 5"))
        assertTrue(texto.contains("1. T-B: 40,0"))
    }

    @Test
    fun `testResolverClassesFallbackRevisao_SemCarregarTFLiteNaAbaDados`() {
        val cfgSimples = ModelConfig(
            modeloSelecionado = MODELOS_EMBUTIDOS[0],
            labelsFiltro = listOf("cacau", "fruto"),
            usarModeloSecundario = false
        )
        assertEquals(listOf("cacau", "fruto"), resolverClassesFallbackRevisao(cfgSimples))

        val cfgCombinado = ModelConfig(
            modeloSelecionado = MODELOS_EMBUTIDOS[0],
            labelsFiltro = listOf("cacau", "fruto"),
            usarModeloSecundario = true
        )
        assertEquals(LABELS_DOENCA_CACAU, resolverClassesFallbackRevisao(cfgCombinado))

        val caixasCustom = listOf(
            CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "CloneEspecial", manual = true)
        )
        assertEquals(
            listOf("CloneEspecial"),
            resolverClassesFallbackRevisao(cfgSimples, caixasCustom)
        )
    }
}
