package com.igor.breedai.userinterface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.calcularEstimativaPlanta
import com.igor.breedai.data.formatarEstimativaCurta
import com.igor.breedai.userinterface.util.ObjectDetectorHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

private val PALETA_CLASSES = listOf(
    Color(0xFF00897B),
    Color(0xFFFFB300),
    Color(0xFF00B0FF),
    Color(0xFFFF5252),
    Color(0xFFE040FB),
    Color(0xFF64FFDA)
)

internal fun rotuloAmigavel(label: String): String {
    val limpo = label.trim()
    return when (limpo.lowercase(Locale.ROOT)) {
        "podridaoparda", "podridão parda", "podridao", "podridão" -> "Podridão"
        "moniliase", "monilíase" -> "Monilíase"
        "vassourabruxa", "vassoura de bruxa", "vassoura" -> "Vassoura"
        "sadio" -> "Sadio"
        "apple" -> "Maçã"
        "banana" -> "Banana"
        "orange" -> "Laranja"
        "" -> "Objeto"
        else -> limpo
    }
}

private fun isRotuloGenerico(rotulo: String): Boolean {
    val low = rotulo.trim().lowercase(Locale.ROOT)
    return low.isBlank() || low == "cacau" || low == "fruto" || low == "objeto"
}

internal fun corParaLabel(
    label: String,
    manual: Boolean = false
): Color {
    val low = label.trim().lowercase(Locale.ROOT)
    return when {
        low == "sadio" -> Color(0xFF2E7D32)
        low.contains("vassoura") -> Color(0xFFE65100)
        low.contains("podrid") -> Color(0xFFC62828)
        low.contains("monili") -> Color(0xFF8E24AA)
        manual && isRotuloGenerico(low) -> Color(0xFF1565C0)
        isRotuloGenerico(low) -> Color(0xFF00E676)
        else -> {
            val hash = kotlin.math.abs(low.hashCode())
            PALETA_CLASSES[hash % PALETA_CLASSES.size]
        }
    }
}

internal fun corParaCaixa(
    rotulo: String,
    manual: Boolean,
    labelsDisponiveis: List<String>
): Color {
    val low = rotulo.trim().lowercase(Locale.ROOT)
    if (low == "sadio" || low.contains("vassoura") || low.contains("podrid") || low.contains("monili")) {
        return corParaLabel(rotulo, manual)
    }
    if (labelsDisponiveis.size <= 1) {
        return if (manual) Color(0xFF1565C0) else Color(0xFF00E676)
    }
    return corParaLabel(rotulo, manual)
}

/**
 * Caixa de detecção auditável com coordenadas normalizadas (0.0 a 1.0)
 * para fidelidade geométrica independente de resolução ou aparelho.
 */
data class CaixaAuditavel(
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
    val rotulo: String = "Objeto",
    val manual: Boolean = false,
    val confianca: Float = 0.90f
) {
    val centroX: Float get() = (normLeft + normRight) / 2f
    val centroY: Float get() = (normTop + normBottom) / 2f
    val largura: Float get() = (normRight - normLeft).coerceAtLeast(0f)
    val altura: Float get() = (normBottom - normTop).coerceAtLeast(0f)
}

internal data class SnapshotRevisao(
    val caixas: List<CaixaAuditavel>,
    val excluidas: List<CaixaAuditavel>
)

internal enum class ModoVisualizacaoOverlay(val labelCurto: String) {
    COMPLETO("Rótulos"),
    SO_CAIXAS("Só caixas"),
    FOTO_LIMPA("Sem caixas")
}

internal fun resolverToqueEmCaixaExistente(
    indiceAtualSelecionado: Int,
    indiceTocado: Int,
    rotuloAtual: String,
    classesDisponiveis: List<String>,
    classePreferida: String
): Pair<Int, String> {
    if (indiceAtualSelecionado != indiceTocado) {
        return indiceTocado to rotuloAtual
    }
    if (classesDisponiveis.size > 1) {
        val curIdx = classesDisponiveis.indexOfFirst { it.equals(rotuloAtual, ignoreCase = true) }
        val novoRotulo = if (curIdx >= 0) {
            classesDisponiveis[(curIdx + 1) % classesDisponiveis.size]
        } else {
            classePreferida.ifBlank { classesDisponiveis.first() }
        }
        return indiceTocado to novoRotulo
    }
    return indiceTocado to rotuloAtual
}

/**
 * Seleciona a caixa mais próxima ao centro tocado em aglomerados densos.
 */
internal fun selecionarIndiceCaixaNoToque(
    caixas: List<CaixaAuditavel>,
    normTapX: Float,
    normTapY: Float,
    margemNormX: Float = 0.03f,
    margemNormY: Float = 0.03f
): Int {
    if (caixas.isEmpty()) return -1

    var melhorIndice = -1
    var melhorDistancia = Float.MAX_VALUE
    var melhorEstritamenteDentro = false

    for (i in caixas.indices) {
        val c = caixas[i]
        val dentroEstrito = normTapX in c.normLeft..c.normRight && normTapY in c.normTop..c.normBottom
        val dentroComMargem = normTapX in (c.normLeft - margemNormX)..(c.normRight + margemNormX) &&
                normTapY in (c.normTop - margemNormY)..(c.normBottom + margemNormY)

        if (dentroComMargem) {
            val dx = normTapX - c.centroX
            val dy = normTapY - c.centroY
            val distSq = dx * dx + dy * dy

            if (dentroEstrito && !melhorEstritamenteDentro) {
                melhorEstritamenteDentro = true
                melhorDistancia = distSq
                melhorIndice = i
            } else if (dentroEstrito == melhorEstritamenteDentro) {
                if (distSq < melhorDistancia) {
                    melhorDistancia = distSq
                    melhorIndice = i
                }
            }
        }
    }

    return melhorIndice
}

internal fun redimensionarCaixaAuditavel(
    caixa: CaixaAuditavel,
    fatorEscala: Float,
    minDim: Float = 0.025f,
    maxDim: Float = 0.95f
): CaixaAuditavel {
    val cx = caixa.centroX
    val cy = caixa.centroY
    val novaLargura = (caixa.largura * fatorEscala).coerceIn(minDim, maxDim)
    val novaAltura = (caixa.altura * fatorEscala).coerceIn(minDim, maxDim)

    val halfW = novaLargura / 2f
    val halfH = novaAltura / 2f

    val nL = (cx - halfW).coerceIn(0f, 1f - novaLargura)
    val nT = (cy - halfH).coerceIn(0f, 1f - novaAltura)
    val nR = (nL + novaLargura).coerceIn(0f, 1f)
    val nB = (nT + novaAltura).coerceIn(0f, 1f)

    return caixa.copy(
        normLeft = nL,
        normTop = nT,
        normRight = nR,
        normBottom = nB
    )
}

internal enum class AlcaCaixa {
    NENHUMA,
    CORPO,
    CANTO_SE,
    CANTO_SD,
    CANTO_IE,
    CANTO_ID
}

internal fun detectarAlcaCaixaSelecionada(
    caixa: CaixaAuditavel,
    normX: Float,
    normY: Float,
    raioNormX: Float,
    raioNormY: Float
): AlcaCaixa {
    val rx = raioNormX.coerceAtLeast(0.001f).toDouble()
    val ry = raioNormY.coerceAtLeast(0.001f).toDouble()
    val cantos = listOf(
        AlcaCaixa.CANTO_SE to hypot((normX - caixa.normLeft) / rx, (normY - caixa.normTop) / ry),
        AlcaCaixa.CANTO_SD to hypot((normX - caixa.normRight) / rx, (normY - caixa.normTop) / ry),
        AlcaCaixa.CANTO_IE to hypot((normX - caixa.normLeft) / rx, (normY - caixa.normBottom) / ry),
        AlcaCaixa.CANTO_ID to hypot((normX - caixa.normRight) / rx, (normY - caixa.normBottom) / ry)
    )
    val cantoMaisProximo = cantos.filter { it.second <= 1.25 }.minByOrNull { it.second }
    if (cantoMaisProximo != null) return cantoMaisProximo.first

    if (normX in (caixa.normLeft - raioNormX * 0.35f)..(caixa.normRight + raioNormX * 0.35f) &&
        normY in (caixa.normTop - raioNormY * 0.35f)..(caixa.normBottom + raioNormY * 0.35f)
    ) {
        return AlcaCaixa.CORPO
    }
    return AlcaCaixa.NENHUMA
}

internal fun moverCaixaAuditavel(
    caixa: CaixaAuditavel,
    deltaNormX: Float,
    deltaNormY: Float
): CaixaAuditavel {
    val w = caixa.largura.coerceIn(0.02f, 1f)
    val h = caixa.altura.coerceIn(0.02f, 1f)
    val novoL = (caixa.normLeft + deltaNormX).coerceIn(0f, 1f - w)
    val novoT = (caixa.normTop + deltaNormY).coerceIn(0f, 1f - h)
    return caixa.copy(
        normLeft = novoL,
        normTop = novoT,
        normRight = (novoL + w).coerceIn(0f, 1f),
        normBottom = (novoT + h).coerceIn(0f, 1f)
    )
}

internal fun ajustarCantoCaixaAuditavel(
    caixa: CaixaAuditavel,
    alca: AlcaCaixa,
    novoNormX: Float,
    novoNormY: Float,
    minDim: Float = 0.03f
): CaixaAuditavel {
    val x = novoNormX.coerceIn(0f, 1f)
    val y = novoNormY.coerceIn(0f, 1f)
    return when (alca) {
        AlcaCaixa.CANTO_SE -> caixa.copy(
            normLeft = x.coerceAtMost(caixa.normRight - minDim).coerceAtLeast(0f),
            normTop = y.coerceAtMost(caixa.normBottom - minDim).coerceAtLeast(0f)
        )
        AlcaCaixa.CANTO_SD -> caixa.copy(
            normRight = x.coerceAtLeast(caixa.normLeft + minDim).coerceAtMost(1f),
            normTop = y.coerceAtMost(caixa.normBottom - minDim).coerceAtLeast(0f)
        )
        AlcaCaixa.CANTO_IE -> caixa.copy(
            normLeft = x.coerceAtMost(caixa.normRight - minDim).coerceAtLeast(0f),
            normBottom = y.coerceAtLeast(caixa.normTop + minDim).coerceAtMost(1f)
        )
        AlcaCaixa.CANTO_ID -> caixa.copy(
            normRight = x.coerceAtLeast(caixa.normLeft + minDim).coerceAtMost(1f),
            normBottom = y.coerceAtLeast(caixa.normTop + minDim).coerceAtMost(1f)
        )
        else -> caixa
    }
}

data class DetEstilizada(
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
    val confianca: Float,
    val rotulo: String = "Objeto"
)

/**
 * Fusão com Coordinate Snapping na foto estática:
 * alinha as caixas do preview sobre a foto final, preserva edições/rótulos do usuário,
 * impede ressurreição de caixas excluídas e adiciona novos objetos detectados na foto estática.
 */
internal fun fundirDeteccoesComSnapping(
    caixasAtuais: List<CaixaAuditavel>,
    deteccoesEstaticas: List<DetEstilizada>,
    caixasExcluidas: List<CaixaAuditavel>
): List<CaixaAuditavel> {
    if (deteccoesEstaticas.isEmpty()) return caixasAtuais

    val resultado = caixasAtuais.toMutableList()
    val indicesPareados = mutableSetOf<Int>()

    for (det in deteccoesEstaticas) {
        val foiExcluida = caixasExcluidas.any { ex ->
            iouNorm(ex, det.normLeft, det.normTop, det.normRight, det.normBottom) > 0.35f
        }
        if (foiExcluida) continue

        val cobreManual = resultado.any { c ->
            c.manual && iouNorm(c, det.normLeft, det.normTop, det.normRight, det.normBottom) > 0.35f
        }
        if (cobreManual) continue

        var melhorIdx = -1
        var melhorIou = 0.20f

        for (i in resultado.indices) {
            if (i in indicesPareados) continue
            val c = resultado[i]
            if (c.manual) continue

            val iou = iouNorm(c, det.normLeft, det.normTop, det.normRight, det.normBottom)
            if (iou > melhorIou) {
                melhorIou = iou
                melhorIdx = i
            }
        }

        if (melhorIdx >= 0) {
            indicesPareados.add(melhorIdx)
            val existente = resultado[melhorIdx]
            val estaticoEspecifico = !isRotuloGenerico(det.rotulo)
            val existenteGenerico = isRotuloGenerico(existente.rotulo)
            val rotuloAtualizado = if (!existente.manual && estaticoEspecifico &&
                (existenteGenerico || !existente.rotulo.equals(det.rotulo, ignoreCase = true))
            ) {
                det.rotulo
            } else {
                existente.rotulo
            }
            resultado[melhorIdx] = existente.copy(
                normLeft = det.normLeft,
                normTop = det.normTop,
                normRight = det.normRight,
                normBottom = det.normBottom,
                rotulo = rotuloAtualizado,
                confianca = maxOf(existente.confianca, det.confianca)
            )
        } else {
            resultado.add(
                CaixaAuditavel(
                    normLeft = det.normLeft,
                    normTop = det.normTop,
                    normRight = det.normRight,
                    normBottom = det.normBottom,
                    rotulo = det.rotulo,
                    manual = false,
                    confianca = det.confianca
                )
            )
        }
    }

    return resultado
}

internal fun fundirContagemEDiagnosticoBreedAI(
    caixasContagem: List<DetEstilizada>,
    deteccoesSecundarias: List<DetEstilizada>,
    iouMinimo: Float = 0.20f,
    confiancaMinimaSecundaria: Float = 0.22f
): List<DetEstilizada> {
    if (deteccoesSecundarias.isEmpty()) return caixasContagem
    if (caixasContagem.isEmpty()) return deteccoesSecundarias

    val resultado = mutableListOf<DetEstilizada>()
    val pareadosSecundario = mutableSetOf<Int>()

    for (base in caixasContagem) {
        var melhorIdx = -1
        var melhorScore = -1f
        val cxBase = (base.normLeft + base.normRight) / 2f
        val cyBase = (base.normTop + base.normBottom) / 2f
        val maxDimBase = maxOf(base.normRight - base.normLeft, base.normBottom - base.normTop).coerceAtLeast(0.05f)

        for (i in deteccoesSecundarias.indices) {
            val sec = deteccoesSecundarias[i]
            val iou = iouCaixasNorm(
                base.normLeft, base.normTop, base.normRight, base.normBottom,
                sec.normLeft, sec.normTop, sec.normRight, sec.normBottom
            )
            val cxSec = (sec.normLeft + sec.normRight) / 2f
            val cySec = (sec.normTop + sec.normBottom) / 2f
            val centroDentro = cxSec in base.normLeft..base.normRight &&
                    cySec in base.normTop..base.normBottom
            val distCentros = kotlin.math.hypot((cxSec - cxBase).toDouble(), (cySec - cyBase).toDouble()).toFloat()
            val proximoPorCentro = distCentros <= 0.35f * maxDimBase

            if (iou >= iouMinimo || centroDentro || proximoPorCentro) {
                val pontuacao = iou + (if (centroDentro) 0.15f else 0f) + (if (proximoPorCentro) 0.08f else 0f)
                if (pontuacao > melhorScore) {
                    melhorScore = pontuacao
                    melhorIdx = i
                }
            }
        }

        if (melhorIdx >= 0) {
            pareadosSecundario.add(melhorIdx)
            val sec = deteccoesSecundarias[melhorIdx]
            val rotuloFinal = if (!isRotuloGenerico(sec.rotulo)) sec.rotulo else base.rotulo
            resultado.add(
                base.copy(
                    rotulo = rotuloFinal,
                    confianca = maxOf(base.confianca, sec.confianca)
                )
            )
        } else {
            resultado.add(base)
        }
    }

    for (i in deteccoesSecundarias.indices) {
        if (i in pareadosSecundario) continue
        val sec = deteccoesSecundarias[i]
        if (isRotuloGenerico(sec.rotulo) || sec.confianca < confiancaMinimaSecundaria) continue

        val cxSec = (sec.normLeft + sec.normRight) / 2f
        val cySec = (sec.normTop + sec.normBottom) / 2f
        val colideComContagem = caixasContagem.any { base ->
            val iou = iouCaixasNorm(
                base.normLeft, base.normTop, base.normRight, base.normBottom,
                sec.normLeft, sec.normTop, sec.normRight, sec.normBottom
            )
            val centroDentro = cxSec in base.normLeft..base.normRight &&
                    cySec in base.normTop..base.normBottom
            iou >= 0.20f || centroDentro
        }
        if (!colideComContagem) {
            resultado.add(sec)
        }
    }

    return resultado
}

@Composable
fun TelaRevisaoCaptura(
    fotoUri: Uri,
    plantId: String,
    deteccoesIniciais: List<Detection>,
    bitmapWidthRef: Float,
    bitmapHeightRef: Float,
    detectorHelper: ObjectDetectorHelper? = null,
    detectorSecundarioHelper: ObjectDetectorHelper? = null,
    labelsFiltro: List<String> = emptyList(),
    caixasAuditadasIniciais: List<CaixaAuditavel>? = null,
    modoSemFrutos: Boolean = false,
    classificacoesPersonalizadas: List<String>? = null,
    onAtualizarClassificacoes: ((List<String>) -> Unit)? = null,
    configRendimento: ConfigConversaoRendimento? = null,
    mostrarBotaoProximaFoto: Boolean = false,
    onConfirmarEProximaFoto: ((totalObjetos: Int, caixas: List<CaixaAuditavel>) -> Unit)? = null,
    onConfirmar: (totalObjetos: Int, caixas: List<CaixaAuditavel>) -> Unit,
    onRefazer: () -> Unit
) {
    val context = LocalContext.current
    val classesIniciais = remember(
        classificacoesPersonalizadas,
        labelsFiltro,
        detectorHelper,
        detectorSecundarioHelper
    ) {
        if (!classificacoesPersonalizadas.isNullOrEmpty()) {
            classificacoesPersonalizadas
        } else {
            val doPrimario = detectorHelper?.obterLabelsModelo()
                ?.filter { it != "???" && it.isNotBlank() }
                ?.takeIf { it.size in 2..30 }
                ?: emptyList()
            val doSecundario = detectorSecundarioHelper?.obterLabelsModelo()
                ?.filter { it != "???" && it.isNotBlank() }
                ?.takeIf { it.size in 2..30 }
                ?: emptyList()
            val combinadas = (labelsFiltro + doPrimario + doSecundario)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.ROOT) }
            val especificas = combinadas.filter { !isRotuloGenerico(it) }
            if (especificas.isNotEmpty()) especificas else combinadas
        }
    }
    val classesDisponiveis = remember(classesIniciais) {
        mutableStateListOf<String>().apply { addAll(classesIniciais) }
    }
    val rotuloPadrao = remember(classesDisponiveis.toList()) {
        classesDisponiveis.firstOrNull { it.equals("Sadio", ignoreCase = true) }
            ?: classesDisponiveis.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: "Objeto"
    }
    var classeParaAdicionar by remember(rotuloPadrao) { mutableStateOf(rotuloPadrao) }
    var mostrarDialogNovaClassificacao by remember { mutableStateOf(false) }

    val caixas = remember(fotoUri, modoSemFrutos) {
        mutableStateListOf<CaixaAuditavel>().apply {
            if (modoSemFrutos) {
                // Foto de comprovação de planta sem frutos começa com 0 objetos
            } else if (!caixasAuditadasIniciais.isNullOrEmpty()) {
                addAll(caixasAuditadasIniciais)
            } else {
                val wRef = bitmapWidthRef.coerceAtLeast(1f)
                val hRef = bitmapHeightRef.coerceAtLeast(1f)
                deteccoesIniciais.forEach { det ->
                    val rect = det.boundingBox
                    val cat = det.categories.firstOrNull()
                    val score = cat?.score ?: 0.85f
                    val rot = cat?.label?.takeIf { it.isNotBlank() } ?: rotuloPadrao
                    add(
                        CaixaAuditavel(
                            normLeft = (rect.left / wRef).coerceIn(0f, 1f),
                            normTop = (rect.top / hRef).coerceIn(0f, 1f),
                            normRight = (rect.right / wRef).coerceIn(0f, 1f),
                            normBottom = (rect.bottom / hRef).coerceIn(0f, 1f),
                            rotulo = rot,
                            manual = false,
                            confianca = score
                        )
                    )
                }
            }
        }
    }

    var indiceSelecionado by remember { mutableIntStateOf(-1) }
    var analisandoFotoEstatica by remember {
        mutableStateOf(!modoSemFrutos && caixasAuditadasIniciais == null && detectorHelper != null)
    }
    val caixasExcluidasManualmente = remember(fotoUri) { mutableListOf<CaixaAuditavel>() }
    val historicoUndo = remember(fotoUri) { mutableStateListOf<SnapshotRevisao>() }
    var modoOverlay by remember(fotoUri) { mutableStateOf(ModoVisualizacaoOverlay.COMPLETO) }

    fun registrarPontoUndo() {
        if (historicoUndo.size >= 25) {
            historicoUndo.removeAt(0)
        }
        historicoUndo.add(
            SnapshotRevisao(
                caixas = caixas.toList(),
                excluidas = caixasExcluidasManualmente.toList()
            )
        )
    }

    fun desfazerUltimoPasso() {
        if (historicoUndo.isEmpty()) return
        val anterior = historicoUndo.removeAt(historicoUndo.lastIndex)
        caixas.clear()
        caixas.addAll(anterior.caixas)
        caixasExcluidasManualmente.clear()
        caixasExcluidasManualmente.addAll(anterior.excluidas)
        if (indiceSelecionado !in caixas.indices) {
            indiceSelecionado = -1
        }
    }

    val imgAspect = remember(fotoUri) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(fotoUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            val exif = context.contentResolver.openInputStream(fotoUri)?.use { stream ->
                ExifInterface(stream)
            }
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE
            val w = if (rotated) options.outHeight else options.outWidth
            val h = if (rotated) options.outWidth else options.outHeight
            if (w > 0 && h > 0) w.toFloat() / h.toFloat() else (3f / 4f)
        } catch (_: Exception) {
            3f / 4f
        }
    }

    // Re-inferência sobre a foto estática com Coordinate Snapping (exceto no modo Sem Frutos)
    LaunchedEffect(fotoUri, modoSemFrutos) {
        if (modoSemFrutos || caixasAuditadasIniciais != null) {
            analisandoFotoEstatica = false
            return@LaunchedEffect
        }
        val helper = detectorHelper ?: run {
            analisandoFotoEstatica = false
            return@LaunchedEffect
        }
        analisandoFotoEstatica = true
        withContext(Dispatchers.IO) {
            val bmp = carregarBitmapFoto(context, fotoUri, maxDim = 1280)
            if (bmp == null) {
                withContext(Dispatchers.Main) { analisandoFotoEstatica = false }
                return@withContext
            }
            try {
                val res = helper.detectarSincrono(bmp)
                if (res != null) {
                    val bmpW = bmp.width.toFloat()
                    val bmpH = bmp.height.toFloat()

                    val candidatasContagem = mutableListOf<DetEstilizada>()
                    val labelsDoModelo = helper.obterLabelsModelo().filter { it != "???" && it.isNotBlank() }
                    res.deteccoes.forEach { det ->
                        val cat = det.categories.firstOrNull()
                        val rawLabel = cat?.label?.takeIf { it.isNotBlank() } ?: rotuloPadrao
                        val labelLower = rawLabel.lowercase(Locale.ROOT)
                        val labelValido = rotuloPassaFiltroModelo(rawLabel, labelsFiltro, labelsDoModelo) ||
                            (detectorSecundarioHelper != null && labelLower in setOf("cacau", "fruto"))
                        if (!labelValido) return@forEach

                        val r = det.boundingBox
                        candidatasContagem.add(
                            DetEstilizada(
                                normLeft = (r.left / bmpW).coerceIn(0f, 1f),
                                normTop = (r.top / bmpH).coerceIn(0f, 1f),
                                normRight = (r.right / bmpW).coerceIn(0f, 1f),
                                normBottom = (r.bottom / bmpH).coerceIn(0f, 1f),
                                confianca = cat?.score ?: 0.85f,
                                rotulo = rawLabel
                            )
                        )
                    }

                    val candidatasEstaticas = if (detectorSecundarioHelper != null) {
                        val resSec = detectorSecundarioHelper.detectarSincrono(bmp)
                        val detsSec = resSec?.deteccoes?.mapNotNull { det ->
                            val cat = det.categories.firstOrNull() ?: return@mapNotNull null
                            val r = det.boundingBox
                            DetEstilizada(
                                normLeft = (r.left / bmpW).coerceIn(0f, 1f),
                                normTop = (r.top / bmpH).coerceIn(0f, 1f),
                                normRight = (r.right / bmpW).coerceIn(0f, 1f),
                                normBottom = (r.bottom / bmpH).coerceIn(0f, 1f),
                                confianca = cat.score,
                                rotulo = cat.label.takeIf { it.isNotBlank() } ?: rotuloPadrao
                            )
                        } ?: emptyList()
                        fundirContagemEDiagnosticoBreedAI(candidatasContagem, detsSec).map { detEst ->
                            if (isRotuloGenerico(detEst.rotulo)) {
                                val classCrop = ObjectDetectorHelper.classificarRecorteDoencaCacau(
                                    bmp,
                                    android.graphics.RectF(
                                        detEst.normLeft * bmpW,
                                        detEst.normTop * bmpH,
                                        detEst.normRight * bmpW,
                                        detEst.normBottom * bmpH
                                    )
                                )
                                detEst.copy(rotulo = classCrop)
                            } else {
                                detEst
                            }
                        }
                    } else {
                        candidatasContagem
                    }

                    withContext(Dispatchers.Main) {
                        val fundidas = fundirDeteccoesComSnapping(
                            caixasAtuais = caixas.toList(),
                            deteccoesEstaticas = candidatasEstaticas,
                            caixasExcluidas = caixasExcluidasManualmente.toList()
                        )
                        caixas.clear()
                        caixas.addAll(fundidas)
                        if (indiceSelecionado !in caixas.indices) {
                            indiceSelecionado = -1
                        }
                    }
                }
            } finally {
                bmp.recycle()
                withContext(Dispatchers.Main) { analisandoFotoEstatica = false }
            }
        }
    }

    val totalObjetos = caixas.size
    val totalIA = caixas.count { !it.manual }
    val totalManuais = caixas.count { it.manual }

    val textPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 28f
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val badgePaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
        }
    }

    var zoomScale by remember(fotoUri) { mutableFloatStateOf(1f) }
    var panOffset by remember(fotoUri) { mutableStateOf(Offset.Zero) }
    var pinchModoFoto by remember(fotoUri) { mutableStateOf(false) }

    LaunchedEffect(indiceSelecionado) {
        if (indiceSelecionado < 0) {
            pinchModoFoto = false
        }
    }

    val modoSemFrutosAtual by rememberUpdatedState(modoSemFrutos)
    val classesAtual by rememberUpdatedState(classesDisponiveis.toList())
    val classeAddAtual by rememberUpdatedState(classeParaAdicionar)
    val indiceSelState = rememberUpdatedState(indiceSelecionado)
    val pinchFotoState = rememberUpdatedState(pinchModoFoto)
    val modoOverlayState = rememberUpdatedState(modoOverlay)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(fotoUri) {
                    val canvasW = size.width.toFloat().coerceAtLeast(1f)
                    val canvasH = size.height.toFloat().coerceAtLeast(1f)
                    val containerAspect = canvasW / canvasH
                    val displayedW: Float
                    val displayedH: Float
                    if (containerAspect > imgAspect) {
                        displayedH = canvasH
                        displayedW = displayedH * imgAspect
                    } else {
                        displayedW = canvasW
                        displayedH = displayedW / imgAspect
                    }
                    val offsetX = (canvasW - displayedW) / 2f
                    val offsetY = (canvasH - displayedH) / 2f
                    val screenCenter = Offset(canvasW / 2f, canvasH / 2f)

                    fun clampPan(pan: Offset, scale: Float): Offset {
                        if (scale <= 1.01f) return Offset.Zero
                        val maxX = (canvasW * (scale - 1f)) / 2f
                        val maxY = (canvasH * (scale - 1f)) / 2f
                        return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
                    }

                    fun screenToNorm(pt: Offset): Offset {
                        val s = zoomScale
                        val p = panOffset
                        val canvasX = screenCenter.x + (pt.x - screenCenter.x - p.x) / s
                        val canvasY = screenCenter.y + (pt.y - screenCenter.y - p.y) / s
                        return Offset((canvasX - offsetX) / displayedW, (canvasY - offsetY) / displayedH)
                    }

                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        if (modoSemFrutosAtual) return@awaitEachGesture

                        val startScreen = firstDown.position
                        val startNorm = screenToNorm(startScreen)
                        val margemNormX = (24.dp.toPx() / (displayedW * zoomScale)).coerceAtLeast(0.01f)
                        val margemNormY = (24.dp.toPx() / (displayedH * zoomScale)).coerceAtLeast(0.01f)

                        val somenteFotoLimpa = modoOverlayState.value == ModoVisualizacaoOverlay.FOTO_LIMPA
                        var activeAlca = AlcaCaixa.NENHUMA
                        var dragIdx = -1
                        val selAtual = indiceSelState.value
                        if (!somenteFotoLimpa) {
                            if (selAtual in caixas.indices) {
                                val alcaSel = detectarAlcaCaixaSelecionada(
                                    caixas[selAtual], startNorm.x, startNorm.y, margemNormX, margemNormY
                                )
                                if (alcaSel != AlcaCaixa.NENHUMA) {
                                    activeAlca = alcaSel
                                    dragIdx = selAtual
                                }
                            }
                            if (dragIdx < 0) {
                                val hit = selecionarIndiceCaixaNoToque(
                                    caixas = caixas,
                                    normTapX = startNorm.x,
                                    normTapY = startNorm.y,
                                    margemNormX = margemNormX,
                                    margemNormY = margemNormY
                                )
                                if (hit >= 0) {
                                    dragIdx = hit
                                    activeAlca = AlcaCaixa.CORPO
                                }
                            }
                        }

                        var isMultiTouch = false
                        var movedPastSlop = false
                        var undoRegistradoNoGesto = false
                        var prevSinglePos = startScreen
                        val touchSlop = viewConfiguration.touchSlop

                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount >= 2) {
                                isMultiTouch = true
                                movedPastSlop = true
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val curSel = indiceSelState.value

                                if (!somenteFotoLimpa && curSel in caixas.indices && !pinchFotoState.value) {
                                    if (!undoRegistradoNoGesto) {
                                        registrarPontoUndo()
                                        undoRegistradoNoGesto = true
                                    }
                                    var boxAtual = caixas[curSel]
                                    if (abs(zoomChange - 1f) > 0.001f) {
                                        boxAtual = redimensionarCaixaAuditavel(
                                            boxAtual, zoomChange, minDim = 0.025f, maxDim = 0.95f
                                        )
                                    }
                                    if (panChange != Offset.Zero) {
                                        val dxNorm = panChange.x / (displayedW * zoomScale)
                                        val dyNorm = panChange.y / (displayedH * zoomScale)
                                        boxAtual = moverCaixaAuditavel(boxAtual, dxNorm, dyNorm)
                                    }
                                    caixas[curSel] = boxAtual
                                } else {
                                    val oldScale = zoomScale
                                    val newScale = (oldScale * zoomChange).coerceIn(1f, 5f)
                                    val ratio = newScale / oldScale
                                    val rawNewPan = centroid + panChange - screenCenter -
                                            (centroid - screenCenter - panOffset) * ratio
                                    zoomScale = newScale
                                    panOffset = clampPan(rawNewPan, newScale)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (pressedCount == 1 && !isMultiTouch) {
                                val ch = event.changes.first()
                                val curPos = ch.position
                                if (!movedPastSlop && (curPos - startScreen).getDistance() > touchSlop) {
                                    movedPastSlop = true
                                }
                                if (movedPastSlop) {
                                    if (!somenteFotoLimpa && dragIdx in caixas.indices) {
                                        if (!undoRegistradoNoGesto) {
                                            registrarPontoUndo()
                                            undoRegistradoNoGesto = true
                                        }
                                        if (indiceSelState.value != dragIdx) {
                                            indiceSelecionado = dragIdx
                                        }
                                        if (activeAlca == AlcaCaixa.CORPO) {
                                            val delta = curPos - prevSinglePos
                                            val dxNorm = delta.x / (displayedW * zoomScale)
                                            val dyNorm = delta.y / (displayedH * zoomScale)
                                            caixas[dragIdx] = moverCaixaAuditavel(caixas[dragIdx], dxNorm, dyNorm)
                                        } else if (activeAlca != AlcaCaixa.NENHUMA) {
                                            val normCur = screenToNorm(curPos)
                                            caixas[dragIdx] = ajustarCantoCaixaAuditavel(
                                                caixas[dragIdx], activeAlca, normCur.x, normCur.y
                                            )
                                        }
                                        ch.consume()
                                    } else if (zoomScale > 1.02f) {
                                        val delta = curPos - prevSinglePos
                                        panOffset = clampPan(panOffset + delta, zoomScale)
                                        ch.consume()
                                    }
                                }
                                prevSinglePos = curPos
                            }
                        } while (event.changes.any { it.pressed })

                        if (!somenteFotoLimpa && !isMultiTouch && !movedPastSlop) {
                            if (startNorm.x in 0f..1f && startNorm.y in 0f..1f) {
                                val hitTap = selecionarIndiceCaixaNoToque(
                                    caixas = caixas,
                                    normTapX = startNorm.x,
                                    normTapY = startNorm.y,
                                    margemNormX = margemNormX,
                                    margemNormY = margemNormY
                                )
                                if (hitTap >= 0) {
                                    val atual = caixas[hitTap]
                                    val (novoIdxSel, novoRotulo) = resolverToqueEmCaixaExistente(
                                        indiceAtualSelecionado = indiceSelState.value,
                                        indiceTocado = hitTap,
                                        rotuloAtual = atual.rotulo,
                                        classesDisponiveis = classesAtual,
                                        classePreferida = classeAddAtual
                                    )
                                    if (novoRotulo != atual.rotulo) {
                                        registrarPontoUndo()
                                        caixas[hitTap] = atual.copy(rotulo = novoRotulo)
                                    }
                                    indiceSelecionado = novoIdxSel
                                } else if (indiceSelState.value >= 0) {
                                    indiceSelecionado = -1
                                } else {
                                    registrarPontoUndo()
                                    val halfW = 0.07f
                                    val halfH = 0.07f
                                    val nova = CaixaAuditavel(
                                        normLeft = (startNorm.x - halfW).coerceIn(0f, 1f - halfW * 2),
                                        normTop = (startNorm.y - halfH).coerceIn(0f, 1f - halfH * 2),
                                        normRight = (startNorm.x + halfW).coerceIn(halfW * 2, 1f),
                                        normBottom = (startNorm.y + halfH).coerceIn(halfH * 2, 1f),
                                        rotulo = classeAddAtual,
                                        manual = true,
                                        confianca = 1.0f
                                    )
                                    caixas.add(nova)
                                    indiceSelecionado = caixas.lastIndex
                                }
                            }
                        }
                    }
                }
        ) {
            val canvasW = constraints.maxWidth.toFloat()
            val canvasH = constraints.maxHeight.toFloat()
            val containerAspect = canvasW / canvasH.coerceAtLeast(1f)

            val displayedW: Float
            val displayedH: Float
            if (containerAspect > imgAspect) {
                displayedH = canvasH
                displayedW = displayedH * imgAspect
            } else {
                displayedW = canvasW
                displayedH = displayedW / imgAspect
            }
            val offsetX = (canvasW - displayedW) / 2f
            val offsetY = (canvasH - displayedH) / 2f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panOffset.x
                        translationY = panOffset.y
                        transformOrigin = TransformOrigin.Center
                    }
            ) {
                AsyncImage(
                    model = fotoUri,
                    contentDescription = "Foto da planta $plantId",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                if (modoOverlay != ModoVisualizacaoOverlay.FOTO_LIMPA) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val invZoom = 1f / zoomScale.coerceAtLeast(1f)
                    caixas.forEachIndexed { idx, caixa ->
                        val bLeft = offsetX + caixa.normLeft * displayedW
                        val bTop = offsetY + caixa.normTop * displayedH
                        val bW = (caixa.normRight - caixa.normLeft) * displayedW
                        val bH = (caixa.normBottom - caixa.normTop) * displayedH
                        val selecionada = idx == indiceSelecionado
                        val cor = corParaCaixa(caixa.rotulo, caixa.manual, labelsFiltro)

                        drawRect(
                            color = cor,
                            topLeft = Offset(bLeft, bTop),
                            size = androidx.compose.ui.geometry.Size(bW, bH),
                            style = Stroke(width = (if (selecionada) 4.5.dp.toPx() else 3.dp.toPx()) * invZoom)
                        )

                        if (selecionada) {
                            val pad = 2.dp.toPx() * invZoom
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(bLeft - pad, bTop - pad),
                                size = androidx.compose.ui.geometry.Size(bW + pad * 2f, bH + pad * 2f),
                                style = Stroke(width = 1.8.dp.toPx() * invZoom)
                            )
                            val handleR = 6.5.dp.toPx() * invZoom
                            val corners = listOf(
                                Offset(bLeft, bTop),
                                Offset(bLeft + bW, bTop),
                                Offset(bLeft, bTop + bH),
                                Offset(bLeft + bW, bTop + bH)
                            )
                            corners.forEach { corner ->
                                drawCircle(color = Color.White, radius = handleR, center = corner)
                                drawCircle(
                                    color = cor,
                                    radius = handleR,
                                    center = corner,
                                    style = Stroke(width = 2.dp.toPx() * invZoom)
                                )
                            }
                        }

                        if (modoOverlay == ModoVisualizacaoOverlay.COMPLETO) {
                            val labelTxt = if (classesDisponiveis.size > 1 || !isRotuloGenerico(caixa.rotulo)) {
                                "#${idx + 1} ${rotuloAmigavel(caixa.rotulo)}"
                            } else if (caixa.manual) {
                                "#${idx + 1} (Manual)"
                            } else {
                                "#${idx + 1}"
                            }
                            val textWidth = textPaint.measureText(labelTxt)
                            val badgeHeight = 32f

                            badgePaint.color = cor.toArgb()
                            drawContext.canvas.nativeCanvas.drawRoundRect(
                                bLeft,
                                (bTop - badgeHeight - 4f).coerceAtLeast(0f),
                                bLeft + textWidth + 16f,
                                bTop,
                                8f, 8f,
                                badgePaint
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                labelTxt,
                                bLeft + 8f,
                                (bTop - 8f).coerceAtLeast(20f),
                                textPaint
                            )
                        }
                    }
                }
                }
            }
        }

        // Cabeçalho superior compacto + Controles flutuantes de zoom
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(plantId, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            if (modoSemFrutos) "Foto de comprovação (Sem Frutos)"
                            else "Toque p/ adicionar • Arraste caixa/cantos • Pinça p/ ampliar",
                            color = Color(0xFFB0BEC5),
                            fontSize = 10.sp
                        )
                    }

                    if (!modoSemFrutos) {
                        Surface(
                            onClick = {
                                val nextOrd = (modoOverlay.ordinal + 1) % ModoVisualizacaoOverlay.entries.size
                                modoOverlay = ModoVisualizacaoOverlay.entries[nextOrd]
                                if (modoOverlay == ModoVisualizacaoOverlay.FOTO_LIMPA) {
                                    indiceSelecionado = -1
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.14f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    if (modoOverlay == ModoVisualizacaoOverlay.FOTO_LIMPA) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Alternar visualização das caixas",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    modoOverlay.labelCurto,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            modoSemFrutos -> Color(0xFFEF6C00)
                            analisandoFotoEstatica -> Color(0xFF1565C0)
                            else -> Color(0xFF2E7D32)
                        }
                    ) {
                        Text(
                            when {
                                modoSemFrutos -> "SEM FRUTOS (0)"
                                analisandoFotoEstatica -> "Analisando IA..."
                                else -> "$totalObjetos Contados"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (zoomScale > 1.05f || (indiceSelecionado in caixas.indices && !modoSemFrutos)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (zoomScale > 1.05f) {
                        Surface(
                            onClick = {
                                zoomScale = 1f
                                panOffset = Offset.Zero
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Black.copy(alpha = 0.78f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E676))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.ZoomOutMap, null, tint = Color(0xFF00E676), modifier = Modifier.size(14.dp))
                                Text(
                                    "Zoom ${"%.1f".format(zoomScale)}x • Resetar",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    if (indiceSelecionado in caixas.indices && !modoSemFrutos) {
                        Surface(
                            onClick = { pinchModoFoto = !pinchModoFoto },
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Black.copy(alpha = 0.78f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (pinchModoFoto) Color(0xFF64B5F6) else Color(0xFFFFD54F)
                            )
                        ) {
                            Text(
                                if (pinchModoFoto) "Pinça (2 dedos): Zoom na Foto" else "Pinça (2 dedos): Tamanho da Caixa",
                                color = if (pinchModoFoto) Color(0xFF90CAF9) else Color(0xFFFFE082),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Painel inferior unificado
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Painel Flutuante da caixa selecionada: - / + / Excluir (+ classes dinâmicas se multiclasse)
            if (indiceSelecionado in caixas.indices && !modoSemFrutos) {
                val caixaAtual = caixas[indiceSelecionado]
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.98f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E676).copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Objeto #${indiceSelecionado + 1}${if (caixaAtual.manual) " (Manual)" else ""}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "Arraste p/ mover • Cantos ou pinça p/ tamanho",
                                    color = Color(0xFFA5D6A7),
                                    fontSize = 10.sp
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        registrarPontoUndo()
                                        caixas[indiceSelecionado] = redimensionarCaixaAuditavel(caixaAtual, 0.85f)
                                    },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                                ) {
                                    Text("−", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                IconButton(
                                    onClick = {
                                        registrarPontoUndo()
                                        caixas[indiceSelecionado] = redimensionarCaixaAuditavel(caixaAtual, 1.18f)
                                    },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                                ) {
                                    Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Button(
                                    onClick = {
                                        registrarPontoUndo()
                                        caixasExcluidasManualmente.add(caixaAtual)
                                        caixas.removeAt(indiceSelecionado)
                                        indiceSelecionado = -1
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(Icons.Default.Delete, "Excluir caixa", tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Excluir", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                IconButton(
                                    onClick = { indiceSelecionado = -1 },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Fechar seleção", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            classesDisponiveis.forEach { classeLabel ->
                                val ativo = caixaAtual.rotulo.equals(classeLabel, ignoreCase = true)
                                val corClasse = corParaLabel(classeLabel, manual = false)
                                Surface(
                                    onClick = {
                                        if (!caixaAtual.rotulo.equals(classeLabel, ignoreCase = true)) {
                                            registrarPontoUndo()
                                            caixas[indiceSelecionado] = caixaAtual.copy(rotulo = classeLabel)
                                        }
                                        classeParaAdicionar = classeLabel
                                        indiceSelecionado = -1
                                    },
                                    color = if (ativo) corClasse else Color(0xFF2C2C2C),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Text(
                                            rotuloAmigavel(classeLabel),
                                            fontSize = 11.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (classesDisponiveis.size > 1) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remover ${rotuloAmigavel(classeLabel)}",
                                                tint = Color.White.copy(alpha = 0.78f),
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clickable {
                                                        classesDisponiveis.remove(classeLabel)
                                                        onAtualizarClassificacoes?.invoke(classesDisponiveis.toList())
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = { mostrarDialogNovaClassificacao = true },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF81C784)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA5D6A7))
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Nova", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Painel inferior de resumo e confirmação
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF181818).copy(alpha = 0.96f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (modoSemFrutos) {
                        Text(
                            "Comprovação visual de planta normal com 0 frutos/objetos.",
                            color = Color(0xFFFFCC80),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        val contagemPorClasse = caixas
                            .groupingBy { rotuloAmigavel(it.rotulo) }
                            .eachCount()
                        val exibirPorClasse = classesDisponiveis.size > 1 ||
                            caixas.any { !isRotuloGenerico(it.rotulo) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ChipResumo("Total", totalObjetos, Color(0xFF00E676))
                            if (configRendimento != null && configRendimento.ativo && totalObjetos > 0) {
                                val estPlanta = calcularEstimativaPlanta(totalObjetos, caixas, configRendimento)
                                val sym = configRendimento.unidadeSimbolo.trim().ifBlank { "unid" }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFFFD54F).copy(alpha = 0.16f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = 0.50f))
                                ) {
                                    Text(
                                        "≈ ${formatarEstimativaCurta(estPlanta.unidadesComerciais, sym)} (${formatarEstimativaCurta(estPlanta.kgEstimado, "kg")})",
                                        color = Color(0xFFFFE082),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            if (exibirPorClasse && contagemPorClasse.isNotEmpty()) {
                                contagemPorClasse.forEach { (rotuloFriendly, qtd) ->
                                    ChipResumo(rotuloFriendly, qtd, corParaLabel(rotuloFriendly, manual = false))
                                }
                            } else {
                                ChipResumo("IA", totalIA, Color(0xFF69F0AE))
                                ChipResumo("Manuais", totalManuais, Color(0xFF1565C0))
                            }
                            if (historicoUndo.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { desfazerUltimoPasso() },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF90CAF9)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6))
                                ) {
                                    Text("↶ Desfazer", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    if (mostrarBotaoProximaFoto && onConfirmarEProximaFoto != null && !modoSemFrutos) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onRefazer,
                                modifier = Modifier.heightIn(min = 52.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.Refresh, "Refazer foto", modifier = Modifier.size(18.dp))
                            }
                            OutlinedButton(
                                onClick = { onConfirmar(totalObjetos, caixas.toList()) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA5D6A7)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF66BB6A)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    "Salvar ($totalObjetos)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = { onConfirmarEProximaFoto(totalObjetos, caixas.toList()) },
                                modifier = Modifier
                                    .weight(1.25f)
                                    .heightIn(min = 52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    "Salvar + Próx. →",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRefazer,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Refazer", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Button(
                                onClick = {
                                    if (modoSemFrutos) {
                                        onConfirmar(0, emptyList())
                                    } else {
                                        onConfirmar(totalObjetos, caixas.toList())
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.45f)
                                    .heightIn(min = 52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (modoSemFrutos) Color(0xFFEF6C00) else Color(0xFF2E7D32)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (modoSemFrutos) "Confirmar sem frutos (0)" else "Salvar contagem ($totalObjetos)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (mostrarDialogNovaClassificacao) {
            var textoNova by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { mostrarDialogNovaClassificacao = false },
                title = { Text("Nova Classificação", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Adicione uma classificação usada na sua cultura ou região (fica salva para as próximas fotos):",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        OutlinedTextField(
                            value = textoNova,
                            onValueChange = { textoNova = it },
                            label = { Text("Nome da classificação") },
                            placeholder = { Text("ex: Madura, Sigatoka, Sem Mancha...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val limpa = textoNova.trim()
                            if (limpa.isNotBlank()) {
                                if (classesDisponiveis.none { it.equals(limpa, ignoreCase = true) }) {
                                    classesDisponiveis.add(limpa)
                                    onAtualizarClassificacoes?.invoke(classesDisponiveis.toList())
                                }
                                val labelFinal = classesDisponiveis.first { it.equals(limpa, ignoreCase = true) }
                                if (indiceSelecionado in caixas.indices) {
                                    registrarPontoUndo()
                                    caixas[indiceSelecionado] = caixas[indiceSelecionado].copy(rotulo = labelFinal)
                                }
                                classeParaAdicionar = labelFinal
                            }
                            mostrarDialogNovaClassificacao = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("Adicionar") }
                },
                dismissButton = {
                    TextButton(onClick = { mostrarDialogNovaClassificacao = false }) { Text("Cancelar") }
                }
            )
        }
    }
}

@Composable
private fun ChipResumo(rotulo: String, total: Int, cor: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cor.copy(alpha = 0.16f),
        border = androidx.compose.foundation.BorderStroke(1.dp, cor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(cor, CircleShape)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "$rotulo: $total",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp
            )
        }
    }
}

internal fun iouNorm(c: CaixaAuditavel, l: Float, t: Float, r: Float, b: Float): Float =
    iouCaixasNorm(c.normLeft, c.normTop, c.normRight, c.normBottom, l, t, r, b)

internal fun iouCaixasNorm(
    l1: Float, t1: Float, r1: Float, b1: Float,
    l2: Float, t2: Float, r2: Float, b2: Float
): Float {
    val interL = maxOf(l1, l2)
    val interT = maxOf(t1, t2)
    val interR = minOf(r1, r2)
    val interB = minOf(b1, b2)
    val w = maxOf(0f, interR - interL)
    val h = maxOf(0f, interB - interT)
    val inter = w * h
    val area1 = maxOf(0f, r1 - l1) * maxOf(0f, b1 - t1)
    val area2 = maxOf(0f, r2 - l2) * maxOf(0f, b2 - t2)
    val union = area1 + area2 - inter
    return if (union <= 0f) 0f else inter / union
}

private fun carregarBitmapFoto(context: Context, uri: Uri, maxDim: Int = 1280): Bitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sampleSize = 1
        var w = origW
        var h = origH
        while (w > maxDim || h > maxDim) {
            sampleSize *= 2
            w /= 2
            h /= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val rawBmp = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }

        if (matrix.isIdentity) {
            rawBmp
        } else {
            val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
            if (rotated != rawBmp) rawBmp.recycle()
            rotated
        }
    } catch (_: Exception) {
        null
    }
}
