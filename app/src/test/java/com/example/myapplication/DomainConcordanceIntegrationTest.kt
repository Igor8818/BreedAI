package com.example.myapplication

import android.net.Uri
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.TipoVariavelExtra
import com.example.myapplication.data.VariavelExtra
import com.example.myapplication.userinterface.CaixaAuditavel
import com.example.myapplication.userinterface.util.PersistenciaLocalRegistros
import com.example.myapplication.userinterface.util.gerarConteudoCsvBreedAI
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
}
