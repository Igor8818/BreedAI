package com.igor.breedai.userinterface

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.MOTIVOS_NAO_AVALIAVEL
import com.igor.breedai.data.ModelConfig
import com.igor.breedai.data.PontoFormulario
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.data.VariavelExtra
import com.igor.breedai.userinterface.util.copiarUriGaleriaParaArquivoLocal
import com.igor.breedai.userinterface.util.criarImageCaptureBreedAI
import org.tensorflow.lite.task.vision.detector.Detection

private val VERDE_ESCURO = Color(0xFF1B5E20)
private val VERDE_APP = Color(0xFF2E7D32)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelaFormularioFieldBook(
    ensaio: String,
    pontos: List<PontoFormulario>,
    registrosSalvos: List<RegistroColeta>,
    labelTratamento: String,
    labelParcela: String,
    labelPlanta: String,
    modelConfig: ModelConfig,
    variaveisAtivas: List<VariavelExtra>,
    onAbrirConfigVariaveis: () -> Unit,
    onCarregarPlanilhaClick: () -> Unit,
    onLimparFormularioClick: () -> Unit,
    onModoImersivoChange: (Boolean) -> Unit,
    onRegistrarPonto: (
        Uri,
        PontoFormulario,
        SituacaoPlanta,
        String,
        Int,
        Map<String, String>,
        List<CaixaAuditavel>
    ) -> Unit,
    todasVariaveis: List<VariavelExtra> = variaveisAtivas,
    onToggleVariavel: ((VariavelExtra, Boolean) -> Unit)? = null,
    onDesativarTodas: (() -> Unit)? = null,
    onCarregarExemploClick: () -> Unit = {},
    classificacoesPersonalizadas: List<String>? = null,
    onAtualizarClassificacoes: ((List<String>) -> Unit)? = null,
    templateCultura: TemplateCultura = TemplateCultura.FRUTICULTURA,
    configRendimento: ConfigConversaoRendimento = ConfigConversaoRendimento(),
    gpsAtivo: Boolean = false,
    onAbrirConfigRendimento: () -> Unit = {}
) {
    val context = LocalContext.current

    val primeiroPendente = remember(pontos.size) {
        val idx = pontos.indexOfFirst { !it.coletado }
        if (idx >= 0) idx else 0
    }
    var indiceAtual by rememberSaveable(pontos.size) { mutableIntStateOf(primeiroPendente) }

    var cameraAtiva by remember { mutableStateOf(false) }
    var mostrarDialogIrParaPlanta by remember { mutableStateOf(false) }
    var fotoParaRevisao by remember { mutableStateOf<Uri?>(null) }
    var caixasCapturadas by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var bitmapWCapturado by remember { mutableFloatStateOf(1f) }
    var bitmapHCapturado by remember { mutableFloatStateOf(1f) }

    var situacaoAtual by remember { mutableStateOf(SituacaoPlanta.NORMAL) }
    var motivoAtual by remember {
        mutableStateOf(templateCultura.motivosSugeridos.firstOrNull() ?: MOTIVOS_NAO_AVALIAVEL.first())
    }
    val valoresExtrasAtual = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(indiceAtual, pontos.size, registrosSalvos.size) {
        if (indiceAtual in pontos.indices) {
            val pt = pontos[indiceAtual]
            val id = "${pt.tratamento}_P${pt.parcela}_Pl${pt.planta}"
            val salvo = registrosSalvos.firstOrNull { it.idCompleto == id }
            valoresExtrasAtual.clear()
            if (salvo != null) {
                situacaoAtual = salvo.situacaoPlanta
                motivoAtual = salvo.motivoNaoAvaliavel.ifBlank {
                    templateCultura.motivosSugeridos.firstOrNull() ?: MOTIVOS_NAO_AVALIAVEL.first()
                }
                valoresExtrasAtual.putAll(salvo.variaveisExtras)
            } else {
                situacaoAtual = SituacaoPlanta.NORMAL
                motivoAtual = templateCultura.motivosSugeridos.firstOrNull() ?: MOTIVOS_NAO_AVALIAVEL.first()
            }
        }
    }

    LaunchedEffect(cameraAtiva, fotoParaRevisao) {
        onModoImersivoChange(cameraAtiva || fotoParaRevisao != null)
    }
    DisposableEffect(Unit) {
        onDispose { onModoImersivoChange(false) }
    }

    BackHandler(enabled = cameraAtiva || fotoParaRevisao != null) {
        if (fotoParaRevisao != null) fotoParaRevisao = null else cameraAtiva = false
    }

    val imageCapture = remember { criarImageCaptureBreedAI() }

    val precisaDetectorPrimario = pontos.isNotEmpty() && (cameraAtiva || fotoParaRevisao != null)
    val detectorHelper = remember(modelConfig, precisaDetectorPrimario) {
        if (precisaDetectorPrimario) {
            criarDetectorPrimarioBreedAI(context, modelConfig, lazyInit = true)
        } else {
            null
        }
    }
    DisposableEffect(detectorHelper) {
        onDispose {
            detectorHelper?.fechar()
        }
    }

    val launcherGaleria = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val arq = copiarUriGaleriaParaArquivoLocal(context, uri)
        if (arq != null) {
            fotoParaRevisao = Uri.fromFile(arq)
            caixasCapturadas = emptyList()
            bitmapWCapturado = 1f
            bitmapHCapturado = 1f
        } else {
            Toast.makeText(context, "Erro ao abrir foto da galeria.", Toast.LENGTH_SHORT).show()
        }
    }

    if (pontos.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.TableView, null, modifier = Modifier.size(52.dp), tint = VERDE_APP)
                    Text(
                        "Planilha de rota",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = VERDE_ESCURO,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Importe uma planilha (.xlsx ou .csv) com as parcelas e plantas do experimento.",
                        color = Color(0xFF5F6F61),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onCarregarPlanilhaClick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Importar planilha (.xlsx ou .csv)", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onCarregarExemploClick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_ESCURO),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Usar planilha de exemplo (12 plantas)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        return
    }

    if (indiceAtual !in pontos.indices) indiceAtual = 0
    val pontoAtual = pontos[indiceAtual]
    val idPonto = "${pontoAtual.tratamento}_P${pontoAtual.parcela}_Pl${pontoAtual.planta}"
    val ordemExibicao = if (pontoAtual.ordem > 0) pontoAtual.ordem else (indiceAtual + 1)

    if (fotoParaRevisao != null) {
        TelaRevisaoCaptura(
            fotoUri = fotoParaRevisao!!,
            plantId = idPonto,
            deteccoesIniciais = caixasCapturadas,
            bitmapWidthRef = bitmapWCapturado,
            bitmapHeightRef = bitmapHCapturado,
            detectorHelper = detectorHelper,
            detectorSecundarioHelper = null,
            criarDetectorSecundarioSobDemanda = if (deveUsarParDetectoresSecundario(modelConfig)) {
                { criarDetectorSecundarioBreedAI(context, modelConfig, lazyInit = false) }
            } else {
                null
            },
            labelsSecundarioFallback = if (deveUsarParDetectoresSecundario(modelConfig)) {
                com.igor.breedai.data.LABELS_DOENCA_CACAU
            } else {
                emptyList()
            },
            labelsFiltro = modelConfig.labelsFiltro,
            modoSemFrutos = situacaoAtual == SituacaoPlanta.SEM_FRUTOS,
            classificacoesPersonalizadas = classificacoesPersonalizadas,
            onAtualizarClassificacoes = onAtualizarClassificacoes,
            configRendimento = configRendimento,
            mostrarBotaoProximaFoto = variaveisAtivas.isEmpty() && indiceAtual < pontos.size - 1,
            onConfirmarEProximaFoto = { totalObj, caixasNovas ->
                onRegistrarPonto(
                    fotoParaRevisao!!,
                    pontoAtual,
                    situacaoAtual,
                    motivoAtual,
                    totalObj,
                    valoresExtrasAtual.toMap(),
                    caixasNovas
                )
                fotoParaRevisao = null
                caixasCapturadas = emptyList()
                situacaoAtual = SituacaoPlanta.NORMAL
                if (indiceAtual < pontos.size - 1) {
                    indiceAtual++
                    cameraAtiva = true
                } else {
                    cameraAtiva = false
                    Toast.makeText(context, "Rota Finalizada!", Toast.LENGTH_SHORT).show()
                }
            },
            onConfirmar = { totalObj, caixasNovas ->
                onRegistrarPonto(
                    fotoParaRevisao!!,
                    pontoAtual,
                    situacaoAtual,
                    motivoAtual,
                    totalObj,
                    valoresExtrasAtual.toMap(),
                    caixasNovas
                )
                fotoParaRevisao = null
                cameraAtiva = false
                situacaoAtual = SituacaoPlanta.NORMAL
                if (indiceAtual < pontos.size - 1) indiceAtual++
                else Toast.makeText(context, "Rota Finalizada!", Toast.LENGTH_SHORT).show()
            },
            onRefazer = { fotoParaRevisao = null }
        )
        return
    }

    if (cameraAtiva && detectorHelper != null) {
        CameraFullscreenBreedAI(
            context = context,
            detectorHelper = detectorHelper,
            imageCapture = imageCapture,
            modelConfig = modelConfig,
            tituloHud = "${pontoAtual.tratamento} | P${pontoAtual.parcela} Pl${pontoAtual.planta}",
            idParaArquivo = idPonto,
            modoSemFrutos = situacaoAtual == SituacaoPlanta.SEM_FRUTOS,
            onCancelar = { cameraAtiva = false },
            onFotoCapturada = { uri, dets, w, h ->
                caixasCapturadas = dets
                bitmapWCapturado = w
                bitmapHCapturado = h
                fotoParaRevisao = uri
            }
        )
        return
    }

    if (mostrarDialogIrParaPlanta) {
        var buscaPonto by remember { mutableStateOf("") }
        val filtrados = remember(pontos, buscaPonto) {
            val q = buscaPonto.trim()
            pontos.mapIndexedNotNull { idx, pt ->
                if (q.isBlank() ||
                    pt.tratamento.contains(q, ignoreCase = true) ||
                    pt.parcela.contains(q, ignoreCase = true) ||
                    pt.planta.contains(q, ignoreCase = true)
                ) idx to pt else null
            }
        }
        AlertDialog(
            onDismissRequest = { mostrarDialogIrParaPlanta = false },
            title = { Text("Ir para planta da rota", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = buscaPonto,
                        onValueChange = { buscaPonto = it },
                        placeholder = { Text("Buscar $labelTratamento, $labelParcela ou $labelPlanta...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtrados) { (idx, pt) ->
                            val sel = idx == indiceAtual
                            Surface(
                                onClick = {
                                    indiceAtual = idx
                                    mostrarDialogIrParaPlanta = false
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = when {
                                    sel -> Color(0xFFC8E6C9)
                                    pt.coletado -> Color(0xFFF1F8E9)
                                    else -> Color(0xFFF7F7F7)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "#${idx + 1} • $labelParcela ${pt.parcela} • $labelPlanta ${pt.planta}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = VERDE_ESCURO
                                        )
                                        Text(pt.tratamento, fontSize = 11.sp, color = Color(0xFF4E6E52))
                                    }
                                    Text(
                                        if (pt.coletado) "✓ ${pt.resumoResultado}" else "Pendente",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (pt.coletado) VERDE_APP else Color(0xFFE65100)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mostrarDialogIrParaPlanta = false }) { Text("Fechar") }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FaixaAcumuladoRendimentoColeta(
                registros = registrosSalvos,
                configRendimento = configRendimento,
                templateCultura = templateCultura,
                gpsAtivo = gpsAtivo,
                onEditarRendimento = onAbrirConfigRendimento
            )

            val coletadasCount = remember(pontos) { pontos.count { it.coletado } }
            val progressoRota = if (pontos.isNotEmpty()) coletadasCount.toFloat() / pontos.size.toFloat() else 0f

            // Bloco 1: Card Superior Unificado de Navegação Estilo Field Book
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { mostrarDialogIrParaPlanta = true }
                        ) {
                            Text(
                                "Planta ${indiceAtual + 1} de ${pontos.size}  •  Ordem #$ordemExibicao ▾",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = VERDE_ESCURO
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (pontoAtual.coletado) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    if (pontoAtual.coletado) "✓ Avaliada (${pontoAtual.resumoResultado})" else "Pendente",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (pontoAtual.coletado) VERDE_APP else Color(0xFFE65100),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = onCarregarPlanilhaClick,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Trocar", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            TextButton(
                                onClick = { onLimparFormularioClick(); indiceAtual = 0 },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF546E7A)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Limpar", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = { if (indiceAtual > 0) indiceAtual-- },
                            enabled = indiceAtual > 0,
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFFE8F5E9),
                                contentColor = VERDE_ESCURO
                            )
                        ) {
                            Icon(Icons.Default.ChevronLeft, "Planta anterior", modifier = Modifier.size(28.dp))
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "$labelParcela ${pontoAtual.parcela}  •  $labelPlanta ${pontoAtual.planta}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = VERDE_ESCURO,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "$labelTratamento: ${pontoAtual.tratamento.ifBlank { "-" }}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E3D30),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (pontoAtual.colunasPlanilha.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    pontoAtual.colunasPlanilha.entries.take(6).forEach { (colNome, colVal) ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFEEF3EE)
                                        ) {
                                            Text(
                                                "$colNome: $colVal",
                                                fontSize = 10.sp,
                                                color = Color(0xFF3E4E40),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        FilledTonalIconButton(
                            onClick = { if (indiceAtual < pontos.size - 1) indiceAtual++ },
                            enabled = indiceAtual < pontos.size - 1,
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFFE8F5E9),
                                contentColor = VERDE_ESCURO
                            )
                        ) {
                            Icon(Icons.Default.ChevronRight, "Próxima planta", modifier = Modifier.size(28.dp))
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        LinearProgressIndicator(
                            progress = { progressoRota },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = VERDE_APP,
                            trackColor = Color(0xFFEEF3EE)
                        )
                        Text(
                            "$coletadasCount de ${pontos.size} avaliadas",
                            fontSize = 10.sp,
                            color = Color(0xFF5F6F61),
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }

            SeletorSituacaoPlantaCard(
                situacao = situacaoAtual,
                onSituacaoChange = { situacaoAtual = it },
                motivoNaoAvaliavel = motivoAtual,
                onMotivoChange = { motivoAtual = it },
                rotuloSemUnidades = templateCultura.rotuloSemUnidades,
                motivosDisponiveis = templateCultura.motivosSugeridos
            )

            BlocoVariaveisExtrasCard(
                variaveisAtivas = variaveisAtivas,
                valoresAtuais = valoresExtrasAtual,
                onAbrirConfigVariaveis = onAbrirConfigVariaveis,
                todasVariaveis = todasVariaveis,
                onToggleVariavel = onToggleVariavel,
                onDesativarTodas = onDesativarTodas
            )

            Spacer(Modifier.height(4.dp))
        }

        // Barra fixa limpa no rodapé (sem setas duplicadas)
        if (situacaoAtual == SituacaoPlanta.NAO_AVALIAVEL) {
            Button(
                onClick = {
                    onRegistrarPonto(
                        Uri.EMPTY,
                        pontoAtual,
                        SituacaoPlanta.NAO_AVALIAVEL,
                        motivoAtual,
                        0,
                        valoresExtrasAtual.toMap(),
                        emptyList()
                    )
                    situacaoAtual = SituacaoPlanta.NORMAL
                    if (indiceAtual < pontos.size - 1) indiceAtual++
                    else Toast.makeText(context, "Rota finalizada!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.ReportProblem, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Registrar não avaliável ($motivoAtual)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { launcherGaleria.launch("image/*") },
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP),
                        border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Image, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Galeria", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Button(
                    onClick = { cameraAtiva = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (situacaoAtual == SituacaoPlanta.SEM_FRUTOS) Color(0xFFEF6C00) else VERDE_APP
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            situacaoAtual == SituacaoPlanta.SEM_FRUTOS -> "Foto de comprovação (0)"
                            pontoAtual.coletado -> "Refazer foto da planta"
                            else -> "Fotografar planta"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
