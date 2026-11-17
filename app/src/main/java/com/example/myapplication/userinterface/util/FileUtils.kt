package com.example.myapplication.userinterface.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.VariavelExtra
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Copia uma imagem selecionada na galeria para o diretório privado do aplicativo,
 * garantindo caminho de arquivo real e persistência permanente no dispositivo.
 */
fun copiarUriGaleriaParaArquivoLocal(context: Context, uriOrigem: Uri): File? {
    return try {
        val input = context.contentResolver.openInputStream(uriOrigem) ?: return null
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val arquivoDestino = File(dir, "BREEDAI_GAL_${System.currentTimeMillis()}.jpg")
        input.use { stream ->
            arquivoDestino.outputStream().use { output ->
                stream.copyTo(output)
            }
        }
        if (arquivoDestino.exists() && arquivoDestino.length() > 0L) arquivoDestino else null
    } catch (_: Exception) {
        null
    }
}

/**
 * Escapa um valor para uso seguro como campo CSV (RFC 4180).
 */
internal fun csvField(value: Any?): String {
    val text = value?.toString() ?: ""
    return if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else {
        text
    }
}

internal fun gerarConteudoCsvBreedAI(
    registros: List<RegistroColeta>,
    labelTratamento: String,
    labelParcela: String,
    labelPlanta: String,
    variaveisAtivas: List<VariavelExtra>,
    classesModeloExportacao: List<String> = emptyList()
): String = buildString {
    val classesValidas = classesModeloExportacao
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

    val colunasFixasInicio = listOf(
        "Coleta",
        "ID_Planta",
        labelTratamento,
        labelParcela,
        labelPlanta,
        "Situacao_Planta",
        "Motivo_Nao_Avaliavel",
        "Total_Contado"
    )
    val colunasVariaveis = variaveisAtivas.map { it.nome }
    val colunasClassesIA = classesValidas.map { "IA_$it" }
    val colunasFixasFim = listOf("Data_Hora", "Latitude", "Longitude", "Deteccoes_IA")

    val header = (colunasFixasInicio + colunasVariaveis + colunasClassesIA + colunasFixasFim)
        .joinToString(",") { csvField(it) }
    appendLine(header)

    registros.forEach { reg ->
        val detJson = PersistenciaLocalRegistros.serializarCaixasParaCsv(reg.caixas)
        val valoresFixosInicio = listOf(
            reg.coletaNome,
            reg.idCompleto,
            reg.tratamento,
            reg.parcela,
            reg.planta,
            reg.situacaoPlanta.rotulo,
            reg.motivoNaoAvaliavel,
            reg.totalObjetos
        )
        val valoresVariaveis = variaveisAtivas.map { v -> reg.variaveisExtras[v.nome] ?: "" }
        val valoresClassesIA = classesValidas.map { cls ->
            reg.caixas.count { c -> c.rotulo.equals(cls, ignoreCase = true) }
        }
        val valoresFixosFim = listOf(reg.data, reg.lat, reg.lng, detJson)

        val linha = (valoresFixosInicio + valoresVariaveis + valoresClassesIA + valoresFixosFim)
            .joinToString(",") { csvField(it) }
        appendLine(linha)
    }
}

fun exportarCSVBreedAI(
    context: Context,
    registros: List<RegistroColeta>,
    coletaNome: String,
    labelTratamento: String,
    labelParcela: String,
    labelPlanta: String,
    variaveisAtivas: List<VariavelExtra>,
    classesModeloExportacao: List<String> = emptyList()
): File? {
    val coletaSafe = coletaNome.ifBlank { "Coleta" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val nomeFinal = "BreedAI_${coletaSafe}_${System.currentTimeMillis()}.csv"
    val conteudo = gerarConteudoCsvBreedAI(
        registros = registros,
        labelTratamento = labelTratamento,
        labelParcela = labelParcela,
        labelPlanta = labelPlanta,
        variaveisAtivas = variaveisAtivas,
        classesModeloExportacao = classesModeloExportacao
    )
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    val payload = bom + conteudo.toByteArray(StandardCharsets.UTF_8)

    // Mantém cópia local pronta em cacheDir/exports/ para compartilhamento imediato
    val exportCacheFile = try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        File(dir, nomeFinal).apply { writeBytes(payload) }
    } catch (_: Exception) {
        null
    }

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nomeFinal)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/BreedAI")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out -> out.write(payload) }
                Toast.makeText(context, "Planilha salva em Downloads/BreedAI!", Toast.LENGTH_SHORT).show()
                return exportCacheFile
            }
        }

        val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BreedAI")
        if (!downloadDir.exists()) downloadDir.mkdirs()
        val file = File(downloadDir, nomeFinal)
        FileOutputStream(file).use { out -> out.write(payload) }
        Toast.makeText(context, "Planilha salva em Downloads/BreedAI!", Toast.LENGTH_SHORT).show()
        return exportCacheFile ?: file
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao exportar CSV: ${e.message}", Toast.LENGTH_LONG).show()
        return exportCacheFile
    }
}

fun compartilharArquivoViaAndroid(
    context: Context,
    arquivo: File,
    mimeType: String,
    tituloChooser: String
) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", arquivo)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, tituloChooser))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao compartilhar arquivo: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
