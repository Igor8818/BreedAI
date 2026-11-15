package com.example.myapplication.userinterface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplication.userinterface.util.ObjectDetectorHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale

private val PALETA_CLASSES = listOf(
    Color(0xFF00E676),
    Color(0xFFFFB300),
    Color(0xFF00B0FF),
    Color(0xFFFF5252),
    Color(0xFFE040FB),
    Color(0xFF64FFDA)
)

internal fun corParaCaixa(
    rotulo: String,
    manual: Boolean,
    labelsDisponiveis: List<String>
): Color {
    if (labelsDisponiveis.size <= 1) {
        return if (manual) Color(0xFF00E5FF) else Color(0xFF00E676)
    }
    val idx = labelsDisponiveis.indexOfFirst { it.equals(rotulo, ignoreCase = true) }
    return if (idx >= 0) {
        PALETA_CLASSES[idx % PALETA_CLASSES.size]
    } else if (manual) {
        Color(0xFF00E5FF)
    } else {
        Color(0xFF00E676)
    }
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
    minDim: Float = 0.03f,
    maxDim: Float = 0.70f
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
            resultado[melhorIdx] = existente.copy(
                normLeft = det.normLeft,
                normTop = det.normTop,
                normRight = det.normRight,
                normBottom = det.normBottom,
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

@Composable
fun TelaRevisaoCaptura(
    fotoUri: Uri,
    plantId: String,
    deteccoesIniciais: List<Detection>,
    bitmapWidthRef: Float,
    bitmapHeightRef: Float,
    detectorHelper: ObjectDetectorHelper? = null,
    labelsFiltro: List<String> = emptyList(),
    caixasAuditadasIniciais: List<CaixaAuditavel>? = null,
    modoSemFrutos: Boolean = false,
    onConfirmar: (totalObjetos: Int, caixas: List<CaixaAuditavel>) -> Unit,
    onRefazer: () -> Unit
) {
    val context = LocalContext.current
    val rotuloPadrao = remember(labelsFiltro) {
        labelsFiltro.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Objeto"
    }

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

                    val candidatasEstaticas = mutableListOf<DetEstilizada>()
                    res.deteccoes.forEach { det ->
                        val cat = det.categories.firstOrNull()
                        val label = (cat?.label ?: rotuloPadrao).lowercase(Locale.ROOT)
                        val labelValido = labelsFiltro.isEmpty() ||
                                labelsFiltro.any { f -> label.contains(f.lowercase(Locale.ROOT)) }
                        if (!labelValido) return@forEach

                        val r = det.boundingBox
                        candidatasEstaticas.add(
                            DetEstilizada(
                                normLeft = (r.left / bmpW).coerceIn(0f, 1f),
                                normTop = (r.top / bmpH).coerceIn(0f, 1f),
                                normRight = (r.right / bmpW).coerceIn(0f, 1f),
                                normBottom = (r.bottom / bmpH).coerceIn(0f, 1f),
                                confianca = cat?.score ?: 0.85f,
                                rotulo = cat?.label?.takeIf { it.isNotBlank() } ?: rotuloPadrao
                            )
                        )
                    }

                    withContext(Dispatchers.Main) {
                        val fundidas = fundirDeteccoesComSnapping(
                            caixasAtuais = caixas.toList(),
                            deteccoesEstaticas = candidatasEstaticas,
                            caixasExcluidas = caixasExcluidasManualmente.toList()
                        )
                        caixas.clear()
                        caixas.addAll(fundidas)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AsyncImage(
            model = fotoUri,
            contentDescription = "Foto da planta $plantId",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(fotoUri, caixas.size, modoSemFrutos) {
                        detectTapGestures { tapOffset ->
                            if (modoSemFrutos) return@detectTapGestures
                            val normTapX = (tapOffset.x - offsetX) / displayedW
                            val normTapY = (tapOffset.y - offsetY) / displayedH

                            if (normTapX in 0f..1f && normTapY in 0f..1f) {
                                val margemNormX = 24.dp.toPx() / displayedW
                                val margemNormY = 24.dp.toPx() / displayedH

                                val idxTocado = selecionarIndiceCaixaNoToque(
                                    caixas = caixas,
                                    normTapX = normTapX,
                                    normTapY = normTapY,
                                    margemNormX = margemNormX,
                                    margemNormY = margemNormY
                                )

                                if (idxTocado >= 0) {
                                    indiceSelecionado = idxTocado
                                } else if (indiceSelecionado >= 0) {
                                    indiceSelecionado = -1
                                } else {
                                    val halfW = 0.07f
                                    val halfH = 0.07f
                                    val nova = CaixaAuditavel(
                                        normLeft = (normTapX - halfW).coerceIn(0f, 1f - halfW * 2),
                                        normTop = (normTapY - halfH).coerceIn(0f, 1f - halfH * 2),
                                        normRight = (normTapX + halfW).coerceIn(halfW * 2, 1f),
                                        normBottom = (normTapY + halfH).coerceIn(halfH * 2, 1f),
                                        rotulo = rotuloPadrao,
                                        manual = true,
                                        confianca = 1.0f
                                    )
                                    caixas.add(nova)
                                    indiceSelecionado = caixas.lastIndex
                                }
                            }
                        }
                    }
            ) {
                caixas.forEachIndexed { idx, caixa ->
                    val bLeft = offsetX + caixa.normLeft * displayedW
                    val bTop = offsetY + caixa.normTop * displayedH
                    val bW = (caixa.normRight - caixa.normLeft) * displayedW
                    val bH = (caixa.normBottom - caixa.normTop) * displayedH
                    val selecionada = idx == indiceSelecionado
                    val cor = corParaCaixa(caixa.rotulo, caixa.manual, labelsFiltro)

                    drawRect(
                        color = cor,
                        topLeft = androidx.compose.ui.geometry.Offset(bLeft, bTop),
                        size = androidx.compose.ui.geometry.Size(bW, bH),
                        style = Stroke(width = if (selecionada) 5.dp.toPx() else 3.dp.toPx())
                    )

                    if (selecionada) {
                        drawRect(
                            color = Color.White,
                            topLeft = androidx.compose.ui.geometry.Offset(bLeft - 2.dp.toPx(), bTop - 2.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(bW + 4.dp.toPx(), bH + 4.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    val labelTxt = if (labelsFiltro.size > 1) {
                        "#${idx + 1} ${caixa.rotulo}"
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

        // Cabeçalho superior compacto
        Surface(
            modifier = Modifier
                .statusBarsPadding()
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
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
                        else "Toque no quadrado p/ ajustar/excluir ou no fundo p/ +",
                        color = Color(0xFFB0BEC5),
                        fontSize = 10.sp
                    )
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
                            Text(
                                "Objeto #${indiceSelecionado + 1}${if (caixaAtual.manual) " (Manual)" else ""}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = {
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

                        // Se o modelo carregado tiver múltiplas classes configuradas, exibe troca de rótulo dinâmica
                        if (labelsFiltro.size > 1) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                labelsFiltro.forEach { classeLabel ->
                                    val ativo = caixaAtual.rotulo.equals(classeLabel, ignoreCase = true)
                                    val corClasse = corParaCaixa(classeLabel, false, labelsFiltro)
                                    Button(
                                        onClick = {
                                            caixas[indiceSelecionado] = caixaAtual.copy(rotulo = classeLabel)
                                            indiceSelecionado = -1
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (ativo) corClasse else Color(0xFF2C2C2C)
                                        ),
                                        modifier = Modifier.height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            classeLabel,
                                            fontSize = 11.sp,
                                            color = if (ativo) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ChipResumo("Total", totalObjetos, Color(0xFF00E676))
                            ChipResumo("IA", totalIA, Color(0xFF69F0AE))
                            ChipResumo("Manuais", totalManuais, Color(0xFF00E5FF))
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRefazer,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("REFAZER", fontSize = 12.sp)
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
                                .weight(1.4f)
                                .height(42.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (modoSemFrutos) Color(0xFFEF6C00) else Color(0xFF2E7D32)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (modoSemFrutos) "CONFIRMAR SEM FRUTOS (0)" else "SALVAR CONTAGEM ($totalObjetos)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
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

internal fun iouNorm(c: CaixaAuditavel, l: Float, t: Float, r: Float, b: Float): Float {
    val interL = maxOf(c.normLeft, l)
    val interT = maxOf(c.normTop, t)
    val interR = minOf(c.normRight, r)
    val interB = minOf(c.normBottom, b)
    val w = maxOf(0f, interR - interL)
    val h = maxOf(0f, interB - interT)
    val inter = w * h
    val area1 = (c.normRight - c.normLeft) * (c.normBottom - c.normTop)
    val area2 = (r - l) * (b - t)
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
