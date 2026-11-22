package com.example.myapplication.userinterface

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.MODELOS_EMBUTIDOS
import com.example.myapplication.data.ModelConfig
import com.example.myapplication.data.ModelEmbutido
import com.example.myapplication.userinterface.util.ImportadorPlanilhaUniversal
import com.example.myapplication.userinterface.util.ObjectDetectorHelper
import com.example.myapplication.userinterface.util.PersistenciaLocalRegistros
import com.example.myapplication.userinterface.util.compartilharArquivoViaAndroid
import com.example.myapplication.userinterface.util.criarImageCaptureBreedAI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelaConfiguracaoModelo(
    configAtual: ModelConfig,
    onSalvar: (ModelConfig) -> Unit,
    onGerarVariaveisDoModelo: ((List<String>) -> Unit)? = null,
    onModoImersivoChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val verde = Color(0xFF2E7D32)

    var modeloSelecionado by remember(configAtual) { mutableStateOf(configAtual.modeloSelecionado) }
    var modeloInternoPath by remember(configAtual) { mutableStateOf(configAtual.modeloInternoPath) }
    var modeloExternoUri by remember(configAtual) { mutableStateOf(configAtual.modeloExternoUri) }
    var modeloExternoNome by remember(configAtual) { mutableStateOf(configAtual.modeloExternoNome) }

    var labelsTexto by remember(configAtual) { mutableStateOf(configAtual.labelsFiltro.joinToString(", ")) }
    var thresholdTexto by remember(configAtual) { mutableStateOf((configAtual.threshold * 100).toInt().toString()) }
    var maxResultados by remember(configAtual) { mutableIntStateOf(configAtual.maxResultados) }
    var usarModeloSecundario by remember(configAtual) { mutableStateOf(configAtual.usarModeloSecundario) }
    var incluirPercentualClassesCsv by remember(configAtual) { mutableStateOf(configAtual.incluirPercentualClassesCsv) }
    var novoLabel by remember { mutableStateOf("") }
    var buscaClasseModelo by remember { mutableStateOf("") }
    var novaClassificacaoTexto by remember { mutableStateOf("") }
    var processandoArquivo by remember { mutableStateOf(false) }
    var classesDisponiveisNoModelo by remember { mutableStateOf<List<String>>(emptyList()) }
    var classificacoesPersonalizadas by remember { mutableStateOf<List<String>>(emptyList()) }
    var diagnosticoModelo by remember { mutableStateOf<ObjectDetectorHelper.ModelInspectionReport?>(null) }
    var modelosImportados by remember { mutableStateOf<List<PersistenciaLocalRegistros.ModeloImportadoLocal>>(emptyList()) }
    var mostrarCacauAvancado by remember { mutableStateOf(false) }
    var testandoNaCamera by remember { mutableStateOf(false) }
    var erroImportacaoDialog by remember { mutableStateOf<String?>(null) }
    var infoDialogTitulo by remember { mutableStateOf<String?>(null) }
    var infoDialogTexto by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        modelosImportados = withContext(Dispatchers.IO) {
            PersistenciaLocalRegistros.listarModelosImportados(context)
        }
    }

    LaunchedEffect(testandoNaCamera) {
        onModoImersivoChange(testandoNaCamera)
    }
    DisposableEffect(Unit) {
        onDispose { onModoImersivoChange(false) }
    }

    if (infoDialogTitulo != null) {
        AlertDialog(
            onDismissRequest = { infoDialogTitulo = null },
            title = { Text(infoDialogTitulo!!, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text(infoDialogTexto, fontSize = 13.sp, lineHeight = 19.sp) },
            confirmButton = {
                Button(
                    onClick = { infoDialogTitulo = null },
                    colors = ButtonDefaults.buttonColors(containerColor = verde)
                ) { Text("Entendi") }
            }
        )
    }

    val listaLabels = remember(labelsTexto) {
        labelsTexto.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    val chaveModeloAtual = remember(modeloSelecionado, modeloInternoPath, modeloExternoUri, modeloExternoNome) {
        ModelConfig(
            modeloSelecionado = modeloSelecionado,
            modeloInternoPath = modeloInternoPath,
            modeloExternoUri = modeloExternoUri,
            modeloExternoNome = modeloExternoNome.trim()
        ).chaveModeloPersistente()
    }

    LaunchedEffect(modeloSelecionado, modeloInternoPath) {
        withContext(Dispatchers.IO) {
            val inspecao = when {
                !modeloInternoPath.isNullOrBlank() -> {
                    val f = File(modeloInternoPath!!)
                    if (f.exists()) ObjectDetectorHelper.inspecionarModeloArquivo(f) else null
                }
                modeloSelecionado != null -> {
                    ObjectDetectorHelper.extrairLabelsDeAsset(context, modeloSelecionado!!.nomeArquivo)
                    val cached = File(context.cacheDir, modeloSelecionado!!.nomeArquivo)
                    if (cached.exists()) ObjectDetectorHelper.inspecionarModeloArquivo(cached) else null
                }
                else -> null
            }
            val labelsValidas = (inspecao?.labelsExtraidas ?: emptyList())
                .filter { it.isNotBlank() && it != "???" }
            val salvasClassif = PersistenciaLocalRegistros
                .carregarClassificacoesModelo(context, chaveModeloAtual)
            withContext(Dispatchers.Main) {
                diagnosticoModelo = inspecao
                classesDisponiveisNoModelo = if (labelsValidas.isNotEmpty()) labelsValidas else (salvasClassif ?: emptyList())
                classificacoesPersonalizadas = salvasClassif ?: if (labelsValidas.size in 2..30) {
                    labelsValidas
                } else {
                    modeloSelecionado?.labelsDefault ?: listaLabels
                }
            }
        }
    }

    if (erroImportacaoDialog != null) {
        AlertDialog(
            onDismissRequest = { erroImportacaoDialog = null },
            title = { Text("Modelo Não Compatível", fontWeight = FontWeight.Bold, color = Color(0xFFC62828)) },
            text = { Text(erroImportacaoDialog!!, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = { erroImportacaoDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = verde)
                ) { Text("Entendi") }
            }
        )
    }

    val launcherModelo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        processandoArquivo = true
        coroutineScope.launch(Dispatchers.IO) {
            val importado = importarArquivoModeloPermanente(context, uri)
            if (importado.compativel && !importado.tflitePath.isNullOrBlank()) {
                PersistenciaLocalRegistros.salvarNomeModeloImportado(
                    context, importado.tflitePath, importado.nomeExibicao
                )
                val labelsParaSalvar = importado.labels.filter { it != "???" && it.isNotBlank() }
                if (labelsParaSalvar.size in 1..30) {
                    val chaveNova = ModelConfig(
                        modeloInternoPath = importado.tflitePath,
                        modeloExternoNome = importado.nomeExibicao
                    ).chaveModeloPersistente()
                    PersistenciaLocalRegistros.salvarClassificacoesModelo(context, chaveNova, labelsParaSalvar)
                }
            }
            val atualizados = PersistenciaLocalRegistros.listarModelosImportados(context)
            withContext(Dispatchers.Main) {
                processandoArquivo = false
                modelosImportados = atualizados
                if (importado.compativel && !importado.tflitePath.isNullOrBlank()) {
                    modeloSelecionado = null
                    modeloExternoUri = null
                    modeloInternoPath = importado.tflitePath
                    modeloExternoNome = importado.nomeExibicao
                    val labelsLimpas = importado.labels.filter { it != "???" && it.isNotBlank() }
                    classesDisponiveisNoModelo = labelsLimpas
                    diagnosticoModelo = importado.inspecao
                    labelsTexto = when {
                        labelsLimpas.isEmpty() -> ""
                        labelsLimpas.size <= 30 -> labelsLimpas.joinToString(", ")
                        else -> ""
                    }
                    importado.threshold?.let { thresholdTexto = (it * 100).toInt().toString() }
                    importado.maxResultados?.let { maxResultados = it.coerceIn(1, 300) }
                    usarModeloSecundario = importado.usarModeloSecundario ?: false
                    Toast.makeText(
                        context,
                        importado.inspecao?.avisoUsuario ?: "Modelo \"${importado.nomeExibicao}\" importado!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    erroImportacaoDialog = importado.erroMensagem
                        ?: importado.inspecao?.avisoUsuario
                        ?: "Não foi possível validar o arquivo (.tflite ou pacote .zip)."
                }
            }
        }
    }

    val launcherLabelsAvulso = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.IO) {
            val bytes = lerBytesComLimite(context, uri, 1024 * 1024)
            val nomeArq = obterNomeArquivo(context, uri)
            val labelsLidas = if (bytes != null && bytes.isNotEmpty()) {
                ObjectDetectorHelper.parseLabelsDeTextoOuYaml(
                    bytes.toString(Charsets.UTF_8),
                    nomeArq
                ).filter { it != "???" && it.isNotBlank() }
            } else {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (labelsLidas.isEmpty()) {
                    Toast.makeText(context, "Nenhuma classe encontrada no arquivo selecionado.", Toast.LENGTH_LONG).show()
                } else {
                    classesDisponiveisNoModelo = labelsLidas
                    labelsTexto = if (labelsLidas.size <= 30) labelsLidas.joinToString(", ") else ""
                    if (labelsLidas.size in 2..30) {
                        classificacoesPersonalizadas = labelsLidas
                        PersistenciaLocalRegistros.salvarClassificacoesModelo(context, chaveModeloAtual, labelsLidas)
                    }
                    Toast.makeText(context, "${labelsLidas.size} classe(s) carregadas de $nomeArq!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val threshold = (thresholdTexto.toIntOrNull() ?: 30).coerceIn(10, 95) / 100f
    val configMontada = ModelConfig(
        modeloSelecionado = modeloSelecionado,
        modeloInternoPath = modeloInternoPath,
        modeloExternoUri = modeloExternoUri,
        modeloExternoNome = modeloExternoNome.trim(),
        labelsFiltro = listaLabels,
        threshold = threshold,
        maxResultados = maxResultados.coerceIn(1, 300),
        usarModeloSecundario = usarModeloSecundario,
        modeloSecundarioArquivo = configAtual.modeloSecundarioArquivo,
        incluirPercentualClassesCsv = incluirPercentualClassesCsv
    )
    val podeSalvar = modeloSelecionado != null || !modeloInternoPath.isNullOrBlank() || modeloExternoUri != null

    if (testandoNaCamera && podeSalvar) {
        BackHandler { testandoNaCamera = false }
        val imageCaptureTeste = remember {
            criarImageCaptureBreedAI()
        }
        val helperTeste = remember(
            configMontada.modeloSelecionado,
            configMontada.modeloInternoPath,
            configMontada.modeloExternoUri,
            configMontada.labelsFiltro,
            configMontada.maxResultados
        ) {
            criarDetectorHelperBreedAI(context, configMontada)
        }
        DisposableEffect(helperTeste) {
            onDispose { helperTeste.fechar() }
        }

        CameraFullscreenBreedAI(
            context = context,
            detectorHelper = helperTeste,
            imageCapture = imageCaptureTeste,
            modelConfig = configMontada,
            tituloHud = "Teste: ${configMontada.nomeExibicao}",
            idParaArquivo = "TESTE",
            modoSemFrutos = false,
            modoTesteRapido = true,
            onThresholdChange = { novo ->
                thresholdTexto = (novo * 100).toInt().coerceIn(10, 95).toString()
            },
            onCancelar = { testandoNaCamera = false },
            onFotoCapturada = { _, _, _, _ -> testandoNaCamera = false }
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val zip = exportarPacoteModeloZip(context, configMontada)
                                withContext(Dispatchers.Main) {
                                    if (zip != null) {
                                        compartilharArquivoViaAndroid(
                                            context = context,
                                            arquivo = zip,
                                            mimeType = "application/zip",
                                            tituloChooser = "Exportar / Compartilhar Pacote de Modelo BreedAI"
                                        )
                                    } else {
                                        Toast.makeText(context, "Erro ao empacotar o modelo para exportação.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = podeSalvar && !processandoArquivo,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = verde)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Exportar pacote", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                    }
                    Button(
                        onClick = {
                            if (classificacoesPersonalizadas.isNotEmpty()) {
                                com.example.myapplication.userinterface.util.PersistenciaLocalRegistros
                                    .salvarClassificacoesModelo(context, chaveModeloAtual, classificacoesPersonalizadas)
                            }
                            onSalvar(configMontada)
                        },
                        enabled = podeSalvar && !processandoArquivo,
                        modifier = Modifier.weight(1.25f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = verde)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Aplicar modelo", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            // ── TESTE RÁPIDO NA CÂMERA ──
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFE8F5E9),
                    border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                configMontada.nomeExibicao,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF1B5E20)
                            )
                            Text(
                                "Teste o modelo ao vivo e calibre a confiança na câmera",
                                fontSize = 11.sp,
                                color = Color(0xFF4E6E52)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { testandoNaCamera = true },
                            enabled = podeSalvar && !processandoArquivo,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = verde),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Testar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ── SEÇÃO 1: MODELO DE DETECÇÃO ──
            item {
                SectionHeader(icon = Icons.Default.Memory, title = "O que a câmera identifica")
            }

            items(MODELOS_EMBUTIDOS) { modelo ->
                val selecionado = modeloInternoPath.isNullOrBlank() &&
                        modeloExternoUri == null &&
                        modeloSelecionado?.nomeArquivo == modelo.nomeArquivo &&
                        modeloSelecionado?.nomeExibicao == modelo.nomeExibicao
                CartaoModeloEmbutido(
                    modelo = modelo,
                    selecionado = selecionado,
                    onClick = {
                        modeloSelecionado = modelo
                        modeloInternoPath = null
                        modeloExternoUri = null
                        modeloExternoNome = ""
                        labelsTexto = modelo.labelsDefault.joinToString(", ")
                        thresholdTexto = (modelo.thresholdSugerido * 100).toInt().toString()
                        classificacoesPersonalizadas = modelo.labelsDefault
                        val ehCacau = modelo.nomeArquivo == "detector.tflite" || modelo.nomeArquivo == "detector_doenca.tflite"
                        if (!ehCacau) {
                            usarModeloSecundario = false
                        }
                    }
                )
            }

            if (modelosImportados.isNotEmpty()) {
                item {
                    SectionHeader(icon = Icons.Default.Inventory2, title = "Meus modelos importados")
                }

                items(modelosImportados, key = { it.path }) { imp ->
                    val selecionado = modeloInternoPath == imp.path
                    val mb = String.format(Locale.US, "%.1f MB", imp.tamanhoBytes / (1024f * 1024f))
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (selecionado) Modifier.border(2.dp, verde, RoundedCornerShape(14.dp))
                                else Modifier
                            )
                            .clickable {
                                modeloSelecionado = null
                                modeloExternoUri = null
                                modeloInternoPath = imp.path
                                modeloExternoNome = imp.nomeExibicao
                                usarModeloSecundario = false
                                val chaveImp = ModelConfig(modeloInternoPath = imp.path).chaveModeloPersistente()
                                val salvasImp = PersistenciaLocalRegistros.carregarClassificacoesModelo(context, chaveImp)
                                labelsTexto = if (salvasImp != null && salvasImp.size in 1..30) {
                                    salvasImp.joinToString(", ")
                                } else {
                                    ""
                                }
                            },
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selecionado) Color(0xFFE8F5E9) else Color.White
                        ),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (selecionado) 0.dp else 1.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (selecionado) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint = if (selecionado) verde else Color(0xFF78909C)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(imp.nomeExibicao, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1C241D))
                                Text("Arquivo próprio • $mb", fontSize = 11.sp, color = Color(0xFF5F6F61))
                            }
                            IconButton(
                                onClick = {
                                    if (PersistenciaLocalRegistros.excluirModeloImportado(context, imp.path)) {
                                        if (modeloInternoPath == imp.path) {
                                            modeloInternoPath = null
                                            modeloExternoNome = ""
                                            modeloSelecionado = MODELOS_EMBUTIDOS.firstOrNull()
                                            labelsTexto = modeloSelecionado?.labelsDefault?.joinToString(", ").orEmpty()
                                        }
                                        modelosImportados = PersistenciaLocalRegistros.listarModelosImportados(context)
                                    }
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, "Remover modelo importado", tint = Color(0xFFC62828), modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
            }

            item {
                val customAtivo = !modeloInternoPath.isNullOrBlank() || modeloExternoUri != null
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (customAtivo) Modifier.border(2.dp, verde, RoundedCornerShape(14.dp))
                            else Modifier
                        ),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (customAtivo) Color(0xFFE8F5E9) else Color.White
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (customAtivo) 0.dp else 1.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (customAtivo) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = verde, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Modelo próprio em uso:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B5E20))
                            }
                            OutlinedTextField(
                                value = modeloExternoNome,
                                onValueChange = { modeloExternoNome = it },
                                label = { Text("Nome do modelo") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Usar modelo próprio (.tflite ou .zip)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1B5E20)
                                )
                                IconButton(
                                    onClick = {
                                        infoDialogTitulo = "Importar modelo próprio"
                                        infoDialogTexto = "Aceita modelos TensorFlow Lite (.tflite) de YOLOv5/v8/v11/Seg/OBB/SSD ou pacotes .zip (BreedAI, Roboflow, Ultralytics) com labels.txt ou data.yaml rodando 100% offline."
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Default.Info, "Detalhes da importação", tint = Color(0xFF5F6F61), modifier = Modifier.size(17.dp))
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { launcherModelo.launch("*/*") },
                            enabled = !processandoArquivo,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = verde),
                            border = BorderStroke(1.dp, verde),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) {
                            if (processandoArquivo) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = verde, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Verificando arquivo...", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            } else {
                                Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (customAtivo) "Importar outro arquivo (.tflite ou .zip)" else "Importar arquivo (.tflite ou .zip)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (customAtivo) {
                            OutlinedButton(
                                onClick = { launcherLabelsAvulso.launch("*/*") },
                                enabled = !processandoArquivo,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1B5E20)),
                                border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Anexar classes (.txt ou data.yaml)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // ── SEÇÃO 2: ANÁLISE DUPLA PARA CACAU (CONDICIONAL / EXPANSÍVEL) ──
            item {
                val modeloAtualEhCacau = modeloInternoPath.isNullOrBlank() &&
                        modeloExternoUri == null &&
                        (modeloSelecionado == null ||
                                modeloSelecionado?.nomeArquivo == "detector.tflite" ||
                                modeloSelecionado?.nomeArquivo == "detector_doenca.tflite")

                if (!modeloAtualEhCacau && !usarModeloSecundario && !mostrarCacauAvancado) {
                    OutlinedCard(
                        onClick = { mostrarCacauAvancado = true },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFDCE7DC)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Análise dupla de doenças (opcional)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF4E6E52)
                            )
                            Icon(Icons.Default.ExpandMore, null, tint = Color(0xFF4E6E52), modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(icon = Icons.Default.AutoAwesome, title = "Análise dupla para cacau")
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (usarModeloSecundario) Modifier.border(2.dp, verde, RoundedCornerShape(14.dp))
                                    else Modifier
                                ),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (usarModeloSecundario) Color(0xFFE8F5E9) else Color.White
                            ),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (usarModeloSecundario) 0.dp else 1.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "Contar + classificar doenças",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = Color(0xFF1B5E20)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            IconButton(
                                                onClick = {
                                                    infoDialogTitulo = "Como funciona a análise dupla"
                                                    infoDialogTexto = "1. Na câmera ao vivo: detecta os frutos rapidamente.\n2. Após a foto: o modelo de doenças separa cada fruto automaticamente em Sadio, Podridão Parda, Monilíase ou Vassoura de Bruxa."
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Info, "Ajuda sobre análise dupla", tint = Color(0xFF5F6F61), modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        Text(
                                            when {
                                                !modeloAtualEhCacau && usarModeloSecundario -> "Aviso: modelo atual não é cacau"
                                                usarModeloSecundario -> "Ativada • Separa frutos por doença na foto"
                                                else -> "Opcional • Somente para avaliações de cacau"
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = when {
                                                !modeloAtualEhCacau && usarModeloSecundario -> Color(0xFFE65100)
                                                usarModeloSecundario -> verde
                                                else -> Color(0xFF5F6F61)
                                            },
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Switch(
                                        checked = usarModeloSecundario,
                                        onCheckedChange = { ligar ->
                                            usarModeloSecundario = ligar
                                            if (ligar && modeloAtualEhCacau) {
                                                val baseLabels = (listaLabels + com.example.myapplication.data.LABELS_DOENCA_CACAU + listOf("cacau", "fruto"))
                                                    .distinct()
                                                labelsTexto = baseLabels.joinToString(", ")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── SEÇÃO 3: PRECISÃO E FILTROS ──
            item {
                SectionHeader(icon = Icons.Default.Tune, title = "Precisão e filtros")
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            val thresholdVal = thresholdTexto.toIntOrNull()?.coerceIn(1, 99) ?: 30
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Certeza mínima: $thresholdVal%", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF1B5E20))
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            infoDialogTitulo = "Certeza mínima da IA"
                                            infoDialogTexto = "Diminua se a IA estiver deixando de marcar frutos. Aumente se estiver marcando folhas ou galhos por engano."
                                        },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(Icons.Default.Info, "Ajuda sobre certeza mínima", tint = Color(0xFF5F6F61), modifier = Modifier.size(15.dp))
                                    }
                                }
                                if (thresholdVal > 60) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFFFECB3)) {
                                        Text(
                                            "Alto",
                                            fontSize = 11.sp,
                                            color = Color(0xFFE65100),
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Slider(
                                value = thresholdVal.toFloat(),
                                onValueChange = { thresholdTexto = it.toInt().toString() },
                                valueRange = 10f..95f,
                                steps = 16,
                                colors = SliderDefaults.colors(thumbColor = verde, activeTrackColor = verde)
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("10% (detecta mais)", fontSize = 11.sp, color = Color(0xFF5F6F61))
                                Text("95% (mais exigente)", fontSize = 11.sp, color = Color(0xFF5F6F61))
                            }
                        }

                        HorizontalDivider(color = Color(0xFFEEF3EE))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Máximo de itens por foto", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF1B5E20))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { if (maxResultados > 1) maxResultados-- }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.RemoveCircle, "Diminuir máximo", tint = Color(0xFF546E7A), modifier = Modifier.size(28.dp))
                                }
                                Text("$maxResultados", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
                                IconButton(onClick = { if (maxResultados < 300) maxResultados++ }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.AddCircle, "Aumentar máximo", tint = verde, modifier = Modifier.size(28.dp))
                                }
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(25, 50, 100, 250).forEach { preset ->
                                FilterChip(
                                    selected = maxResultados == preset,
                                    onClick = { maxResultados = preset },
                                    label = { Text("$preset itens", fontSize = 11.sp) }
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Exportar % por classe no CSV",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF1B5E20)
                                )
                                Text(
                                    "Calcula automaticamente IA_Pct_<Classe> apenas na planilha exportada",
                                    fontSize = 11.sp,
                                    color = Color(0xFF5F6F61)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = incluirPercentualClassesCsv,
                                onCheckedChange = { incluirPercentualClassesCsv = it }
                            )
                        }

                        HorizontalDivider(color = Color(0xFFEEF3EE))

                        Text("O que contar na foto (Filtro de classes)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF1B5E20))

                        diagnosticoModelo?.let { diag ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (diag.compativel) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                border = BorderStroke(1.dp, if (diag.compativel) Color(0xFFA5D6A7) else Color(0xFFEF9A9A)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        diag.arquiteturaResumo,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (diag.compativel) Color(0xFF1B5E20) else Color(0xFFC62828)
                                    )
                                    Text(
                                        diag.avisoUsuario,
                                        fontSize = 11.sp,
                                        color = Color(0xFF3E4E40)
                                    )
                                }
                            }
                        }

                        if (classesDisponiveisNoModelo.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Itens que este modelo sabe reconhecer (${classesDisponiveisNoModelo.size}): toque para ligar/desligar",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF4E6E52)
                                )
                                if (classesDisponiveisNoModelo.size > 8) {
                                    OutlinedTextField(
                                        value = buscaClasseModelo,
                                        onValueChange = { buscaClasseModelo = it },
                                        label = { Text("Buscar objeto ou fruta (ex: banana, apple)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val temBananaCoco = classesDisponiveisNoModelo.any { it.equals("banana", ignoreCase = true) }
                                    val temMacaCoco = classesDisponiveisNoModelo.any { it.equals("apple", ignoreCase = true) }
                                    if (temBananaCoco) {
                                        AssistChip(
                                            onClick = { labelsTexto = "banana" },
                                            label = { Text("Só Banana", fontSize = 11.sp) }
                                        )
                                    }
                                    if (temMacaCoco) {
                                        AssistChip(
                                            onClick = { labelsTexto = "apple" },
                                            label = { Text("Só Maçã", fontSize = 11.sp) }
                                        )
                                    }
                                    val sugeridas = classesDisponiveisNoModelo
                                        .filter {
                                            buscaClasseModelo.isBlank() ||
                                                it.contains(buscaClasseModelo.trim(), ignoreCase = true) ||
                                                traduzirLabelDisplay(it).contains(buscaClasseModelo.trim(), ignoreCase = true)
                                        }
                                        .take(15)
                                    sugeridas.forEach { cls ->
                                        val ativa = listaLabels.any { it.equals(cls, ignoreCase = true) }
                                        FilterChip(
                                            selected = ativa,
                                            onClick = {
                                                val nova = listaLabels.toMutableList()
                                                if (ativa) nova.removeAll { it.equals(cls, ignoreCase = true) }
                                                else nova.add(cls)
                                                labelsTexto = nova.joinToString(", ")
                                            },
                                            label = { Text(traduzirLabelDisplay(cls), fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            "Itens ativos para contagem (cada um vira uma coluna na planilha exportada):",
                            fontSize = 11.sp,
                            color = Color(0xFF5F6F61)
                        )

                        if (listaLabels.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listaLabels.chunked(2).forEach { linha ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        linha.forEach { label ->
                                            LabelChip(
                                                texto = traduzirLabelDisplay(label),
                                                onRemover = {
                                                    val nova = listaLabels.toMutableList().also { l -> l.remove(label) }
                                                    labelsTexto = nova.joinToString(", ")
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("Contando tudo: qualquer item reconhecido pela IA aparecerá na foto.", color = Color.Gray, fontSize = 12.sp)
                        }

                        if (onGerarVariaveisDoModelo != null && listaLabels.size > 1) {
                            OutlinedButton(
                                onClick = { onGerarVariaveisDoModelo(listaLabels) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = verde)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Confirmar classes selecionadas na planilha", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = novoLabel,
                                onValueChange = { novoLabel = it },
                                label = { Text("Adicionar classe") },
                                placeholder = { Text("ex: fruto, espiga...") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(
                                onClick = {
                                    val limpo = novoLabel.trim()
                                    if (limpo.isNotBlank()) {
                                        val nova = listaLabels.toMutableList()
                                        if (nova.none { it.equals(limpo, ignoreCase = true) }) {
                                            nova.add(limpo.lowercase(Locale.ROOT))
                                        }
                                        labelsTexto = nova.joinToString(", ")
                                        novoLabel = ""
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(verde)
                            ) {
                                Icon(Icons.Default.Add, null, tint = Color.White)
                            }
                        }

                        if (listaLabels.isNotEmpty()) {
                            TextButton(
                                onClick = { labelsTexto = "" },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF546E7A)),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.ClearAll, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Aceitar todas as classes", fontSize = 12.sp)
                            }
                        }

                        HorizontalDivider(color = Color(0xFFEEF3EE))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text(
                                    "Classificações na revisão",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF1B5E20)
                                )
                                Spacer(Modifier.width(4.dp))
                                IconButton(
                                    onClick = {
                                        infoDialogTitulo = "Classificações da revisão pós-foto"
                                        infoDialogTexto = "Opções exibidas ao tocar em um fruto depois da foto. Você pode remover o que não usa na sua região ou adicionar novos rótulos."
                                    },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(Icons.Default.Info, "Ajuda sobre classificações", tint = Color(0xFF5F6F61), modifier = Modifier.size(15.dp))
                                }
                            }
                            TextButton(
                                onClick = {
                                    com.example.myapplication.userinterface.util.PersistenciaLocalRegistros
                                        .limparClassificacoesModelo(context, chaveModeloAtual)
                                    val baseModelo = if (classesDisponiveisNoModelo.size in 2..30) {
                                        classesDisponiveisNoModelo
                                    } else {
                                        modeloSelecionado?.labelsDefault ?: listaLabels
                                    }
                                    classificacoesPersonalizadas = baseModelo
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("Puxar do modelo", fontSize = 11.sp, color = verde, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (classificacoesPersonalizadas.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                classificacoesPersonalizadas.chunked(2).forEach { linha ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        linha.forEach { cls ->
                                            LabelChip(
                                                texto = traduzirLabelDisplay(cls),
                                                onRemover = {
                                                    if (classificacoesPersonalizadas.size > 1) {
                                                        val nova = classificacoesPersonalizadas.toMutableList().apply { remove(cls) }
                                                        classificacoesPersonalizadas = nova
                                                        com.example.myapplication.userinterface.util.PersistenciaLocalRegistros
                                                            .salvarClassificacoesModelo(context, chaveModeloAtual, nova)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = novaClassificacaoTexto,
                                onValueChange = { novaClassificacaoTexto = it },
                                label = { Text("Nova classificação") },
                                placeholder = { Text("ex: Madura, Sigatoka...") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(
                                onClick = {
                                    val limpo = novaClassificacaoTexto.trim()
                                    if (limpo.isNotBlank()) {
                                        val nova = classificacoesPersonalizadas.toMutableList()
                                        if (nova.none { it.equals(limpo, ignoreCase = true) }) {
                                            nova.add(limpo)
                                            classificacoesPersonalizadas = nova
                                            com.example.myapplication.userinterface.util.PersistenciaLocalRegistros
                                                .salvarClassificacoesModelo(context, chaveModeloAtual, nova)
                                        }
                                        novaClassificacaoTexto = ""
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(verde)
                            ) {
                                Icon(Icons.Default.Add, null, tint = Color.White)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private data class ModeloImportado(
    val compativel: Boolean,
    val tflitePath: String? = null,
    val nomeExibicao: String = "",
    val labels: List<String> = emptyList(),
    val threshold: Float? = null,
    val maxResultados: Int? = null,
    val usarModeloSecundario: Boolean? = null,
    val inspecao: ObjectDetectorHelper.ModelInspectionReport? = null,
    val erroMensagem: String? = null
)

private fun lerBytesComLimite(context: Context, uri: Uri, maxBytes: Int): ByteArray? {
    val stream = context.contentResolver.openInputStream(uri) ?: return null
    return stream.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}

private fun importarArquivoModeloPermanente(context: Context, uri: Uri): ModeloImportado {
    return try {
        val maxTamanhoModelo = 160 * 1024 * 1024 // Limite anti-OOM de 160 MB
        val rawBytes = lerBytesComLimite(context, uri, maxTamanhoModelo)
            ?: return ModeloImportado(
                compativel = false,
                erroMensagem = "Arquivo muito grande (acima de 160 MB) ou inacessível."
            )
        if (rawBytes.isEmpty()) {
            return ModeloImportado(compativel = false, erroMensagem = "O arquivo selecionado está vazio.")
        }

        val dirModelos = File(context.filesDir, "modelos_ia").apply { mkdirs() }
        val nomeOriginal = obterNomeArquivo(context, uri)
            .substringBeforeLast(".")
            .ifBlank { "modelo_${System.currentTimeMillis()}" }
        val nomeSanitizado = nomeOriginal.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        val isZipContainer = ImportadorPlanilhaUniversal.isXlsxMagicBytes(rawBytes)
        if (isZipContainer && !ObjectDetectorHelper.isTfliteFlatBuffer(rawBytes)) {
            var tfliteBytes: ByteArray? = null
            var manifest: ModelConfig.ManifestData? = null
            var labelsExternoPrioridade = -1
            var labelsExternoNome = ""
            var labelsExternoConteudo = ""
            ZipInputStream(rawBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // Proteção contra Zip Slip (caminhos maliciosos ../) e Zip Bomb
                    if (!entry.isDirectory && ImportadorPlanilhaUniversal.isSafeZipEntryName(entry.name)) {
                        val nomeLower = entry.name.lowercase(Locale.ROOT)
                        val baseLower = nomeLower.substringAfterLast('/')
                        when {
                            nomeLower.endsWith(".tflite") -> {
                                tfliteBytes = ImportadorPlanilhaUniversal.readZipEntryBounded(zis, maxTamanhoModelo)
                            }
                            baseLower == "model_config.json" -> {
                                val jsonBytes = ImportadorPlanilhaUniversal.readZipEntryBounded(zis, 1024 * 1024)
                                if (jsonBytes != null) {
                                    manifest = ModelConfig.fromManifestJson(jsonBytes.toString(Charsets.UTF_8))
                                }
                            }
                            baseLower in setOf("labels.txt", "classes.txt", "labelmap.txt", "data.yaml", "metadata.yaml") ||
                                baseLower.endsWith(".yaml") || baseLower.endsWith(".yml") -> {
                                val prio = when (baseLower) {
                                    "labels.txt", "classes.txt", "labelmap.txt" -> 3
                                    "data.yaml" -> 2
                                    "metadata.yaml" -> 1
                                    else -> 0
                                }
                                val txtBytes = ImportadorPlanilhaUniversal.readZipEntryBounded(zis, 1024 * 1024)
                                if (txtBytes != null && prio >= labelsExternoPrioridade) {
                                    val txt = txtBytes.toString(Charsets.UTF_8)
                                    if (txt.isNotBlank()) {
                                        labelsExternoPrioridade = prio
                                        labelsExternoNome = baseLower
                                        labelsExternoConteudo = txt
                                    }
                                }
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            val modelData = tfliteBytes
                ?: return ModeloImportado(
                    compativel = false,
                    erroMensagem = "O pacote .zip não contém nenhum arquivo .tflite seguro."
                )

            val destFile = File(dirModelos, "${nomeSanitizado}_${System.currentTimeMillis()}.tflite")
            destFile.writeBytes(modelData)
            val inspecaoBruta = ObjectDetectorHelper.inspecionarModeloArquivo(destFile)
            if (!inspecaoBruta.compativel) {
                destFile.delete()
                return ModeloImportado(compativel = false, inspecao = inspecaoBruta, erroMensagem = inspecaoBruta.avisoUsuario)
            }
            val labelsExternas = if (labelsExternoConteudo.isNotBlank()) {
                ObjectDetectorHelper.parseLabelsDeTextoOuYaml(labelsExternoConteudo, labelsExternoNome)
            } else {
                emptyList()
            }
            val labels = manifest?.labelsFiltro?.takeIf { it.isNotEmpty() }
                ?: inspecaoBruta.labelsExtraidas.takeIf { it.isNotEmpty() }
                ?: labelsExternas
            val inspecaoFinal = if (inspecaoBruta.labelsExtraidas.isEmpty() && labels.isNotEmpty()) {
                inspecaoBruta.copy(
                    labelsExtraidas = labels,
                    avisoUsuario = "Compatível! Encontramos ${labels.size} categoria(s) no pacote."
                )
            } else {
                inspecaoBruta
            }
            return ModeloImportado(
                compativel = true,
                tflitePath = destFile.absolutePath,
                nomeExibicao = manifest?.nomeExibicao?.ifBlank { nomeOriginal } ?: nomeOriginal,
                labels = labels,
                threshold = manifest?.threshold,
                maxResultados = manifest?.maxResultados,
                usarModeloSecundario = manifest?.usarModeloSecundario,
                inspecao = inspecaoFinal
            )
        }

        if (!ObjectDetectorHelper.isTfliteFlatBuffer(rawBytes)) {
            return ModeloImportado(
                compativel = false,
                erroMensagem = "Arquivo inválido: o arquivo selecionado não possui assinatura TensorFlow Lite (TFL3) nem é um pacote .zip válido."
            )
        }

        val destFile = File(dirModelos, "${nomeSanitizado}_${System.currentTimeMillis()}.tflite")
        destFile.writeBytes(rawBytes)
        val inspecao = ObjectDetectorHelper.inspecionarModeloArquivo(destFile)
        if (!inspecao.compativel) {
            destFile.delete()
            return ModeloImportado(compativel = false, inspecao = inspecao, erroMensagem = inspecao.avisoUsuario)
        }
        ModeloImportado(
            compativel = true,
            tflitePath = destFile.absolutePath,
            nomeExibicao = nomeOriginal,
            labels = inspecao.labelsExtraidas,
            inspecao = inspecao
        )
    } catch (e: Exception) {
        ModeloImportado(
            compativel = false,
            erroMensagem = "Falha ao importar o modelo: ${e.message?.take(120) ?: "erro desconhecido"}"
        )
    }
}

internal fun traduzirLabelDisplay(label: String): String {
    return when (label.trim().lowercase(Locale.ROOT)) {
        "apple" -> "Maçã"
        "banana" -> "Banana"
        "orange" -> "Laranja"
        "podridaoparda", "podridão parda", "podridao", "podridão" -> "Podridão Parda"
        "vassourabruxa", "vassoura de bruxa", "vassoura" -> "Vassoura de Bruxa"
        "moniliase", "monilíase" -> "Monilíase"
        "sadio" -> "Sadio"
        "cacau" -> "Cacau"
        "fruto" -> "Fruto"
        else -> label
    }
}

private fun exportarPacoteModeloZip(context: Context, config: ModelConfig): File? {
    return try {
        val modelBytes: ByteArray? = when {
            !config.modeloInternoPath.isNullOrBlank() -> {
                File(config.modeloInternoPath).takeIf { it.exists() }?.readBytes()
            }
            config.modeloExternoUri != null -> {
                context.contentResolver.openInputStream(config.modeloExternoUri)?.use { it.readBytes() }
            }
            config.modeloSelecionado != null -> {
                context.assets.open(config.modeloSelecionado.nomeArquivo).use { it.readBytes() }
            }
            else -> null
        }
        if (modelBytes == null || modelBytes.isEmpty()) return null

        val dirExports = File(context.cacheDir, "exports").apply { mkdirs() }
        val nomeSafe = config.nomeExibicao.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val zipFile = File(dirExports, "${nomeSafe}_breedai_modelo.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("model.tflite"))
            zos.write(modelBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("model_config.json"))
            zos.write(config.toManifestJson().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        zipFile
    } catch (_: Exception) {
        null
    }
}

private fun obterNomeArquivo(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val nome = cursor.getString(idx)
                if (!nome.isNullOrBlank()) return nome
            }
        }
    } catch (_: Exception) {}
    return uri.lastPathSegment?.substringAfterLast("/") ?: "modelo.tflite"
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1B5E20))
    }
}

@Composable
private fun CartaoModeloEmbutido(
    modelo: ModelEmbutido,
    selecionado: Boolean,
    onClick: () -> Unit
) {
    val verde = Color(0xFF2E7D32)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selecionado) Modifier.border(2.dp, verde, RoundedCornerShape(14.dp))
                else Modifier
            )
            .clickable { onClick() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selecionado) Color(0xFFE8F5E9) else Color.White
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (selecionado) 0.dp else 1.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (selecionado) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                null,
                tint = if (selecionado) verde else Color(0xFF78909C)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(modelo.nomeExibicao, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1C241D))
                Text(modelo.descricao, fontSize = 12.sp, color = Color(0xFF5F6F61))
                Text(
                    "Identifica: ${modelo.labelsDefault.joinToString(", ") { traduzirLabelDisplay(it) }}",
                    fontSize = 11.sp,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun LabelChip(texto: String, onRemover: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFE8F5E9),
        modifier = Modifier.border(1.dp, Color(0xFF81C784), RoundedCornerShape(20.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(texto, fontSize = 13.sp, color = Color(0xFF2E7D32))
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Close,
                contentDescription = "Remover $texto",
                tint = Color(0xFF2E7D32),
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onRemover() }
            )
        }
    }
}
