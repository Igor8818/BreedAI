package com.example.myapplication.userinterface

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.MOTIVOS_NAO_AVALIAVEL
import com.example.myapplication.data.ModelConfig
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.TipoVariavelExtra
import com.example.myapplication.data.VariavelExtra
import com.example.myapplication.userinterface.util.BoundingBoxOverlay
import com.example.myapplication.userinterface.util.CameraWithAI
import com.example.myapplication.userinterface.util.ObjectDetectorHelper
import com.example.myapplication.userinterface.util.tirarFoto
import kotlinx.coroutines.delay
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale

private val VERDE_PRINCIPAL = Color(0xFF2E7D32)

fun criarDetectorHelperBreedAI(
    context: Context,
    modelConfig: ModelConfig
): ObjectDetectorHelper {
    val source = when {
        !modelConfig.modeloInternoPath.isNullOrBlank() ->
            ObjectDetectorHelper.ModelSource.InternalFile(
                modelConfig.modeloInternoPath,
                modelConfig.modeloExternoNome
            )
        modelConfig.modeloExternoUri != null ->
            ObjectDetectorHelper.ModelSource.External(
                modelConfig.modeloExternoUri,
                modelConfig.modeloExternoNome
            )
        modelConfig.modeloSelecionado != null ->
            ObjectDetectorHelper.ModelSource.Asset(modelConfig.modeloSelecionado.nomeArquivo)
        else -> ObjectDetectorHelper.ModelSource.Asset("detector.tflite")
    }
    return ObjectDetectorHelper(
        context = context,
        detectorListener = null,
        modelSource = source,
        scoreThreshold = modelConfig.threshold,
        maxResults = modelConfig.maxResultados,
        defaultLabel = modelConfig.labelsFiltro.firstOrNull() ?: "objeto",
        customLabels = modelConfig.labelsFiltro
    )
}

internal fun deveUsarParDetectoresSecundario(modelConfig: ModelConfig): Boolean =
    modelConfig.usarModeloSecundario

internal fun rotuloPassaFiltroModelo(
    labelBruto: String,
    labelsFiltro: List<String>,
    labelsModelo: List<String>
): Boolean {
    fun norm(s: String): String {
        val nfd = java.text.Normalizer.normalize(
            s.trim().lowercase(Locale.ROOT),
            java.text.Normalizer.Form.NFD
        )
        return nfd.replace(Regex("\\p{M}+"), "")
    }

    val normRaw = norm(labelBruto)
    val normFriendly = norm(rotuloAmigavel(labelBruto))
    if (labelsFiltro.isEmpty()) return true
    val filtrosNorm = labelsFiltro.map { norm(it) }.filter { it.isNotBlank() }
    if (filtrosNorm.isEmpty()) return true

    val bateDireto = filtrosNorm.any { f ->
        f == normRaw || f == normFriendly ||
            normRaw.contains(f) || normFriendly.contains(f) ||
            (f.length >= 3 && (f.contains(normRaw) || f.contains(normFriendly)))
    }
    if (bateDireto) return true

    if (normRaw in setOf("podridaoparda", "moniliase", "sadio", "vassourabruxa") &&
        filtrosNorm.all { it in setOf("cacau", "fruto", "objeto") }
    ) {
        return true
    }

    val filtroCasaComModelo = labelsModelo.isNotEmpty() &&
        filtrosNorm.any { f ->
            labelsModelo.any { m ->
                val nm = norm(m)
                val nf = norm(rotuloAmigavel(m))
                nm == f || nf == f || nm.contains(f) || nf.contains(f) || f.contains(nm) || f.contains(nf)
            }
        }
    return if (filtroCasaComModelo) {
        normRaw == "objeto"
    } else {
        labelsModelo.any { norm(it) == normRaw } || normRaw in setOf("objeto", "cacau", "fruto")
    }
}

fun criarParDetectoresBreedAI(
    context: Context,
    modelConfig: ModelConfig
): Pair<ObjectDetectorHelper, ObjectDetectorHelper?> {
    if (!deveUsarParDetectoresSecundario(modelConfig)) {
        return criarDetectorHelperBreedAI(context, modelConfig) to null
    }

    val usaDoencaComoPrincipal =
        modelConfig.modeloInternoPath.isNullOrBlank() &&
                modelConfig.modeloExternoUri == null &&
                (modelConfig.modeloSelecionado == null ||
                        modelConfig.modeloSelecionado.nomeArquivo == "detector_doenca.tflite" ||
                        modelConfig.modeloSelecionado.nomeArquivo == "detector.tflite")

    val primario = if (usaDoencaComoPrincipal) {
        ObjectDetectorHelper(
            context = context,
            detectorListener = null,
            modelSource = ObjectDetectorHelper.ModelSource.Asset("detector.tflite"),
            scoreThreshold = minOf(modelConfig.threshold, 0.25f),
            maxResults = modelConfig.maxResultados,
            defaultLabel = "objeto",
            customLabels = listOf("cacau", "fruto")
        )
    } else {
        criarDetectorHelperBreedAI(context, modelConfig)
    }

    val secundario = ObjectDetectorHelper(
        context = context,
        detectorListener = null,
        modelSource = ObjectDetectorHelper.ModelSource.Asset(modelConfig.modeloSecundarioArquivo),
        scoreThreshold = minOf(modelConfig.threshold, 0.22f),
        maxResults = modelConfig.maxResultados,
        defaultLabel = com.example.myapplication.data.LABELS_DOENCA_CACAU.firstOrNull() ?: "objeto",
        customLabels = com.example.myapplication.data.LABELS_DOENCA_CACAU
    )

    return primario to secundario
}

@Composable
fun CameraFullscreenBreedAI(
    context: Context,
    detectorHelper: ObjectDetectorHelper,
    imageCapture: ImageCapture,
    modelConfig: ModelConfig,
    tituloHud: String,
    idParaArquivo: String,
    modoSemFrutos: Boolean,
    modoTesteRapido: Boolean = false,
    onThresholdChange: ((Float) -> Unit)? = null,
    onCancelar: () -> Unit,
    onFotoCapturada: (Uri, List<Detection>, Float, Float) -> Unit
) {
    // Mantém estado dentro de MutableState sem lê-lo neste Composable pai para evitar recompor PreviewView/CameraWithAI a cada frame
    val resultadoAtualState = remember { mutableStateOf<ObjectDetectorHelper.ResultadoDeteccao?>(null) }
    val ultimoResultadoValidoState = remember { mutableStateOf<ObjectDetectorHelper.ResultadoDeteccao?>(null) }
    val ultimoTimestampValidoState = remember { mutableLongStateOf(0L) }

    val haptics = LocalHapticFeedback.current
    var lanternaAtiva by remember { mutableStateOf(false) }
    var suportaLanterna by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var capturandoFoto by remember { mutableStateOf(false) }
    var focoPonto by remember { mutableStateOf<Offset?>(null) }
    var thresholdAoVivo by remember(modelConfig.threshold) { mutableFloatStateOf(modelConfig.threshold) }

    LaunchedEffect(focoPonto) {
        if (focoPonto != null) {
            delay(950L)
            focoPonto = null
        }
    }

    val labelsModelo = remember(
        detectorHelper,
        modelConfig.modeloSelecionado,
        modelConfig.modeloInternoPath,
        modelConfig.labelsFiltro
    ) {
        detectorHelper.obterLabelsModelo().filter { it != "???" && it.isNotBlank() }
    }

    fun filtrarDeteccoes(deteccoes: List<Detection>): List<Detection> {
        if (modoSemFrutos) return emptyList()
        return deteccoes.filter { det ->
            val label = det.categories.firstOrNull()?.label ?: ""
            val score = det.categories.firstOrNull()?.score ?: 0f
            score >= thresholdAoVivo && rotuloPassaFiltroModelo(label, modelConfig.labelsFiltro, labelsModelo)
        }
    }

    Box(Modifier.fillMaxSize()) {
        CameraWithAI(
            helper = detectorHelper,
            capture = imageCapture,
            lanternaAtiva = lanternaAtiva,
            zoomRatio = zoomRatio,
            onSuportaLanterna = { suportaLanterna = it },
            onTapFocus = { x, y -> focoPonto = Offset(x, y) }
        ) { res ->
            resultadoAtualState.value = res
            if (res.deteccoes.isNotEmpty()) {
                ultimoResultadoValidoState.value = res
                ultimoTimestampValidoState.longValue = System.currentTimeMillis()
            }
        }

        CameraLiveOverlayAndControls(
            resultadoAtualState = resultadoAtualState,
            ultimoResultadoValidoState = ultimoResultadoValidoState,
            ultimoTimestampValidoState = ultimoTimestampValidoState,
            tituloHud = tituloHud,
            modoSemFrutos = modoSemFrutos,
            modoTesteRapido = modoTesteRapido,
            suportaLanterna = suportaLanterna,
            lanternaAtiva = lanternaAtiva,
            onToggleLanterna = { lanternaAtiva = !lanternaAtiva },
            zoomRatio = zoomRatio,
            onZoomChange = { zoomRatio = it },
            capturandoFoto = capturandoFoto,
            focoPonto = focoPonto,
            thresholdAoVivo = thresholdAoVivo,
            onThresholdSliderChange = { novo ->
                thresholdAoVivo = novo
                detectorHelper.scoreThreshold = novo
                onThresholdChange?.invoke(novo)
            },
            filtrarDeteccoes = ::filtrarDeteccoes,
            onCancelar = onCancelar,
            onCapturarClick = { listaFiltrada, frameWidth, frameHeight ->
                if (capturandoFoto) return@CameraLiveOverlayAndControls
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                capturandoFoto = true
                val snapshotCaixas = listaFiltrada.toList()
                tirarFoto(
                    context = context,
                    capture = imageCapture,
                    id = idParaArquivo,
                    onErro = { msg ->
                        capturandoFoto = false
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    onSucesso = { uri ->
                        capturandoFoto = false
                        onFotoCapturada(uri, snapshotCaixas, frameWidth, frameHeight)
                    }
                )
            }
        )
    }
}

@Composable
private fun BoxScope.CameraLiveOverlayAndControls(
    resultadoAtualState: State<ObjectDetectorHelper.ResultadoDeteccao?>,
    ultimoResultadoValidoState: State<ObjectDetectorHelper.ResultadoDeteccao?>,
    ultimoTimestampValidoState: State<Long>,
    tituloHud: String,
    modoSemFrutos: Boolean,
    modoTesteRapido: Boolean,
    suportaLanterna: Boolean,
    lanternaAtiva: Boolean,
    onToggleLanterna: () -> Unit,
    zoomRatio: Float,
    onZoomChange: (Float) -> Unit,
    capturandoFoto: Boolean,
    focoPonto: Offset?,
    thresholdAoVivo: Float,
    onThresholdSliderChange: (Float) -> Unit,
    filtrarDeteccoes: (List<Detection>) -> List<Detection>,
    onCancelar: () -> Unit,
    onCapturarClick: (List<Detection>, Float, Float) -> Unit
) {
    val resultadoAtual = resultadoAtualState.value
    val resultadoAtivo = if ((resultadoAtual?.deteccoes?.size ?: 0) > 0) {
        resultadoAtual
    } else if (System.currentTimeMillis() - ultimoTimestampValidoState.value < 650L) {
        ultimoResultadoValidoState.value
    } else {
        resultadoAtual
    }

    val deteccoesAtuais = resultadoAtivo?.deteccoes ?: emptyList()
    val frameWidth = resultadoAtivo?.larguraImagem ?: 1f
    val frameHeight = resultadoAtivo?.alturaImagem ?: 1f
    val listaFiltrada = remember(resultadoAtivo, modoSemFrutos, thresholdAoVivo, deteccoesAtuais) {
        filtrarDeteccoes(deteccoesAtuais)
    }

    if (!modoSemFrutos) {
        BoundingBoxOverlay(
            detections = listaFiltrada,
            bitmapWidth = frameWidth,
            bitmapHeight = frameHeight,
            modifier = Modifier.fillMaxSize()
        )
    }

    if (focoPonto != null) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0xFF00E676),
                radius = 34.dp.toPx(),
                center = focoPonto,
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                radius = 4.dp.toPx(),
                center = focoPonto
            )
        }
    }

    if (suportaLanterna) {
        IconButton(
            onClick = onToggleLanterna,
            modifier = Modifier
                .statusBarsPadding()
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 6.dp)
                .background(
                    if (lanternaAtiva) Color(0xFFFFC107).copy(alpha = 0.90f) else Color.Black.copy(alpha = 0.65f),
                    CircleShape
                )
        ) {
            Icon(
                if (lanternaAtiva) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = if (lanternaAtiva) "Desligar lanterna" else "Ligar lanterna",
                tint = if (lanternaAtiva) Color.Black else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }

    Surface(
        Modifier
            .statusBarsPadding()
            .align(Alignment.TopCenter)
            .padding(horizontal = 56.dp, vertical = 6.dp),
        color = Color.Black.copy(alpha = 0.78f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                tituloHud,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (modoSemFrutos) "• Sem Prod. (0)" else "• IA: ${listaFiltrada.size}",
                color = if (modoSemFrutos) Color(0xFFFFB74D) else Color(0xFF00E676),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }

    IconButton(
        onClick = onCancelar,
        modifier = Modifier
            .statusBarsPadding()
            .align(Alignment.TopEnd)
            .padding(end = 10.dp, top = 6.dp)
    ) {
        Icon(Icons.Default.Cancel, "Fechar câmera", tint = Color.White, modifier = Modifier.size(30.dp))
    }

    Column(
        modifier = Modifier
            .navigationBarsPadding()
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (modoTesteRapido) {
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Certeza mínima ao vivo: ${(thresholdAoVivo * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${listaFiltrada.size} na tela",
                            color = Color(0xFF00E676),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = (thresholdAoVivo * 100f).coerceIn(10f, 95f),
                        onValueChange = { pct -> onThresholdSliderChange((pct.toInt() / 100f).coerceIn(0.10f, 0.95f)) },
                        valueRange = 10f..95f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E676),
                            activeTrackColor = Color(0xFF00E676)
                        )
                    )
                }
            }
        }

        // Atalho de Zoom 1x / 2x
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(1f to "1x", 2f to "2x").forEach { (zVal, zLabel) ->
                val ativo = kotlin.math.abs(zoomRatio - zVal) < 0.1f
                Surface(
                    onClick = { onZoomChange(zVal) },
                    shape = RoundedCornerShape(18.dp),
                    color = if (ativo) VERDE_PRINCIPAL else Color.Black.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, if (ativo) Color(0xFF00E676) else Color.White.copy(alpha = 0.45f))
                ) {
                    Text(
                        zLabel,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
            }
        }

        if (modoTesteRapido) {
            Button(
                onClick = onCancelar,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VERDE_PRINCIPAL)
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Concluir teste (${listaFiltrada.size} detectados)", fontWeight = FontWeight.Bold)
            }
        } else {
            ExtendedFloatingActionButton(
                onClick = { onCapturarClick(listaFiltrada, frameWidth, frameHeight) },
                containerColor = if (modoSemFrutos) Color(0xFFEF6C00) else VERDE_PRINCIPAL,
                contentColor = Color.White,
                text = {
                    if (capturandoFoto) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Salvando foto...", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            if (modoSemFrutos) "Registrar sem produção (0)" else "Capturar (${listaFiltrada.size})",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                icon = { if (!capturandoFoto) Icon(Icons.Default.PhotoCamera, null) }
            )
        }
    }
}

/**
 * Bloco padronizado de Situação da Planta com exatamente 3 opções:
 * Normal (foto obrigatória + IA), Sem Frutos (foto de comprovação = 0) e Não avaliável (dispensa foto).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeletorSituacaoPlantaCard(
    situacao: SituacaoPlanta,
    onSituacaoChange: (SituacaoPlanta) -> Unit,
    motivoNaoAvaliavel: String,
    onMotivoChange: (String) -> Unit,
    rotuloSemUnidades: String = "Sem Frutos",
    motivosDisponiveis: List<String> = MOTIVOS_NAO_AVALIAVEL
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Condição da planta",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color(0xFF1B5E20)
            )

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFEEF3EE),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SituacaoPlanta.entries.forEach { opcao ->
                        val selecionada = situacao == opcao
                        val corAtiva = when (opcao) {
                            SituacaoPlanta.NORMAL -> VERDE_PRINCIPAL
                            SituacaoPlanta.SEM_FRUTOS -> Color(0xFFEF6C00)
                            SituacaoPlanta.NAO_AVALIAVEL -> Color(0xFFC62828)
                        }
                        val rotuloExibido = when (opcao) {
                            SituacaoPlanta.NORMAL -> "Normal"
                            SituacaoPlanta.SEM_FRUTOS -> rotuloSemUnidades
                            SituacaoPlanta.NAO_AVALIAVEL -> "Não Avaliável"
                        }
                        Surface(
                            onClick = { onSituacaoChange(opcao) },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selecionada) corAtiva else Color.Transparent
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Text(
                                    rotuloExibido,
                                    fontSize = 12.sp,
                                    fontWeight = if (selecionada) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selecionada) Color.White else Color(0xFF3E4E40),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            when (situacao) {
                SituacaoPlanta.NORMAL -> Unit
                SituacaoPlanta.SEM_FRUTOS -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFF3E0),
                        border = BorderStroke(1.dp, Color(0xFFFFCC80)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Exige foto comprobatória • registra 0",
                            fontSize = 11.sp,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                SituacaoPlanta.NAO_AVALIAVEL -> {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        motivosDisponiveis.forEach { motivo ->
                            FilterChip(
                                selected = motivoNaoAvaliavel == motivo,
                                onClick = { onMotivoChange(motivo) },
                                label = { Text(motivo, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFFFCDD2),
                                    selectedLabelColor = Color(0xFFB71C1C)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Seção de Variáveis Fenotípicas (Traits) inspirada no Field Book.
 * Recolhida em cartão compacto clicável quando não há variáveis ativas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlocoVariaveisExtrasCard(
    variaveisAtivas: List<VariavelExtra>,
    valoresAtuais: MutableMap<String, String>,
    onAbrirConfigVariaveis: () -> Unit,
    todasVariaveis: List<VariavelExtra> = variaveisAtivas,
    onToggleVariavel: ((VariavelExtra, Boolean) -> Unit)? = null,
    onDesativarTodas: (() -> Unit)? = null
) {
    var mostrarAjudaModoRapido by remember { mutableStateOf(false) }

    if (mostrarAjudaModoRapido) {
        AlertDialog(
            onDismissRequest = { mostrarAjudaModoRapido = false },
            title = { Text("Anotações da coleta", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text(
                    "Modo rápido salva e exporta somente a contagem feita pela IA na foto. Para anotar outros campos (ex: Vírus, Nota, Diâmetro), toque em qualquer opção abaixo ou em + Criar.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { mostrarAjudaModoRapido = false },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_PRINCIPAL)
                ) { Text("Entendi") }
            }
        )
    }

    if (variaveisAtivas.isEmpty() && onToggleVariavel == null) {
        ElevatedCard(
            onClick = onAbrirConfigVariaveis,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFE8F5E9), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Tune, null, tint = VERDE_PRINCIPAL, modifier = Modifier.size(18.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Anotações extras",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20)
                    )
                    Text(
                        "Opcional • Ative campos manuais ou crie novos",
                        fontSize = 11.sp,
                        color = Color(0xFF5F6F61),
                        maxLines = 1
                    )
                }
                Text(
                    "Configurar",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = VERDE_PRINCIPAL
                )
            }
        }
        return
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Tune, null, tint = VERDE_PRINCIPAL, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (variaveisAtivas.isEmpty()) "Anotações extras"
                        else "Anotações extras (${variaveisAtivas.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFF1B5E20),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { mostrarAjudaModoRapido = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Ajuda sobre anotações extras",
                            tint = Color(0xFF5F6F61),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (valoresAtuais.isNotEmpty()) {
                        TextButton(
                            onClick = { valoresAtuais.clear() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Limpar", fontSize = 11.sp, color = Color(0xFF546E7A), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    TextButton(
                        onClick = onAbrirConfigVariaveis,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("+ Gerenciar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VERDE_PRINCIPAL)
                    }
                }
            }

            if (onToggleVariavel != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (onDesativarTodas != null) {
                        FilterChip(
                            selected = variaveisAtivas.isEmpty(),
                            onClick = onDesativarTodas,
                            label = {
                                Text(
                                    "Somente contagem IA",
                                    fontSize = 11.sp,
                                    fontWeight = if (variaveisAtivas.isEmpty()) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        )
                    }
                    todasVariaveis.forEach { v ->
                        FilterChip(
                            selected = v.ativa,
                            onClick = { onToggleVariavel(v, !v.ativa) },
                            label = { Text(v.nome, fontSize = 11.sp) }
                        )
                    }
                }
            }

            variaveisAtivas.forEach { variavel ->
                    val valorAtual = valoresAtuais[variavel.nome] ?: ""
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(variavel.nome, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF2E7D32))
                        when (variavel.tipo) {
                            TipoVariavelExtra.LISTA_OPCOES -> {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    variavel.opcoes.forEach { opcao ->
                                        val sel = valorAtual == opcao
                                        FilterChip(
                                            selected = sel,
                                            onClick = {
                                                if (sel) valoresAtuais.remove(variavel.nome)
                                                else valoresAtuais[variavel.nome] = opcao
                                            },
                                            label = { Text(opcao, fontSize = 12.sp) }
                                        )
                                    }
                                }
                            }
                            TipoVariavelExtra.NUMERICO -> {
                                OutlinedTextField(
                                    value = valorAtual,
                                    onValueChange = { valoresAtuais[variavel.nome] = it },
                                    placeholder = { Text("Valor numérico") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            TipoVariavelExtra.TEXTO -> {
                                OutlinedTextField(
                                    value = valorAtual,
                                    onValueChange = { valoresAtuais[variavel.nome] = it },
                                    placeholder = { Text("Anotação / valor") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
fun ConteudoGerenciarVariaveisExtras(
    variaveis: List<VariavelExtra>,
    onToggleAtiva: (VariavelExtra, Boolean) -> Unit,
    onAdicionarVariavel: (nome: String, tipo: TipoVariavelExtra, opcoes: List<String>) -> Unit,
    onRemoverVariavel: (VariavelExtra) -> Unit
) {
    var novoNome by remember { mutableStateOf("") }
    var novoTipo by remember { mutableStateOf(TipoVariavelExtra.LISTA_OPCOES) }
    var novasOpcoesTxt by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Ative ou crie variáveis extras personalizadas para esta coleta (estilo Field Book):",
            fontSize = 12.sp,
            color = Color.Gray
        )

        variaveis.forEach { v ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (v.ativa) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(v.nome, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        val detalhe = if (v.tipo == TipoVariavelExtra.LISTA_OPCOES && v.opcoes.isNotEmpty()) {
                            "${v.tipo.rotulo}: ${v.opcoes.joinToString(", ")}"
                        } else {
                            v.tipo.rotulo
                        }
                        Text(detalhe, fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = v.ativa,
                        onCheckedChange = { onToggleAtiva(v, it) }
                    )
                    IconButton(
                        onClick = { onRemoverVariavel(v) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, "Excluir variável", tint = Color(0xFFC62828), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Criar Nova Variável", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1B5E20))

        OutlinedTextField(
            value = novoNome,
            onValueChange = { novoNome = it },
            label = { Text("Nome da Variável") },
            placeholder = { Text("Ex: Altura (m), Severidade, Vigor") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TipoVariavelExtra.entries.forEach { t ->
                FilterChip(
                    selected = novoTipo == t,
                    onClick = { novoTipo = t },
                    label = { Text(t.rotulo, fontSize = 11.sp) }
                )
            }
        }

        if (novoTipo == TipoVariavelExtra.LISTA_OPCOES) {
            OutlinedTextField(
                value = novasOpcoesTxt,
                onValueChange = { novasOpcoesTxt = it },
                label = { Text("Opções (separadas por vírgula)") },
                placeholder = { Text("Ex: Baixo, Médio, Alto") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Button(
            onClick = {
                val nomeLimpo = novoNome.trim()
                if (nomeLimpo.isNotBlank()) {
                    val ops = if (novoTipo == TipoVariavelExtra.LISTA_OPCOES) {
                        novasOpcoesTxt.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    } else emptyList()
                    onAdicionarVariavel(nomeLimpo, novoTipo, ops)
                    novoNome = ""
                    novasOpcoesTxt = ""
                }
            },
            enabled = novoNome.isNotBlank() &&
                    (novoTipo != TipoVariavelExtra.LISTA_OPCOES || novasOpcoesTxt.isNotBlank()),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = VERDE_PRINCIPAL)
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Adicionar variável", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
fun DialogGerenciarVariaveisExtras(
    variaveis: List<VariavelExtra>,
    onToggleAtiva: (VariavelExtra, Boolean) -> Unit,
    onAdicionarVariavel: (nome: String, tipo: TipoVariavelExtra, opcoes: List<String>) -> Unit,
    onRemoverVariavel: (VariavelExtra) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anotações extras da coleta", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                ConteudoGerenciarVariaveisExtras(
                    variaveis = variaveis,
                    onToggleAtiva = onToggleAtiva,
                    onAdicionarVariavel = onAdicionarVariavel,
                    onRemoverVariavel = onRemoverVariavel
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VERDE_PRINCIPAL)
            ) {
                Text("Concluído", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
