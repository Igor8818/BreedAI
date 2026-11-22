package com.igor.breedai

import android.net.Uri
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.PontoFormulario
import com.igor.breedai.data.PresetRendimento
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TipoVariavelExtra
import com.igor.breedai.data.VariavelExtra
import com.igor.breedai.userinterface.CaixaAuditavel
import com.igor.breedai.userinterface.util.PersistenciaLocalRegistros
import com.igor.breedai.userinterface.util.gerarConteudoCsvBreedAI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Teste de integração ponta-a-ponta offline do ecossistema BreedAI:
 * Coleta Normal (IA + Caixa Manual + Variáveis Extras) + Sem Frutos + Não Avaliável
 * -> Persistência JSON local -> Exportação CSV agnóstica sem amarração com backend.
 */
class DomainConcordanceIntegrationTest {

    @Test
    fun `jornada completa offline preserva situacao variaveis extras e exporta CSV limpo`() {
        val mockUri = Mockito.mock(Uri::class.java)
        Mockito.`when`(mockUri.toString()).thenReturn("file:///storage/emulated/0/Pictures/BreedAI_Analise/foto_1.jpg")

        val variaveisAtivas = listOf(
            VariavelExtra(
                id = "var_cor",
                nome = "Cor do Fruto",
                tipo = TipoVariavelExtra.LISTA_OPCOES,
                opcoes = listOf("Verde", "Amarelo"),
                ativa = true,
                ordem = 1
            ),
            VariavelExtra(
                id = "var_diam",
                nome = "Diâmetro (cm)",
                tipo = TipoVariavelExtra.NUMERICO,
                ativa = true,
                ordem = 2
            )
        )

        val regNormal = RegistroColeta(
            uri = mockUri,
            idCompleto = "Safra_2026_GEN01_P1_1",
            coletaNome = "Safra_2026",
            tratamento = "GEN-01",
            parcela = "1",
            planta = "1",
            situacaoPlanta = SituacaoPlanta.NORMAL,
            diagnostico = "Contagem: 3",
            data = "2026-11-15 14:20:00",
            lat = -14.7912,
            lng = -39.2315,
            totalObjetos = 3,
            variaveisExtras = mapOf("Cor do Fruto" to "Amarelo", "Diâmetro (cm)" to "15.4"),
            caixas = listOf(
                CaixaAuditavel(0.10f, 0.10f, 0.25f, 0.30f, rotulo = "fruto", manual = false, confianca = 0.92f),
                CaixaAuditavel(0.35f, 0.20f, 0.50f, 0.40f, rotulo = "fruto", manual = false, confianca = 0.87f),
                CaixaAuditavel(0.60f, 0.50f, 0.75f, 0.70f, rotulo = "Manual", manual = true, confianca = 1.0f)
            )
        )

        val regSemFrutos = RegistroColeta(
            uri = mockUri,
            idCompleto = "Safra_2026_GEN01_P1_2",
            coletaNome = "Safra_2026",
            tratamento = "GEN-01",
            parcela = "1",
            planta = "2",
            situacaoPlanta = SituacaoPlanta.SEM_FRUTOS,
            diagnostico = "Sem Frutos (comprovação)",
            data = "2026-11-15 14:21:00",
            lat = -14.7913,
            lng = -39.2316,
            totalObjetos = 0,
            variaveisExtras = mapOf("Diâmetro (cm)" to "11.0"),
            caixas = emptyList()
        )

        val regNaoAvaliavel = RegistroColeta(
            uri = Uri.EMPTY ?: Mockito.mock(Uri::class.java),
            idCompleto = "Safra_2026_GEN01_P1_3",
            coletaNome = "Safra_2026",
            tratamento = "GEN-01",
            parcela = "1",
            planta = "3",
            situacaoPlanta = SituacaoPlanta.NAO_AVALIAVEL,
            motivoNaoAvaliavel = "Cavalo",
            diagnostico = "Não avaliável: Cavalo",
            data = "2026-11-15 14:22:00",
            lat = 0.0,
            lng = 0.0,
            totalObjetos = 0,
            variaveisExtras = emptyMap(),
            caixas = emptyList()
        )

        val registros = listOf(regNormal, regSemFrutos, regNaoAvaliavel)
        assertTrue(registros.all { it.isConcordante() })

        // 1) Round-trip JSON de variáveis extras
        val jsonVars = PersistenciaLocalRegistros.serializarVariaveisExtrasJson(variaveisAtivas).toString()
        val varsRestauradas = PersistenciaLocalRegistros.deserializarVariaveisExtrasJson(jsonVars)
        assertEquals(2, varsRestauradas.size)
        assertEquals("Cor do Fruto", varsRestauradas[0].nome)
        assertEquals(listOf("Verde", "Amarelo"), varsRestauradas[0].opcoes)

        // 2) Round-trip JSON de registros locais
        val jsonReg = PersistenciaLocalRegistros.serializarRegistrosParaJson(registros).toString()
        val regsRestaurados = PersistenciaLocalRegistros.deserializarRegistrosDeJson(jsonReg)
        assertEquals(3, regsRestaurados.size)
        assertEquals(SituacaoPlanta.NORMAL, regsRestaurados[0].situacaoPlanta)
        assertEquals("Amarelo", regsRestaurados[0].variaveisExtras["Cor do Fruto"])
        assertEquals(3, regsRestaurados[0].caixas.size)
        assertEquals("Manual", regsRestaurados[0].caixas[2].rotulo)
        assertEquals(SituacaoPlanta.SEM_FRUTOS, regsRestaurados[1].situacaoPlanta)
        assertEquals(0, regsRestaurados[1].totalObjetos)
        assertEquals(SituacaoPlanta.NAO_AVALIAVEL, regsRestaurados[2].situacaoPlanta)
        assertEquals("Cavalo", regsRestaurados[2].motivoNaoAvaliavel)

        // 3) Exportação CSV universal com colunas dinâmicas
        val csv = gerarConteudoCsvBreedAI(
            registros = regsRestaurados,
            labelTratamento = "Clone",
            labelParcela = "Bloco",
            labelPlanta = "Planta",
            variaveisAtivas = varsRestauradas
        )

        val linhas = csv.trim().lines()
        assertEquals(4, linhas.size)
        val cabecalho = linhas[0]
        assertTrue(cabecalho.startsWith("Coleta,ID_Planta,Clone,Bloco,Planta,Situacao_Planta,Motivo_Nao_Avaliavel,Total_Contado,"))
        assertTrue(cabecalho.contains("Cor do Fruto,Diâmetro (cm),Data_Hora,Latitude,Longitude,Deteccoes_IA"))
        assertFalse(cabecalho.contains("Vassoura"))
        assertFalse(cabecalho.contains("Podridao"))
        assertFalse(cabecalho.contains("Status_Sync"))

        assertTrue(linhas[1].contains("GEN-01,1,1,Normal,,3,Amarelo,15.4"))
        assertTrue(linhas[2].contains("GEN-01,1,2,Sem Frutos,,0,,11.0"))
        assertTrue(linhas[3].contains("GEN-01,1,3,Não avaliável,Cavalo,0,,,2026-11-15 14:22:00"))
    }

    @Test
    fun `exportacao CSV multi-classe inclui colunas automaticas IA_classe para modelos generalistas`() {
        val regFrutas = RegistroColeta(
            uri = Uri.EMPTY ?: Mockito.mock(Uri::class.java),
            idCompleto = "Pomar_T1_P1_1",
            coletaNome = "Pomar_2026",
            tratamento = "POMAR-A",
            parcela = "1",
            planta = "1",
            situacaoPlanta = SituacaoPlanta.NORMAL,
            diagnostico = "3 (Maçã: 2, Banana: 1)",
            data = "2026-11-16 09:00:00",
            lat = -14.79,
            lng = -39.23,
            totalObjetos = 3,
            variaveisExtras = emptyMap(),
            caixas = listOf(
                CaixaAuditavel(0.1f, 0.1f, 0.3f, 0.3f, rotulo = "apple", manual = false, confianca = 0.91f),
                CaixaAuditavel(0.4f, 0.1f, 0.6f, 0.3f, rotulo = "apple", manual = false, confianca = 0.88f),
                CaixaAuditavel(0.1f, 0.5f, 0.4f, 0.8f, rotulo = "banana", manual = false, confianca = 0.85f)
            )
        )

        val csv = gerarConteudoCsvBreedAI(
            registros = listOf(regFrutas),
            labelTratamento = "Cultivar",
            labelParcela = "Bloco",
            labelPlanta = "Planta",
            variaveisAtivas = emptyList(),
            classesModeloExportacao = listOf("apple", "banana", "orange")
        )

        val linhas = csv.trim().lines()
        assertEquals(2, linhas.size)
        assertTrue(linhas[0].contains("IA_apple,IA_banana,IA_orange"))
        assertTrue(linhas[1].contains("POMAR-A,1,1,Normal,,3,2,1,0,2026-11-16 09:00:00"))
    }

    @Test
    fun `testCsvExportPreservesOriginalSpreadsheetColumnsAndOptionalPercentages`() {
        val pontos = listOf(
            PontoFormulario(
                tratamento = "CLONE-7",
                parcela = "2",
                planta = "5",
                colunasPlanilha = linkedMapOf(
                    "Bloco" to "B3",
                    "Genotipo" to "CCN-51"
                )
            )
        )
        val reg = RegistroColeta(
            uri = Uri.EMPTY ?: Mockito.mock(Uri::class.java),
            idCompleto = "CLONE-7_P2_Pl5",
            coletaNome = "Ensaio_Geral",
            tratamento = "CLONE-7",
            parcela = "2",
            planta = "5",
            situacaoPlanta = SituacaoPlanta.NORMAL,
            diagnostico = "4 (Sadio: 3, Vassoura: 1)",
            data = "2026-11-17 10:30:00",
            lat = -14.79,
            lng = -39.23,
            totalObjetos = 4,
            variaveisExtras = emptyMap(),
            caixas = listOf(
                CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "Sadio"),
                CaixaAuditavel(0.3f, 0.1f, 0.4f, 0.2f, rotulo = "Sadio"),
                CaixaAuditavel(0.5f, 0.1f, 0.6f, 0.2f, rotulo = "Sadio"),
                CaixaAuditavel(0.7f, 0.1f, 0.8f, 0.2f, rotulo = "Vassoura")
            )
        )

        val csvPadrao = gerarConteudoCsvBreedAI(
            registros = listOf(reg),
            labelTratamento = "Clone",
            labelParcela = "Parcela",
            labelPlanta = "Planta",
            variaveisAtivas = emptyList(),
            classesModeloExportacao = listOf("Sadio", "Vassoura"),
            pontosFormulario = pontos
        )
        val linhasPadrao = csvPadrao.trim().lines()
        assertEquals(2, linhasPadrao.size)
        assertTrue(linhasPadrao[0].contains("Bloco,Genotipo"))
        assertTrue(linhasPadrao[1].contains("B3,CCN-51"))
        assertFalse(linhasPadrao[0].contains("IA_Pct_"))

        val csvComPct = gerarConteudoCsvBreedAI(
            registros = listOf(reg),
            labelTratamento = "Clone",
            labelParcela = "Parcela",
            labelPlanta = "Planta",
            variaveisAtivas = emptyList(),
            classesModeloExportacao = listOf("Sadio", "Vassoura"),
            pontosFormulario = pontos,
            incluirPercentualClasses = true
        )
        val linhasPct = csvComPct.trim().lines()
        assertEquals(2, linhasPct.size)
        assertTrue(linhasPct[0].contains("IA_Pct_Sadio,IA_Pct_Vassoura"))
        assertTrue(linhasPct[1].contains(",3,1,75.0,25.0,"))
    }

    @Test
    fun `testCsvExportComRendimentoAtivoIncluiColunasKgEArrobas`() {
        val mockUri = Mockito.mock(Uri::class.java)
        val pontos = listOf(
            PontoFormulario(tratamento = "TRAT-01", parcela = "1", planta = "1")
        )
        val caixas = List(22) {
            CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "Sadio", manual = false, confianca = 0.9f)
        } + List(11) {
            CaixaAuditavel(0.4f, 0.4f, 0.5f, 0.5f, rotulo = "Vassoura", manual = false, confianca = 0.85f)
        }
        val reg = RegistroColeta(
            uri = mockUri,
            idCompleto = "TRAT-01_P1_Pl1",
            coletaNome = "Ensaio_Rend",
            tratamento = "TRAT-01",
            parcela = "1",
            planta = "1",
            situacaoPlanta = SituacaoPlanta.NORMAL,
            diagnostico = "",
            data = "2026-11-19 10:15:00",
            lat = 0.0,
            lng = 0.0,
            totalObjetos = 33,
            caixas = caixas
        )

        val csv = gerarConteudoCsvBreedAI(
            registros = listOf(reg),
            labelTratamento = "Tratamento",
            labelParcela = "Parcela",
            labelPlanta = "Planta",
            variaveisAtivas = emptyList(),
            classesModeloExportacao = listOf("Sadio", "Vassoura"),
            pontosFormulario = pontos,
            configRendimento = ConfigConversaoRendimento.dePreset(PresetRendimento.CACAU_ARROBA)
        )
        val linhas = csv.trim().lines()
        assertEquals(2, linhas.size)
        assertTrue(linhas[0].contains("Aproveitaveis,Kg_Estimado,Unid_Estimada_Arrobas"))
        assertTrue(linhas[1].contains(",22,1.00,0.07,"))
    }
}
