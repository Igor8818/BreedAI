package com.example.myapplication

import com.example.myapplication.userinterface.CaixaAuditavel
import com.example.myapplication.userinterface.DetEstilizada
import com.example.myapplication.userinterface.fundirDeteccoesComSnapping
import com.example.myapplication.userinterface.iouNorm
import com.example.myapplication.userinterface.redimensionarCaixaAuditavel
import com.example.myapplication.userinterface.selecionarIndiceCaixaNoToque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Testes unitários para as invariantes matemáticas de Letterbox, Projeção Geométrica,
 * Snapping Pós-Captura, Seleção por Proximidade e Redimensionamento Auditável.
 */
class LetterboxAndGeometryUnitTest {

    @Test
    fun testLetterboxCalculations_4_3_Ratio() {
        val imgW = 960
        val imgH = 1280
        val targetSize = 640

        val scale = min(targetSize.toFloat() / imgW, targetSize.toFloat() / imgH)
        val newW = (imgW * scale).toInt()
        val newH = (imgH * scale).toInt()
        val padX = (targetSize - newW) / 2f
        val padY = (targetSize - newH) / 2f

        assertEquals(0.5f, scale, 0.0001f)
        assertEquals(480, newW)
        assertEquals(640, newH)
        assertEquals(80f, padX, 0.01f)
        assertEquals(0f, padY, 0.01f)
    }

    @Test
    fun testLetterboxCalculations_16_9_Ratio() {
        val imgW = 1920
        val imgH = 1080
        val targetSize = 640

        val scale = min(targetSize.toFloat() / imgW, targetSize.toFloat() / imgH)
        val newW = (imgW * scale).toInt()
        val newH = (imgH * scale).toInt()
        val padX = (targetSize - newW) / 2f
        val padY = (targetSize - newH) / 2f

        assertEquals(0.3333333f, scale, 0.0001f)
        assertEquals(640, newW)
        assertEquals(360, newH)
        assertEquals(0f, padX, 0.01f)
        assertEquals(140f, padY, 0.01f)
    }

    @Test
    fun testInverseProjection_TensorToOriginalImage() {
        val imgW = 960f
        val imgH = 1280f
        val padX = 80f
        val padY = 0f
        val scale = 0.5f
        val targetSize = 640f

        val tx1 = 0.25f * targetSize
        val ty1 = 0.25f * targetSize
        val tx2 = 0.75f * targetSize
        val ty2 = 0.75f * targetSize

        val ox1 = ((tx1 - padX) / scale).coerceIn(0f, imgW)
        val oy1 = ((ty1 - padY) / scale).coerceIn(0f, imgH)
        val ox2 = ((tx2 - padX) / scale).coerceIn(0f, imgW)
        val oy2 = ((ty2 - padY) / scale).coerceIn(0f, imgH)

        assertEquals(160f, ox1, 0.1f)
        assertEquals(320f, oy1, 0.1f)
        assertEquals(800f, ox2, 0.1f)
        assertEquals(960f, oy2, 0.1f)

        val normW = (ox2 - ox1) / imgW
        val normH = (oy2 - oy1) / imgH
        assertEquals(0.6666f, normW, 0.001f)
        assertEquals(0.50f, normH, 0.001f)
    }

    @Test
    fun testInverseProjection_ClampingBoundaries() {
        val imgW = 1920f
        val imgH = 1080f
        val padX = 0f
        val padY = 140f
        val scale = 640f / 1920f

        val tx1 = 0f
        val ty1 = 10f
        val tx2 = 640f
        val ty2 = 630f

        val ox1 = ((tx1 - padX) / scale).coerceIn(0f, imgW)
        val oy1 = ((ty1 - padY) / scale).coerceIn(0f, imgH)
        val ox2 = ((tx2 - padX) / scale).coerceIn(0f, imgW)
        val oy2 = ((ty2 - padY) / scale).coerceIn(0f, imgH)

        assertEquals(0f, ox1, 0.1f)
        assertEquals(0f, oy1, 0.1f)
        assertEquals(1920f, ox2, 0.1f)
        assertEquals(1080f, oy2, 0.1f)
    }

    @Test
    fun testIoUCalculation_IdenticalAndDisjoint() {
        val boxA = CaixaAuditavel(0.1f, 0.1f, 0.5f, 0.5f, rotulo = "cacau")
        val iouSame = iouNorm(boxA, 0.1f, 0.1f, 0.5f, 0.5f)
        assertEquals(1.0f, iouSame, 0.001f)

        val iouDisjoint = iouNorm(boxA, 0.6f, 0.6f, 0.9f, 0.9f)
        assertEquals(0.0f, iouDisjoint, 0.001f)

        val iouHalf = iouNorm(boxA, 0.3f, 0.1f, 0.7f, 0.5f)
        assertEquals(0.08f / 0.24f, iouHalf, 0.001f)
    }

    @Test
    fun testAntiResurrection_DetectionThreshold() {
        val caixaExcluida = CaixaAuditavel(0.2f, 0.3f, 0.6f, 0.7f, rotulo = "cacau")
        val detX = 0.21f
        val detY = 0.31f
        val detR = 0.61f
        val detB = 0.71f

        val jaExcluida = iouNorm(caixaExcluida, detX, detY, detR, detB) > 0.30f
        assertTrue("Caixa excluída deve bloquear re-detecção com IoU > 0.30", jaExcluida)

        val detDistante = iouNorm(caixaExcluida, 0.7f, 0.7f, 0.9f, 0.9f) > 0.30f
        assertTrue(!detDistante)
    }

    @Test
    fun testSelecaoCaixaNoToque_ObjetosAgrupadosEscolheCentroMaisProximo() {
        val obj1 = CaixaAuditavel(0.20f, 0.20f, 0.40f, 0.50f, rotulo = "cacau")
        val obj2 = CaixaAuditavel(0.35f, 0.20f, 0.55f, 0.50f, rotulo = "fruto")
        val caixas = listOf(obj1, obj2)

        val idx1 = selecionarIndiceCaixaNoToque(caixas, 0.28f, 0.35f, 0.03f, 0.03f)
        assertEquals(0, idx1)

        val idx2 = selecionarIndiceCaixaNoToque(caixas, 0.48f, 0.35f, 0.03f, 0.03f)
        assertEquals(1, idx2)

        val idxVazio = selecionarIndiceCaixaNoToque(caixas, 0.85f, 0.85f, 0.03f, 0.03f)
        assertEquals(-1, idxVazio)
    }

    @Test
    fun testRedimensionarCaixaAuditavel_PreservaCentroERespeitaLimites() {
        val original = CaixaAuditavel(0.40f, 0.40f, 0.60f, 0.60f, rotulo = "Manual", manual = true)

        val ampliada = redimensionarCaixaAuditavel(original, 1.20f)
        assertEquals(0.50f, ampliada.centroX, 0.001f)
        assertEquals(0.50f, ampliada.centroY, 0.001f)
        assertEquals(0.24f, ampliada.largura, 0.001f)
        assertEquals(0.24f, ampliada.altura, 0.001f)
        assertEquals(true, ampliada.manual)

        val reduzida = redimensionarCaixaAuditavel(original, 0.80f)
        assertEquals(0.50f, reduzida.centroX, 0.001f)
        assertEquals(0.16f, reduzida.largura, 0.001f)

        val canto = CaixaAuditavel(0.01f, 0.01f, 0.15f, 0.15f, rotulo = "cacau")
        val cantoAmpliado = redimensionarCaixaAuditavel(canto, 3.0f)
        assertTrue(cantoAmpliado.normLeft >= 0f)
        assertTrue(cantoAmpliado.normTop >= 0f)
        assertTrue(cantoAmpliado.normRight <= 1f)
        assertTrue(cantoAmpliado.normBottom <= 1f)
    }

    @Test
    fun testCoordinateSnappingPosCaptura_AtualizaCoordenadasPreservandoRotulo() {
        val caixaPreview = CaixaAuditavel(
            normLeft = 0.20f,
            normTop = 0.30f,
            normRight = 0.40f,
            normBottom = 0.55f,
            rotulo = "classe_usuario",
            manual = false,
            confianca = 0.82f
        )

        val detFotoEstatica = DetEstilizada(
            normLeft = 0.22f,
            normTop = 0.32f,
            normRight = 0.42f,
            normBottom = 0.57f,
            confianca = 0.94f,
            rotulo = "cacau"
        )

        val detNovoObjeto = DetEstilizada(
            normLeft = 0.65f,
            normTop = 0.60f,
            normRight = 0.85f,
            normBottom = 0.85f,
            confianca = 0.89f,
            rotulo = "cacau"
        )

        val caixasFundidas = fundirDeteccoesComSnapping(
            caixasAtuais = listOf(caixaPreview),
            deteccoesEstaticas = listOf(detFotoEstatica, detNovoObjeto),
            caixasExcluidas = emptyList()
        )

        assertEquals(2, caixasFundidas.size)
        val snap = caixasFundidas[0]
        assertEquals(0.22f, snap.normLeft, 0.001f)
        assertEquals(0.32f, snap.normTop, 0.001f)
        assertEquals("classe_usuario", snap.rotulo)
        assertEquals(0.94f, snap.confianca, 0.001f)

        val novo = caixasFundidas[1]
        assertEquals(0.65f, novo.normLeft, 0.001f)
        assertEquals("cacau", novo.rotulo)
    }
}
