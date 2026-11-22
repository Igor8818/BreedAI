package com.igor.breedai

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.data.calcularRendimentoColeta
import com.igor.breedai.data.formatarEstimativaCurta
import com.igor.breedai.ui.theme.BreedAITheme
import com.igor.breedai.userinterface.*
import com.igor.breedai.userinterface.util.ImportadorPlanilhaUniversal
import com.igor.breedai.viewmodel.BreedAIViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val VERDE_ESCURO = Color(0xFF1B5E20)
private val VERDE_APP = Color(0xFF2E7D32)

internal fun formatarDiagnosticoRegistro(
    situacao: SituacaoPlanta,
    motivoNaoAval: String,
    totalObjetos: Int,
    caixas: List<CaixaAuditavel>
): String = when (situacao) {
    SituacaoPlanta.NORMAL -> {
        val contagemClasses = caixas
            .groupingBy { rotuloAmigavel(it.rotulo) }
            .eachCount()
        val temClasseEspecifica = caixas.any { c ->
            val low = c.rotulo.trim().lowercase(Locale.ROOT)
            low.isNotEmpty() && low != "cacau" && low != "fruto" && low != "objeto"
        }
        if ((contagemClasses.size > 1 || temClasseEspecifica) && contagemClasses.isNotEmpty()) {
            val resumo = contagemClasses.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            "$totalObjetos ($resumo)"
        } else {
            totalObjetos.toString()
        }
    }
    SituacaoPlanta.SEM_FRUTOS -> "Sem Frutos (0)"
    SituacaoPlanta.NAO_AVALIAVEL -> "Não avaliável ($motivoNaoAval)"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[BreedAIViewModel::class.java]
        setContent {
            BreedAITheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BreedAIApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            (event == null || event.repeatCount == 0)
        ) {
            val tratado = VolumeShutterBridge.onShutter?.invoke() == true
            if (tratado) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreedAIApp(viewModel: BreedAIViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val uiState by viewModel.uiState.collectAsState()

    val coletaId = uiState.coletaId
    val templateAtual = uiState.templateAtual
    val coletasSalvas = uiState.coletasSalvas
    val coletaNome = uiState.coletaNome
    val labelTratamento = uiState.labelTratamento
    val labelParcela = uiState.labelParcela
    val labelPlanta = uiState.labelPlanta
    val tipoNumParcela = uiState.tipoNumParcela
    val tipoNumPlanta = uiState.tipoNumPlanta
    val autoAvancarPlanta = uiState.autoAvancarPlanta
    val configRendimento = uiState.configRendimento
    val listaRegistros = uiState.registros
    val formularioPontos = uiState.formularioPontos
    val variaveisExtras = uiState.variaveisExtras
    val variaveisAtivas = uiState.variaveisAtivas
    val historicoTratamentos = uiState.historicoTratamentos
    val modelConfig = uiState.modelConfig
    val classificacoesAtivasModelo = uiState.classificacoesAtivasModelo
    val gpsAtivo = uiState.gpsAtivo

    // Navegação principal em 4 abas intuitivas:
    // 0 = Início | 1 = Coletar (Rota / Livre) | 2 = Dados | 3 = Modelo IA
    var abaSelecionada by rememberSaveable { mutableIntStateOf(0) }
    var modoColetaGuiada by rememberSaveable { mutableStateOf(formularioPontos.isNotEmpty()) }

    var tratamentoManual by rememberSaveable { mutableStateOf("T-01") }
    var parcelaNumManual by rememberSaveable { mutableIntStateOf(1) }
    var plantaNumManual by rememberSaveable { mutableIntStateOf(1) }
    var parcelaTxtManual by rememberSaveable { mutableStateOf("1") }
    var plantaTxtManual by rememberSaveable { mutableStateOf("1") }

    var registroParaEdicao by remember { mutableStateOf<RegistroColeta?>(null) }
    var mostrarTelaConfigEnsaio by rememberSaveable { mutableStateOf(false) }
    var mostrarDialogConfigRendimento by remember { mutableStateOf(false) }
    var mostrarDialogRenomearColeta by remember { mutableStateOf(false) }
    var mostrarDialogSobreOffline by remember { mutableStateOf(false) }
    var mostrarDialogNovaColeta by remember { mutableStateOf(false) }
    var mostrarDialogMinhasColetas by remember { mutableStateOf(false) }
    var mostrarDialogGerenciarVariaveis by remember { mutableStateOf(false) }

    // Estado de importação manual de colunas (.xlsx / .csv)
    var planilhaPendente by remember { mutableStateOf<ImportadorPlanilhaUniversal.PlanilhaLida?>(null) }

    val launcherPlanilha = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.IO) {
            val lida = ImportadorPlanilhaUniversal.lerPlanilha(context, uri)
            withContext(Dispatchers.Main) {
                if (lida.cabecalho.isEmpty() || lida.linhas.isEmpty()) {
                    Toast.makeText(
                        context,
                        "Planilha vazia ou formato não reconhecido. Use .xlsx ou .csv.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                val sugestao = ImportadorPlanilhaUniversal.sugerirMapeamento(lida.cabecalho)
                if (sugestao.completo) {
                    val pontos = ImportadorPlanilhaUniversal.extrairPontosFormulario(
                        lida, sugestao.idxTratamento, sugestao.idxParcela, sugestao.idxPlanta
                    )
                    if (pontos.isEmpty()) {
                        Toast.makeText(context, "Nenhuma linha válida encontrada na planilha.", Toast.LENGTH_LONG).show()
                    } else {
                        viewModel.aplicarPlanilhaPontos(pontos)
                        mostrarTelaConfigEnsaio = false
                        modoColetaGuiada = true
                        abaSelecionada = 1
                        Toast.makeText(
                            context,
                            "${pontos.size} pontos carregados (${lida.formato})!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    planilhaPendente = lida
                }
            }
        }
    }

    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    viewModel.atualizarLocalizacao(it.latitude, it.longitude)
                }
            }
        }
    }

    DisposableEffect(fusedLocationClient) {
        onDispose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    val launcherPermissoes = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissoes ->
        if (permissoes[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissoes[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, android.os.Looper.getMainLooper())
        }
    }

    LaunchedEffect(Unit) {
        launcherPermissoes.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // ── DIÁLOGOS GLOBAIS ──

    if (mostrarDialogConfigRendimento) {
        DialogConfiguracaoRendimento(
            configAtual = configRendimento,
            onDismiss = { mostrarDialogConfigRendimento = false },
            onSalvar = { novaRend ->
                viewModel.salvarConfigRendimento(novaRend)
                mostrarDialogConfigRendimento = false
                Toast.makeText(
                    context,
                    if (novaRend.ativo) "Conversão para ${novaRend.unidadeSimbolo} salva!"
                    else "Conversão de rendimento desativada.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    if (uiState.mostrarTutorialPrimeiraVez) {
        AlertDialog(
            onDismissRequest = { viewModel.marcarTutorialVisto() },
            title = {
                Text("Bem-vindo ao BreedAI", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = VERDE_ESCURO)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Conte objetos, identifique doenças e organize seus experimentos direto no celular, sem precisar de internet:",
                        fontSize = 13.sp,
                        color = Color(0xFF3E4E40),
                        lineHeight = 18.sp
                    )
                    TutorialItemStep("1. Escolha o que a câmera identifica", "Na aba Modelo IA, selecione um modelo embutido ou importe seu arquivo .tflite/.zip e teste ao vivo na câmera.")
                    TutorialItemStep("2. Organize sua coleta ou anotações", "Gerencie múltiplas coletas no Início, escolha o perfil da cultura e ative Anotações extras se desejar.")
                    TutorialItemStep("3. Fotografe na aba Coletar", "Alterne entre Modo Rota (planilha .xlsx/.csv) e Modo Livre conforme sua necessidade no campo.")
                    TutorialItemStep("4. Revise e exporte", "Toque nos quadros da foto para ajustar, use Desfazer se errar e exporte CSV ou ZIP completo na aba Dados.")
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.marcarTutorialVisto() },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("Começar agora", fontWeight = FontWeight.Bold) }
            }
        )
    }

    if (mostrarDialogRenomearColeta) {
        var nomeTemp by remember(coletaNome) { mutableStateOf(coletaNome) }
        val sugestaoData = remember(templateAtual) {
            "${templateAtual.nomeExibicao} ${SimpleDateFormat("dd/MM", Locale.ROOT).format(Date())}"
        }
        AlertDialog(
            onDismissRequest = { mostrarDialogRenomearColeta = false },
            title = { Text("Renomear coleta atual", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Altere o nome desta coleta sem apagar nenhum dado já registrado:",
                        fontSize = 13.sp,
                        color = Color(0xFF5F6F61)
                    )
                    OutlinedTextField(
                        value = nomeTemp,
                        onValueChange = { nomeTemp = it },
                        label = { Text("Nome da coleta") },
                        singleLine = true,
                        trailingIcon = {
                            if (nomeTemp.isNotEmpty()) {
                                IconButton(onClick = { nomeTemp = "" }) {
                                    Icon(Icons.Default.Cancel, "Limpar nome")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SuggestionChip(
                            onClick = { nomeTemp = sugestaoData },
                            label = { Text(sugestaoData, fontSize = 11.sp, maxLines = 1) }
                        )
                        SuggestionChip(
                            onClick = { nomeTemp = "Ensaio Bloco 01" },
                            label = { Text("Ensaio Bloco 01", fontSize = 11.sp, maxLines = 1) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (viewModel.renomearColeta(nomeTemp)) {
                            Toast.makeText(context, "Nome alterado para \"${nomeTemp.trim()}\"!", Toast.LENGTH_SHORT).show()
                        }
                        mostrarDialogRenomearColeta = false
                    },
                    enabled = nomeTemp.trim().isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("Salvar nome") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogRenomearColeta = false }) { Text("Cancelar") }
            }
        )
    }

    if (mostrarDialogSobreOffline) {
        AlertDialog(
            onDismissRequest = { mostrarDialogSobreOffline = false },
            title = { Text("BreedAI • 100% offline e privado", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Plataforma de fenotipagem em campo e contagem assistida por Visão Computacional embarcada (TensorFlow Lite).",
                        fontSize = 13.sp
                    )
                    HorizontalDivider()
                    Text("• Processamento local: imagens e inferências de IA ocorrem 100% no dispositivo.", fontSize = 12.sp, color = Color.DarkGray)
                    Text("• Dados do usuário: fotos, anotações e planilhas CSV ficam armazenados localmente.", fontSize = 12.sp, color = Color.DarkGray)
                    Text("• GPS opcional: coordenadas gravadas apenas para exportação científica local.", fontSize = 12.sp, color = Color.DarkGray)
                }
            },
            confirmButton = {
                Button(
                    onClick = { mostrarDialogSobreOffline = false },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("Entendi") }
            }
        )
    }

    if (mostrarDialogNovaColeta) {
        var novoNome by remember { mutableStateOf("Coleta ${SimpleDateFormat("ddMM HHmm", Locale.ROOT).format(Date())}") }
        var templateSel by remember { mutableStateOf(templateAtual) }
        var manterFormularioAtual by remember { mutableStateOf(false) }
        var iniciarSomenteContagem by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { mostrarDialogNovaColeta = false },
            title = { Text("Criar nova coleta", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (listaRegistros.isNotEmpty()) {
                        Text(
                            "Seus ${listaRegistros.size} registros de \"$coletaNome\" continuarão salvos e você poderá reabri-los em Minhas Coletas.",
                            fontSize = 12.sp,
                            color = VERDE_ESCURO
                        )
                    }
                    OutlinedTextField(
                        value = novoNome,
                        onValueChange = { novoNome = it },
                        label = { Text("Nome da nova coleta") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Perfil da cultura:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = VERDE_ESCURO)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TemplateCultura.entries.forEach { tpl ->
                            FilterChip(
                                selected = templateSel == tpl,
                                onClick = { templateSel = tpl },
                                label = { Text(tpl.nomeExibicao, fontSize = 11.sp) }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = iniciarSomenteContagem, onCheckedChange = { iniciarSomenteContagem = it })
                        Text("Iniciar somente com contagem IA (desativar anotações extras)", fontSize = 12.sp)
                    }
                    if (formularioPontos.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = manterFormularioAtual, onCheckedChange = { manterFormularioAtual = it })
                            Text("Copiar planilha do Formulário atual para a nova coleta", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nomeFinal = viewModel.criarNovaColeta(
                            nome = novoNome,
                            templateId = templateSel.id,
                            manterPlanilhaAtual = manterFormularioAtual,
                            iniciarSomenteContagem = iniciarSomenteContagem
                        )
                        parcelaNumManual = 1
                        plantaNumManual = 1
                        parcelaTxtManual = "1"
                        plantaTxtManual = "1"
                        tratamentoManual = templateSel.exemplosTratamento.firstOrNull() ?: "T-01"
                        mostrarDialogNovaColeta = false
                        Toast.makeText(context, "Coleta \"$nomeFinal\" criada!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("CRIAR COLETA") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogNovaColeta = false }) { Text("Cancelar") }
            }
        )
    }

    if (mostrarDialogMinhasColetas) {
        LaunchedEffect(Unit) { viewModel.atualizarListaColetas() }
        AlertDialog(
            onDismissRequest = { mostrarDialogMinhasColetas = false },
            title = { Text("Minhas coletas (${coletasSalvas.size})", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Toque em uma coleta para alternar sem perder dados:",
                        fontSize = 12.sp,
                        color = Color(0xFF5F6F61)
                    )
                    coletasSalvas.forEach { item ->
                        val ativa = item.id == coletaId
                        val tpl = TemplateCultura.fromId(item.templateId)
                        ElevatedCard(
                            onClick = {
                                if (!ativa) {
                                    viewModel.alternarColeta(item.id)?.let { nomeAberta ->
                                        Toast.makeText(context, "Coleta \"$nomeAberta\" aberta!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                mostrarDialogMinhasColetas = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (ativa) Modifier.border(2.dp, VERDE_APP, RoundedCornerShape(12.dp))
                                    else Modifier
                                ),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (ativa) Color(0xFFE8F5E9) else Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.nome,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = VERDE_ESCURO
                                    )
                                    Text(
                                        "${tpl.nomeExibicao} • ${item.totalRegistros} registros" +
                                            if (item.totalPontosRota > 0) " • ${item.totalPontosRota} na rota" else "",
                                        fontSize = 11.sp,
                                        color = Color(0xFF5F6F61)
                                    )
                                }
                                if (ativa) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = VERDE_APP
                                    ) {
                                        Text(
                                            "Ativa",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            mostrarDialogMinhasColetas = false
                                            mostrarDialogRenomearColeta = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, "Renomear coleta", tint = VERDE_ESCURO, modifier = Modifier.size(18.dp))
                                    }
                                }
                                if (coletasSalvas.size > 1) {
                                    IconButton(
                                        onClick = { viewModel.excluirColeta(item.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, "Excluir coleta", tint = Color(0xFFC62828), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        mostrarDialogMinhasColetas = false
                        mostrarDialogNovaColeta = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("+ Nova coleta") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogMinhasColetas = false }) { Text("Fechar") }
            }
        )
    }

    if (mostrarDialogGerenciarVariaveis) {
        DialogGerenciarVariaveisExtras(
            variaveis = variaveisExtras,
            onToggleAtiva = { variavel, ativa -> viewModel.alternarVariavelNaColeta(variavel, ativa) },
            onAdicionarVariavel = { nome, tipo, opcoes -> viewModel.adicionarVariavel(nome, tipo, opcoes) },
            onRemoverVariavel = { variavel -> viewModel.removerVariavel(variavel) },
            onDismiss = { mostrarDialogGerenciarVariaveis = false }
        )
    }

    if (planilhaPendente != null) {
        val pendente = planilhaPendente!!
        val cols = pendente.cabecalho
        val primeiraLinha = pendente.linhas.firstOrNull().orEmpty()
        val labelsCols = cols.mapIndexed { idx, col ->
            val ex = primeiraLinha.getOrNull(idx)?.trim().orEmpty()
            if (ex.isNotBlank()) "$col (ex: $ex)" else col
        }
        var idxSelTrat by remember(pendente) { mutableIntStateOf(0.coerceAtMost((cols.size - 1).coerceAtLeast(0))) }
        var idxSelParc by remember(pendente) { mutableIntStateOf(1.coerceAtMost((cols.size - 1).coerceAtLeast(0))) }
        var idxSelPlan by remember(pendente) { mutableIntStateOf(2.coerceAtMost((cols.size - 1).coerceAtLeast(0))) }

        AlertDialog(
            onDismissRequest = { planilhaPendente = null },
            title = { Text("Vincular Colunas (${pendente.formato})", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Escolha qual coluna corresponde a cada identificador fixo:", fontSize = 13.sp, color = Color.Gray)
                    Text("$labelTratamento / Variedade:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    SeletorDropdownSimples(labelsCols, labelsCols.getOrNull(idxSelTrat) ?: "") { escolhido ->
                        val i = labelsCols.indexOf(escolhido)
                        if (i >= 0) idxSelTrat = i
                    }
                    Text("$labelParcela / Bloco:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    SeletorDropdownSimples(labelsCols, labelsCols.getOrNull(idxSelParc) ?: "") { escolhido ->
                        val i = labelsCols.indexOf(escolhido)
                        if (i >= 0) idxSelParc = i
                    }
                    Text("$labelPlanta / ID:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    SeletorDropdownSimples(labelsCols, labelsCols.getOrNull(idxSelPlan) ?: "") { escolhido ->
                        val i = labelsCols.indexOf(escolhido)
                        if (i >= 0) idxSelPlan = i
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pontos = ImportadorPlanilhaUniversal.extrairPontosFormulario(
                            pendente,
                            idxSelTrat,
                            idxSelParc,
                            idxSelPlan
                        )
                        viewModel.aplicarPlanilhaPontos(pontos)
                        planilhaPendente = null
                        mostrarTelaConfigEnsaio = false
                        modoColetaGuiada = true
                        abaSelecionada = 1
                        Toast.makeText(context, "${pontos.size} pontos carregados!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { planilhaPendente = null }) { Text("Cancelar") }
            }
        )
    }

    // ── MODO DE REVISÃO DE REGISTRO DA EDIÇÃO DOS DADOS ──
    if (registroParaEdicao != null) {
        val reg = registroParaEdicao!!
        val (detectorHelperRevisao, detectorSecundarioRevisao) = remember(modelConfig) {
            criarParDetectoresBreedAI(context, modelConfig)
        }
        DisposableEffect(detectorHelperRevisao, detectorSecundarioRevisao) {
            onDispose {
                detectorHelperRevisao.fechar()
                detectorSecundarioRevisao?.fechar()
            }
        }
        BackHandler { registroParaEdicao = null }

        TelaRevisaoCaptura(
            fotoUri = reg.uri,
            plantId = reg.idCompleto,
            deteccoesIniciais = emptyList(),
            bitmapWidthRef = 1f,
            bitmapHeightRef = 1f,
            detectorHelper = detectorHelperRevisao,
            detectorSecundarioHelper = detectorSecundarioRevisao,
            labelsFiltro = modelConfig.labelsFiltro,
            caixasAuditadasIniciais = reg.caixas,
            modoSemFrutos = reg.situacaoPlanta == SituacaoPlanta.SEM_FRUTOS,
            classificacoesPersonalizadas = classificacoesAtivasModelo,
            configRendimento = configRendimento,
            onAtualizarClassificacoes = { novasCls -> viewModel.atualizarClassificacoesAtivas(novasCls) },
            onConfirmar = { totalObj, caixasNovas ->
                val totalFinal = viewModel.atualizarCaixasRegistro(reg.idCompleto, totalObj, caixasNovas)
                if (totalFinal != null) {
                    Toast.makeText(context, "Registro atualizado ($totalFinal objetos).", Toast.LENGTH_SHORT).show()
                }
                registroParaEdicao = null
            },
            onRefazer = { registroParaEdicao = null }
        )
        return
    }

    // ── TELA DEDICADA DE CONFIGURAÇÕES DA COLETA & VARIÁVEIS ──
    if (mostrarTelaConfigEnsaio) {
        BackHandler { mostrarTelaConfigEnsaio = false }
        Scaffold(
            topBar = {
                Surface(color = Color.White, shadowElevation = 2.dp) {
                    TopAppBar(
                        title = { Text("Configurações da coleta", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                        navigationIcon = {
                            IconButton(onClick = { mostrarTelaConfigEnsaio = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.White,
                            titleContentColor = VERDE_ESCURO,
                            navigationIconContentColor = VERDE_ESCURO
                        )
                    )
                }
            }
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                TelaConfiguracaoEnsaio(
                    coletaNome = coletaNome,
                    onSalvarNomeColeta = { novo ->
                        if (viewModel.renomearColeta(novo)) {
                            Toast.makeText(context, "Nome da coleta salvo!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    label1 = labelTratamento,
                    label2 = labelParcela,
                    label3 = labelPlanta,
                    isNum2 = tipoNumParcela,
                    isNum3 = tipoNumPlanta,
                    autoAvancarPlanta = autoAvancarPlanta,
                    onSalvarCamposFixos = { l1, l2, l3, n2, n3, autoAv ->
                        viewModel.salvarCamposFixos(l1, l2, l3, n2, n3, autoAv)
                        Toast.makeText(context, "Campos de navegação salvos!", Toast.LENGTH_SHORT).show()
                    },
                    pontosRotaCount = formularioPontos.size,
                    onImportarPlanilha = { launcherPlanilha.launch("*/*") },
                    onLimparPlanilha = { viewModel.limparPlanilha() },
                    onIniciarNovaColeta = { mostrarDialogNovaColeta = true },
                    variaveisExtras = variaveisExtras,
                    onToggleVariavelAtiva = { variavel, ativa -> viewModel.alternarVariavelNaColeta(variavel, ativa) },
                    onAdicionarVariavel = { nome, tipo, opcoes -> viewModel.adicionarVariavel(nome, tipo, opcoes) },
                    onRemoverVariavel = { variavel -> viewModel.removerVariavel(variavel) }
                )
            }
        }
        return
    }

    // ── NAVEGAÇÃO PRINCIPAL PASSO-A-PASSO (TOPBAR + 4 ABAS FIXAS) ──
    var modoImersivoAtivo by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!modoImersivoAtivo) {
                val tituloSecaoAtual = when (abaSelecionada) {
                    0 -> "BreedAI"
                    1 -> if (modoColetaGuiada) "Coletar • Rota" else "Coletar • Livre"
                    2 -> "Dados e Exportação"
                    else -> "Modelo de IA"
                }
                val textoChipIA = when {
                    modelConfig.usarModeloSecundario -> "Contar+Doença"
                    modelConfig.nomeExibicao.contains("Doença", ignoreCase = true) -> "IA: Doenças"
                    modelConfig.nomeExibicao.contains("Banana", ignoreCase = true) -> "IA: Banana"
                    modelConfig.nomeExibicao.contains("Maçã", ignoreCase = true) -> "IA: Maçã"
                    modelConfig.nomeExibicao.contains("Contagem", ignoreCase = true) -> "IA: Contagem"
                    else -> "IA: ${modelConfig.nomeExibicao}"
                }
                Surface(color = Color.White, shadowElevation = 2.dp) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.White,
                            titleContentColor = VERDE_ESCURO
                        ),
                        title = {
                            if (abaSelecionada == 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFFE8F5E9),
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.Spa,
                                                null,
                                                tint = VERDE_APP,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            "BreedAI",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = VERDE_ESCURO,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "Fenotipagem & Visão IA",
                                            fontSize = 11.sp,
                                            color = Color(0xFF5F6F61),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        tituloSecaoAtual,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VERDE_ESCURO,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Surface(
                                        onClick = { mostrarDialogMinhasColetas = true },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFF1F8E9)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.FolderOpen,
                                                null,
                                                tint = VERDE_APP,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                coletaNome,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = VERDE_ESCURO,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                null,
                                                tint = VERDE_APP,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        actions = {
                            Surface(
                                onClick = { abaSelecionada = 3 },
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFFE8F5E9),
                                border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                                modifier = Modifier.widthIn(max = 128.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, null, tint = VERDE_APP, modifier = Modifier.size(14.dp))
                                    Text(
                                        textoChipIA,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VERDE_ESCURO,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.abrirTutorial() }) {
                                Icon(Icons.AutoMirrored.Filled.HelpOutline, "Tutorial rápido", tint = VERDE_APP)
                            }
                            IconButton(onClick = { mostrarTelaConfigEnsaio = true }) {
                                Icon(Icons.Default.Tune, "Configurações da coleta", tint = VERDE_APP)
                            }
                        }
                    )
                }
            }
        },
        bottomBar = {
            if (!modoImersivoAtivo) {
                val navItemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = VERDE_ESCURO,
                    selectedTextColor = VERDE_ESCURO,
                    indicatorColor = Color(0xFFE8F5E9),
                    unselectedIconColor = Color(0xFF5F6F61),
                    unselectedTextColor = Color(0xFF5F6F61)
                )
                Surface(color = Color.White, shadowElevation = 6.dp) {
                    NavigationBar(containerColor = Color.White) {
                        NavigationBarItem(
                            selected = abaSelecionada == 0,
                            onClick = { abaSelecionada = 0 },
                            icon = { Icon(Icons.Default.Home, null) },
                            label = { Text("Início", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = abaSelecionada == 1,
                            onClick = { abaSelecionada = 1 },
                            icon = {
                                if (formularioPontos.isNotEmpty()) {
                                    BadgedBox(
                                        badge = {
                                            Badge(containerColor = VERDE_APP, contentColor = Color.White) {
                                                Text("${formularioPontos.size}", fontSize = 9.sp)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.PhotoCamera, null)
                                    }
                                } else {
                                    Icon(Icons.Default.PhotoCamera, null)
                                }
                            },
                            label = { Text("Coletar", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = abaSelecionada == 2,
                            onClick = { abaSelecionada = 2 },
                            icon = {
                                if (listaRegistros.isNotEmpty()) {
                                    BadgedBox(
                                        badge = {
                                            Badge(containerColor = VERDE_APP, contentColor = Color.White) {
                                                Text("${listaRegistros.size}", fontSize = 9.sp)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.TableChart, null)
                                    }
                                } else {
                                    Icon(Icons.Default.TableChart, null)
                                }
                            },
                            label = { Text("Dados", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = abaSelecionada == 3,
                            onClick = { abaSelecionada = 3 },
                            icon = { Icon(Icons.Default.SmartToy, null) },
                            label = { Text("Modelo IA", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                            colors = navItemColors
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(if (modoImersivoAtivo) PaddingValues(0.dp) else padding)
        ) {
            val classesExportacao = remember(modelConfig, classificacoesAtivasModelo, listaRegistros) {
                val daConfig = modelConfig.labelsFiltro
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals("cacau", true) && !it.equals("fruto", true) && !it.equals("objeto", true) }
                val personalizadas = (classificacoesAtivasModelo ?: emptyList())
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals("cacau", true) && !it.equals("fruto", true) && !it.equals("objeto", true) }
                val defaultsDoenca = if (personalizadas.isEmpty() && daConfig.isEmpty() &&
                    (modelConfig.modeloSelecionado?.nomeArquivo == "detector_doenca.tflite" || modelConfig.usarModeloSecundario)
                ) {
                    listOf("Sadio", "VassouraBruxa", "PodridaoParda", "Moniliase")
                } else {
                    emptyList()
                }
                val dosRegistros = listaRegistros
                    .flatMap { it.caixas }
                    .map { it.rotulo.trim() }
                    .filter { it.isNotBlank() && !it.equals("cacau", true) && !it.equals("fruto", true) && !it.equals("objeto", true) }
                val fontePrincipal = if (personalizadas.isNotEmpty()) personalizadas else (daConfig + defaultsDoenca)
                (fontePrincipal + dosRegistros).distinct()
            }

            val rendimentoResumoHero = remember(listaRegistros, configRendimento) {
                if (configRendimento.ativo && listaRegistros.isNotEmpty()) {
                    val rend = calcularRendimentoColeta(listaRegistros, configRendimento)
                    val sym = configRendimento.unidadeSimbolo.trim().ifBlank { "unid" }
                    val acum = formatarEstimativaCurta(rend.unidadesAcumuladas, sym)
                    val ha = if (configRendimento.plantasPorHa > 0) {
                        " • ${formatarEstimativaCurta(rend.realUnidadesHa, "$sym/ha")}"
                    } else ""
                    "$acum$ha"
                } else {
                    null
                }
            }

            when (abaSelecionada) {
                0 -> TelaInicioPassoAPasso(
                    coletaNome = coletaNome,
                    templateAtual = templateAtual,
                    totalColetasSalvas = coletasSalvas.size.coerceAtLeast(1),
                    totalRegistros = listaRegistros.size,
                    totalPontosFormulario = formularioPontos.size,
                    pontosColetadosFormulario = formularioPontos.count { it.coletado },
                    rendimentoResumoTexto = rendimentoResumoHero,
                    onAbrirConfigRendimento = { mostrarDialogConfigRendimento = true },
                    modelConfig = modelConfig,
                    variaveisAtivas = variaveisAtivas,
                    todasVariaveis = variaveisExtras,
                    classesExportacao = classesExportacao,
                    onRenomearColeta = { mostrarDialogRenomearColeta = true },
                    onNovaColeta = { mostrarDialogNovaColeta = true },
                    onAbrirMinhasColetas = { mostrarDialogMinhasColetas = true },
                    onAtivarSomenteContagem = { viewModel.ativarModoSomenteContagem() },
                    onAlternarVariavel = { variavel, ativa -> viewModel.alternarVariavelNaColeta(variavel, ativa) },
                    onGerenciarVariaveis = { mostrarDialogGerenciarVariaveis = true },
                    onIrParaModelo = { abaSelecionada = 3 },
                    onIrParaFormulario = {
                        modoColetaGuiada = true
                        abaSelecionada = 1
                    },
                    onIrParaLivre = {
                        modoColetaGuiada = false
                        abaSelecionada = 1
                    },
                    onIrParaDados = { abaSelecionada = 2 },
                    onCarregarExemplo = {
                        viewModel.carregarPlanilhaExemplo()
                        modoColetaGuiada = true
                        abaSelecionada = 1
                        Toast.makeText(context, "Planilha exemplo carregada (12 plantas)!", Toast.LENGTH_SHORT).show()
                    },
                    onAbrirTutorial = { viewModel.abrirTutorial() },
                    onAbrirSobreOffline = { mostrarDialogSobreOffline = true }
                )

                1 -> Column(modifier = Modifier.fillMaxSize()) {
                    if (!modoImersivoAtivo) {
                        Surface(
                            color = Color.White,
                            shadowElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .background(Color(0xFFEEF3EE), RoundedCornerShape(10.dp))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Surface(
                                    onClick = { modoColetaGuiada = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (modoColetaGuiada) VERDE_APP else Color.Transparent
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            if (formularioPontos.isNotEmpty()) "Modo Rota (${formularioPontos.size})" else "Modo Rota (Planilha)",
                                            fontSize = 12.sp,
                                            fontWeight = if (modoColetaGuiada) FontWeight.Bold else FontWeight.Medium,
                                            color = if (modoColetaGuiada) Color.White else Color(0xFF3E4E40),
                                            maxLines = 1
                                        )
                                    }
                                }
                                Surface(
                                    onClick = { modoColetaGuiada = false },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (!modoColetaGuiada) VERDE_APP else Color.Transparent
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "Modo Livre",
                                            fontSize = 12.sp,
                                            fontWeight = if (!modoColetaGuiada) FontWeight.Bold else FontWeight.Medium,
                                            color = if (!modoColetaGuiada) Color.White else Color(0xFF3E4E40),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (modoColetaGuiada) {
                            TelaFormularioFieldBook(
                                ensaio = coletaNome,
                                pontos = formularioPontos,
                                registrosSalvos = listaRegistros,
                                labelTratamento = labelTratamento,
                                labelParcela = labelParcela,
                                labelPlanta = labelPlanta,
                                modelConfig = modelConfig,
                                variaveisAtivas = variaveisAtivas,
                                todasVariaveis = variaveisExtras,
                                onToggleVariavel = { variavel, ativa -> viewModel.alternarVariavelNaColeta(variavel, ativa) },
                                onDesativarTodas = { viewModel.ativarModoSomenteContagem() },
                                onAbrirConfigVariaveis = { mostrarTelaConfigEnsaio = true },
                                onCarregarPlanilhaClick = { launcherPlanilha.launch("*/*") },
                                onCarregarExemploClick = {
                                    viewModel.carregarPlanilhaExemplo()
                                    Toast.makeText(context, "Planilha exemplo carregada (12 plantas)!", Toast.LENGTH_SHORT).show()
                                },
                                onLimparFormularioClick = { viewModel.limparPlanilha() },
                                onModoImersivoChange = { modoImersivoAtivo = it },
                                classificacoesPersonalizadas = classificacoesAtivasModelo,
                                onAtualizarClassificacoes = { viewModel.atualizarClassificacoesAtivas(it) },
                                templateCultura = templateAtual,
                                configRendimento = configRendimento,
                                gpsAtivo = gpsAtivo,
                                onAbrirConfigRendimento = { mostrarDialogConfigRendimento = true },
                                onRegistrarPonto = { uri, ponto, situacao, motivo, totalObj, extras, caixas ->
                                    viewModel.salvarOuAtualizarRegistro(
                                        uri = uri,
                                        tratamento = ponto.tratamento,
                                        parcela = ponto.parcela,
                                        planta = ponto.planta,
                                        situacao = situacao,
                                        motivoNaoAval = motivo,
                                        totalObjetos = totalObj,
                                        extras = extras,
                                        caixas = caixas
                                    )?.let { msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        } else {
                            TelaColetaLivreFieldBook(
                                ensaio = coletaNome,
                                registrosSalvos = listaRegistros,
                                label1 = labelTratamento,
                                label2 = labelParcela,
                                label3 = labelPlanta,
                                isNum2 = tipoNumParcela,
                                isNum3 = tipoNumPlanta,
                                autoAvancarPlanta = autoAvancarPlanta,
                                onAutoAvancarChange = { viewModel.salvarAutoAvancarPlanta(it) },
                                tratamento = tratamentoManual,
                                onTratamentoChange = { tratamentoManual = it },
                                historicoTratamentos = historicoTratamentos,
                                parcelaNum = parcelaNumManual,
                                onParcelaNumChange = { parcelaNumManual = it },
                                parcelaTxt = parcelaTxtManual,
                                onParcelaTxtChange = { parcelaTxtManual = it },
                                plantaNum = plantaNumManual,
                                onPlantaNumChange = { plantaNumManual = it },
                                plantaTxt = plantaTxtManual,
                                onPlantaTxtChange = { plantaTxtManual = it },
                                modelConfig = modelConfig,
                                variaveisAtivas = variaveisAtivas,
                                todasVariaveis = variaveisExtras,
                                onToggleVariavel = { variavel, ativa -> viewModel.alternarVariavelNaColeta(variavel, ativa) },
                                onDesativarTodas = { viewModel.ativarModoSomenteContagem() },
                                onAbrirConfigVariaveis = { mostrarTelaConfigEnsaio = true },
                                onModoImersivoChange = { modoImersivoAtivo = it },
                                classificacoesPersonalizadas = classificacoesAtivasModelo,
                                onAtualizarClassificacoes = { viewModel.atualizarClassificacoesAtivas(it) },
                                templateCultura = templateAtual,
                                configRendimento = configRendimento,
                                gpsAtivo = gpsAtivo,
                                onAbrirConfigRendimento = { mostrarDialogConfigRendimento = true },
                                onSalvarColeta = { uri, trat, parc, pl, situacao, motivo, totalObj, extras, caixas ->
                                    viewModel.salvarOuAtualizarRegistro(
                                        uri = uri,
                                        tratamento = trat,
                                        parcela = parc,
                                        planta = pl,
                                        situacao = situacao,
                                        motivoNaoAval = motivo,
                                        totalObjetos = totalObj,
                                        extras = extras,
                                        caixas = caixas
                                    )?.let { msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                    if (tipoNumPlanta && autoAvancarPlanta) plantaNumManual++
                                }
                            )
                        }
                    }
                }

                2 -> TelaEdicaoDados(
                    registros = listaRegistros,
                    coletaNome = coletaNome,
                    labelTratamento = labelTratamento,
                    labelParcela = labelParcela,
                    labelPlanta = labelPlanta,
                    variaveisExtrasConfig = variaveisExtras,
                    classesModeloExportacao = classesExportacao,
                    pontosFormulario = formularioPontos,
                    incluirPercentualClassesCsv = modelConfig.incluirPercentualClassesCsv,
                    templateCultura = templateAtual,
                    configRendimento = configRendimento,
                    onAbrirConfigRendimento = { mostrarDialogConfigRendimento = true },
                    onAbrirConfigVariaveis = { mostrarTelaConfigEnsaio = true },
                    onEditarCaixas = { reg ->
                        if (reg.uri != Uri.EMPTY) {
                            registroParaEdicao = reg
                        } else {
                            Toast.makeText(context, "Este registro não possui foto.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAtualizarDadosRegistro = { atualizado ->
                        if (viewModel.atualizarDadosRegistro(atualizado)) {
                            Toast.makeText(context, "Registro atualizado!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onExcluirRegistro = { reg ->
                        viewModel.excluirRegistro(reg)
                        Toast.makeText(context, "Registro excluído.", Toast.LENGTH_SHORT).show()
                    }
                )

                else -> TelaConfiguracaoModelo(
                    configAtual = modelConfig,
                    onModoImersivoChange = { modoImersivoAtivo = it },
                    onSalvar = { novaConfig ->
                        viewModel.salvarModelo(novaConfig)
                        modoColetaGuiada = formularioPontos.isNotEmpty()
                        abaSelecionada = 1
                        Toast.makeText(
                            context,
                            "Modelo \"${novaConfig.nomeExibicao}\" ativado!",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onGerarVariaveisDoModelo = { classesSelecionadas ->
                        viewModel.atualizarLabelsFiltroModelo(classesSelecionadas)
                        Toast.makeText(
                            context,
                            "${classesSelecionadas.size} classe(s) configuradas como colunas IA_ automáticas no CSV.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }
}
