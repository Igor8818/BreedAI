package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.MOTIVOS_NAO_AVALIAVEL
import com.example.myapplication.data.ModelConfig
import com.example.myapplication.data.PontoFormulario
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.VariavelExtra
import com.example.myapplication.userinterface.*
import com.example.myapplication.userinterface.util.ImportadorPlanilhaUniversal
import com.example.myapplication.userinterface.util.PersistenciaLocalRegistros
import com.example.myapplication.userinterface.util.copiarUriGaleriaParaArquivoLocal
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.task.vision.detector.Detection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val VERDE_ESCURO = Color(0xFF1B5E20)
private val VERDE_APP = Color(0xFF2E7D32)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BreedAIApp()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreedAIApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val listaRegistros = remember { mutableStateListOf<RegistroColeta>() }
    val formularioPontos = remember { mutableStateListOf<PontoFormulario>() }
    val variaveisExtras = remember { mutableStateListOf<VariavelExtra>() }
    val historicoTratamentos = remember { mutableStateListOf<String>() }

    // Ordem solicitada pelo Uilson: 0=Modelo IA, 1=Formulário (default), 2=Coleta Livre, 3=Edição dos Dados
    var abaSelecionada by rememberSaveable { mutableIntStateOf(1) }

    var coletaNome by rememberSaveable { mutableStateOf("Coleta_Campo_01") }
    var labelTratamento by rememberSaveable { mutableStateOf("Tratamento") }
    var labelParcela by rememberSaveable { mutableStateOf("Parcela") }
    var labelPlanta by rememberSaveable { mutableStateOf("Planta") }
    var tipoNumParcela by rememberSaveable { mutableStateOf(true) }
    var tipoNumPlanta by rememberSaveable { mutableStateOf(true) }

    var tratamentoManual by rememberSaveable { mutableStateOf("") }
    var parcelaNumManual by rememberSaveable { mutableIntStateOf(1) }
    var plantaNumManual by rememberSaveable { mutableIntStateOf(1) }
    var parcelaTxtManual by rememberSaveable { mutableStateOf("1") }
    var plantaTxtManual by rememberSaveable { mutableStateOf("1") }

    var latAtual by remember { mutableDoubleStateOf(0.0) }
    var lngAtual by remember { mutableDoubleStateOf(0.0) }

    var modelConfig by remember {
        mutableStateOf(PersistenciaLocalRegistros.carregarConfiguracaoModelo(context))
    }

    var registroParaEdicao by remember { mutableStateOf<RegistroColeta?>(null) }
    var mostrarDialogRenomearColeta by remember { mutableStateOf(false) }
    var mostrarDialogNovaColeta by remember { mutableStateOf(false) }
    var mostrarDialogGerenciarVariaveis by remember { mutableStateOf(false) }

    // Estado de importação manual de colunas (.xlsx / .csv)
    var planilhaPendente by remember { mutableStateOf<ImportadorPlanilhaUniversal.PlanilhaLida?>(null) }

    fun salvarMetadadosColeta() {
        PersistenciaLocalRegistros.salvarConfigColeta(
            context,
            PersistenciaLocalRegistros.ConfigColetaLocal(
                coletaNome = coletaNome,
                labelTratamento = labelTratamento,
                labelParcela = labelParcela,
                labelPlanta = labelPlanta,
                tipoNumParcela = tipoNumParcela,
                tipoNumPlanta = tipoNumPlanta
            )
        )
    }

    fun reconciliarFormularioComRegistros(
        pontos: MutableList<PontoFormulario>,
        registros: List<RegistroColeta>
    ) {
        for (i in pontos.indices) {
            val ponto = pontos[i]
            val idCompleto = "${ponto.tratamento}_P${ponto.parcela}_Pl${ponto.planta}"
            val reg = registros.firstOrNull { it.idCompleto == idCompleto }
            val novoPonto = if (reg != null) {
                val resumo = when (reg.situacaoPlanta) {
                    SituacaoPlanta.NORMAL -> "${reg.totalObjetos} obj."
                    SituacaoPlanta.SEM_FRUTOS -> "Sem Frutos (0)"
                    SituacaoPlanta.NAO_AVALIAVEL -> "N. Avaliável (${reg.motivoNaoAvaliavel})"
                }
                ponto.copy(coletado = true, resumoResultado = resumo)
            } else {
                ponto.copy(coletado = false, resumoResultado = "")
            }
            if (novoPonto != ponto) {
                pontos[i] = novoPonto
            }
        }
    }

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
                        formularioPontos.clear()
                        formularioPontos.addAll(pontos)
                        reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
                        PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
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
                    latAtual = it.latitude
                    lngAtual = it.longitude
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
        val cfg = PersistenciaLocalRegistros.carregarConfigColeta(context)
        coletaNome = cfg.coletaNome
        labelTratamento = cfg.labelTratamento
        labelParcela = cfg.labelParcela
        labelPlanta = cfg.labelPlanta
        tipoNumParcela = cfg.tipoNumParcela
        tipoNumPlanta = cfg.tipoNumPlanta

        val salvos = PersistenciaLocalRegistros.carregarRegistros(context)
        if (salvos.isNotEmpty() && listaRegistros.isEmpty()) {
            listaRegistros.addAll(salvos)
            salvos.map { it.tratamento }.filter { it.isNotBlank() }.distinct().forEach {
                if (!historicoTratamentos.contains(it)) historicoTratamentos.add(it)
            }
        }

        val (_, formSalvo) = PersistenciaLocalRegistros.carregarFormulario(context)
        if (formSalvo.isNotEmpty() && formularioPontos.isEmpty()) {
            formularioPontos.addAll(formSalvo)
            reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
            formSalvo.map { it.tratamento }.filter { it.isNotBlank() }.distinct().forEach {
                if (!historicoTratamentos.contains(it)) historicoTratamentos.add(it)
            }
        }

        val varsCarregadas = PersistenciaLocalRegistros.carregarVariaveisExtras(context)
        variaveisExtras.clear()
        variaveisExtras.addAll(varsCarregadas)

        launcherPermissoes.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    // ── DIÁLOGOS GLOBAIS ──

    if (mostrarDialogRenomearColeta) {
        var nomeTemp by remember(coletaNome) { mutableStateOf(coletaNome) }
        AlertDialog(
            onDismissRequest = { mostrarDialogRenomearColeta = false },
            title = { Text("Renomear Coleta Atual", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Altere o nome desta coleta (ex: TC-2001_Fev2026) sem apagar nenhum dado já registrado:",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                    OutlinedTextField(
                        value = nomeTemp,
                        onValueChange = { nomeTemp = it },
                        label = { Text("Nome da Coleta") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val novo = nomeTemp.trim()
                        if (novo.isNotBlank()) {
                            coletaNome = novo
                            for (i in listaRegistros.indices) {
                                listaRegistros[i] = listaRegistros[i].copy(coletaNome = novo)
                            }
                            PersistenciaLocalRegistros.salvarRegistros(context, listaRegistros)
                            if (formularioPontos.isNotEmpty()) {
                                PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, novo)
                            }
                            salvarMetadadosColeta()
                        }
                        mostrarDialogRenomearColeta = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("SALVAR NOME") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogRenomearColeta = false }) { Text("Cancelar") }
            }
        )
    }

    if (mostrarDialogNovaColeta) {
        var novoNome by remember { mutableStateOf("Coleta_${SimpleDateFormat("ddMM_HHmm", Locale.ROOT).format(Date())}") }
        var limparFormularioTambem by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { mostrarDialogNovaColeta = false },
            title = { Text("Iniciar Nova Coleta?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (listaRegistros.isNotEmpty()) {
                        Text(
                            "Atenção: há ${listaRegistros.size} registros salvos na coleta \"$coletaNome\". Exporte o CSV antes se ainda precisar deles.",
                            fontSize = 12.sp,
                            color = Color(0xFFC62828)
                        )
                    }
                    OutlinedTextField(
                        value = novoNome,
                        onValueChange = { novoNome = it },
                        label = { Text("Nome da Nova Coleta") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (formularioPontos.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = limparFormularioTambem, onCheckedChange = { limparFormularioTambem = it })
                            Text("Remover também a planilha carregada no Formulário", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nomeFinal = novoNome.trim().ifBlank { "Coleta_01" }
                        coletaNome = nomeFinal
                        listaRegistros.clear()
                        PersistenciaLocalRegistros.salvarRegistros(context, emptyList())
                        parcelaNumManual = 1
                        plantaNumManual = 1
                        parcelaTxtManual = "1"
                        plantaTxtManual = "1"
                        if (limparFormularioTambem) {
                            formularioPontos.clear()
                            PersistenciaLocalRegistros.limparFormulario(context)
                        } else {
                            reconciliarFormularioComRegistros(formularioPontos, emptyList())
                            PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, nomeFinal)
                        }
                        salvarMetadadosColeta()
                        mostrarDialogNovaColeta = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("INICIAR NOVA") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogNovaColeta = false }) { Text("Cancelar") }
            }
        )
    }

    if (mostrarDialogGerenciarVariaveis) {
        DialogGerenciarVariaveisExtras(
            variaveis = variaveisExtras,
            onToggleAtiva = { variavel, ativa ->
                val idx = variaveisExtras.indexOfFirst { it.id == variavel.id }
                if (idx >= 0) {
                    variaveisExtras[idx] = variaveisExtras[idx].copy(ativa = ativa)
                    PersistenciaLocalRegistros.salvarVariaveisExtras(context, variaveisExtras)
                }
            },
            onAdicionarVariavel = { nome, tipo, opcoes ->
                variaveisExtras.add(
                    VariavelExtra(
                        id = "var_${UUID.randomUUID().toString().take(8)}",
                        nome = nome,
                        tipo = tipo,
                        opcoes = opcoes,
                        ativa = true,
                        ordem = variaveisExtras.size + 1
                    )
                )
                PersistenciaLocalRegistros.salvarVariaveisExtras(context, variaveisExtras)
            },
            onRemoverVariavel = { variavel ->
                variaveisExtras.removeAll { it.id == variavel.id }
                PersistenciaLocalRegistros.salvarVariaveisExtras(context, variaveisExtras)
            },
            onDismiss = { mostrarDialogGerenciarVariaveis = false }
        )
    }

    if (planilhaPendente != null) {
        val pendente = planilhaPendente!!
        val cols = pendente.cabecalho
        var selTrat by remember(pendente) { mutableStateOf(cols.firstOrNull() ?: "") }
        var selParc by remember(pendente) { mutableStateOf(cols.getOrNull(1) ?: cols.firstOrNull() ?: "") }
        var selPlan by remember(pendente) { mutableStateOf(cols.getOrNull(2) ?: cols.firstOrNull() ?: "") }

        AlertDialog(
            onDismissRequest = { planilhaPendente = null },
            title = { Text("Vincular Colunas (${pendente.formato})", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Escolha qual coluna corresponde a cada identificador fixo:", fontSize = 13.sp, color = Color.Gray)
                    Text("$labelTratamento / Variedade:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    SeletorDropdownSimples(cols, selTrat) { selTrat = it }
                    Text("$labelParcela / Bloco:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    SeletorDropdownSimples(cols, selParc) { selParc = it }
                    Text("$labelPlanta / ID:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    SeletorDropdownSimples(cols, selPlan) { selPlan = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pontos = ImportadorPlanilhaUniversal.extrairPontosFormulario(
                            pendente,
                            cols.indexOf(selTrat),
                            cols.indexOf(selParc),
                            cols.indexOf(selPlan)
                        )
                        formularioPontos.clear()
                        formularioPontos.addAll(pontos)
                        reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
                        PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
                        planilhaPendente = null
                        Toast.makeText(context, "${pontos.size} pontos carregados!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("CONFIRMAR") }
            },
            dismissButton = {
                TextButton(onClick = { planilhaPendente = null }) { Text("Cancelar") }
            }
        )
    }

    // ── MODO DE REVISÃO DE REGISTRO DA EDIÇÃO DOS DADOS ──
    if (registroParaEdicao != null) {
        val reg = registroParaEdicao!!
        val detectorHelperRevisao = remember(modelConfig) { criarDetectorHelperBreedAI(context, modelConfig) }
        DisposableEffect(detectorHelperRevisao) {
            onDispose { detectorHelperRevisao.fechar() }
        }
        BackHandler { registroParaEdicao = null }

        TelaRevisaoCaptura(
            fotoUri = reg.uri,
            plantId = reg.idCompleto,
            deteccoesIniciais = emptyList(),
            bitmapWidthRef = 1f,
            bitmapHeightRef = 1f,
            detectorHelper = detectorHelperRevisao,
            labelsFiltro = modelConfig.labelsFiltro,
            caixasAuditadasIniciais = reg.caixas,
            modoSemFrutos = reg.situacaoPlanta == SituacaoPlanta.SEM_FRUTOS,
            onConfirmar = { totalObj, caixasNovas ->
                val idx = listaRegistros.indexOfFirst { it.idCompleto == reg.idCompleto }
                if (idx != -1) {
                    listaRegistros[idx] = listaRegistros[idx].copy(
                        diagnostico = totalObj.toString(),
                        totalObjetos = totalObj,
                        caixas = caixasNovas
                    )
                    PersistenciaLocalRegistros.salvarRegistros(context, listaRegistros)
                    reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
                    PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
                    Toast.makeText(context, "Registro atualizado ($totalObj objetos).", Toast.LENGTH_SHORT).show()
                }
                registroParaEdicao = null
            },
            onRefazer = { registroParaEdicao = null }
        )
        return
    }

    // ── SCAFFOLD PRINCIPAL COM MENU SUPERIOR DESTACADO (SEM MENU HAMBÚRGUER) ──
    var modoImersivoAtivo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (!modoImersivoAtivo) {
                Surface(shadowElevation = 4.dp, color = VERDE_ESCURO) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    "BreedAI",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp
                                )
                                Surface(
                                    onClick = { mostrarDialogRenomearColeta = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.White.copy(alpha = 0.16f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            coletaNome,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Default.Edit, "Renomear coleta", tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    onClick = { abaSelecionada = 0 },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF1B5E20),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        "IA: ${modelConfig.nomeExibicao}",
                                        color = Color(0xFFC8E6C9),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Surface(
                                    onClick = { mostrarDialogNovaColeta = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFFFEB3B).copy(alpha = 0.22f)
                                ) {
                                    Text(
                                        "+ Nova",
                                        color = Color(0xFFFFEB3B),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        PrimaryTabRow(
                            selectedTabIndex = abaSelecionada,
                            containerColor = VERDE_APP,
                            contentColor = Color.White,
                            indicator = {
                                TabRowDefaults.PrimaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(abaSelecionada),
                                    color = Color(0xFFFFEB3B),
                                    height = 4.dp
                                )
                            }
                        ) {
                            Tab(
                                selected = abaSelecionada == 0,
                                onClick = { abaSelecionada = 0 },
                                icon = { Icon(Icons.Default.SmartToy, null, modifier = Modifier.size(18.dp)) },
                                text = { Text("Modelo IA", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            )
                            Tab(
                                selected = abaSelecionada == 1,
                                onClick = { abaSelecionada = 1 },
                                icon = { Icon(Icons.Default.Assignment, null, modifier = Modifier.size(18.dp)) },
                                text = { Text("Formulário", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            )
                            Tab(
                                selected = abaSelecionada == 2,
                                onClick = { abaSelecionada = 2 },
                                icon = { Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(18.dp)) },
                                text = { Text("Coleta Livre", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            )
                            Tab(
                                selected = abaSelecionada == 3,
                                onClick = { abaSelecionada = 3 },
                                icon = { Icon(Icons.Default.TableView, null, modifier = Modifier.size(18.dp)) },
                                text = { Text("Edição Dados", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            )
                        }
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
            val variaveisAtivas = remember(variaveisExtras.toList()) {
                variaveisExtras.filter { it.ativa }
            }

            fun salvarOuAtualizarRegistro(
                uri: Uri,
                tratamento: String,
                parcela: String,
                planta: String,
                situacao: SituacaoPlanta,
                motivoNaoAval: String,
                totalObjetos: Int,
                extras: Map<String, String>,
                caixas: List<CaixaAuditavel>
            ) {
                val data = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(Date())
                val idCompleto = "${tratamento}_P${parcela}_Pl${planta}"
                if (tratamento.isNotBlank() && !historicoTratamentos.contains(tratamento)) {
                    historicoTratamentos.add(tratamento)
                }
                val diagTexto = when (situacao) {
                    SituacaoPlanta.NORMAL -> totalObjetos.toString()
                    SituacaoPlanta.SEM_FRUTOS -> "Sem Frutos (0)"
                    SituacaoPlanta.NAO_AVALIAVEL -> "Não avaliável ($motivoNaoAval)"
                }
                listaRegistros.removeAll { it.idCompleto == idCompleto }
                listaRegistros.add(
                    RegistroColeta(
                        uri = uri,
                        idCompleto = idCompleto,
                        coletaNome = coletaNome,
                        tratamento = tratamento,
                        parcela = parcela,
                        planta = planta,
                        situacaoPlanta = situacao,
                        motivoNaoAvaliavel = if (situacao == SituacaoPlanta.NAO_AVALIAVEL) motivoNaoAval else "",
                        diagnostico = diagTexto,
                        data = data,
                        lat = latAtual,
                        lng = lngAtual,
                        totalObjetos = if (situacao == SituacaoPlanta.NORMAL) totalObjetos else 0,
                        variaveisExtras = extras.filterValues { it.isNotBlank() },
                        caixas = if (situacao == SituacaoPlanta.NORMAL) caixas else emptyList()
                    )
                )
                PersistenciaLocalRegistros.salvarRegistros(context, listaRegistros)
                reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
                if (formularioPontos.isNotEmpty()) {
                    PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
                }
            }

            when (abaSelecionada) {
                0 -> TelaConfiguracaoModelo(
                    configAtual = modelConfig,
                    onSalvar = { novaConfig ->
                        modelConfig = novaConfig
                        PersistenciaLocalRegistros.salvarConfiguracaoModelo(context, novaConfig)
                        abaSelecionada = 1
                        Toast.makeText(
                            context,
                            "Modelo \"${novaConfig.nomeExibicao}\" ativado!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                1 -> TelaFormularioFieldBook(
                    pontos = formularioPontos,
                    registrosSalvos = listaRegistros,
                    labelTratamento = labelTratamento,
                    labelParcela = labelParcela,
                    labelPlanta = labelPlanta,
                    modelConfig = modelConfig,
                    variaveisAtivas = variaveisAtivas,
                    onAbrirConfigVariaveis = { mostrarDialogGerenciarVariaveis = true },
                    onCarregarPlanilhaClick = { launcherPlanilha.launch("*/*") },
                    onLimparFormularioClick = {
                        formularioPontos.clear()
                        PersistenciaLocalRegistros.limparFormulario(context)
                    },
                    onModoImersivoChange = { modoImersivoAtivo = it },
                    onRegistrarPonto = { uri, ponto, situacao, motivo, totalObj, extras, caixas ->
                        salvarOuAtualizarRegistro(
                            uri = uri,
                            tratamento = ponto.tratamento,
                            parcela = ponto.parcela,
                            planta = ponto.planta,
                            situacao = situacao,
                            motivoNaoAval = motivo,
                            totalObjetos = totalObj,
                            extras = extras,
                            caixas = caixas
                        )
                    }
                )

                2 -> TelaColetaLivreFieldBook(
                    label1 = labelTratamento,
                    label2 = labelParcela,
                    label3 = labelPlanta,
                    isNum2 = tipoNumParcela,
                    isNum3 = tipoNumPlanta,
                    onConfigLabels = { l1, l2, l3, n2, n3 ->
                        labelTratamento = l1
                        labelParcela = l2
                        labelPlanta = l3
                        tipoNumParcela = n2
                        tipoNumPlanta = n3
                        salvarMetadadosColeta()
                    },
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
                    onAbrirConfigVariaveis = { mostrarDialogGerenciarVariaveis = true },
                    onModoImersivoChange = { modoImersivoAtivo = it },
                    onSalvarColeta = { uri, trat, parc, pl, situacao, motivo, totalObj, extras, caixas ->
                        salvarOuAtualizarRegistro(
                            uri = uri,
                            tratamento = trat,
                            parcela = parc,
                            planta = pl,
                            situacao = situacao,
                            motivoNaoAval = motivo,
                            totalObjetos = totalObj,
                            extras = extras,
                            caixas = caixas
                        )
                        if (tipoNumPlanta) plantaNumManual++
                    }
                )

                3 -> TelaEdicaoDados(
                    registros = listaRegistros,
                    coletaNome = coletaNome,
                    labelTratamento = labelTratamento,
                    labelParcela = labelParcela,
                    labelPlanta = labelPlanta,
                    variaveisExtrasConfig = variaveisExtras,
                    onAbrirConfigVariaveis = { mostrarDialogGerenciarVariaveis = true },
                    onEditarCaixas = { reg ->
                        if (reg.uri != Uri.EMPTY) {
                            registroParaEdicao = reg
                        } else {
                            Toast.makeText(context, "Este registro não possui foto.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAtualizarDadosRegistro = { atualizado ->
                        val idx = listaRegistros.indexOfFirst { it.idCompleto == atualizado.idCompleto }
                        if (idx >= 0) {
                            listaRegistros[idx] = atualizado
                            PersistenciaLocalRegistros.salvarRegistros(context, listaRegistros)
                            reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
                            if (formularioPontos.isNotEmpty()) {
                                PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
                            }
                            Toast.makeText(context, "Registro atualizado!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onExcluirRegistro = { reg ->
                        listaRegistros.remove(reg)
                        PersistenciaLocalRegistros.salvarRegistros(context, listaRegistros)
                        reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
                        if (formularioPontos.isNotEmpty()) {
                            PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
                        }
                        Toast.makeText(context, "Registro excluído.", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════
// ABA 1: FORMULÁRIO (MODO ROTA / FIELD BOOK COM PARCELA & PLANTA FIXAS)
// ════════════════════════════════════════════════════
@Composable
fun TelaFormularioFieldBook(
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
    ) -> Unit
) {
    val context = LocalContext.current

    val primeiroPendente = remember(pontos.size) {
        val idx = pontos.indexOfFirst { !it.coletado }
        if (idx >= 0) idx else 0
    }
    var indiceAtual by rememberSaveable(pontos.size) { mutableIntStateOf(primeiroPendente) }

    var cameraAtiva by remember { mutableStateOf(false) }
    var fotoParaRevisao by remember { mutableStateOf<Uri?>(null) }
    var caixasCapturadas by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var bitmapWCapturado by remember { mutableFloatStateOf(1f) }
    var bitmapHCapturado by remember { mutableFloatStateOf(1f) }

    var situacaoAtual by remember { mutableStateOf(SituacaoPlanta.NORMAL) }
    var motivoAtual by remember { mutableStateOf(MOTIVOS_NAO_AVALIAVEL.first()) }
    val valoresExtrasAtual = remember { mutableStateMapOf<String, String>() }

    // Sincroniza situação e variáveis extras ao navegar entre plantas ou reiniciar coleta
    LaunchedEffect(indiceAtual, pontos.size, registrosSalvos.size) {
        if (indiceAtual in pontos.indices) {
            val pt = pontos[indiceAtual]
            val id = "${pt.tratamento}_P${pt.parcela}_Pl${pt.planta}"
            val salvo = registrosSalvos.firstOrNull { it.idCompleto == id }
            valoresExtrasAtual.clear()
            if (salvo != null) {
                situacaoAtual = salvo.situacaoPlanta
                motivoAtual = salvo.motivoNaoAvaliavel.ifBlank { MOTIVOS_NAO_AVALIAVEL.first() }
                valoresExtrasAtual.putAll(salvo.variaveisExtras)
            } else {
                situacaoAtual = SituacaoPlanta.NORMAL
                motivoAtual = MOTIVOS_NAO_AVALIAVEL.first()
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

    val imageCapture = remember {
        ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
    }

    val detectorHelper = remember(modelConfig) { criarDetectorHelperBreedAI(context, modelConfig) }
    DisposableEffect(detectorHelper) {
        onDispose { detectorHelper.fechar() }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Assignment, null, modifier = Modifier.size(64.dp), tint = VERDE_APP)
            Spacer(Modifier.height(14.dp))
            Text("Nenhum formulário carregado", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Importe uma planilha do seu experimento (.xlsx do Excel ou .csv/.tsv) com as colunas de $labelTratamento, $labelParcela e $labelPlanta.",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onCarregarPlanilhaClick,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("CARREGAR PLANILHA (.XLSX OU .CSV)", fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    if (indiceAtual !in pontos.indices) indiceAtual = 0
    val pontoAtual = pontos[indiceAtual]
    val idPonto = "${pontoAtual.tratamento}_P${pontoAtual.parcela}_Pl${pontoAtual.planta}"

    if (fotoParaRevisao != null) {
        TelaRevisaoCaptura(
            fotoUri = fotoParaRevisao!!,
            plantId = idPonto,
            deteccoesIniciais = caixasCapturadas,
            bitmapWidthRef = bitmapWCapturado,
            bitmapHeightRef = bitmapHCapturado,
            detectorHelper = detectorHelper,
            labelsFiltro = modelConfig.labelsFiltro,
            modoSemFrutos = situacaoAtual == SituacaoPlanta.SEM_FRUTOS,
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
                if (indiceAtual < pontos.size - 1) indiceAtual++
                else Toast.makeText(context, "Última planta do formulário concluída!", Toast.LENGTH_SHORT).show()
            },
            onRefazer = { fotoParaRevisao = null }
        )
        return
    }

    if (cameraAtiva) {
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

    val totalColetados = pontos.count { it.coletado }

    Column(modifier = Modifier.fillMaxSize()) {
        // BARRA FIXA DE NAVEGAÇÃO (PARCELA / PLANTA / TRATAMENTO SEMPRE VISÍVEIS)
        Surface(
            color = if (pontoAtual.coletado) Color(0xFFE8F5E9) else Color(0xFFFFF8E1),
            shadowElevation = 2.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { if (indiceAtual > 0) indiceAtual-- },
                        enabled = indiceAtual > 0
                    ) {
                        Icon(
                            Icons.Default.ArrowBackIos,
                            "Planta anterior",
                            tint = if (indiceAtual > 0) VERDE_APP else Color.Gray
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            pontoAtual.tratamento,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = VERDE_ESCURO
                        )
                        Text(
                            "$labelParcela ${pontoAtual.parcela}  •  $labelPlanta ${pontoAtual.planta}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.Black
                        )
                        Text(
                            "Item ${indiceAtual + 1}/${pontos.size} • Concluídos: $totalColetados/${pontos.size}",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }

                    IconButton(
                        onClick = { if (indiceAtual < pontos.size - 1) indiceAtual++ },
                        enabled = indiceAtual < pontos.size - 1
                    ) {
                        Icon(
                            Icons.Default.ArrowForwardIos,
                            "Próxima planta",
                            tint = if (indiceAtual < pontos.size - 1) VERDE_APP else Color.Gray
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = { (indiceAtual + 1).toFloat() / pontos.size.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = VERDE_APP
                )

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pontoAtual.coletado) {
                        Text("Salvo: ${pontoAtual.resumoResultado}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VERDE_APP)
                    } else {
                        Text("Pendente", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE65100))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Próx. pendente",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_APP,
                            modifier = Modifier.clickable {
                                val prox = pontos.indexOfFirst { !it.coletado }
                                if (prox >= 0) indiceAtual = prox
                                else Toast.makeText(context, "Todas as plantas foram coletadas!", Toast.LENGTH_SHORT).show()
                            }
                        )
                        Text(
                            "• Trocar planilha",
                            fontSize = 11.sp,
                            color = Color(0xFF1565C0),
                            modifier = Modifier.clickable { onCarregarPlanilhaClick() }
                        )
                        Text(
                            "• Limpar",
                            fontSize = 11.sp,
                            color = Color(0xFFC62828),
                            modifier = Modifier.clickable {
                                onLimparFormularioClick()
                                indiceAtual = 0
                            }
                        )
                    }
                }
            }
        }

        // CORPO ROLÁVEL: DETALHES DA PLANILHA -> SITUAÇÃO -> VARIÁVEIS -> FOTO
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (pontoAtual.colunasPlanilha.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F6F4)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Dados da Planilha:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text(
                            pontoAtual.colunasPlanilha.entries.joinToString("  |  ") { "${it.key}: ${it.value}" },
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    }
                }
            }

            SeletorSituacaoPlantaCard(
                situacao = situacaoAtual,
                onSituacaoChange = { situacaoAtual = it },
                motivoNaoAvaliavel = motivoAtual,
                onMotivoChange = { motivoAtual = it }
            )

            BlocoVariaveisExtrasCard(
                variaveisAtivas = variaveisAtivas,
                valoresAtuais = valoresExtrasAtual,
                onAbrirConfigVariaveis = onAbrirConfigVariaveis
            )

            Spacer(Modifier.height(4.dp))

            when (situacaoAtual) {
                SituacaoPlanta.NORMAL -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { launcherGaleria.launch("image/*") },
                            modifier = Modifier.weight(1f).height(54.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP)
                        ) {
                            Icon(Icons.Default.Image, null)
                            Spacer(Modifier.width(6.dp))
                            Text("GALERIA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { cameraAtiva = true },
                            modifier = Modifier.weight(1.4f).height(54.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                        ) {
                            Icon(Icons.Default.PhotoCamera, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (pontoAtual.coletado) "REFAZER FOTO" else "ABRIR CÂMERA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                SituacaoPlanta.SEM_FRUTOS -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { launcherGaleria.launch("image/*") },
                            modifier = Modifier.weight(1f).height(54.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF6C00))
                        ) {
                            Icon(Icons.Default.Image, null)
                            Spacer(Modifier.width(6.dp))
                            Text("GALERIA (0)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { cameraAtiva = true },
                            modifier = Modifier.weight(1.5f).height(54.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00))
                        ) {
                            Icon(Icons.Default.PhotoCamera, null)
                            Spacer(Modifier.width(6.dp))
                            Text("FOTO SEM FRUTOS (0)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                SituacaoPlanta.NAO_AVALIAVEL -> {
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
                            if (indiceAtual < pontos.size - 1) indiceAtual++
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) {
                        Icon(Icons.Default.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("SALVAR NÃO AVALIÁVEL ($motivoAtual) ▶", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════
// ABA 2: COLETA LIVRE — Ordem exata: Contador (Parcela/Planta) -> Situação -> Variáveis -> Foto
// ════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaColetaLivreFieldBook(
    label1: String,
    label2: String,
    label3: String,
    isNum2: Boolean,
    isNum3: Boolean,
    onConfigLabels: (String, String, String, Boolean, Boolean) -> Unit,
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
    ) -> Unit
) {
    val context = LocalContext.current

    var cameraAtiva by remember { mutableStateOf(false) }
    var menuTratamentosExpandido by remember { mutableStateOf(false) }
    var mostrarDialogLabels by remember { mutableStateOf(false) }

    var fotoParaRevisao by remember { mutableStateOf<Uri?>(null) }
    var caixasCapturadas by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var bitmapWCapturado by remember { mutableFloatStateOf(1f) }
    var bitmapHCapturado by remember { mutableFloatStateOf(1f) }

    var situacaoAtual by rememberSaveable { mutableStateOf(SituacaoPlanta.NORMAL) }
    var motivoAtual by rememberSaveable { mutableStateOf(MOTIVOS_NAO_AVALIAVEL.first()) }
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

    val imageCapture = remember {
        ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
    }

    val detectorHelper = remember(modelConfig) { criarDetectorHelperBreedAI(context, modelConfig) }
    DisposableEffect(detectorHelper) {
        onDispose { detectorHelper.fechar() }
    }

    val pVal = if (isNum2) parcelaNum.toString() else parcelaTxt.trim()
    val plVal = if (isNum3) plantaNum.toString() else plantaTxt.trim()
    val tratVal = tratamento.trim()
    val identificacaoValida = tratVal.isNotBlank() && pVal.isNotBlank() && plVal.isNotBlank()
    val idAtual = "${tratVal}_P${pVal}_Pl${plVal}"

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
            labelsFiltro = modelConfig.labelsFiltro,
            modoSemFrutos = situacaoAtual == SituacaoPlanta.SEM_FRUTOS,
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
            onRefazer = { fotoParaRevisao = null }
        )
        return
    }

    if (cameraAtiva) {
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

    if (mostrarDialogLabels) {
        var t1 by remember { mutableStateOf(label1) }
        var t2 by remember { mutableStateOf(label2) }
        var t3 by remember { mutableStateOf(label3) }
        var n2 by remember { mutableStateOf(isNum2) }
        var n3 by remember { mutableStateOf(isNum3) }

        AlertDialog(
            onDismissRequest = { mostrarDialogLabels = false },
            title = { Text("Nomes dos Identificadores", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = t1, onValueChange = { t1 = it }, label = { Text("Campo 1 (Ex: Tratamento / Variedade)") })
                    HorizontalDivider()
                    OutlinedTextField(value = t2, onValueChange = { t2 = it }, label = { Text("Campo 2 (Ex: Parcela / Bloco)") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = n2, onCheckedChange = { n2 = it })
                        Text("Usar contador numérico (+/-)", fontSize = 13.sp)
                    }
                    HorizontalDivider()
                    OutlinedTextField(value = t3, onValueChange = { t3 = it }, label = { Text("Campo 3 (Ex: Planta)") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = n3, onCheckedChange = { n3 = it })
                        Text("Usar contador numérico (+/-)", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfigLabels(
                            t1.ifBlank { "Tratamento" },
                            t2.ifBlank { "Parcela" },
                            t3.ifBlank { "Planta" },
                            n2,
                            n3
                        )
                        mostrarDialogLabels = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("SALVAR") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogLabels = false }) { Text("Cancelar") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── 1. IDENTIFICAÇÃO E CONTADOR FIXO (PARCELA / PLANTA / TRATAMENTO) ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)),
            border = BorderStroke(1.dp, Color(0xFFC5E1A5))
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "1. Contador e Identificação",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = VERDE_ESCURO
                    )
                    IconButton(onClick = { mostrarDialogLabels = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Settings, "Renomear identificadores", tint = VERDE_APP, modifier = Modifier.size(18.dp))
                    }
                }

                // Contadores Parcela e Planta lado a lado
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(label2.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            if (isNum2) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { if (parcelaNum > 1) onParcelaNumChange(parcelaNum - 1) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.RemoveCircle, null, tint = Color(0xFFC62828), modifier = Modifier.size(26.dp))
                                    }
                                    Text("$parcelaNum", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp))
                                    IconButton(
                                        onClick = { onParcelaNumChange(parcelaNum + 1) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.AddCircle, null, tint = VERDE_APP, modifier = Modifier.size(26.dp))
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = parcelaTxt,
                                    onValueChange = onParcelaTxtChange,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(label3.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            if (isNum3) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { if (plantaNum > 1) onPlantaNumChange(plantaNum - 1) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.RemoveCircle, null, tint = Color(0xFFC62828), modifier = Modifier.size(26.dp))
                                    }
                                    Text("$plantaNum", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = VERDE_ESCURO, modifier = Modifier.padding(horizontal = 8.dp))
                                    IconButton(
                                        onClick = { onPlantaNumChange(plantaNum + 1) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.AddCircle, null, tint = VERDE_APP, modifier = Modifier.size(26.dp))
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = plantaTxt,
                                    onValueChange = onPlantaTxtChange,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Campo de Tratamento (com autocomplete local dos já digitados, sem CEPEC)
                val sugestoes = remember(tratamento, historicoTratamentos.toList()) {
                    val b = tratamento.trim()
                    if (b.isBlank()) historicoTratamentos.take(20)
                    else historicoTratamentos.filter { it.contains(b, ignoreCase = true) }.take(20)
                }

                ExposedDropdownMenuBox(
                    expanded = menuTratamentosExpandido && sugestoes.isNotEmpty(),
                    onExpandedChange = {
                        if (sugestoes.isNotEmpty()) menuTratamentosExpandido = !menuTratamentosExpandido
                    }
                ) {
                    OutlinedTextField(
                        value = tratamento,
                        onValueChange = {
                            onTratamentoChange(it)
                            menuTratamentosExpandido = sugestoes.isNotEmpty()
                        },
                        label = { Text(label1) },
                        placeholder = { Text("Ex: T-01, Var-A, Controle...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                        leadingIcon = { Icon(Icons.Default.LocalOffer, null, tint = VERDE_APP) },
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 2. SITUAÇÃO DA PLANTA ──
        SeletorSituacaoPlantaCard(
            situacao = situacaoAtual,
            onSituacaoChange = { situacaoAtual = it },
            motivoNaoAvaliavel = motivoAtual,
            onMotivoChange = { motivoAtual = it }
        )

        // ── 3. VARIÁVEIS EXTRAS (FIELD BOOK) ──
        BlocoVariaveisExtrasCard(
            variaveisAtivas = variaveisAtivas,
            valoresAtuais = valoresExtras,
            onAbrirConfigVariaveis = onAbrirConfigVariaveis
        )

        // ── 4. FOTO / SALVAR ──
        if (!identificacaoValida) {
            Text(
                "Preencha \"$label1\", \"$label2\" e \"$label3\" para liberar o registro.",
                fontSize = 12.sp,
                color = Color(0xFFC62828),
                fontWeight = FontWeight.Medium
            )
        }

        when (situacaoAtual) {
            SituacaoPlanta.NORMAL -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { launcherGaleria.launch("image/*") },
                        enabled = identificacaoValida,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP)
                    ) {
                        Icon(Icons.Default.Image, null)
                        Spacer(Modifier.width(6.dp))
                        Text("GALERIA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { cameraAtiva = true },
                        enabled = identificacaoValida,
                        modifier = Modifier.weight(1.4f).height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                    ) {
                        Icon(Icons.Default.PhotoCamera, null)
                        Spacer(Modifier.width(6.dp))
                        Text("ABRIR CÂMERA", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            SituacaoPlanta.SEM_FRUTOS -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { launcherGaleria.launch("image/*") },
                        enabled = identificacaoValida,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF6C00))
                    ) {
                        Icon(Icons.Default.Image, null)
                        Spacer(Modifier.width(6.dp))
                        Text("GALERIA (0)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { cameraAtiva = true },
                        enabled = identificacaoValida,
                        modifier = Modifier.weight(1.5f).height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00))
                    ) {
                        Icon(Icons.Default.PhotoCamera, null)
                        Spacer(Modifier.width(6.dp))
                        Text("FOTO SEM FRUTOS (0)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            SituacaoPlanta.NAO_AVALIAVEL -> {
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
                    enabled = identificacaoValida,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) {
                    Icon(Icons.Default.CheckCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("REGISTRAR NÃO AVALIÁVEL ($motivoAtual) E AVANÇAR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeletorDropdownSimples(
    opcoes: List<String>,
    selecionado: String,
    onSelecionadoChange: (String) -> Unit
) {
    var expandido by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expandido,
        onExpandedChange = { expandido = !expandido }
    ) {
        OutlinedTextField(
            value = selecionado,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandido) }
        )
        ExposedDropdownMenu(expanded = expandido, onDismissRequest = { expandido = false }) {
            opcoes.forEach { opcao ->
                DropdownMenuItem(
                    text = { Text(opcao) },
                    onClick = {
                        onSelecionadoChange(opcao)
                        expandido = false
                    }
                )
            }
        }
    }
}
