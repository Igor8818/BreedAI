package com.example.myapplication.userinterface

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
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
import com.example.myapplication.userinterface.util.compartilharArquivoViaAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Composable
fun TelaConfiguracaoModelo(
    configAtual: ModelConfig,
    onSalvar: (ModelConfig) -> Unit
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
    var novoLabel by remember { mutableStateOf("") }
    var processandoArquivo by remember { mutableStateOf(false) }

    val listaLabels = remember(labelsTexto) {
        labelsTexto.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    val launcherModelo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        processandoArquivo = true
        coroutineScope.launch(Dispatchers.IO) {
            val importado = importarArquivoModeloPermanente(context, uri)
            withContext(Dispatchers.Main) {
                processandoArquivo = false
                if (importado != null) {
                    modeloSelecionado = null
                    modeloExternoUri = null
                    modeloInternoPath = importado.tflitePath
                    modeloExternoNome = importado.nomeExibicao
                    val msgImport = if (importado.labels.isNotEmpty()) {
                        labelsTexto = importado.labels.joinToString(", ")
                        "Modelo \"${importado.nomeExibicao}\" importado com sucesso!"
                    } else {
                        "Modelo \"${importado.nomeExibicao}\" importado sem rótulos embutidos — ajuste os rótulos da IA abaixo."
                    }
                    importado.threshold?.let {
                        thresholdTexto = (it * 100).toInt().toString()
                    }
                    importado.maxResultados?.let {
                        maxResultados = it
                    }
                    importado.usarModeloSecundario?.let {
                        usarModeloSecundario = it
                    }
                    Toast.makeText(
                        context,
                        msgImport,
                        if (importado.labels.isNotEmpty()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Não foi possível importar o arquivo (.tflite ou pacote .zip).",
                        Toast.LENGTH_LONG
                    ).show()
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
        maxResultados = maxResultados,
        usarModeloSecundario = usarModeloSecundario,
        modeloSecundarioArquivo = configAtual.modeloSecundarioArquivo
    )
    val podeSalvar = modeloSelecionado != null || !modeloInternoPath.isNullOrBlank() || modeloExternoUri != null

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
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = verde)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("EXPORTAR .ZIP", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                    }
                    Button(
                        onClick = { onSalvar(configMontada) },
                        enabled = podeSalvar && !processandoArquivo,
                        modifier = Modifier.weight(1.25f).height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = verde)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("APLICAR MODELO", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
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
            // ── SEÇÃO 1: ESCOLHER MODELO DE VISÃO COMPUTACIONAL ──
            item {
                SectionHeader(icon = Icons.Default.Memory, title = "1. Escolher Modelo de Visão Computacional")
            }

            items(MODELOS_EMBUTIDOS) { modelo ->
                val selecionado = modeloInternoPath.isNullOrBlank() &&
                        modeloExternoUri == null &&
                        modeloSelecionado?.nomeArquivo == modelo.nomeArquivo
                CartaoModeloEmbutido(
                    modelo = modelo,
                    selecionado = selecionado,
                    onClick = {
                        modeloSelecionado = modelo
                        modeloInternoPath = null
                        modeloExternoUri = null
                        modeloExternoNome = ""
                        labelsTexto = modelo.labelsDefault.joinToString(", ")
                    }
                )
            }

            item {
                val customAtivo = !modeloInternoPath.isNullOrBlank() || modeloExternoUri != null
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (customAtivo) 2.dp else 1.dp,
                            color = if (customAtivo) verde else Color(0xFFDCE7DC),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (customAtivo) Color(0xFFE8F5E9) else Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (customAtivo) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = verde, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Modelo externo ativo:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B5E20))
                            }
                            OutlinedTextField(
                                value = modeloExternoNome,
                                onValueChange = { modeloExternoNome = it },
                                label = { Text("Nome de Exibição") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                "Compatível com modelos YOLO (.tflite) ou pacotes .zip exportados pelo BreedAI.",
                                fontSize = 12.sp,
                                color = Color(0xFF5F6F61)
                            )
                        }

                        OutlinedButton(
                            onClick = { launcherModelo.launch("*/*") },
                            enabled = !processandoArquivo,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = verde),
                            border = BorderStroke(1.dp, verde),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (processandoArquivo) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = verde, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("IMPORTANDO...")
                            } else {
                                Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (customAtivo) "TROCAR MODELO (.TFLITE / .ZIP)" else "IMPORTAR MODELO EXTERNO (.TFLITE / .ZIP)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ── SEÇÃO 2: MODO COMBINADO (2 IAS) ──
            item {
                SectionHeader(icon = Icons.Default.AutoAwesome, title = "2. Modo Combinado (2 IAs)")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (usarModeloSecundario) 2.dp else 1.dp,
                            color = if (usarModeloSecundario) verde else Color(0xFFDCE7DC),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (usarModeloSecundario) Color(0xFFE8F5E9) else Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Contagem + Diagnóstico",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF1B5E20)
                            )
                            Text(
                                "Usa o modelo de contagem na câmera e classifica doenças automaticamente na revisão.",
                                fontSize = 12.sp,
                                color = Color(0xFF4E5E50)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = usarModeloSecundario,
                            onCheckedChange = {
                                usarModeloSecundario = it
                                if (it) {
                                    val baseLabels = (listaLabels + com.example.myapplication.data.LABELS_DOENCA_CACAU + listOf("cacau", "fruto"))
                                        .distinct()
                                    labelsTexto = baseLabels.joinToString(", ")
                                }
                            }
                        )
                    }
                }
            }

            // ── SEÇÃO 3: SENSIBILIDADE E CLASSES DETECTADAS ──
            item {
                SectionHeader(icon = Icons.Default.Tune, title = "3. Sensibilidade e Classes Detectadas")
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFDCE7DC))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            val thresholdVal = thresholdTexto.toIntOrNull()?.coerceIn(1, 99) ?: 30
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Confiança mínima: $thresholdVal%", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF1B5E20))
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
                                Text("10% (mais sensível)", fontSize = 11.sp, color = Color.Gray)
                                Text("95% (mais restrito)", fontSize = 11.sp, color = Color.Gray)
                            }
                        }

                        HorizontalDivider(color = Color(0xFFEEF3EE))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Limite por foto", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF1B5E20))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { if (maxResultados > 1) maxResultados-- }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.RemoveCircle, null, tint = Color(0xFFC62828), modifier = Modifier.size(28.dp))
                                }
                                Text("$maxResultados", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
                                IconButton(onClick = { if (maxResultados < 50) maxResultados++ }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.AddCircle, null, tint = verde, modifier = Modifier.size(28.dp))
                                }
                            }
                        }

                        HorizontalDivider(color = Color(0xFFEEF3EE))

                        Text("Classes / Rótulos filtrados", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF1B5E20))

                        if (listaLabels.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listaLabels.chunked(2).forEach { linha ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        linha.forEach { label ->
                                            LabelChip(
                                                texto = label,
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
                            Text("Sem filtro: aceita todas as classes do modelo.", color = Color.Gray, fontSize = 12.sp)
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
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC62828)),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.ClearAll, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Aceitar todas as classes", fontSize = 12.sp)
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
    val tflitePath: String,
    val nomeExibicao: String,
    val labels: List<String>,
    val threshold: Float? = null,
    val maxResultados: Int? = null,
    val usarModeloSecundario: Boolean? = null
)

private fun importarArquivoModeloPermanente(context: Context, uri: Uri): ModeloImportado? {
    return try {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (rawBytes.isEmpty()) return null

        val dirModelos = File(context.filesDir, "modelos_ia").apply { mkdirs() }
        val nomeOriginal = obterNomeArquivo(context, uri)
            .substringBeforeLast(".")
            .ifBlank { "modelo_${System.currentTimeMillis()}" }
        val nomeSanitizado = nomeOriginal.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        // Se for pacote .zip exportado pelo BreedAI (magic bytes PK..) e contiver .tflite dentro
        val isZipContainer = ImportadorPlanilhaUniversal.isXlsxMagicBytes(rawBytes)
        if (isZipContainer && !isTfliteFlatBuffer(rawBytes)) {
            var tfliteBytes: ByteArray? = null
            var manifest: ModelConfig.ManifestData? = null
            ZipInputStream(rawBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val nomeLower = entry.name.lowercase(Locale.ROOT)
                    when {
                        nomeLower.endsWith(".tflite") -> tfliteBytes = zis.readBytes()
                        nomeLower == "model_config.json" || nomeLower.endsWith("/model_config.json") -> {
                            val json = zis.readBytes().toString(Charsets.UTF_8)
                            manifest = ModelConfig.fromManifestJson(json)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            val modelData = tfliteBytes
            if (modelData != null && modelData.isNotEmpty()) {
                val destFile = File(dirModelos, "${nomeSanitizado}_${System.currentTimeMillis()}.tflite")
                destFile.writeBytes(modelData)
                val labels = manifest?.labelsFiltro?.takeIf { it.isNotEmpty() }
                    ?: ObjectDetectorHelper.extractLabelsFromFile(destFile)
                return ModeloImportado(
                    tflitePath = destFile.absolutePath,
                    nomeExibicao = manifest?.nomeExibicao?.ifBlank { nomeOriginal } ?: nomeOriginal,
                    labels = labels,
                    threshold = manifest?.threshold,
                    maxResultados = manifest?.maxResultados,
                    usarModeloSecundario = manifest?.usarModeloSecundario
                )
            }
        }

        // Arquivo .tflite direto
        val destFile = File(dirModelos, "${nomeSanitizado}_${System.currentTimeMillis()}.tflite")
        destFile.writeBytes(rawBytes)
        val labelsExtraidos = ObjectDetectorHelper.extractLabelsFromFile(destFile)
        ModeloImportado(
            tflitePath = destFile.absolutePath,
            nomeExibicao = nomeOriginal,
            labels = labelsExtraidos
        )
    } catch (_: Exception) {
        null
    }
}

private fun isTfliteFlatBuffer(bytes: ByteArray): Boolean {
    return bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "TFL3"
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selecionado) 2.dp else 1.dp,
                color = if (selecionado) verde else Color.LightGray,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selecionado) Color(0xFFE8F5E9) else Color.White
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (selecionado) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                null,
                tint = if (selecionado) verde else Color.Gray
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(modelo.nomeExibicao, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(modelo.descricao, fontSize = 12.sp, color = Color.Gray)
                Text(
                    "Classes padrão: ${modelo.labelsDefault.joinToString(", ")}",
                    fontSize = 11.sp,
                    color = Color(0xFF558B2F)
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
