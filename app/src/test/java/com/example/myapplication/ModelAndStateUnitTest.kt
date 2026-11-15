package com.example.myapplication

import android.net.Uri
import com.example.myapplication.data.MODELOS_EMBUTIDOS
import com.example.myapplication.data.MOTIVOS_NAO_AVALIAVEL
import com.example.myapplication.data.ModelConfig
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.TipoVariavelExtra
import com.example.myapplication.data.VariavelExtra
import com.example.myapplication.data.variaveisExtrasIniciais
import com.example.myapplication.userinterface.CaixaAuditavel
import com.example.myapplication.userinterface.util.ObjectDetectorHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ModelAndStateUnitTest {

    @Test
    fun `modelos embutidos devem trazer modelo de doencas e modelo de contagem`() {
        assertEquals(2, MODELOS_EMBUTIDOS.size)
        val doencaModel = MODELOS_EMBUTIDOS.first()
        assertEquals("detector_doenca.tflite", doencaModel.nomeArquivo)
        assertEquals("Cacau Doenças (Pré-carregado)", doencaModel.nomeExibicao)
        assertEquals(listOf("PodridaoParda", "Moniliase", "Sadio", "VassouraBruxa"), doencaModel.labelsDefault)

        val contagemModel = MODELOS_EMBUTIDOS[1]
        assertEquals("detector.tflite", contagemModel.nomeArquivo)
        assertTrue(contagemModel.labelsDefault.contains("cacau"))

        val defaultCfg = ModelConfig()
        assertTrue(defaultCfg.labelsFiltro.containsAll(doencaModel.labelsDefault))
    }

    @Test
    fun `cores semanticas e resolucao multi-classe devem mapear doencas de cacau`() {
        val corSadio = com.example.myapplication.userinterface.corParaLabel("Sadio")
        val corVassoura = com.example.myapplication.userinterface.corParaLabel("VassouraBruxa")
        val corPodridao = com.example.myapplication.userinterface.corParaLabel("PodridaoParda")
        val corMoniliase = com.example.myapplication.userinterface.corParaLabel("Moniliase")

        assertNotEquals(corSadio, corVassoura)
        assertNotEquals(corVassoura, corPodridao)
        assertNotEquals(corPodridao, corMoniliase)
        assertEquals("Podridão", com.example.myapplication.userinterface.rotuloAmigavel("PodridaoParda"))
        assertEquals("Monilíase", com.example.myapplication.userinterface.rotuloAmigavel("Moniliase"))
        assertEquals("Vassoura", com.example.myapplication.userinterface.rotuloAmigavel("VassouraBruxa"))

        val resolved = com.example.myapplication.userinterface.util.ObjectDetectorHelper.resolverRotuloPorIndice(
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
            maxResultados = 30
        )
        assertEquals("Soja Vagem v3", cfg.nomeExibicao)

        val json = cfg.toManifestJson()
        val parsed = ModelConfig.fromManifestJson(json)
        assertNotNull(parsed)
        assertEquals("Soja Vagem v3", parsed!!.nomeExibicao)
        assertEquals(listOf("vagem", "grao"), parsed.labelsFiltro)
        assertEquals(0.42f, parsed.threshold, 0.001f)
        assertEquals(30, parsed.maxResultados)
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
    }
}
