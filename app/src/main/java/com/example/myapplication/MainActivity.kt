package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.example.myapplication.ui.theme.BreedAITheme
import com.example.myapplication.userinterface.util.exportarCSVBreedAI
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.ConfigConversaoRendimento
import com.example.myapplication.data.MOTIVOS_NAO_AVALIAVEL
import com.example.myapplication.data.ModelConfig
import com.example.myapplication.data.PontoFormulario
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.TemplateCultura
import com.example.myapplication.data.TipoVariavelExtra
import com.example.myapplication.data.VariavelExtra
import com.example.myapplication.data.calcularRendimentoColeta
import com.example.myapplication.data.formatarEstimativaCurta
import com.example.myapplication.userinterface.*
import com.example.myapplication.userinterface.util.ImportadorPlanilhaUniversal
import com.example.myapplication.userinterface.util.PersistenciaLocalRegistros
import com.example.myapplication.userinterface.util.copiarUriGaleriaParaArquivoLocal
import com.example.myapplication.userinterface.util.criarImageCaptureBreedAI
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
        setContent {
            BreedAITheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BreedAIApp()
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
fun BreedAIApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val listaRegistros = remember { mutableStateListOf<RegistroColeta>() }
    val formularioPontos = remember { mutableStateListOf<PontoFormulario>() }
    val variaveisExtras = remember { mutableStateListOf<VariavelExtra>() }
    val historicoTratamentos = remember { mutableStateListOf<String>() }

    // Navegação principal em 4 abas intuitivas:
    // 0 = Início | 1 = Coletar (Rota / Livre) | 2 = Dados | 3 = Modelo IA
    var abaSelecionada by rememberSaveable { mutableIntStateOf(0) }
    var modoColetaGuiada by rememberSaveable { mutableStateOf(false) }
    var mostrarTutorialPrimeiraVez by rememberSaveable { mutableStateOf(false) }

    var coletaId by rememberSaveable { mutableStateOf("coleta_padrao") }
    var templateId by rememberSaveable { mutableStateOf("fruticultura") }
    val templateAtual = TemplateCultura.fromId(templateId)
    val coletasSalvas = remember { mutableStateListOf<PersistenciaLocalRegistros.ResumoColetaSalva>() }
    var coletaNome by rememberSaveable { mutableStateOf("Coleta Campo 01") }
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
    val gpsAtivo = latAtual != 0.0 || lngAtual != 0.0
    var configRendimento by remember { mutableStateOf(ConfigConversaoRendimento()) }
    var mostrarDialogConfigRendimento by remember { mutableStateOf(false) }

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
    var mostrarDialogMinhasColetas by remember { mutableStateOf(false) }
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
            val reg = registros.firstOrNull { it.idCompleto.equals(idCompleto, ignoreCase = true) }
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
        val tratExemplos = templateAtual.exemplosTratamento
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
        modoColetaGuiada = true
        Toast.makeText(context, "Planilha exemplo carregada (12 plantas)!", Toast.LENGTH_SHORT).show()
    }

    fun salvarMetadadosColeta() {
        PersistenciaLocalRegistros.salvarConfigColeta(
            context,
            PersistenciaLocalRegistros.ConfigColetaLocal(
                coletaId = coletaId,
                templateId = templateId,
                coletaNome = coletaNome,
                labelTratamento = labelTratamento,
                labelParcela = labelParcela,
                labelPlanta = labelPlanta,
                tipoNumParcela = tipoNumParcela,
                tipoNumPlanta = tipoNumPlanta,
                autoAvancarPlanta = autoAvancarPlanta,
                rendimentoAtivo = configRendimento.ativo,
                rendimentoPresetId = configRendimento.presetId,
                rendimentoUnidade = configRendimento.unidadeSimbolo,
                rendimentoItensPorKg = configRendimento.itensPorKg,
                rendimentoKgPorUnidade = configRendimento.kgPorUnidade,
                rendimentoPlantasPorHa = configRendimento.plantasPorHa,
                rendimentoDescontarDoencas = configRendimento.descontarDoencas
            )
        )
    }

    fun atualizarListaColetas() {
        coletasSalvas.clear()
        coletasSalvas.addAll(PersistenciaLocalRegistros.listarColetasSalvas(context))
    }

    fun aplicarEstadoDaColetaAtiva(cfg: PersistenciaLocalRegistros.ConfigColetaLocal) {
        coletaId = cfg.coletaId
        templateId = cfg.templateId
        coletaNome = cfg.coletaNome
        labelTratamento = cfg.labelTratamento
        labelParcela = cfg.labelParcela
        labelPlanta = cfg.labelPlanta
        tipoNumParcela = cfg.tipoNumParcela
        tipoNumPlanta = cfg.tipoNumPlanta
        autoAvancarPlanta = cfg.autoAvancarPlanta
        configRendimento = cfg.toConfigRendimento()

        historicoTratamentos.clear()
        val tratamentosVistos = LinkedHashSet<String>()

        val salvos = PersistenciaLocalRegistros.carregarRegistros(context)
        listaRegistros.clear()
        listaRegistros.addAll(salvos)
        salvos.map { it.tratamento.trim() }.filter { it.isNotBlank() }.forEach {
            if (tratamentosVistos.size < 40) tratamentosVistos.add(it)
        }

        val (_, formSalvo) = PersistenciaLocalRegistros.carregarFormulario(context)
        formularioPontos.clear()
        formularioPontos.addAll(formSalvo)
        reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
        formSalvo.map { it.tratamento.trim() }.filter { it.isNotBlank() }.forEach {
            if (tratamentosVistos.size < 40) tratamentosVistos.add(it)
        }
        historicoTratamentos.addAll(tratamentosVistos)

        sincronizarAtivasPorColeta(cfg.coletaNome)
        atualizarListaColetas()
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
                        atualizarListaColetas()
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
        val varsCarregadas = PersistenciaLocalRegistros.carregarVariaveisExtras(context)
        variaveisExtras.clear()
        variaveisExtras.addAll(varsCarregadas)
        aplicarEstadoDaColetaAtiva(cfg)
        if (formularioPontos.isNotEmpty()) {
            modoColetaGuiada = true
        }

        if (!PersistenciaLocalRegistros.isTutorialVisto(context)) {
            mostrarTutorialPrimeiraVez = true
        }

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
                configRendimento = novaRend
                salvarMetadadosColeta()
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
                    onClick = {
                        PersistenciaLocalRegistros.marcarTutorialVisto(context)
                        mostrarTutorialPrimeiraVez = false
                    },
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
                            atualizarListaColetas()
                            Toast.makeText(context, "Nome alterado para \"$novo\"!", Toast.LENGTH_SHORT).show()
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
                        val nomeFinal = novoNome.trim().ifBlank { "Coleta 01" }
                        val novaCfg = PersistenciaLocalRegistros.criarNovaColetaIsolada(
                            context = context,
                            nome = nomeFinal,
                            templateId = templateSel.id,
                            manterPlanilhaAtual = manterFormularioAtual
                        )
                        parcelaNumManual = 1
                        plantaNumManual = 1
                        parcelaTxtManual = "1"
                        plantaTxtManual = "1"
                        tratamentoManual = templateSel.exemplosTratamento.firstOrNull() ?: "T-01"
                        aplicarEstadoDaColetaAtiva(novaCfg)
                        if (iniciarSomenteContagem) {
                            ativarModoSomenteContagem()
                        } else {
                            PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
                                context, nomeFinal, variaveisExtras.filter { it.ativa }.map { it.id }
                            )
                        }
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
        LaunchedEffect(Unit) { atualizarListaColetas() }
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
                                    PersistenciaLocalRegistros.alternarColetaAtiva(context, item.id)?.let { cfgCarregada ->
                                        aplicarEstadoDaColetaAtiva(cfgCarregada)
                                        Toast.makeText(context, "Coleta \"${cfgCarregada.coletaNome}\" aberta!", Toast.LENGTH_SHORT).show()
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
                                        onClick = {
                                            PersistenciaLocalRegistros.excluirColetaSalva(context, item.id)
                                            val novaAtiva = PersistenciaLocalRegistros.carregarConfigColeta(context)
                                            aplicarEstadoDaColetaAtiva(novaAtiva)
                                        },
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
                        atualizarListaColetas()
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
            onAtualizarClassificacoes = { novasCls ->
                classificacoesAtivasModelo = novasCls
                PersistenciaLocalRegistros.salvarClassificacoesModelo(
                    context, modelConfig.chaveModeloPersistente(), novasCls
                )
            },
            onConfirmar = { totalObj, caixasNovas ->
                val idx = listaRegistros.indexOfFirst { it.idCompleto.equals(reg.idCompleto, ignoreCase = true) }
                if (idx != -1) {
                    val atual = listaRegistros[idx]
                    val situacaoFinal = atual.situacaoPlanta
                    val totalFinal = if (situacaoFinal == SituacaoPlanta.NORMAL) totalObj else 0
                    val caixasFinais = if (situacaoFinal == SituacaoPlanta.NORMAL) caixasNovas else emptyList()
                    listaRegistros[idx] = atual.copy(
                        diagnostico = formatarDiagnosticoRegistro(
                            situacao = situacaoFinal,
                            motivoNaoAval = atual.motivoNaoAvaliavel,
                            totalObjetos = totalFinal,
                            caixas = caixasFinais
                        ),
                        totalObjetos = totalFinal,
                        caixas = caixasFinais
                    )
                    PersistenciaLocalRegistros.salvarRegistros(context, listaRegistros)
                    reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
                    if (formularioPontos.isNotEmpty()) {
                        PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
                    }
                    atualizarListaColetas()
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
                        atualizarListaColetas()
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
                            IconButton(onClick = { mostrarTutorialPrimeiraVez = true }) {
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
                val tratLimpo = tratamento.trim()
                if (tratLimpo.isNotBlank() &&
                    historicoTratamentos.size < 40 &&
                    historicoTratamentos.none { it.equals(tratLimpo, ignoreCase = true) }
                ) {
                    historicoTratamentos.add(tratLimpo)
                }
                val diagTexto = formatarDiagnosticoRegistro(
                    situacao = situacao,
                    motivoNaoAval = motivoNaoAval,
                    totalObjetos = totalObjetos,
                    caixas = caixas
                )
                listaRegistros.removeAll { it.idCompleto.equals(idCompleto, ignoreCase = true) }
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
                atualizarListaColetas()
                if (situacao == SituacaoPlanta.NORMAL) {
                    val totObjColeta = listaRegistros
                        .filter { it.situacaoPlanta == SituacaoPlanta.NORMAL }
                        .sumOf { it.totalObjetos }
                    val msgExtra = if (configRendimento.ativo) {
                        val rend = calcularRendimentoColeta(listaRegistros, configRendimento)
                        val sym = configRendimento.unidadeSimbolo.trim().ifBlank { "unid" }
                        " • Coleta: $totObjColeta (≈ ${formatarEstimativaCurta(rend.unidadesAcumuladas, sym)})"
                    } else {
                        " • Total coleta: $totObjColeta"
                    }
                    Toast.makeText(context, "Salvo: $totalObjetos$msgExtra", Toast.LENGTH_SHORT).show()
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

            val rendimentoResumoHero = remember(listaRegistros.toList(), configRendimento) {
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
                    onAtivarSomenteContagem = { ativarModoSomenteContagem() },
                    onAlternarVariavel = { variavel, ativa -> alternarVariavelNaColeta(variavel, ativa) },
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
                        carregarPlanilhaExemplo()
                        modoColetaGuiada = true
                        abaSelecionada = 1
                    },
                    onAbrirTutorial = { mostrarTutorialPrimeiraVez = true },
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
                                onToggleVariavel = { variavel, ativa -> alternarVariavelNaColeta(variavel, ativa) },
                                onDesativarTodas = { ativarModoSomenteContagem() },
                                onAbrirConfigVariaveis = { mostrarTelaConfigEnsaio = true },
                                onCarregarPlanilhaClick = { launcherPlanilha.launch("*/*") },
                                onCarregarExemploClick = { carregarPlanilhaExemplo() },
                                onLimparFormularioClick = {
                                    formularioPontos.clear()
                                    PersistenciaLocalRegistros.limparFormulario(context)
                                    atualizarListaColetas()
                                },
                                onModoImersivoChange = { modoImersivoAtivo = it },
                                classificacoesPersonalizadas = classificacoesAtivasModelo,
                                onAtualizarClassificacoes = ::atualizarClassificacoesAtivas,
                                templateCultura = templateAtual,
                                configRendimento = configRendimento,
                                gpsAtivo = gpsAtivo,
                                onAbrirConfigRendimento = { mostrarDialogConfigRendimento = true },
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
                                templateCultura = templateAtual,
                                configRendimento = configRendimento,
                                gpsAtivo = gpsAtivo,
                                onAbrirConfigRendimento = { mostrarDialogConfigRendimento = true },
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
                        val idx = listaRegistros.indexOfFirst { it.idCompleto.equals(atualizado.idCompleto, ignoreCase = true) }
                        if (idx >= 0) {
                            listaRegistros[idx] = atualizado
                            PersistenciaLocalRegistros.salvarRegistros(context, listaRegistros)
                            reconciliarFormularioComRegistros(formularioPontos, listaRegistros)
                            if (formularioPontos.isNotEmpty()) {
                                PersistenciaLocalRegistros.salvarFormulario(context, formularioPontos, coletaNome)
                            }
                            atualizarListaColetas()
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
                        atualizarListaColetas()
                        Toast.makeText(context, "Registro excluído.", Toast.LENGTH_SHORT).show()
                    }
                )

                else -> TelaConfiguracaoModelo(
                    configAtual = modelConfig,
                    onModoImersivoChange = { modoImersivoAtivo = it },
                    onSalvar = { novaConfig ->
                        modelConfig = novaConfig
                        PersistenciaLocalRegistros.salvarConfiguracaoModelo(context, novaConfig)
                        classificacoesAtivasModelo = PersistenciaLocalRegistros.carregarClassificacoesModelo(
                            context, novaConfig.chaveModeloPersistente()
                        )
                        modoColetaGuiada = formularioPontos.isNotEmpty()
                        abaSelecionada = 1
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
    templateAtual: TemplateCultura,
    totalColetasSalvas: Int,
    totalRegistros: Int,
    totalPontosFormulario: Int,
    pontosColetadosFormulario: Int = 0,
    rendimentoResumoTexto: String? = null,
    onAbrirConfigRendimento: () -> Unit = {},
    modelConfig: ModelConfig,
    variaveisAtivas: List<VariavelExtra>,
    todasVariaveis: List<VariavelExtra>,
    classesExportacao: List<String>,
    onRenomearColeta: () -> Unit,
    onNovaColeta: () -> Unit,
    onAbrirMinhasColetas: () -> Unit,
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
    var mostrarAjudaAnotacoes by remember { mutableStateOf(false) }

    if (mostrarAjudaAnotacoes) {
        AlertDialog(
            onDismissRequest = { mostrarAjudaAnotacoes = false },
            title = { Text("Anotações da coleta", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text(
                    "Modo rápido salva e exporta somente a contagem feita pela IA na foto. Para anotar campos extras (ex: Vírus, Cor, Diâmetro), toque em um chip ou em Gerenciar.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { mostrarAjudaAnotacoes = false },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("Entendi") }
            }
        )
    }

    val classesAmigaveis = remember(modelConfig.labelsFiltro, classesExportacao) {
        val origem = if (modelConfig.labelsFiltro.isNotEmpty()) modelConfig.labelsFiltro else classesExportacao
        origem.map { traduzirLabelDisplay(it) }.distinct()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Card da Coleta Ativa
        Surface(
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF164A1B), Color(0xFF2E7D32))
                        )
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "COLETA ATIVA",
                            color = Color(0xFFA5D6A7),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Surface(
                            color = Color.White.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Spa,
                                    null,
                                    tint = Color(0xFFC8E6C9),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    templateAtual.nomeExibicao,
                                    color = Color(0xFFE8F5E9),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Campo clicável de edição rápida do nome da coleta
                    Surface(
                        onClick = onRenomearColeta,
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.32f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    coletaNome,
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Toque para editar o nome da coleta",
                                    color = Color(0xFFC8E6C9),
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White.copy(alpha = 0.22f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Editar", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Métricas + Progresso da rota
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircleOutline,
                                        null,
                                        tint = Color(0xFF69F0AE),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        "$totalRegistros plantas salvas",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Surface(
                                color = Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Route,
                                        null,
                                        tint = Color(0xFFC8E6C9),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        if (totalPontosFormulario > 0) {
                                            "$pontosColetadosFormulario/$totalPontosFormulario na rota"
                                        } else {
                                            "Sem planilha"
                                        },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (totalPontosFormulario > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (pontosColetadosFormulario.toFloat() / totalPontosFormulario.toFloat()).coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF69F0AE),
                                trackColor = Color.White.copy(alpha = 0.20f)
                            )
                        }

                        if (!rendimentoResumoTexto.isNullOrBlank()) {
                            Surface(
                                onClick = onAbrirConfigRendimento,
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Rendimento estimado: $rendimentoResumoTexto",
                                        color = Color(0xFFFFE082),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "Ajustar",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Botões de gestão de coletas
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onAbrirMinhasColetas,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.FolderCopy, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Trocar ($totalColetasSalvas)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Button(
                            onClick = onNovaColeta,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = VERDE_ESCURO
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Nova coleta", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }

        // 2. Iniciar coleta em campo — Dois cartões visuais lado a lado
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Iniciar coleta em campo",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = VERDE_ESCURO,
                modifier = Modifier.padding(horizontal = 2.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ElevatedCard(
                    onClick = onIrParaFormulario,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F5E9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Route, null, tint = VERDE_APP, modifier = Modifier.size(22.dp))
                        }
                        Text(
                            "Modo Rota",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_ESCURO,
                            maxLines = 1
                        )
                        Text(
                            if (totalPontosFormulario > 0) {
                                "$pontosColetadosFormulario de $totalPontosFormulario avaliadas"
                            } else {
                                "Seguir planilha .xlsx/.csv"
                            },
                            fontSize = 11.sp,
                            color = Color(0xFF5F6F61),
                            maxLines = 2
                        )
                        Text(
                            "Abrir rota →",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_APP
                        )
                    }
                }

                ElevatedCard(
                    onClick = onIrParaLivre,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F5E9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoCamera, null, tint = VERDE_ESCURO, modifier = Modifier.size(22.dp))
                        }
                        Text(
                            "Coleta Livre",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_ESCURO,
                            maxLines = 1
                        )
                        Text(
                            "Fotografar e contar direto",
                            fontSize = 11.sp,
                            color = Color(0xFF5F6F61),
                            maxLines = 2
                        )
                        Text(
                            "Abrir câmera →",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_APP
                        )
                    }
                }
            }

            if (totalPontosFormulario == 0) {
                Surface(
                    onClick = onCarregarExemplo,
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFE8F5E9),
                    border = BorderStroke(1.dp, Color(0xFFC8E6C9)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayCircleOutline,
                            null,
                            tint = VERDE_APP,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Sem planilha? Carregar rota de exemplo (12 plantas)",
                            fontSize = 12.sp,
                            color = VERDE_ESCURO,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. Configuração rápida (IA e Anotações)
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onIrParaModelo() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFE8F5E9),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, null, tint = VERDE_APP, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Modelo de IA • Certeza ${(modelConfig.threshold * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = Color(0xFF5F6F61)
                        )
                        Text(
                            modelConfig.nomeExibicao,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = VERDE_ESCURO,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (classesAmigaveis.isEmpty()) "Reconhecendo todas as classes do modelo"
                            else "Contando: ${classesAmigaveis.take(4).joinToString(", ")}",
                            fontSize = 11.sp,
                            color = Color(0xFF5F6F61),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        onClick = onIrParaModelo,
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFE8F5E9)
                    ) {
                        Text(
                            "Trocar",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_ESCURO,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFFEEF3EE))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Anotações extras", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = VERDE_ESCURO)
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFF1F8E9)
                        ) {
                            Text(
                                if (variaveisAtivas.isEmpty()) "Só contagem" else "${variaveisAtivas.size} ativa(s)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = VERDE_APP,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        IconButton(
                            onClick = { mostrarAjudaAnotacoes = true },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(Icons.Default.Info, "Ajuda sobre anotações", tint = Color(0xFF5F6F61), modifier = Modifier.size(15.dp))
                        }
                    }
                    TextButton(onClick = onGerenciarVariaveis, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text("Gerenciar", fontSize = 12.sp, color = VERDE_APP, fontWeight = FontWeight.Bold)
                    }
                }

                val chipColors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFFE8F5E9),
                    selectedLabelColor = VERDE_ESCURO
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = variaveisAtivas.isEmpty(),
                            onClick = onAtivarSomenteContagem,
                            colors = chipColors,
                            label = { Text("Somente contagem IA", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                    items(todasVariaveis) { variavel ->
                        FilterChip(
                            selected = variavel.ativa,
                            onClick = { onAlternarVariavel(variavel, !variavel.ativa) },
                            colors = chipColors,
                            label = { Text(variavel.nome, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // 4. Dados salvos e Exportação
        ElevatedCard(
            onClick = onIrParaDados,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFE8F5E9),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.TableChart, null, tint = VERDE_ESCURO, modifier = Modifier.size(20.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Dados salvos e exportação",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = VERDE_ESCURO
                    )
                    Text(
                        "$totalRegistros planta(s) registrada(s) • Exportar CSV ou ZIP",
                        fontSize = 11.sp,
                        color = Color(0xFF5F6F61),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = VERDE_APP)
            }
        }

        // 5. Rodapé
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onAbrirTutorial) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, null, modifier = Modifier.size(15.dp), tint = VERDE_APP)
                Spacer(Modifier.width(4.dp))
                Text("Tutorial rápido", fontSize = 12.sp, color = VERDE_APP)
            }
            TextButton(onClick = onAbrirSobreOffline) {
                Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(15.dp), tint = Color(0xFF4E6E52))
                Spacer(Modifier.width(4.dp))
                Text("Privacidade 100% offline", fontSize = 12.sp, color = Color(0xFF4E6E52))
            }
        }
    }
}

// ════════════════════════════════════════════════════
// ABA 1: MODO ROTA (LAYOUT LIMPO IDÊNTICO AO CACAUAPP + PLANILHA UNIVERSAL)
// ════════════════════════════════════════════════════
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
            detectorSecundarioHelper = detectorSecundarioHelper,
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
            detectorSecundarioHelper = detectorSecundarioHelper,
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
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Identificação da coleta", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                OutlinedTextField(
                    value = nomeTemp,
                    onValueChange = { nomeTemp = it },
                    label = { Text("Nome da coleta ou experimento") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val limpo = nomeTemp.trim()
                            if (limpo.isNotBlank()) onSalvarNomeColeta(limpo)
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                    ) {
                        Text("Salvar nome", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onIniciarNovaColeta,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB0BEC5)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF455A64))
                    ) {
                        Text("Nova coleta", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Campos fixos (navegação)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) {
                    Text("Salvar campos e navegação", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Planilha de rota (.xlsx ou .csv)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                Text(
                    if (pontosRotaCount > 0) "$pontosRotaCount pontos carregados no Formulário."
                    else "Nenhuma planilha carregada.",
                    fontSize = 12.sp,
                    color = Color(0xFF5F6F61)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onImportarPlanilha,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (pontosRotaCount > 0) "Trocar" else "Importar", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (pontosRotaCount > 0) {
                        OutlinedButton(
                            onClick = onLimparPlanilha,
                            modifier = Modifier.weight(0.75f).heightIn(min = 52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                        ) {
                            Text("Limpar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anotações extras", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
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
