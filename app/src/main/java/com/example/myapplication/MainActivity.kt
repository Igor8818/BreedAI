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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.style.TextOverflow
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

    // Abas na barra inferior limpa (igual ao CacauApp): 0=Coleta Livre, 1=Modo Rota, 2=Dados, 3=Modelo IA
    var abaSelecionada by rememberSaveable { mutableIntStateOf(0) }

    var coletaNome by rememberSaveable { mutableStateOf("Coleta_Campo_01") }
    var labelTratamento by rememberSaveable { mutableStateOf("Tratamento") }
    var labelParcela by rememberSaveable { mutableStateOf("Parcela") }
    var labelPlanta by rememberSaveable { mutableStateOf("Planta") }
    var tipoNumParcela by rememberSaveable { mutableStateOf(true) }
    var tipoNumPlanta by rememberSaveable { mutableStateOf(true) }

    var tratamentoManual by rememberSaveable { mutableStateOf("T-01") }
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

    var mostrarDialogMenuEnsaio by remember { mutableStateOf(false) }

    if (mostrarDialogMenuEnsaio) {
        var nomeTemp by remember(coletaNome) { mutableStateOf(coletaNome) }
        AlertDialog(
            onDismissRequest = { mostrarDialogMenuEnsaio = false },
            title = { Text("Configurações da Coleta", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nomeTemp,
                        onValueChange = { nomeTemp = it },
                        label = { Text("Nome do Ensaio / Coleta") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            mostrarDialogMenuEnsaio = false
                            mostrarDialogGerenciarVariaveis = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP)
                    ) {
                        Icon(Icons.Default.PlaylistAdd, null)
                        Spacer(Modifier.width(8.dp))
                        Text("VARIÁVEIS EXTRAS (${variaveisExtras.count { it.ativa }})")
                    }
                    OutlinedButton(
                        onClick = {
                            mostrarDialogMenuEnsaio = false
                            mostrarDialogNovaColeta = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                    ) {
                        Icon(Icons.Default.AddCircleOutline, null)
                        Spacer(Modifier.width(8.dp))
                        Text("INICIAR NOVA COLETA (ZERAR)")
                    }
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
                        mostrarDialogMenuEnsaio = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("SALVAR") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogMenuEnsaio = false }) { Text("Fechar") }
            }
        )
    }

    // ── SCAFFOLD PRINCIPAL COM BARRA INFERIOR LIMPA (MESMA ERGONOMIA DO CACAUAPP) ──
    var modoImersivoAtivo by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            if (!modoImersivoAtivo) {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = abaSelecionada == 0,
                        onClick = { abaSelecionada = 0 },
                        icon = { Icon(Icons.Default.PhotoCamera, null) },
                        label = { Text("Coleta Livre", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                    NavigationBarItem(
                        selected = abaSelecionada == 1,
                        onClick = { abaSelecionada = 1 },
                        icon = { Icon(Icons.Default.Route, null) },
                        label = { Text("Modo Rota", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                    NavigationBarItem(
                        selected = abaSelecionada == 2,
                        onClick = { abaSelecionada = 2 },
                        icon = { Icon(Icons.Default.Collections, null) },
                        label = { Text("Dados (${listaRegistros.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                    NavigationBarItem(
                        selected = abaSelecionada == 3,
                        onClick = { abaSelecionada = 3 },
                        icon = { Icon(Icons.Default.SmartToy, null) },
                        label = { Text("Modelo IA", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { mostrarDialogMenuEnsaio = true },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { Text("Ensaio", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
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
                0 -> TelaColetaLivreFieldBook(
                    ensaio = coletaNome,
                    registrosSalvos = listaRegistros,
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

                1 -> TelaFormularioFieldBook(
                    ensaio = coletaNome,
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

                2 -> TelaEdicaoDados(
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

                3 -> TelaConfiguracaoModelo(
                    configAtual = modelConfig,
                    onSalvar = { novaConfig ->
                        modelConfig = novaConfig
                        PersistenciaLocalRegistros.salvarConfiguracaoModelo(context, novaConfig)
                        abaSelecionada = 0
                        Toast.makeText(
                            context,
                            "Modelo \"${novaConfig.nomeExibicao}\" ativado!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════
// ABA 1: MODO ROTA (LAYOUT LIMPO IDÊNTICO AO CACAUAPP + PLANILHA UNIVERSAL)
// ════════════════════════════════════════════════════
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
    var mostrarDialogNaoAvaliavel by remember { mutableStateOf(false) }
    var mostrarDialogVariaveisPreenchimento by remember { mutableStateOf(false) }
    val valoresExtrasAtual = remember { mutableStateMapOf<String, String>() }

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
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.TableView, null, modifier = Modifier.size(64.dp), tint = VERDE_APP)
            Spacer(Modifier.height(16.dp))
            Text("Nenhuma Rota Carregada", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Importe uma planilha (.xlsx ou .csv) com as colunas de $labelTratamento, $labelParcela e $labelPlanta para navegar planta a planta.",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onCarregarPlanilhaClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("ESCOLHER ARQUIVO CSV / XLSX", fontWeight = FontWeight.Bold)
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
                situacaoAtual = SituacaoPlanta.NORMAL
                if (indiceAtual < pontos.size - 1) indiceAtual++
                else Toast.makeText(context, "Rota Finalizada!", Toast.LENGTH_SHORT).show()
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

    if (mostrarDialogNaoAvaliavel) {
        AlertDialog(
            onDismissRequest = { mostrarDialogNaoAvaliavel = false },
            title = { Text("Planta Não Coletável", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selecione o motivo para registrar na planilha:", fontSize = 13.sp, color = Color.Gray)
                    MOTIVOS_NAO_AVALIAVEL.forEach { op ->
                        Surface(
                            onClick = { motivoAtual = op },
                            shape = RoundedCornerShape(8.dp),
                            color = if (motivoAtual == op) Color(0xFFFFEBEE) else Color(0xFFF5F5F5),
                            border = BorderStroke(1.dp, if (motivoAtual == op) Color(0xFFC62828) else Color.LightGray),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = motivoAtual == op, onClick = { motivoAtual = op })
                                Spacer(Modifier.width(6.dp))
                                Text(op, fontWeight = if (motivoAtual == op) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        mostrarDialogNaoAvaliavel = false
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text("CONFIRMAR") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogNaoAvaliavel = false }) { Text("Cancelar") }
            }
        )
    }

    if (mostrarDialogVariaveisPreenchimento) {
        AlertDialog(
            onDismissRequest = { mostrarDialogVariaveisPreenchimento = false },
            title = { Text("Variáveis da Planta", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    BlocoVariaveisExtrasCard(
                        variaveisAtivas = variaveisAtivas,
                        valoresAtuais = valoresExtrasAtual,
                        onAbrirConfigVariaveis = {
                            mostrarDialogVariaveisPreenchimento = false
                            onAbrirConfigVariaveis()
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { mostrarDialogVariaveisPreenchimento = false },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("CONCLUÍDO") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Sessão em Rota: $ensaio",
                fontWeight = FontWeight.Bold,
                color = VERDE_ESCURO,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCarregarPlanilhaClick) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Trocar", fontSize = 12.sp)
                }
                TextButton(
                    onClick = { onLimparFormularioClick(); indiceAtual = 0 },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Limpar", fontSize = 12.sp)
                }
            }
        }

        // Badge modelo (compacto em 1 linha)
        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFE8F5E9), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, null, tint = VERDE_APP, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${modelConfig.nomeExibicao} • ${(modelConfig.threshold * 100).toInt()}% conf.",
                    fontSize = 12.sp,
                    color = VERDE_APP,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Ordem na Planilha: #$ordemExibicao  •  Planta ${indiceAtual + 1} de ${pontos.size}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = { mostrarDialogVariaveisPreenchimento = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.PlaylistAdd, null, modifier = Modifier.size(16.dp), tint = VERDE_APP)
                Spacer(Modifier.width(4.dp))
                Text("Variáveis (${valoresExtrasAtual.count { it.value.isNotBlank() }}/${variaveisAtivas.size})", fontSize = 12.sp, color = VERDE_APP)
            }
        }
        LinearProgressIndicator(
            progress = { (indiceAtual + 1).toFloat() / pontos.size.toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = VERDE_APP
        )
        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (pontoAtual.coletado) Color(0xFFE8F5E9) else Color(0xFFFFF3E0))
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (pontoAtual.coletado) "PLANTA #$ordemExibicao CONCLUÍDA (EDITÁVEL)" else "PLANTA #$ordemExibicao DA PLANILHA",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (pontoAtual.coletado) VERDE_APP else Color(0xFFE65100)
                )
                Spacer(Modifier.height(12.dp))
                Text(labelTratamento.uppercase(), fontSize = 12.sp, color = Color.Gray)
                Text(
                    pontoAtual.tratamento.ifBlank { "[VAZIO]" },
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(labelParcela.uppercase(), fontSize = 12.sp, color = Color.Gray)
                        Text(pontoAtual.parcela, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(labelPlanta.uppercase(), fontSize = 12.sp, color = Color.Gray)
                        Text(pontoAtual.planta, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (pontoAtual.coletado) {
                    Spacer(Modifier.height(8.dp))
                    Text("Resultado: ${pontoAtual.resumoResultado}", fontWeight = FontWeight.Bold, color = VERDE_APP)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    situacaoAtual = SituacaoPlanta.SEM_FRUTOS
                    cameraAtiva = true
                },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF6C00)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.HideImage, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("SEM FRUTOS (0)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            OutlinedButton(
                onClick = { mostrarDialogNaoAvaliavel = true },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ReportProblem, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("NÃO COLETÁVEL", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (indiceAtual > 0) indiceAtual-- }, enabled = indiceAtual > 0) {
                Icon(Icons.Default.ArrowBackIos, null, modifier = Modifier.size(34.dp), tint = if (indiceAtual > 0) VERDE_APP else Color.Gray)
            }
            OutlinedButton(
                onClick = {
                    situacaoAtual = SituacaoPlanta.NORMAL
                    launcherGaleria.launch("image/*")
                },
                modifier = Modifier.height(56.dp).padding(horizontal = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Image, null)
                Spacer(Modifier.width(4.dp))
                Text("GALERIA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    situacaoAtual = SituacaoPlanta.NORMAL
                    cameraAtiva = true
                },
                modifier = Modifier.height(56.dp).weight(1f).padding(end = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PhotoCamera, null)
                Spacer(Modifier.width(4.dp))
                Text(if (pontoAtual.coletado) "REFAZER" else "CÂMERA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { if (indiceAtual < pontos.size - 1) indiceAtual++ }, enabled = indiceAtual < pontos.size - 1) {
                Icon(Icons.Default.ArrowForwardIos, null, modifier = Modifier.size(34.dp), tint = if (indiceAtual < pontos.size - 1) VERDE_APP else Color.Gray)
            }
        }
    }
}

// ════════════════════════════════════════════════════
// ABA 0: COLETA LIVRE (LAYOUT LIMPO E INTUITIVO IGUAL AO CACAUAPP)
// ════════════════════════════════════════════════════
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
    var mostrarDialogNaoAvaliavel by remember { mutableStateOf(false) }
    var mostrarDialogVariaveisPreenchimento by remember { mutableStateOf(false) }

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

    val pVal = if (isNum2) parcelaNum.toString() else parcelaTxt.trim().ifBlank { "1" }
    val plVal = if (isNum3) plantaNum.toString() else plantaTxt.trim().ifBlank { "1" }
    val tratVal = tratamento.trim().ifBlank { "Geral" }
    val idAtual = "${tratVal}_P${pVal}_Pl${plVal}"
    val registroExistente = remember(registrosSalvos.size, idAtual) {
        registrosSalvos.firstOrNull { it.idCompleto.equals(idAtual, ignoreCase = true) }
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
            title = { Text("Personalizar Variáveis Fixas", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = t1, onValueChange = { t1 = it }, label = { Text("Variável 1 (Ex: Tratamento / Clone)") })
                    HorizontalDivider()
                    OutlinedTextField(value = t2, onValueChange = { t2 = it }, label = { Text("Variável 2 (Ex: Parcela / Bloco)") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = n2, onCheckedChange = { n2 = it })
                        Text("Usar contador numérico (+/-)", fontSize = 13.sp)
                    }
                    HorizontalDivider()
                    OutlinedTextField(value = t3, onValueChange = { t3 = it }, label = { Text("Variável 3 (Ex: Planta)") })
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

    if (mostrarDialogNaoAvaliavel) {
        AlertDialog(
            onDismissRequest = { mostrarDialogNaoAvaliavel = false },
            title = { Text("Planta Não Coletável", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selecione o motivo para registrar:", fontSize = 13.sp, color = Color.Gray)
                    MOTIVOS_NAO_AVALIAVEL.forEach { op ->
                        Surface(
                            onClick = { motivoAtual = op },
                            shape = RoundedCornerShape(8.dp),
                            color = if (motivoAtual == op) Color(0xFFFFEBEE) else Color(0xFFF5F5F5),
                            border = BorderStroke(1.dp, if (motivoAtual == op) Color(0xFFC62828) else Color.LightGray),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = motivoAtual == op, onClick = { motivoAtual = op })
                                Spacer(Modifier.width(6.dp))
                                Text(op, fontWeight = if (motivoAtual == op) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        mostrarDialogNaoAvaliavel = false
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text("CONFIRMAR") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogNaoAvaliavel = false }) { Text("Cancelar") }
            }
        )
    }

    if (mostrarDialogVariaveisPreenchimento) {
        AlertDialog(
            onDismissRequest = { mostrarDialogVariaveisPreenchimento = false },
            title = { Text("Variáveis Extras da Planta", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    BlocoVariaveisExtrasCard(
                        variaveisAtivas = variaveisAtivas,
                        valoresAtuais = valoresExtras,
                        onAbrirConfigVariaveis = {
                            mostrarDialogVariaveisPreenchimento = false
                            onAbrirConfigVariaveis()
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { mostrarDialogVariaveisPreenchimento = false },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("CONCLUÍDO") }
            }
        )
    }

    // ── TELA LIMPA E AREJADA (IDÊNTICA AO CACAUAPP) ──
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Sessão Manual: $ensaio",
                fontWeight = FontWeight.Bold,
                color = VERDE_APP,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        if (variaveisAtivas.isEmpty()) onAbrirConfigVariaveis()
                        else mostrarDialogVariaveisPreenchimento = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.PlaylistAdd, null, tint = VERDE_APP, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (variaveisAtivas.isEmpty()) "+ Variáveis"
                        else "Variáveis (${valoresExtras.count { it.value.isNotBlank() }}/${variaveisAtivas.size})",
                        fontSize = 12.sp,
                        color = VERDE_APP,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = { mostrarDialogLabels = true }) {
                    Icon(Icons.Default.Settings, "Renomear campos", tint = VERDE_APP)
                }
            }
        }

        // Badge do modelo ativo (1 linha, sem poluição visual)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFE8F5E9),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.SmartToy, null, tint = VERDE_APP, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = buildString {
                        append("Modelo: ${modelConfig.nomeExibicao}")
                        append(" • ${(modelConfig.threshold * 100).toInt()}%")
                        if (modelConfig.labelsFiltro.isNotEmpty()) {
                            append(" (${modelConfig.labelsFiltro.take(3).joinToString(", ")}${if (modelConfig.labelsFiltro.size > 3) "…" else ""})")
                        }
                    },
                    fontSize = 12.sp,
                    color = VERDE_APP,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        val sugestoes = remember(tratamento, historicoTratamentos.toList()) {
            val b = tratamento.trim()
            if (b.isBlank()) historicoTratamentos.take(25)
            else historicoTratamentos.filter { it.contains(b, ignoreCase = true) }.take(25)
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
                placeholder = { Text("Ex: T-01, Variedade A, CCN-51...") },
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

        Spacer(Modifier.height(20.dp))

        if (isNum2) {
            Text(label2, fontSize = 12.sp, color = Color.Gray)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = { if (parcelaNum > 1) onParcelaNumChange(parcelaNum - 1) }) {
                    Icon(Icons.Default.RemoveCircle, null, tint = Color.Red, modifier = Modifier.size(40.dp))
                }
                Text(
                    "$parcelaNum",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                IconButton(onClick = { onParcelaNumChange(parcelaNum + 1) }) {
                    Icon(Icons.Default.AddCircle, null, tint = VERDE_APP, modifier = Modifier.size(40.dp))
                }
            }
        } else {
            OutlinedTextField(
                value = parcelaTxt,
                onValueChange = onParcelaTxtChange,
                label = { Text(label2) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Layers, null, tint = VERDE_APP) }
            )
        }

        Spacer(Modifier.height(20.dp))

        if (isNum3) {
            Card(
                modifier = Modifier.fillMaxWidth().height(104.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (registroExistente != null) Color(0xFFE8F5E9) else Color(0xFFF1F8E9)
                )
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { if (plantaNum > 1) onPlantaNumChange(plantaNum - 1) }) {
                        Icon(Icons.Default.RemoveCircle, "Planta anterior", tint = Color.Red, modifier = Modifier.size(40.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (registroExistente != null) "${label3.uppercase()} • ✓ JÁ COLETADA (${registroExistente.diagnostico})"
                            else label3.uppercase(),
                            fontSize = 11.sp,
                            color = VERDE_APP,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "$plantaNum",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = VERDE_ESCURO
                        )
                    }
                    IconButton(onClick = { onPlantaNumChange(plantaNum + 1) }) {
                        Icon(Icons.Default.AddCircle, "Próxima planta", tint = VERDE_APP, modifier = Modifier.size(40.dp))
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = plantaTxt,
                onValueChange = onPlantaTxtChange,
                label = { Text(label3) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.PinDrop, null, tint = VERDE_APP) }
            )
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    situacaoAtual = SituacaoPlanta.SEM_FRUTOS
                    cameraAtiva = true
                },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF6C00)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.HideImage, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("SEM FRUTOS (0)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            OutlinedButton(
                onClick = { mostrarDialogNaoAvaliavel = true },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ReportProblem, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("NÃO COLETÁVEL", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    situacaoAtual = SituacaoPlanta.NORMAL
                    launcherGaleria.launch("image/*")
                },
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP)
            ) {
                Icon(Icons.Default.Image, null)
                Spacer(Modifier.width(6.dp))
                Text("GALERIA", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    situacaoAtual = SituacaoPlanta.NORMAL
                    cameraAtiva = true
                },
                modifier = Modifier.weight(1.3f).height(60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
            ) {
                Icon(Icons.Default.PhotoCamera, null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (registroExistente != null) "REFAZER FOTO" else "ABRIR CÂMERA",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
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
