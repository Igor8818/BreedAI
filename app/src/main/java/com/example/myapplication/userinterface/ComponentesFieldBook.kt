package com.example.myapplication.userinterface

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
        else -> ObjectDetectorHelper.ModelSource.Asset("detector_doenca.tflite")
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
            deteccoesAtuais.filter { det ->
                val label = det.categories.firstOrNull()?.label?.lowercase(Locale.ROOT) ?: ""
                val score = det.categories.firstOrNull()?.score ?: 0f
                score >= modelConfig.threshold &&
                        (modelConfig.labelsFiltro.isEmpty() ||
                                modelConfig.labelsFiltro.any { f -> label.contains(f.lowercase(Locale.ROOT)) })
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFFAFAFA),
        border = BorderStroke(1.dp, Color(0xFFE0E0E0))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Situação da Planta",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFF1B5E20)
                )
            }

            // Segmented control em uma única linha compacta
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFEEEEEE),
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
                                    opcao.rotulo,
                                    fontSize = 12.sp,
                                    fontWeight = if (selecionada) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selecionada) Color.White else Color.DarkGray,
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
                    Text(
                        "Foto comprobatória obrigatória • salva contagem 0.",
                        fontSize = 11.sp,
                        color = Color(0xFFE65100),
                        fontWeight = FontWeight.Medium
                    )
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
 * Seção de Variáveis Extras inspirada no Field Book (Cor do Fruto, Nota de Vírus, Diâmetro, etc.).
 * Recolhida em botão compacto de 1 linha quando não há variáveis ativas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlocoVariaveisExtrasCard(
    variaveisAtivas: List<VariavelExtra>,
    valoresAtuais: MutableMap<String, String>,
    onAbrirConfigVariaveis: () -> Unit
) {
    if (variaveisAtivas.isEmpty()) {
        OutlinedButton(
            onClick = onAbrirConfigVariaveis,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2E7D32)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.AddCircleOutline, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("+ Configurar Variáveis Extras (Opcional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F9F5)),
        border = BorderStroke(1.dp, Color(0xFFC8E6C9))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ListAlt, null, tint = VERDE_PRINCIPAL, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Variáveis (${variaveisAtivas.size})",
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
                        Icon(Icons.Default.AddCircleOutline, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Configurar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
fun DialogGerenciarVariaveisExtras(
    variaveis: List<VariavelExtra>,
    onToggleAtiva: (VariavelExtra, Boolean) -> Unit,
    onAdicionarVariavel: (nome: String, tipo: TipoVariavelExtra, opcoes: List<String>) -> Unit,
    onRemoverVariavel: (VariavelExtra) -> Unit,
    onDismiss: () -> Unit
) {
    var novoNome by remember { mutableStateOf("") }
    var novoTipo by remember { mutableStateOf(TipoVariavelExtra.LISTA_OPCOES) }
    var novasOpcoesTxt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Variáveis Extras da Coleta", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Ative ou desative variáveis pré-cadastradas para esta coleta (estilo Field Book):",
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
