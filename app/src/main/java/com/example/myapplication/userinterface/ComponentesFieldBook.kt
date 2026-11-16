package com.example.myapplication.userinterface

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
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

fun criarParDetectoresBreedAI(
    context: Context,
    modelConfig: ModelConfig
): Pair<ObjectDetectorHelper, ObjectDetectorHelper?> {
    if (!modelConfig.usarModeloSecundario) {
        return criarDetectorHelperBreedAI(context, modelConfig) to null
    }

    val usaDoencaEmbutidoComoPrincipal =
        modelConfig.modeloInternoPath.isNullOrBlank() &&
                modelConfig.modeloExternoUri == null &&
                (modelConfig.modeloSelecionado == null || modelConfig.modeloSelecionado.nomeArquivo == "detector_doenca.tflite")

    val primario = if (usaDoencaEmbutidoComoPrincipal) {
        ObjectDetectorHelper(
            context = context,
            detectorListener = null,
            modelSource = ObjectDetectorHelper.ModelSource.Asset("detector.tflite"),
            scoreThreshold = modelConfig.threshold,
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
        scoreThreshold = modelConfig.threshold,
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
    onCancelar: () -> Unit,
    onFotoCapturada: (Uri, List<Detection>, Float, Float) -> Unit
) {
    var resultadoAtual by remember { mutableStateOf<ObjectDetectorHelper.ResultadoDeteccao?>(null) }
    var ultimoResultadoValido by remember { mutableStateOf<ObjectDetectorHelper.ResultadoDeteccao?>(null) }
    var ultimoTimestampValidoMs by remember { mutableLongStateOf(0L) }

    Box(Modifier.fillMaxSize()) {
        CameraWithAI(detectorHelper, imageCapture) { res ->
            resultadoAtual = res
            if (res.deteccoes.isNotEmpty()) {
                ultimoResultadoValido = res
                ultimoTimestampValidoMs = System.currentTimeMillis()
            }
        }

        val resultadoAtivo = if ((resultadoAtual?.deteccoes?.size ?: 0) > 0) {
            resultadoAtual
        } else if (System.currentTimeMillis() - ultimoTimestampValidoMs < 650L) {
            ultimoResultadoValido
        } else {
            resultadoAtual
        }

        val deteccoesAtuais = resultadoAtivo?.deteccoes ?: emptyList()
        val frameWidth = resultadoAtivo?.larguraImagem ?: 1f
        val frameHeight = resultadoAtivo?.alturaImagem ?: 1f

        val listaFiltrada = if (modoSemFrutos) {
            emptyList()
        } else {
            val labelsModelo = detectorHelper.obterLabelsModelo()
            deteccoesAtuais.filter { det ->
                val label = det.categories.firstOrNull()?.label?.lowercase(Locale.ROOT) ?: ""
                val score = det.categories.firstOrNull()?.score ?: 0f
                score >= modelConfig.threshold &&
                        (modelConfig.labelsFiltro.isEmpty() ||
                                modelConfig.labelsFiltro.any { f -> label.contains(f.lowercase(Locale.ROOT)) } ||
                                labelsModelo.any { m -> label.equals(m.lowercase(Locale.ROOT), ignoreCase = true) } ||
                                label in setOf("objeto", "cacau", "fruto"))
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

        Surface(
            Modifier
                .statusBarsPadding()
                .align(Alignment.TopCenter)
                .padding(horizontal = 52.dp, vertical = 6.dp),
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
                    if (modoSemFrutos) "• Sem Frutos (0)" else "• IA: ${listaFiltrada.size}",
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

        ExtendedFloatingActionButton(
            onClick = {
                val snapshotCaixas = listaFiltrada.toList()
                val snapshotW = frameWidth
                val snapshotH = frameHeight
                tirarFoto(context, imageCapture, idParaArquivo) { uri ->
                    onFotoCapturada(uri, snapshotCaixas, snapshotW, snapshotH)
                }
            },
            modifier = Modifier
                .navigationBarsPadding()
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            containerColor = if (modoSemFrutos) Color(0xFFEF6C00) else VERDE_PRINCIPAL,
            contentColor = Color.White,
            text = {
                Text(
                    if (modoSemFrutos) "CAPTURAR PROVA (0 FRUTOS)" else "CAPTURAR (${listaFiltrada.size})",
                    fontWeight = FontWeight.Bold
                )
            },
            icon = { Icon(Icons.Default.PhotoCamera, null) }
        )
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
    onMotivoChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFDCE7DC))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Estado Fenotípico da Planta",
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
                            SituacaoPlanta.SEM_FRUTOS -> "Sem Frutos"
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
                            "Exige foto comprobatória • registra 0 frutos",
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
                        MOTIVOS_NAO_AVALIAVEL.forEach { motivo ->
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
    onAbrirConfigVariaveis: () -> Unit
) {
    if (variaveisAtivas.isEmpty()) {
        Card(
            onClick = onAbrirConfigVariaveis,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFDCE7DC))
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
                        "Variáveis Fenotípicas (Traits)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20)
                    )
                    Text(
                        "Toque para ativar notas de vigor, maturação ou medidas",
                        fontSize = 11.sp,
                        color = Color(0xFF5F6F61)
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFDCE7DC))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = VERDE_PRINCIPAL, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Variáveis Fenotípicas (${variaveisAtivas.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFF1B5E20)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (valoresAtuais.isNotEmpty()) {
                        TextButton(
                            onClick = { valoresAtuais.clear() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Limpar", fontSize = 11.sp, color = Color(0xFFC62828), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    TextButton(
                        onClick = onAbrirConfigVariaveis,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Editar Traits", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VERDE_PRINCIPAL)
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
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = VERDE_PRINCIPAL)
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("ADICIONAR VARIÁVEL")
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
        title = { Text("Variáveis Extras da Coleta", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
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
                colors = ButtonDefaults.buttonColors(containerColor = VERDE_PRINCIPAL)
            ) {
                Text("CONCLUÍDO")
            }
        }
    )
}
