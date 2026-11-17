package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.example.myapplication.userinterface.util.exportarCSVBreedAI
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
import com.example.myapplication.data.MOTIVOS_NAO_AVALIAVEL
import com.example.myapplication.data.ModelConfig
import com.example.myapplication.data.PontoFormulario
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.TipoVariavelExtra
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

private val BreedAIColorScheme = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F5E9),
    onPrimaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFF2E7D32),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8E6C9),
    onSecondaryContainer = Color(0xFF1B5E20),
    tertiary = Color(0xFFEF6C00),
    onTertiary = Color.White,
    background = Color(0xFFF5F7F5),
    onBackground = Color(0xFF1C241D),
    surface = Color.White,
    onSurface = Color(0xFF1C241D),
    surfaceVariant = Color(0xFFEEF3EE),
    onSurfaceVariant = Color(0xFF425244),
    outline = Color(0xFFB8CBB8),
    outlineVariant = Color(0xFFDCE7DC)
)

@Composable
fun BreedAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BreedAIColorScheme,
        content = content
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BreedAITheme {
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

    // Navegação Principal Passo a Passo (Sem Hambúrguer) na ordem do Dr. Uilson:
    // 0 = Início (Home) | 1 = 1. Modelo IA | 2 = 2. Formulário | 3 = 3. Coleta Livre | 4 = 4. Edição Dados
    var abaSelecionada by rememberSaveable { mutableIntStateOf(0) }
    var mostrarTutorialPrimeiraVez by rememberSaveable { mutableStateOf(false) }

    var coletaNome by rememberSaveable { mutableStateOf("Coleta_Campo_01") }
    var labelTratamento by rememberSaveable { mutableStateOf("Tratamento") }
    var labelParcela by rememberSaveable { mutableStateOf("Parcela") }
    var labelPlanta by rememberSaveable { mutableStateOf("Planta") }
    var tipoNumParcela by rememberSaveable { mutableStateOf(true) }
    var tipoNumPlanta by rememberSaveable { mutableStateOf(true) }
    var autoAvancarPlanta by rememberSaveable { mutableStateOf(true) }

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
    var classificacoesAtivasModelo by remember {
        mutableStateOf(
            PersistenciaLocalRegistros.carregarClassificacoesModelo(
                context, modelConfig.chaveModeloPersistente()
            )
        )
    }
    LaunchedEffect(modelConfig) {
        classificacoesAtivasModelo = PersistenciaLocalRegistros.carregarClassificacoesModelo(
            context, modelConfig.chaveModeloPersistente()
        )
    }

    var registroParaEdicao by remember { mutableStateOf<RegistroColeta?>(null) }
    var mostrarTelaConfigEnsaio by rememberSaveable { mutableStateOf(false) }
    var mostrarDialogRenomearColeta by remember { mutableStateOf(false) }
    var mostrarDialogSobreOffline by remember { mutableStateOf(false) }
    var mostrarDialogNovaColeta by remember { mutableStateOf(false) }
    var mostrarDialogGerenciarVariaveis by remember { mutableStateOf(false) }

    // Estado de importação manual de colunas (.xlsx / .csv)
    var planilhaPendente by remember { mutableStateOf<ImportadorPlanilhaUniversal.PlanilhaLida?>(null) }

    fun sincronizarAtivasPorColeta(nome: String) {
        val salvas = PersistenciaLocalRegistros.carregarVariaveisAtivasPorColeta(context, nome)
        if (salvas != null) {
            for (i in variaveisExtras.indices) {
                val v = variaveisExtras[i]
                val deveAtivar = salvas.contains(v.id)
                if (v.ativa != deveAtivar) variaveisExtras[i] = v.copy(ativa = deveAtivar)
            }
        }
    }

    fun alternarVariavelNaColeta(variavel: VariavelExtra, ativa: Boolean) {
        val idx = variaveisExtras.indexOfFirst { it.id == variavel.id }
        if (idx >= 0) {
            variaveisExtras[idx] = variaveisExtras[idx].copy(ativa = ativa)
            PersistenciaLocalRegistros.salvarVariaveisExtras(context, variaveisExtras)
            PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
                context, coletaNome, variaveisExtras.filter { it.ativa }.map { it.id }
            )
        }
    }

    fun ativarModoSomenteContagem() {
        for (i in variaveisExtras.indices) {
            if (variaveisExtras[i].ativa) {
                variaveisExtras[i] = variaveisExtras[i].copy(ativa = false)
            }
        }
        PersistenciaLocalRegistros.salvarVariaveisExtras(context, variaveisExtras)
        PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(context, coletaNome, emptyList())
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

    fun carregarPlanilhaExemplo() {
        val tratExemplos = listOf("CCN-51", "PS-1319", "CEPEC-2002")
        val pontosExemplo = mutableListOf<PontoFormulario>()
        var ord = 1
        for ((pIdx, trat) in tratExemplos.withIndex()) {
            val parcela = (pIdx + 1).toString()
            for (pl in 1..4) {
                pontosExemplo.add(
                    PontoFormulario(
                        tratamento = trat,
                        parcela = parcela,
                        planta = pl.toString(),
                        ordem = ord++
                    )
                )
            }
        }
        formularioPontos.clear()
        formularioPontos.addAll(pontosExemplo)
        reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
        PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
        Toast.makeText(context, "Planilha exemplo carregada (12 plantas)!", Toast.LENGTH_SHORT).show()
    }

    fun salvarMetadadosColeta() {
        PersistenciaLocalRegistros.salvarConfigColeta(
            context,
            PersistenciaLocalRegistros.ConfigColetaLocal(
                coletaNome = coletaNome,
                labelTratamento = labelTratamento,
                labelParcela = labelParcela,
                labelPlanta = labelPlanta,
                tipoNumParcela = tipoNumParcela,
                tipoNumPlanta = tipoNumPlanta,
                autoAvancarPlanta = autoAvancarPlanta
            )
        )
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
                        mostrarTelaConfigEnsaio = false
                        abaSelecionada = 2
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
        autoAvancarPlanta = cfg.autoAvancarPlanta

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
        sincronizarAtivasPorColeta(cfg.coletaNome)

        if (!PersistenciaLocalRegistros.isTutorialVisto(context)) {
            mostrarTutorialPrimeiraVez = true
        }

        launcherPermissoes.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    // ── DIÁLOGOS GLOBAIS ──

    if (mostrarTutorialPrimeiraVez) {
        AlertDialog(
            onDismissRequest = {
                PersistenciaLocalRegistros.marcarTutorialVisto(context)
                mostrarTutorialPrimeiraVez = false
            },
            title = {
                Text("Bem-vindo ao BreedAI", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = VERDE_ESCURO)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Conte frutos, identifique doenças e organize seus experimentos direto no celular, sem precisar de internet:",
                        fontSize = 13.sp,
                        color = Color(0xFF3E4E40),
                        lineHeight = 18.sp
                    )
                    TutorialItemStep("1. Escolha o que a câmera deve identificar", "Na aba 1. Modelo, selecione um dos modelos prontos (Contagem, Doenças do Cacau, Banana ou Maçã) ou importe seu próprio modelo.")
                    TutorialItemStep("2. Escolha se quer só contar ou anotar extras", "Na aba Início, deixe 'Somente Contagem IA' se só precisa da contagem na foto, ou ligue campos extras (ex: Vírus, Cor, Diâmetro) para esta coleta.")
                    TutorialItemStep("3. Tire as fotos no campo", "Siga sua planilha na aba 2. Form. (ou teste com a planilha de exemplo) ou fotografe direto na aba 3. Livre.")
                    TutorialItemStep("4. Confira e exporte sua planilha", "Depois da foto, toque nos quadrados para ajustar qualquer marcação e exporte tudo pronto na aba 4. Dados.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        PersistenciaLocalRegistros.marcarTutorialVisto(context)
                        mostrarTutorialPrimeiraVez = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("COMEÇAR AGORA", fontWeight = FontWeight.Bold) }
            }
        )
    }

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
                            PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
                                context, novo, variaveisExtras.filter { it.ativa }.map { it.id }
                            )
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

    if (mostrarDialogSobreOffline) {
        AlertDialog(
            onDismissRequest = { mostrarDialogSobreOffline = false },
            title = { Text("BreedAI • 100% Offline e Privado", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Plataforma de fenotipagem em campo e contagem assistida por Visão Computacional embarcada (TensorFlow Lite).",
                        fontSize = 13.sp
                    )
                    HorizontalDivider()
                    Text("• Processamento Local: Todas as imagens e inferências de IA ocorrem 100% no dispositivo.", fontSize = 12.sp, color = Color.DarkGray)
                    Text("• Dados do Usuário: Fotos, variáveis Field Book e arquivos CSV permanecem armazenados localmente sob controle do usuário.", fontSize = 12.sp, color = Color.DarkGray)
                    Text("• GPS Opcional: As coordenadas geográficas são gravadas apenas para exportação científica local.", fontSize = 12.sp, color = Color.DarkGray)
                }
            },
            confirmButton = {
                Button(
                    onClick = { mostrarDialogSobreOffline = false },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("ENTENDI") }
            }
        )
    }

    if (mostrarDialogNovaColeta) {
        var novoNome by remember { mutableStateOf("Coleta_${SimpleDateFormat("ddMM_HHmm", Locale.ROOT).format(Date())}") }
        var limparFormularioTambem by remember { mutableStateOf(false) }
        var iniciarSomenteContagem by remember { mutableStateOf(true) }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = iniciarSomenteContagem, onCheckedChange = { iniciarSomenteContagem = it })
                        Text("Iniciar somente com Contagem IA (desativar variáveis extras)", fontSize = 12.sp)
                    }
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
                        if (iniciarSomenteContagem) {
                            ativarModoSomenteContagem()
                        } else {
                            PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
                                context, nomeFinal, variaveisExtras.filter { it.ativa }.map { it.id }
                            )
                        }
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
                alternarVariavelNaColeta(variavel, ativa)
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
                PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
                    context, coletaNome, variaveisExtras.filter { it.ativa }.map { it.id }
                )
            },
            onRemoverVariavel = { variavel ->
                variaveisExtras.removeAll { it.id == variavel.id }
                PersistenciaLocalRegistros.salvarVariaveisExtras(context, variaveisExtras)
                PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
                    context, coletaNome, variaveisExtras.filter { it.ativa }.map { it.id }
                )
            },
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
                        formularioPontos.clear()
                        formularioPontos.addAll(pontos)
                        reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
                        PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
                        planilhaPendente = null
                        mostrarTelaConfigEnsaio = false
                        abaSelecionada = 2
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
            onAtualizarClassificacoes = { novasCls ->
                classificacoesAtivasModelo = novasCls
                PersistenciaLocalRegistros.salvarClassificacoesModelo(
                    context, modelConfig.chaveModeloPersistente(), novasCls
                )
            },
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

    // ── TELA DEDICADA DE CONFIGURAÇÕES DA COLETA & VARIÁVEIS ──
    if (mostrarTelaConfigEnsaio) {
        BackHandler { mostrarTelaConfigEnsaio = false }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Configurações & Variáveis Extras", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                    navigationIcon = {
                        IconButton(onClick = { mostrarTelaConfigEnsaio = false }) {
                            Icon(Icons.Default.ArrowBack, "Voltar")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = VERDE_APP,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
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
                        coletaNome = novo
                        for (i in listaRegistros.indices) {
                            listaRegistros[i] = listaRegistros[i].copy(coletaNome = novo)
                        }
                        PersistenciaLocalRegistros.salvarRegistros(context, listaRegistros)
                        if (formularioPontos.isNotEmpty()) {
                            PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, novo)
                        }
                        salvarMetadadosColeta()
                        Toast.makeText(context, "Nome da coleta salvo!", Toast.LENGTH_SHORT).show()
                    },
                    label1 = labelTratamento,
                    label2 = labelParcela,
                    label3 = labelPlanta,
                    isNum2 = tipoNumParcela,
                    isNum3 = tipoNumPlanta,
                    autoAvancarPlanta = autoAvancarPlanta,
                    onSalvarCamposFixos = { l1, l2, l3, n2, n3, autoAv ->
                        labelTratamento = l1
                        labelParcela = l2
                        labelPlanta = l3
                        tipoNumParcela = n2
                        tipoNumPlanta = n3
                        autoAvancarPlanta = autoAv
                        salvarMetadadosColeta()
                        Toast.makeText(context, "Campos de navegação salvos!", Toast.LENGTH_SHORT).show()
                    },
                    pontosRotaCount = formularioPontos.size,
                    onImportarPlanilha = { launcherPlanilha.launch("*/*") },
                    onLimparPlanilha = {
                        formularioPontos.clear()
                        PersistenciaLocalRegistros.limparFormulario(context)
                    },
                    onIniciarNovaColeta = { mostrarDialogNovaColeta = true },
                    variaveisExtras = variaveisExtras,
                    onToggleVariavelAtiva = { variavel, ativa ->
                        alternarVariavelNaColeta(variavel, ativa)
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
                        PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
                            context, coletaNome, variaveisExtras.filter { it.ativa }.map { it.id }
                        )
                    },
                    onRemoverVariavel = { variavel ->
                        variaveisExtras.removeAll { it.id == variavel.id }
                        PersistenciaLocalRegistros.salvarVariaveisExtras(context, variaveisExtras)
                        PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
                            context, coletaNome, variaveisExtras.filter { it.ativa }.map { it.id }
                        )
                    }
                )
            }
        }
        return
    }

    // ── NAVEGAÇÃO PRINCIPAL PASSO-A-PASSO (TOPBAR + 5 ABAS FIXAS SEM HAMBÚRGUER) ──
    var modoImersivoAtivo by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!modoImersivoAtivo) {
                val tituloSecaoAtual = when (abaSelecionada) {
                    0 -> "BreedAI"
                    1 -> "1. Modelo IA"
                    2 -> "2. Formulário"
                    3 -> "3. Coleta Livre"
                    else -> "4. Dados & CSV"
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
                            Column(
                                modifier = Modifier
                                    .clickable { mostrarDialogRenomearColeta = true }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    tituloSecaoAtual,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VERDE_ESCURO,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Coleta: $coletaNome ✎",
                                    fontSize = 11.sp,
                                    color = Color(0xFF4E6E52),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        actions = {
                            Surface(
                                onClick = { abaSelecionada = 1 },
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
                            IconButton(onClick = { mostrarTutorialPrimeiraVez = true }) {
                                Icon(Icons.Default.HelpOutline, "Tutorial passo a passo", tint = VERDE_APP)
                            }
                            IconButton(onClick = { mostrarTelaConfigEnsaio = true }) {
                                Icon(Icons.Default.Tune, "Configurações da Coleta", tint = VERDE_APP)
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
                            label = { Text("Início", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = abaSelecionada == 1,
                            onClick = { abaSelecionada = 1 },
                            icon = { Icon(Icons.Default.SmartToy, null) },
                            label = { Text("1. Modelo", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = abaSelecionada == 2,
                            onClick = { abaSelecionada = 2 },
                            icon = {
                                if (formularioPontos.isNotEmpty()) {
                                    BadgedBox(
                                        badge = {
                                            Badge(containerColor = VERDE_APP, contentColor = Color.White) {
                                                Text("${formularioPontos.size}", fontSize = 9.sp)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Assignment, null)
                                    }
                                } else {
                                    Icon(Icons.Default.Assignment, null)
                                }
                            },
                            label = { Text("2. Form.", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = abaSelecionada == 3,
                            onClick = { abaSelecionada = 3 },
                            icon = { Icon(Icons.Default.PhotoCamera, null) },
                            label = { Text("3. Livre", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = abaSelecionada == 4,
                            onClick = { abaSelecionada = 4 },
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
                            label = { Text("4. Dados", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
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

            val classesExportacao = remember(modelConfig, classificacoesAtivasModelo, listaRegistros.toList()) {
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

            fun atualizarClassificacoesAtivas(novasCls: List<String>) {
                classificacoesAtivasModelo = novasCls
                PersistenciaLocalRegistros.salvarClassificacoesModelo(
                    context, modelConfig.chaveModeloPersistente(), novasCls
                )
            }

            when (abaSelecionada) {
                0 -> TelaInicioPassoAPasso(
                    coletaNome = coletaNome,
                    totalRegistros = listaRegistros.size,
                    totalPontosFormulario = formularioPontos.size,
                    modelConfig = modelConfig,
                    variaveisAtivas = variaveisAtivas,
                    todasVariaveis = variaveisExtras,
                    classesExportacao = classesExportacao,
                    onRenomearColeta = { mostrarDialogRenomearColeta = true },
                    onNovaColeta = { mostrarDialogNovaColeta = true },
                    onAtivarSomenteContagem = { ativarModoSomenteContagem() },
                    onAlternarVariavel = { variavel, ativa -> alternarVariavelNaColeta(variavel, ativa) },
                    onGerenciarVariaveis = { mostrarDialogGerenciarVariaveis = true },
                    onIrParaModelo = { abaSelecionada = 1 },
                    onIrParaFormulario = { abaSelecionada = 2 },
                    onIrParaLivre = { abaSelecionada = 3 },
                    onIrParaDados = { abaSelecionada = 4 },
                    onCarregarExemplo = {
                        carregarPlanilhaExemplo()
                        abaSelecionada = 2
                    },
                    onAbrirTutorial = { mostrarTutorialPrimeiraVez = true },
                    onAbrirSobreOffline = { mostrarDialogSobreOffline = true }
                )

                1 -> TelaConfiguracaoModelo(
                    configAtual = modelConfig,
                    onSalvar = { novaConfig ->
                        modelConfig = novaConfig
                        PersistenciaLocalRegistros.salvarConfiguracaoModelo(context, novaConfig)
                        classificacoesAtivasModelo = PersistenciaLocalRegistros.carregarClassificacoesModelo(
                            context, novaConfig.chaveModeloPersistente()
                        )
                        abaSelecionada = if (formularioPontos.isNotEmpty()) 2 else 3
                        Toast.makeText(
                            context,
                            "Modelo \"${novaConfig.nomeExibicao}\" ativado!",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onGerarVariaveisDoModelo = { classesSelecionadas ->
                        modelConfig = modelConfig.copy(labelsFiltro = classesSelecionadas)
                        PersistenciaLocalRegistros.salvarConfiguracaoModelo(context, modelConfig)
                        Toast.makeText(
                            context,
                            "${classesSelecionadas.size} classe(s) configuradas como colunas IA_ automáticas no CSV.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )

                2 -> TelaFormularioFieldBook(
                    ensaio = coletaNome,
                    pontos = formularioPontos,
                    registrosSalvos = listaRegistros,
                    labelTratamento = labelTratamento,
                    labelParcela = labelParcela,
                    labelPlanta = labelPlanta,
                    modelConfig = modelConfig,
                    variaveisAtivas = variaveisAtivas,
                    todasVariaveis = variaveisExtras,
                    onToggleVariavel = { variavel, ativa -> alternarVariavelNaColeta(variavel, ativa) },
                    onDesativarTodas = { ativarModoSomenteContagem() },
                    onAbrirConfigVariaveis = { mostrarTelaConfigEnsaio = true },
                    onCarregarPlanilhaClick = { launcherPlanilha.launch("*/*") },
                    onCarregarExemploClick = { carregarPlanilhaExemplo() },
                    onLimparFormularioClick = {
                        formularioPontos.clear()
                        PersistenciaLocalRegistros.limparFormulario(context)
                    },
                    onModoImersivoChange = { modoImersivoAtivo = it },
                    classificacoesPersonalizadas = classificacoesAtivasModelo,
                    onAtualizarClassificacoes = ::atualizarClassificacoesAtivas,
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

                3 -> TelaColetaLivreFieldBook(
                    ensaio = coletaNome,
                    registrosSalvos = listaRegistros,
                    label1 = labelTratamento,
                    label2 = labelParcela,
                    label3 = labelPlanta,
                    isNum2 = tipoNumParcela,
                    isNum3 = tipoNumPlanta,
                    autoAvancarPlanta = autoAvancarPlanta,
                    onAutoAvancarChange = {
                        autoAvancarPlanta = it
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
                    todasVariaveis = variaveisExtras,
                    onToggleVariavel = { variavel, ativa -> alternarVariavelNaColeta(variavel, ativa) },
                    onDesativarTodas = { ativarModoSomenteContagem() },
                    onAbrirConfigVariaveis = { mostrarTelaConfigEnsaio = true },
                    onModoImersivoChange = { modoImersivoAtivo = it },
                    classificacoesPersonalizadas = classificacoesAtivasModelo,
                    onAtualizarClassificacoes = ::atualizarClassificacoesAtivas,
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
                        if (tipoNumPlanta && autoAvancarPlanta) plantaNumManual++
                    }
                )

                else -> TelaEdicaoDados(
                    registros = listaRegistros,
                    coletaNome = coletaNome,
                    labelTratamento = labelTratamento,
                    labelParcela = labelParcela,
                    labelPlanta = labelPlanta,
                    variaveisExtrasConfig = variaveisExtras,
                    classesModeloExportacao = classesExportacao,
                    onAbrirConfigVariaveis = { mostrarTelaConfigEnsaio = true },
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

@Composable
private fun TutorialItemStep(titulo: String, descricao: String) {
    Surface(
        color = Color(0xFFF3F8F3),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFFDCE7DC)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = VERDE_ESCURO)
            Text(descricao, fontSize = 12.sp, color = Color(0xFF3E4E40), lineHeight = 16.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaInicioPassoAPasso(
    coletaNome: String,
    totalRegistros: Int,
    totalPontosFormulario: Int,
    modelConfig: ModelConfig,
    variaveisAtivas: List<VariavelExtra>,
    todasVariaveis: List<VariavelExtra>,
    classesExportacao: List<String>,
    onRenomearColeta: () -> Unit,
    onNovaColeta: () -> Unit,
    onAtivarSomenteContagem: () -> Unit,
    onAlternarVariavel: (VariavelExtra, Boolean) -> Unit,
    onGerenciarVariaveis: () -> Unit,
    onIrParaModelo: () -> Unit,
    onIrParaFormulario: () -> Unit,
    onIrParaLivre: () -> Unit,
    onIrParaDados: () -> Unit,
    onCarregarExemplo: () -> Unit,
    onAbrirTutorial: () -> Unit,
    onAbrirSobreOffline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = VERDE_ESCURO,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Guia Passo a Passo de Campo",
                    color = Color(0xFFC8E6C9),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    coletaNome,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$totalRegistros plantas coletadas • $totalPontosFormulario na planilha de rota",
                    color = Color(0xFFE8F5E9),
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRenomearColeta,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Renomear", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onNovaColeta,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.AddCircleOutline, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Nova Coleta", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Passo 1: O que a IA vai identificar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFDCE7DC))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Passo 1 • O que a IA vai identificar?", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                    Surface(color = Color(0xFFE8F5E9), shape = RoundedCornerShape(10.dp)) {
                        Text(
                            if (modelConfig.usarModeloSecundario) "Contar + Doenças" else "100% Offline",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_ESCURO,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    modelConfig.nomeExibicao,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color(0xFF1C241D)
                )
                Text(
                    if (modelConfig.labelsFiltro.isEmpty()) {
                        "Reconhecendo todas as categorias disponíveis neste modelo."
                    } else {
                        "Contando na tela: ${modelConfig.labelsFiltro.joinToString(", ")}"
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF5F6F61)
                )
                Button(
                    onClick = onIrParaModelo,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.SmartToy, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("1. Escolher ou Importar Modelo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Passo 2: Anotações Extras (Opcional)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFDCE7DC))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Passo 2 • Anotações Extras (Opcional)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                    TextButton(onClick = onGerenciarVariaveis, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.Tune, null, modifier = Modifier.size(14.dp), tint = VERDE_APP)
                        Spacer(Modifier.width(4.dp))
                        Text("Criar/Excluir", fontSize = 11.sp, color = VERDE_APP, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    if (variaveisAtivas.isEmpty()) {
                        "Modo rápido ativo: você vai apenas fotografar e contar com a IA (sem campos manuais)."
                    } else {
                        "Campos ativos além da foto (${variaveisAtivas.size}): ${variaveisAtivas.joinToString(", ") { it.nome }}"
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF425244),
                    lineHeight = 17.sp
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = variaveisAtivas.isEmpty(),
                            onClick = onAtivarSomenteContagem,
                            label = { Text("Somente Contagem IA", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                    items(todasVariaveis) { variavel ->
                        FilterChip(
                            selected = variavel.ativa,
                            onClick = { onAlternarVariavel(variavel, !variavel.ativa) },
                            label = { Text(variavel.nome, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // Passo 3: Coletar no Campo
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFDCE7DC))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Passo 3 • Coletar no Campo", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                Text(
                    "• 2. Form.: siga uma lista pronta de parcelas/plantas da sua planilha.\n• 3. Coleta Livre: vá direto para a câmera, sem planilha.",
                    fontSize = 12.sp,
                    color = Color(0xFF5F6F61),
                    lineHeight = 17.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onIrParaFormulario,
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Assignment, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("2. Form. ($totalPontosFormulario)", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onIrParaLivre,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, VERDE_APP),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_ESCURO),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("3. Coleta Livre", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                if (totalPontosFormulario == 0) {
                    OutlinedButton(
                        onClick = onCarregarExemplo,
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Testar com Planilha de Exemplo (12 plantas)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Passo 4: Revisão & Exportação
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFDCE7DC))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Passo 4 • Conferir e Exportar Planilha (CSV)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                Text(
                    if (classesExportacao.isNotEmpty()) {
                        "Ao exportar, a planilha já incluirá automaticamente colunas de contagem para: ${classesExportacao.joinToString(", ")}."
                    } else {
                        "Confira suas fotos, GPS, anotações e gere o arquivo .csv pronto para abrir no Excel ou R."
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF5F6F61),
                    lineHeight = 17.sp
                )
                Button(
                    onClick = onIrParaDados,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.TableChart, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("4. Abrir Dados & Exportar ($totalRegistros)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onAbrirTutorial) {
                        Icon(Icons.Default.HelpOutline, null, modifier = Modifier.size(15.dp), tint = VERDE_APP)
                        Spacer(Modifier.width(4.dp))
                        Text("Rever Tutorial", fontSize = 11.sp, color = VERDE_APP)
                    }
                    TextButton(onClick = onAbrirSobreOffline) {
                        Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(15.dp), tint = Color(0xFF4E6E52))
                        Spacer(Modifier.width(4.dp))
                        Text("Privacidade Offline", fontSize = 11.sp, color = Color(0xFF4E6E52))
                    }
                }
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
    ) -> Unit,
    todasVariaveis: List<VariavelExtra> = variaveisAtivas,
    onToggleVariavel: ((VariavelExtra, Boolean) -> Unit)? = null,
    onDesativarTodas: (() -> Unit)? = null,
    onCarregarExemploClick: () -> Unit = {},
    classificacoesPersonalizadas: List<String>? = null,
    onAtualizarClassificacoes: ((List<String>) -> Unit)? = null
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

    val (detectorHelper, detectorSecundarioHelper) = remember(modelConfig) {
        criarParDetectoresBreedAI(context, modelConfig)
    }
    DisposableEffect(detectorHelper, detectorSecundarioHelper) {
        onDispose {
            detectorHelper.fechar()
            detectorSecundarioHelper?.fechar()
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFDCE7DC))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.TableView, null, modifier = Modifier.size(56.dp), tint = VERDE_APP)
                    Text(
                        "Rota de Campo (Planilha)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = VERDE_ESCURO,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Importe uma planilha .xlsx ou .csv com a ordem das parcelas e plantas do experimento para coleta sequencial.",
                        color = Color(0xFF5F6F61),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onCarregarPlanilhaClick,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("IMPORTAR PLANILHA (.XLSX / .CSV)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onCarregarExemploClick,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_ESCURO)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("CARREGAR PLANILHA DE EXEMPLO (12 PLANTAS)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
            detectorSecundarioHelper = detectorSecundarioHelper,
            labelsFiltro = modelConfig.labelsFiltro,
            modoSemFrutos = situacaoAtual == SituacaoPlanta.SEM_FRUTOS,
            classificacoesPersonalizadas = classificacoesPersonalizadas,
            onAtualizarClassificacoes = onAtualizarClassificacoes,
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
            val coletadasCount = remember(pontos) { pontos.count { it.coletado } }
            val progressoRota = if (pontos.isNotEmpty()) coletadasCount.toFloat() / pontos.size.toFloat() else 0f

            // Bloco 1: Card Superior Unificado de Navegação Estilo Field Book
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, if (pontoAtual.coletado) Color(0xFF81C784) else Color(0xFFDCE7DC))
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Planta ${indiceAtual + 1} de ${pontos.size}  •  Ordem #$ordemExibicao",
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
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC62828)),
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
                onMotivoChange = { motivoAtual = it }
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
                    else Toast.makeText(context, "Rota Finalizada!", Toast.LENGTH_SHORT).show()
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
                    "REGISTRAR NÃO AVALIÁVEL ($motivoAtual)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { launcherGaleria.launch("image/*") },
                    modifier = Modifier.weight(1f).height(54.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP),
                    border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Image, null)
                    Spacer(Modifier.width(6.dp))
                    Text("GALERIA", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { cameraAtiva = true },
                    modifier = Modifier.weight(1.5f).height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (situacaoAtual == SituacaoPlanta.SEM_FRUTOS) Color(0xFFEF6C00) else VERDE_APP
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PhotoCamera, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            situacaoAtual == SituacaoPlanta.SEM_FRUTOS -> "FOTO PROVA (0)"
                            pontoAtual.coletado -> "REFAZER FOTO"
                            else -> "FOTOGRAFAR PLANTA"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════
// ABA 0 (SUBMODO LIVRE): FIELD BOOK INLINE E COMPACTO
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
    onAtualizarClassificacoes: ((List<String>) -> Unit)? = null
) {
    val context = LocalContext.current

    var cameraAtiva by remember { mutableStateOf(false) }
    var menuTratamentosExpandido by remember { mutableStateOf(false) }

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

    val (detectorHelper, detectorSecundarioHelper) = remember(modelConfig) {
        criarParDetectoresBreedAI(context, modelConfig)
    }
    DisposableEffect(detectorHelper, detectorSecundarioHelper) {
        onDispose {
            detectorHelper.fechar()
            detectorSecundarioHelper?.fechar()
        }
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
            detectorSecundarioHelper = detectorSecundarioHelper,
            labelsFiltro = modelConfig.labelsFiltro,
            modoSemFrutos = situacaoAtual == SituacaoPlanta.SEM_FRUTOS,
            classificacoesPersonalizadas = classificacoesPersonalizadas,
            onAtualizarClassificacoes = onAtualizarClassificacoes,
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
            // Card 1: Identificação no Ensaio
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFDCE7DC)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Identificação da Planta",
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
                            placeholder = { Text("Ex: Genótipo A, Clone CCN-51, T-01") },
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
                onMotivoChange = { motivoAtual = it }
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
                Text("REGISTRAR PLANTA NÃO AVALIÁVEL ($motivoAtual)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { launcherGaleria.launch("image/*") },
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VERDE_APP)
                ) {
                    Icon(Icons.Default.Image, null)
                    Spacer(Modifier.width(6.dp))
                    Text("GALERIA", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { cameraAtiva = true },
                    modifier = Modifier.weight(1.5f).height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (situacaoAtual == SituacaoPlanta.SEM_FRUTOS) Color(0xFFEF6C00) else VERDE_APP
                    )
                ) {
                    Icon(Icons.Default.PhotoCamera, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            situacaoAtual == SituacaoPlanta.SEM_FRUTOS -> "FOTO PROVA (0)"
                            registroExistente != null -> "REFAZER FOTO"
                            else -> "FOTOGRAFAR PLANTA"
                        },
                        fontSize = 13.sp,
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
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (destaque) Color(0xFFE8F5E9) else Color(0xFFF4F9F4)
        ),
        border = BorderStroke(1.dp, if (destaque) VERDE_APP else Color(0xFFC8E6C9))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                titulo.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = VERDE_APP,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onDecrement, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.RemoveCircle, "Diminuir $titulo", tint = Color(0xFFC62828), modifier = Modifier.size(28.dp))
                }
                Text(
                    "$valor",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = VERDE_ESCURO
                )
                IconButton(onClick = onIncrement, modifier = Modifier.size(34.dp)) {
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

@Composable
fun TelaConfiguracaoEnsaio(
    coletaNome: String,
    onSalvarNomeColeta: (String) -> Unit,
    label1: String,
    label2: String,
    label3: String,
    isNum2: Boolean,
    isNum3: Boolean,
    autoAvancarPlanta: Boolean,
    onSalvarCamposFixos: (String, String, String, Boolean, Boolean, Boolean) -> Unit,
    pontosRotaCount: Int,
    onImportarPlanilha: () -> Unit,
    onLimparPlanilha: () -> Unit,
    onIniciarNovaColeta: () -> Unit,
    variaveisExtras: List<VariavelExtra>,
    onToggleVariavelAtiva: (VariavelExtra, Boolean) -> Unit,
    onAdicionarVariavel: (String, TipoVariavelExtra, List<String>) -> Unit,
    onRemoverVariavel: (VariavelExtra) -> Unit
) {
    var nomeTemp by remember(coletaNome) { mutableStateOf(coletaNome) }
    var l1Temp by remember(label1) { mutableStateOf(label1) }
    var l2Temp by remember(label2) { mutableStateOf(label2) }
    var l3Temp by remember(label3) { mutableStateOf(label3) }
    var num2Temp by remember(isNum2) { mutableStateOf(isNum2) }
    var num3Temp by remember(isNum3) { mutableStateOf(isNum3) }
    var autoAvTemp by remember(autoAvancarPlanta) { mutableStateOf(autoAvancarPlanta) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Configuração do Ensaio e Variáveis", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = VERDE_ESCURO)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Identificação da Coleta", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_APP)
                OutlinedTextField(
                    value = nomeTemp,
                    onValueChange = { nomeTemp = it },
                    label = { Text("Nome da Coleta / Experimento") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val limpo = nomeTemp.trim()
                            if (limpo.isNotBlank()) onSalvarNomeColeta(limpo)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                    ) {
                        Text("SALVAR NOME", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onIniciarNovaColeta,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                    ) {
                        Text("NOVA COLETA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Variáveis Fixas (Navegação)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_APP)
                OutlinedTextField(
                    value = l1Temp,
                    onValueChange = { l1Temp = it },
                    label = { Text("Campo 1 (Ex: Tratamento / Clone / Genótipo)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = l2Temp,
                    onValueChange = { l2Temp = it },
                    label = { Text("Campo 2 (Ex: Parcela / Bloco)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = num2Temp, onCheckedChange = { num2Temp = it })
                    Text("Usar contador numérico (+/-) no Campo 2", fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = l3Temp,
                    onValueChange = { l3Temp = it },
                    label = { Text("Campo 3 (Ex: Planta / Repetição)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = num3Temp, onCheckedChange = { num3Temp = it })
                    Text("Usar contador numérico (+/-) no Campo 3", fontSize = 13.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Avançar $l3Temp automaticamente ao salvar", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = autoAvTemp, onCheckedChange = { autoAvTemp = it })
                }
                Button(
                    onClick = {
                        onSalvarCamposFixos(
                            l1Temp.ifBlank { "Tratamento" },
                            l2Temp.ifBlank { "Parcela" },
                            l3Temp.ifBlank { "Planta" },
                            num2Temp,
                            num3Temp,
                            autoAvTemp
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) {
                    Text("SALVAR CAMPOS E NAVEGAÇÃO", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. Planilha de Rota (.xlsx / .csv)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_APP)
                Text(
                    if (pontosRotaCount > 0) "$pontosRotaCount pontos carregados no Modo Rota."
                    else "Nenhuma planilha carregada para navegação sequencial.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onImportarPlanilha,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (pontosRotaCount > 0) "TROCAR" else "IMPORTAR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (pontosRotaCount > 0) {
                        OutlinedButton(
                            onClick = onLimparPlanilha,
                            modifier = Modifier.weight(0.75f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                        ) {
                            Text("LIMPAR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4. Variáveis Extras (Field Book)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_APP)
                ConteudoGerenciarVariaveisExtras(
                    variaveis = variaveisExtras,
                    onToggleAtiva = onToggleVariavelAtiva,
                    onAdicionarVariavel = onAdicionarVariavel,
                    onRemoverVariavel = onRemoverVariavel
                )
            }
        }

        Spacer(Modifier.height(12.dp))
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
