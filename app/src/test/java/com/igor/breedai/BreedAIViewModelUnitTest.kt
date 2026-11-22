package com.igor.breedai

import android.net.Uri
import com.igor.breedai.data.PontoFormulario
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.data.VariavelExtra
import com.igor.breedai.userinterface.CaixaAuditavel
import com.igor.breedai.viewmodel.BreedAIUiState
import com.igor.breedai.viewmodel.reconciliarFormularioComRegistros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class BreedAIViewModelUnitTest {

    private fun mockUri(): Uri = Mockito.mock(Uri::class.java)

    @Test
    fun `reconciliarFormularioComRegistros marca pontos coletados por situacao e limpa pendentes`() {
        val pontos = listOf(
            PontoFormulario(tratamento = "T-01", parcela = "1", planta = "1", ordem = 1),
            PontoFormulario(tratamento = "T-01", parcela = "1", planta = "2", ordem = 2, coletado = true, resumoResultado = "obsoleto"),
            PontoFormulario(tratamento = "T-02", parcela = "2", planta = "1", ordem = 3),
            PontoFormulario(tratamento = "T-02", parcela = "2", planta = "2", ordem = 4)
        )
        val registros = listOf(
            RegistroColeta(
                uri = mockUri(),
                idCompleto = "T-01_P1_Pl1",
                coletaNome = "Ensaio A",
                tratamento = "T-01",
                parcela = "1",
                planta = "1",
                situacaoPlanta = SituacaoPlanta.NORMAL,
                diagnostico = "7",
                data = "21/11/2026 10:00:00",
                lat = 0.0,
                lng = 0.0,
                totalObjetos = 7
            ),
            RegistroColeta(
                uri = mockUri(),
                idCompleto = "t-02_p2_pl1",
                coletaNome = "Ensaio A",
                tratamento = "T-02",
                parcela = "2",
                planta = "1",
                situacaoPlanta = SituacaoPlanta.SEM_FRUTOS,
                diagnostico = "Sem Frutos (0)",
                data = "21/11/2026 10:05:00",
                lat = 0.0,
                lng = 0.0,
                totalObjetos = 0
            ),
            RegistroColeta(
                uri = mockUri(),
                idCompleto = "T-02_P2_Pl2",
                coletaNome = "Ensaio A",
                tratamento = "T-02",
                parcela = "2",
                planta = "2",
                situacaoPlanta = SituacaoPlanta.NAO_AVALIAVEL,
                motivoNaoAvaliavel = "Morta",
                diagnostico = "Não avaliável (Morta)",
                data = "21/11/2026 10:06:00",
                lat = 0.0,
                lng = 0.0,
                totalObjetos = 0
            )
        )

        val reconciliados = reconciliarFormularioComRegistros(pontos, registros)

        assertTrue(reconciliados[0].coletado)
        assertEquals("7 obj.", reconciliados[0].resumoResultado)

        assertFalse(reconciliados[1].coletado)
        assertEquals("", reconciliados[1].resumoResultado)

        assertTrue(reconciliados[2].coletado)
        assertEquals("Sem Frutos (0)", reconciliados[2].resumoResultado)

        assertTrue(reconciliados[3].coletado)
        assertEquals("N. Avaliável (Morta)", reconciliados[3].resumoResultado)
    }

    @Test
    fun `BreedAIUiState computa templateAtual, gpsAtivo e variaveisAtivas corretamente`() {
        val vars = listOf(
            VariavelExtra(id = "v1", nome = "Cor", ativa = true, ordem = 1),
            VariavelExtra(id = "v2", nome = "Vigor", ativa = false, ordem = 2),
            VariavelExtra(id = "v3", nome = "Diâmetro", ativa = true, ordem = 3)
        )
        val estadoInativo = BreedAIUiState(
            templateId = "graos",
            variaveisExtras = vars,
            latAtual = 0.0,
            lngAtual = 0.0
        )
        assertEquals(TemplateCultura.GRAOS, estadoInativo.templateAtual)
        assertFalse(estadoInativo.gpsAtivo)
        assertEquals(listOf("v1", "v3"), estadoInativo.variaveisAtivas.map { it.id })

        val estadoComGps = estadoInativo.copy(latAtual = -14.79, lngAtual = -39.27)
        assertTrue(estadoComGps.gpsAtivo)
    }

    @Test
    fun `transicao de situacao NORMAL para SEM_FRUTOS e NAO_AVALIAVEL atualiza resumo e diagnostico`() {
        val ponto = PontoFormulario(tratamento = "Gen01", parcela = "3", planta = "5", ordem = 1)
        val caixas = listOf(
            CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "Sadio"),
            CaixaAuditavel(0.3f, 0.3f, 0.4f, 0.4f, rotulo = "PodridaoParda")
        )

        val regNormal = RegistroColeta(
            uri = mockUri(),
            idCompleto = "Gen01_P3_Pl5",
            coletaNome = "Safra",
            tratamento = "Gen01",
            parcela = "3",
            planta = "5",
            situacaoPlanta = SituacaoPlanta.NORMAL,
            diagnostico = formatarDiagnosticoRegistro(SituacaoPlanta.NORMAL, "", 2, caixas),
            data = "21/11/2026 11:00:00",
            lat = 0.0,
            lng = 0.0,
            totalObjetos = 2,
            caixas = caixas
        )
        assertTrue(regNormal.isConcordante())
        assertEquals("2 obj.", reconciliarFormularioComRegistros(listOf(ponto), listOf(regNormal)).first().resumoResultado)

        val regSemFrutos = regNormal.copy(
            situacaoPlanta = SituacaoPlanta.SEM_FRUTOS,
            diagnostico = formatarDiagnosticoRegistro(SituacaoPlanta.SEM_FRUTOS, "", 0, emptyList()),
            totalObjetos = 0,
            caixas = emptyList()
        )
        assertTrue(regSemFrutos.isConcordante())
        assertEquals("Sem Frutos (0)", reconciliarFormularioComRegistros(listOf(ponto), listOf(regSemFrutos)).first().resumoResultado)

        val regNaoAvaliavel = regSemFrutos.copy(
            situacaoPlanta = SituacaoPlanta.NAO_AVALIAVEL,
            motivoNaoAvaliavel = "Pequena",
            diagnostico = formatarDiagnosticoRegistro(SituacaoPlanta.NAO_AVALIAVEL, "Pequena", 0, emptyList())
        )
        assertTrue(regNaoAvaliavel.isConcordante())
        assertEquals("N. Avaliável (Pequena)", reconciliarFormularioComRegistros(listOf(ponto), listOf(regNaoAvaliavel)).first().resumoResultado)
    }
}
