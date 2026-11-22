package com.igor.breedai.userinterface

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.MOTIVOS_NAO_AVALIAVEL
import com.igor.breedai.data.ModelConfig
import com.igor.breedai.data.PresetRendimento
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.data.TipoVariavelExtra
import com.igor.breedai.data.VariavelExtra
import com.igor.breedai.data.calcularRendimentoColeta
import com.igor.breedai.data.formatarEstimativaCurta
import com.igor.breedai.userinterface.util.BoundingBoxOverlay
import com.igor.breedai.userinterface.util.CameraWithAI
import com.igor.breedai.userinterface.util.ObjectDetectorHelper
import com.igor.breedai.userinterface.util.tirarFoto
import kotlinx.coroutines.delay
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale

private val VERDE_PRINCIPAL = Color(0xFF2E7D32)

/**
 * Ponte leve para disparo da câmera pelo botão físico de volume sem acoplar Activity às telas Compose.
 */
object VolumeShutterBridge {
    @Volatile
    var onShutter: (() -> Boolean)? = null
}

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
        defaultLabel = com.igor.breedai.data.LABELS_DOENCA_CACAU.firstOrNull() ?: "objeto",
        customLabels = com.igor.breedai.data.LABELS_DOENCA_CACAU
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
    var temPermissaoCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcherPermissaoCam = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedida ->
        temPermissaoCamera = concedida
    }

    if (!temPermissaoCamera) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0E1810))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.PhotoCamera, null, tint = VERDE_PRINCIPAL, modifier = Modifier.size(44.dp))
                    Text(
                        "Permissão de câmera necessária",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color(0xFF1B5E20)
                    )
                    Text(
                        "Para detectar frutos e objetos ao vivo no campo, libere o acesso à câmera do aparelho.",
                        fontSize = 13.sp,
                        color = Color(0xFF4E6E52)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancelar,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text("Voltar") }
                        Button(
                            onClick = { launcherPermissaoCam.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.weight(1.3f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VERDE_PRINCIPAL)
                        ) { Text("Permitir câmera", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        return
    }

    // Mantém estado dentro de MutableState sem lê-lo neste Composable pai para evitar recompor PreviewView/CameraWithAI a cada frame
    val resultadoAtualState = remember { mutableStateOf<ObjectDetectorHelper.ResultadoDeteccao?>(null) }
    val ultimoResultadoValidoState = remember { mutableStateOf<ObjectDetectorHelper.ResultadoDeteccao?>(null) }
    val ultimoTimestampValidoState = remember { mutableLongStateOf(0L) }

    val haptics = LocalHapticFeedback.current
    var lanternaAtiva by remember { mutableStateOf(false) }
    var suportaLanterna by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomDisponivel by remember { mutableFloatStateOf(4f) }
    var capturandoFoto by remember { mutableStateOf(false) }
    var focoPonto by remember { mutableStateOf<Offset?>(null) }
    var thresholdAoVivo by remember(modelConfig.threshold) { mutableFloatStateOf(modelConfig.threshold) }
    var mostrarAjusteCerteza by remember(modoTesteRapido) { mutableStateOf(modoTesteRapido) }
    var erroCameraBanner by remember { mutableStateOf<String?>(null) }

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

    val aoDispararFoto: (List<Detection>, Float, Float) -> Unit = { listaFiltrada, frameWidth, frameHeight ->
        if (!capturandoFoto) {
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
    }

    Box(Modifier.fillMaxSize()) {
        CameraWithAI(
            helper = detectorHelper,
            capture = imageCapture,
            lanternaAtiva = lanternaAtiva,
            zoomRatio = zoomRatio,
            onSuportaLanterna = { suportaLanterna = it },
            onZoomMaxDisponivel = { maxZoomDisponivel = it },
            onZoomRatioChanged = { zoomRatio = it },
            onErroCamera = { erroCameraBanner = it },
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
            mostrarAjusteCerteza = mostrarAjusteCerteza,
            onToggleAjusteCerteza = { mostrarAjusteCerteza = !mostrarAjusteCerteza },
            suportaLanterna = suportaLanterna,
            lanternaAtiva = lanternaAtiva,
            onToggleLanterna = { lanternaAtiva = !lanternaAtiva },
            zoomRatio = zoomRatio,
            maxZoomDisponivel = maxZoomDisponivel,
            onZoomChange = { zoomRatio = it },
            capturandoFoto = capturandoFoto,
            focoPonto = focoPonto,
            erroCameraBanner = erroCameraBanner,
            thresholdAoVivo = thresholdAoVivo,
            onThresholdSliderChange = { novo ->
                thresholdAoVivo = novo
                detectorHelper.scoreThreshold = novo
                onThresholdChange?.invoke(novo)
            },
            filtrarDeteccoes = ::filtrarDeteccoes,
            onCancelar = onCancelar,
            onCapturarClick = aoDispararFoto
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
    mostrarAjusteCerteza: Boolean,
    onToggleAjusteCerteza: () -> Unit,
    suportaLanterna: Boolean,
    lanternaAtiva: Boolean,
    onToggleLanterna: () -> Unit,
    zoomRatio: Float,
    maxZoomDisponivel: Float,
    onZoomChange: (Float) -> Unit,
    capturandoFoto: Boolean,
    focoPonto: Offset?,
    erroCameraBanner: String?,
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

    val snapshotCapturaState = rememberUpdatedState(Triple(listaFiltrada, frameWidth, frameHeight))
    val capturandoState = rememberUpdatedState(capturandoFoto)
    val onCapturarState = rememberUpdatedState(onCapturarClick)
    DisposableEffect(modoTesteRapido) {
        if (!modoTesteRapido) {
            VolumeShutterBridge.onShutter = {
                if (!capturandoState.value) {
                    val (lista, w, h) = snapshotCapturaState.value
                    onCapturarState.value(lista, w, h)
                }
                true
            }
        }
        onDispose {
            VolumeShutterBridge.onShutter = null
        }
    }

    var semDeteccaoHaTempo by remember { mutableStateOf(false) }
    LaunchedEffect(listaFiltrada.size, modoSemFrutos, modoTesteRapido, erroCameraBanner) {
        if (modoSemFrutos || modoTesteRapido || erroCameraBanner != null || listaFiltrada.isNotEmpty()) {
            semDeteccaoHaTempo = false
        } else {
            delay(4000L)
            semDeteccaoHaTempo = true
        }
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
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
        if (!erroCameraBanner.isNullOrBlank()) {
            Surface(
                color = Color(0xFFE65100).copy(alpha = 0.88f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    erroCameraBanner,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        } else if (semDeteccaoHaTempo && !mostrarAjusteCerteza) {
            Surface(
                onClick = onToggleAjusteCerteza,
                color = Color.Black.copy(alpha = 0.80f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFFFFB74D))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Tune, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(15.dp))
                    Text(
                        "Nenhum item? Aproxime ou toque p/ ajustar certeza (${(thresholdAoVivo * 100).toInt()}%)",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (mostrarAjusteCerteza) {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${listaFiltrada.size} na tela",
                                color = Color(0xFF00E676),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!modoTesteRapido) {
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = onToggleAjusteCerteza, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.Close, "Ocultar slider", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
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

        // Atalho de Zoom + Ajuste rápido de certeza
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val opcoesZoom = if (maxZoomDisponivel >= 1.5f) listOf(1f to "1x", 2f to "2x") else listOf(1f to "1x")
            opcoesZoom.forEach { (zVal, zLabel) ->
                val ativo = kotlin.math.abs(zoomRatio - zVal) < 0.15f
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
            if (zoomRatio > 1.15f && kotlin.math.abs(zoomRatio - 2f) >= 0.15f) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = VERDE_PRINCIPAL,
                    border = BorderStroke(1.dp, Color(0xFF00E676))
                ) {
                    Text(
                        String.format(Locale.US, "%.1fx", zoomRatio),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            if (!modoTesteRapido && !modoSemFrutos) {
                Surface(
                    onClick = onToggleAjusteCerteza,
                    shape = RoundedCornerShape(18.dp),
                    color = if (mostrarAjusteCerteza) VERDE_PRINCIPAL else Color.Black.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Tune, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text(
                            "${(thresholdAoVivo * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                            if (modoSemFrutos) "Registrar sem produção (0)" else "Capturar (${listaFiltrada.size}) • Vol",
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

/**
 * Faixa compacta de progresso acumulado na coleta (frutos + rendimento aproximado em @/kg/sc) e indicador GPS.
 */
@Composable
fun FaixaAcumuladoRendimentoColeta(
    registros: List<RegistroColeta>,
    configRendimento: ConfigConversaoRendimento,
    templateCultura: TemplateCultura,
    gpsAtivo: Boolean,
    onEditarRendimento: () -> Unit
) {
    val rend = remember(registros.toList(), configRendimento) {
        calcularRendimentoColeta(registros, configRendimento)
    }
    val totalContado = remember(registros.toList()) {
        registros.filter { it.situacaoPlanta == SituacaoPlanta.NORMAL }.sumOf { it.totalObjetos }
    }
    val unidadeLbl = templateCultura.unidadePlural.lowercase(Locale.ROOT)

    Surface(
        onClick = onEditarRendimento,
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFDCE7DC)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (gpsAtivo) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (gpsAtivo) VERDE_PRINCIPAL else Color(0xFFEF6C00), CircleShape)
                    )
                    Text(
                        if (gpsAtivo) "GPS" else "Sem GPS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (gpsAtivo) Color(0xFF1B5E20) else Color(0xFFE65100)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                val textoLinha = if (configRendimento.ativo) {
                    val sym = configRendimento.unidadeSimbolo.trim().ifBlank { "unid" }
                    val unidStr = formatarEstimativaCurta(rend.unidadesAcumuladas, sym)
                    val haStr = if (registros.isNotEmpty() && configRendimento.plantasPorHa > 0) {
                        " (~${formatarEstimativaCurta(rend.realUnidadesHa, "$sym/ha")})"
                    } else ""
                    "${registros.size} pl • $totalContado $unidadeLbl • ≈ $unidStr$haStr"
                } else {
                    "${registros.size} planta(s) salva(s) • $totalContado $unidadeLbl"
                }
                Text(
                    textoLinha,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1B5E20),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Default.Tune, null, tint = VERDE_PRINCIPAL, modifier = Modifier.size(14.dp))
                Text(
                    if (configRendimento.ativo) (configRendimento.unidadeSimbolo.ifBlank { "Rend." }) else "Rend.",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = VERDE_PRINCIPAL
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogConfiguracaoRendimento(
    configAtual: ConfigConversaoRendimento,
    onDismiss: () -> Unit,
    onSalvar: (ConfigConversaoRendimento) -> Unit
) {
    var ativo by remember(configAtual) { mutableStateOf(configAtual.ativo) }
    var presetId by remember(configAtual) { mutableStateOf(configAtual.presetId) }
    var simboloTxt by remember(configAtual) { mutableStateOf(configAtual.unidadeSimbolo) }
    var itensPorKgTxt by remember(configAtual) {
        mutableStateOf(String.format(Locale.US, "%.1f", configAtual.itensPorKg).removeSuffix(".0"))
    }
    var gPorItemTxt by remember(configAtual) {
        val g = 1000.0 / configAtual.itensPorKg.coerceAtLeast(0.01)
        mutableStateOf(String.format(Locale.US, "%.0f", g))
    }
    var kgPorUnidTxt by remember(configAtual) {
        mutableStateOf(String.format(Locale.US, "%.1f", configAtual.kgPorUnidade).removeSuffix(".0"))
    }
    var plantasHaTxt by remember(configAtual) { mutableStateOf(configAtual.plantasPorHa.toString()) }
    var descontarDoencas by remember(configAtual) { mutableStateOf(configAtual.descontarDoencas) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversão de Rendimento (@ / kg)", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Estima peso e unidades comerciais a partir da contagem de frutos. A contagem exata sempre continua visível.",
                    fontSize = 12.sp,
                    color = Color(0xFF4E6E52)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PresetRendimento.entries.filter { it != PresetRendimento.PERSONALIZADO }.forEach { preset ->
                        val sel = (preset == PresetRendimento.DESATIVADO && !ativo) ||
                            (ativo && presetId == preset.id)
                        FilterChip(
                            selected = sel,
                            onClick = {
                                if (preset == PresetRendimento.DESATIVADO) {
                                    ativo = false
                                    presetId = preset.id
                                } else {
                                    ativo = true
                                    presetId = preset.id
                                    simboloTxt = preset.unidadeSimbolo
                                    itensPorKgTxt = String.format(Locale.US, "%.1f", preset.itensPorKg).removeSuffix(".0")
                                    gPorItemTxt = String.format(Locale.US, "%.0f", 1000.0 / preset.itensPorKg)
                                    kgPorUnidTxt = String.format(Locale.US, "%.1f", preset.kgPorUnidade).removeSuffix(".0")
                                    plantasHaTxt = preset.plantasPorHa.toString()
                                }
                            },
                            label = { Text(preset.nomeExibicao, fontSize = 11.sp) }
                        )
                    }
                }

                if (ativo) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = itensPorKgTxt,
                            onValueChange = { txt ->
                                itensPorKgTxt = txt
                                presetId = PresetRendimento.PERSONALIZADO.id
                                val v = txt.replace(',', '.').toDoubleOrNull()
                                if (v != null && v > 0.0) {
                                    gPorItemTxt = String.format(Locale.US, "%.0f", 1000.0 / v)
                                }
                            },
                            label = { Text("Itens / kg (IF)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = gPorItemTxt,
                            onValueChange = { txt ->
                                gPorItemTxt = txt
                                presetId = PresetRendimento.PERSONALIZADO.id
                                val g = txt.replace(',', '.').toDoubleOrNull()
                                if (g != null && g > 0.0) {
                                    itensPorKgTxt = String.format(Locale.US, "%.1f", 1000.0 / g).removeSuffix(".0")
                                }
                            },
                            label = { Text("Peso/item (g)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = simboloTxt,
                            onValueChange = {
                                simboloTxt = it.take(8)
                                presetId = PresetRendimento.PERSONALIZADO.id
                            },
                            label = { Text("Unidade (@, sc)") },
                            singleLine = true,
                            modifier = Modifier.weight(0.9f)
                        )
                        OutlinedTextField(
                            value = kgPorUnidTxt,
                            onValueChange = {
                                kgPorUnidTxt = it
                                presetId = PresetRendimento.PERSONALIZADO.id
                            },
                            label = { Text("kg por unid.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1.1f)
                        )
                    }

                    OutlinedTextField(
                        value = plantasHaTxt,
                        onValueChange = {
                            plantasHaTxt = it.filter { ch -> ch.isDigit() }
                        },
                        label = { Text("Densidade de plantio (plantas/ha)") },
                        placeholder = { Text("Ex: 1111 para 3x3m (0 para ocultar @/ha)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Descontar frutos doentes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B5E20))
                            Text("Somente sadios contam para o @ comercial", fontSize = 11.sp, color = Color(0xFF5F6F61))
                        }
                        Switch(checked = descontarDoencas, onCheckedChange = { descontarDoencas = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val itensKg = (itensPorKgTxt.replace(',', '.').toDoubleOrNull() ?: 22.0).coerceIn(0.1, 5000.0)
                    val kgUnid = (kgPorUnidTxt.replace(',', '.').toDoubleOrNull() ?: 15.0).coerceIn(0.1, 10000.0)
                    val plHa = (plantasHaTxt.toIntOrNull() ?: 0).coerceIn(0, 500000)
                    onSalvar(
                        ConfigConversaoRendimento(
                            ativo = ativo,
                            presetId = presetId,
                            unidadeSimbolo = simboloTxt.trim().ifBlank { if (kgUnid == 1.0) "kg" else "@" },
                            itensPorKg = itensKg,
                            kgPorUnidade = kgUnid,
                            plantasPorHa = plHa,
                            descontarDoencas = descontarDoencas
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = VERDE_PRINCIPAL)
            ) {
                Text("Salvar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
