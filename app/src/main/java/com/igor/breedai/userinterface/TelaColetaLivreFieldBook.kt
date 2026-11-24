package com.igor.breedai.userinterface

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.MOTIVOS_NAO_AVALIAVEL
import com.igor.breedai.data.ModelConfig
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.data.VariavelExtra
import com.igor.breedai.userinterface.util.copiarUriGaleriaParaArquivoLocal
import com.igor.breedai.userinterface.util.criarImageCaptureBreedAI
import org.tensorflow.lite.task.vision.detector.Detection

private val VERDE_ESCURO = Color(0xFF1B5E20)
private val VERDE_APP = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaColetaLivreFieldBook(
    ensaio: String,
    registrosSalvos: List<RegistroColeta>,
    label1: String,
    label2: String,
    label3: String,
    isNum2: Boolean,
    isNum3: Boolean,
    autoAvancarPlanta: Boolean,
    onAutoAvancarChange: (Boolean) -> Unit,
    tratamento: String,
    onTratamentoChange: (String) -> Unit,
    historicoTratamentos: List<String>,
    parcelaNum: Int,
    onParcelaNumChange: (Int) -> Unit,
    parcelaTxt: String,
    onParcelaTxtChange: (String) -> Unit,
    plantaNum: Int,
    onPlantaNumChange: (Int) -> Unit,
    plantaTxt: String,
    onPlantaTxtChange: (String) -> Unit,
    modelConfig: ModelConfig,
    variaveisAtivas: List<VariavelExtra>,
    onAbrirConfigVariaveis: () -> Unit,
    onModoImersivoChange: (Boolean) -> Unit,
    onSalvarColeta: (
        Uri,
        String,
        String,
        String,
        SituacaoPlanta,
        String,
        Int,
        Map<String, String>,
        List<CaixaAuditavel>
    ) -> Unit,
    todasVariaveis: List<VariavelExtra> = variaveisAtivas,
    onToggleVariavel: ((VariavelExtra, Boolean) -> Unit)? = null,
    onDesativarTodas: (() -> Unit)? = null,
    classificacoesPersonalizadas: List<String>? = null,
    onAtualizarClassificacoes: ((List<String>) -> Unit)? = null,
    templateCultura: TemplateCultura = TemplateCultura.FRUTICULTURA,
    configRendimento: ConfigConversaoRendimento = ConfigConversaoRendimento(),
    gpsAtivo: Boolean = false,
    onAbrirConfigRendimento: () -> Unit = {}
) {
    val context = LocalContext.current

    var cameraAtiva by remember { mutableStateOf(false) }
    var menuTratamentosExpandido by remember { mutableStateOf(false) }

    var fotoParaRevisao by remember { mutableStateOf<Uri?>(null) }
    var caixasCapturadas by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var bitmapWCapturado by remember { mutableFloatStateOf(1f) }
    var bitmapHCapturado by remember { mutableFloatStateOf(1f) }

    var situacaoAtual by rememberSaveable { mutableStateOf(SituacaoPlanta.NORMAL) }
    var motivoAtual by rememberSaveable {
        mutableStateOf(templateCultura.motivosSugeridos.firstOrNull() ?: MOTIVOS_NAO_AVALIAVEL.first())
    }
    val valoresExtras = remember { mutableStateMapOf<String, String>() }

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

    val precisaDetectorPrimario = cameraAtiva || fotoParaRevisao != null
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

    val pVal = if (isNum2) parcelaNum.toString() else parcelaTxt.trim().ifBlank { "1" }
    val plVal = if (isNum3) plantaNum.toString() else plantaTxt.trim().ifBlank { "1" }
    val tratVal = tratamento.trim().ifBlank { "Geral" }
    val idAtual = "${tratVal}_P${pVal}_Pl${plVal}"
    val registroExistente = remember(registrosSalvos.size, idAtual) {
        registrosSalvos.firstOrNull { it.idCompleto.equals(idAtual, ignoreCase = true) }
    }

    LaunchedEffect(idAtual, registrosSalvos.size) {
        val salvo = registrosSalvos.firstOrNull { it.idCompleto.equals(idAtual, ignoreCase = true) }
        valoresExtras.clear()
        if (salvo != null) {
            situacaoAtual = salvo.situacaoPlanta
            motivoAtual = salvo.motivoNaoAvaliavel.ifBlank {
                templateCultura.motivosSugeridos.firstOrNull() ?: MOTIVOS_NAO_AVALIAVEL.first()
            }
            valoresExtras.putAll(salvo.variaveisExtras)
        } else {
            situacaoAtual = SituacaoPlanta.NORMAL
            motivoAtual = templateCultura.motivosSugeridos.firstOrNull() ?: MOTIVOS_NAO_AVALIAVEL.first()
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
            Toast.makeText(context, "Erro ao carregar foto da galeria.", Toast.LENGTH_SHORT).show()
        }
    }

    if (fotoParaRevisao != null) {
        TelaRevisaoCaptura(
            fotoUri = fotoParaRevisao!!,
            plantId = idAtual,
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
            mostrarBotaoProximaFoto = variaveisAtivas.isEmpty() && isNum3 && autoAvancarPlanta,
            onConfirmar = { totalObj, caixasNovas ->
                onSalvarColeta(
                    fotoParaRevisao!!,
                    tratVal,
                    pVal,
                    plVal,
                    situacaoAtual,
                    motivoAtual,
                    totalObj,
                    valoresExtras.toMap(),
                    caixasNovas
                )
                fotoParaRevisao = null
                cameraAtiva = false
                situacaoAtual = SituacaoPlanta.NORMAL
                valoresExtras.clear()
            },
            onConfirmarEProximaFoto = { totalObj, caixasNovas ->
                onSalvarColeta(
                    fotoParaRevisao!!,
                    tratVal,
                    pVal,
                    plVal,
                    situacaoAtual,
                    motivoAtual,
                    totalObj,
                    valoresExtras.toMap(),
                    caixasNovas
                )
                fotoParaRevisao = null
                caixasCapturadas = emptyList()
                situacaoAtual = SituacaoPlanta.NORMAL
                valoresExtras.clear()
                cameraAtiva = true
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
            tituloHud = "$tratVal | $label2 $pVal • $label3 $plVal",
            idParaArquivo = idAtual,
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

    // ── LAYOUT INLINE ESTILO FIELD BOOK (IDENTIFICAÇÃO -> ESTADO FENOTÍPICO -> VARIÁVEIS -> FOTO) ──
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

            // Card 1: Identificação no Ensaio
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Identificação da planta",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_ESCURO
                        )
                        if (isNum3) {
                            FilterChip(
                                selected = autoAvancarPlanta,
                                onClick = { onAutoAvancarChange(!autoAvancarPlanta) },
                                label = {
                                    Text(
                                        if (autoAvancarPlanta) "Auto-avançar $label3" else "Avanço manual",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFE8F5E9),
                                    selectedLabelColor = VERDE_ESCURO
                                ),
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isNum2) {
                            CardContadorInline(
                                titulo = label2,
                                valor = parcelaNum,
                                destaque = false,
                                subtitulo = "Bloco / Local",
                                onDecrement = { if (parcelaNum > 1) onParcelaNumChange(parcelaNum - 1) },
                                onIncrement = { onParcelaNumChange(parcelaNum + 1) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            OutlinedTextField(
                                value = parcelaTxt,
                                onValueChange = onParcelaTxtChange,
                                label = { Text(label2) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (isNum3) {
                            CardContadorInline(
                                titulo = label3,
                                valor = plantaNum,
                                destaque = registroExistente != null,
                                subtitulo = registroExistente?.let { "✓ Avaliada (${it.totalObjetos})" } ?: "Nova leitura",
                                onDecrement = { if (plantaNum > 1) onPlantaNumChange(plantaNum - 1) },
                                onIncrement = { onPlantaNumChange(plantaNum + 1) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            OutlinedTextField(
                                value = plantaTxt,
                                onValueChange = onPlantaTxtChange,
                                label = { Text(label3) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    val sugestoes = remember(tratamento, historicoTratamentos.toList()) {
                        val b = tratamento.trim()
                        if (b.isBlank()) historicoTratamentos.take(12)
                        else historicoTratamentos.filter { it.contains(b, ignoreCase = true) }.take(12)
                    }

                    ExposedDropdownMenuBox(
                        expanded = menuTratamentosExpandido && sugestoes.isNotEmpty(),
                        onExpandedChange = {
                            if (sugestoes.isNotEmpty()) menuTratamentosExpandido = !menuTratamentosExpandido
                        }
                    ) {
                        OutlinedTextField(
                            value = tratamento,
                            onValueChange = { onTratamentoChange(it) },
                            label = { Text(label1) },
                            placeholder = { Text("Ex: Tratamento 01, Genótipo A, Talhão Norte") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                            leadingIcon = { Icon(Icons.Default.Eco, null, tint = VERDE_APP) },
                            trailingIcon = {
                                if (sugestoes.isNotEmpty()) {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuTratamentosExpandido)
                                }
                            }
                        )
                        if (sugestoes.isNotEmpty()) {
                            ExposedDropdownMenu(
                                expanded = menuTratamentosExpandido,
                                onDismissRequest = { menuTratamentosExpandido = false }
                            ) {
                                sugestoes.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item) },
                                        onClick = {
                                            onTratamentoChange(item)
                                            menuTratamentosExpandido = false
                                        }
                                    )
                                }
                            }
                        }
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
                valoresAtuais = valoresExtras,
                onAbrirConfigVariaveis = onAbrirConfigVariaveis,
                todasVariaveis = todasVariaveis,
                onToggleVariavel = onToggleVariavel,
                onDesativarTodas = onDesativarTodas
            )

            Spacer(Modifier.height(4.dp))
        }

        // Barra fixa de ação na base
        if (situacaoAtual == SituacaoPlanta.NAO_AVALIAVEL) {
            Button(
                onClick = {
                    onSalvarColeta(
                        Uri.EMPTY,
                        tratVal,
                        pVal,
                        plVal,
                        SituacaoPlanta.NAO_AVALIAVEL,
                        motivoAtual,
                        0,
                        valoresExtras.toMap(),
                        emptyList()
                    )
                    situacaoAtual = SituacaoPlanta.NORMAL
                    valoresExtras.clear()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) {
                Icon(Icons.Default.ReportProblem, null)
                Spacer(Modifier.width(8.dp))
                Text("Registrar não avaliável ($motivoAtual)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP),
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
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (situacaoAtual == SituacaoPlanta.SEM_FRUTOS) Color(0xFFEF6C00) else VERDE_APP
                    )
                ) {
                    Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            situacaoAtual == SituacaoPlanta.SEM_FRUTOS -> "Foto de comprovação (0)"
                            registroExistente != null -> "Refazer foto da planta"
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

@Composable
private fun CardContadorInline(
    titulo: String,
    valor: Int,
    destaque: Boolean,
    subtitulo: String?,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (destaque) Color(0xFFE8F5E9) else Color(0xFFF6F9F6)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                titulo,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = VERDE_ESCURO,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onDecrement, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.RemoveCircle, "Diminuir $titulo", tint = Color(0xFF546E7A), modifier = Modifier.size(28.dp))
                }
                Text(
                    "$valor",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = VERDE_ESCURO
                )
                IconButton(onClick = onIncrement, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.AddCircle, "Aumentar $titulo", tint = VERDE_APP, modifier = Modifier.size(28.dp))
                }
            }
            Box(
                modifier = Modifier.height(20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!subtitulo.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (destaque) Color(0xFFC8E6C9) else Color(0xFFE8F5E9)
                    ) {
                        Text(
                            subtitulo,
                            fontSize = 10.sp,
                            color = if (destaque) VERDE_ESCURO else Color(0xFF4E6E52),
                            fontWeight = if (destaque) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
