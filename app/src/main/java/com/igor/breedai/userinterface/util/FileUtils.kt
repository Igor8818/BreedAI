package com.igor.breedai.userinterface.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.PontoFormulario
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.VariavelExtra
import com.igor.breedai.data.calcularEstimativaPlanta
import com.igor.breedai.data.calcularRankingTratamentos
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

private fun nomeColunaUnidadeRendimento(simbolo: String): String {
    val limpo = simbolo.trim()
    if (limpo == "@") return "Unid_Estimada_Arrobas"
    val safe = limpo.replace(Regex("[^a-zA-Z0-9_]"), "")
    return if (safe.isNotBlank()) "Unid_Estimada_$safe" else "Unid_Estimada"
}

internal fun gerarConteudoCsvBreedAI(
    registros: List<RegistroColeta>,
    labelTratamento: String,
    labelParcela: String,
    labelPlanta: String,
    variaveisAtivas: List<VariavelExtra>,
    classesModeloExportacao: List<String> = emptyList(),
    pontosFormulario: List<PontoFormulario> = emptyList(),
    incluirPercentualClasses: Boolean = false,
    configRendimento: ConfigConversaoRendimento? = null
): String = buildString {
    val classesValidas = classesModeloExportacao
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.ROOT) }

    val colunasFixasInicio = listOf(
        "Coleta",
        "ID_Planta",
        labelTratamento,
        labelParcela,
        labelPlanta
    )
    val colunasFixasPosId = listOf(
        "Situacao_Planta",
        "Motivo_Nao_Avaliavel",
        "Total_Contado"
    )
    val colunasVariaveis = variaveisAtivas.map { it.nome }
    val colunasClassesIA = classesValidas.map { "IA_$it" }
    val colunasPctIA = if (incluirPercentualClasses && classesValidas.isNotEmpty()) {
        classesValidas.map { "IA_Pct_$it" }
    } else {
        emptyList()
    }
    val rendimentoAtivo = configRendimento != null && configRendimento.ativo
    val colunasRendimento = if (rendimentoAtivo) {
        listOf("Aproveitaveis", "Kg_Estimado", nomeColunaUnidadeRendimento(configRendimento!!.unidadeSimbolo))
    } else {
        emptyList()
    }
    val colunasFixasFim = listOf("Data_Hora", "Latitude", "Longitude", "Deteccoes_IA")

    val nomesReservados = (colunasFixasInicio + colunasFixasPosId + colunasVariaveis +
            colunasClassesIA + colunasPctIA + colunasRendimento + colunasFixasFim)
        .map { it.trim().lowercase(Locale.ROOT) }
        .toSet()

    val colunasPlanilhaExtras = linkedSetOf<String>()
    pontosFormulario.forEach { ponto ->
        ponto.colunasPlanilha.keys.forEach { chave ->
            val limpa = chave.trim()
            if (limpa.isNotBlank() && limpa.lowercase(Locale.ROOT) !in nomesReservados) {
                colunasPlanilhaExtras.add(limpa)
            }
        }
    }
    val listaColsPlanilha = colunasPlanilhaExtras.toList()

    val mapaPontosPorId = mutableMapOf<String, PontoFormulario>()
    val mapaPontosPorTripla = mutableMapOf<Triple<String, String, String>, PontoFormulario>()
    pontosFormulario.forEach { p ->
        val idPadrao = "${p.tratamento}_P${p.parcela}_Pl${p.planta}".lowercase(Locale.ROOT)
        mapaPontosPorId.putIfAbsent(idPadrao, p)
        val tripla = Triple(
            p.tratamento.trim().lowercase(Locale.ROOT),
            p.parcela.trim().lowercase(Locale.ROOT),
            p.planta.trim().lowercase(Locale.ROOT)
        )
        mapaPontosPorTripla.putIfAbsent(tripla, p)
    }

    val header = (colunasFixasInicio + listaColsPlanilha + colunasFixasPosId +
            colunasVariaveis + colunasClassesIA + colunasPctIA + colunasRendimento + colunasFixasFim)
        .joinToString(",") { csvField(it) }
    appendLine(header)

    registros.forEach { reg ->
        val detJson = PersistenciaLocalRegistros.serializarCaixasParaCsv(reg.caixas)
        val pontoCorrespondente = if (listaColsPlanilha.isNotEmpty()) {
            mapaPontosPorId[reg.idCompleto.trim().lowercase(Locale.ROOT)]
                ?: mapaPontosPorTripla[Triple(
                    reg.tratamento.trim().lowercase(Locale.ROOT),
                    reg.parcela.trim().lowercase(Locale.ROOT),
                    reg.planta.trim().lowercase(Locale.ROOT)
                )]
        } else null

        val valoresInicio = listOf(
            reg.coletaNome,
            reg.idCompleto,
            reg.tratamento,
            reg.parcela,
            reg.planta
        )
        val valoresPlanilha = listaColsPlanilha.map { col ->
            pontoCorrespondente?.colunasPlanilha?.get(col) ?: ""
        }
        val valoresPosId = listOf(
            reg.situacaoPlanta.rotulo,
            reg.motivoNaoAvaliavel,
            reg.totalObjetos
        )
        val valoresVariaveis = variaveisAtivas.map { v -> reg.variaveisExtras[v.nome] ?: "" }
        val contagensPorClasse = classesValidas.map { cls ->
            reg.caixas.count { c -> c.rotulo.equals(cls, ignoreCase = true) }
        }
        val valoresPctIA = if (colunasPctIA.isNotEmpty()) {
            contagensPorClasse.map { qtd ->
                if (reg.totalObjetos > 0) {
                    String.format(Locale.US, "%.1f", (qtd * 100.0) / reg.totalObjetos.toDouble())
                } else {
                    "0.0"
                }
            }
        } else {
            emptyList()
        }
        val valoresRendimento = if (rendimentoAtivo) {
            if (reg.situacaoPlanta == SituacaoPlanta.NORMAL) {
                val est = calcularEstimativaPlanta(reg.totalObjetos, reg.caixas, configRendimento!!)
                listOf(
                    est.aproveitaveis.toString(),
                    String.format(Locale.US, "%.2f", est.kgEstimado),
                    String.format(Locale.US, "%.2f", est.unidadesComerciais)
                )
            } else {
                listOf("0", "0.00", "0.00")
            }
        } else {
            emptyList()
        }
        val valoresFixosFim = listOf(reg.data, reg.lat, reg.lng, detJson)

        val linha = (valoresInicio + valoresPlanilha + valoresPosId +
                valoresVariaveis + contagensPorClasse + valoresPctIA + valoresRendimento + valoresFixosFim)
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
    classesModeloExportacao: List<String> = emptyList(),
    pontosFormulario: List<PontoFormulario> = emptyList(),
    incluirPercentualClasses: Boolean = false,
    configRendimento: ConfigConversaoRendimento? = null
): File? {
    val coletaSafe = coletaNome.ifBlank { "Coleta" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val nomeFinal = "BreedAI_${coletaSafe}_${System.currentTimeMillis()}.csv"
    val conteudo = gerarConteudoCsvBreedAI(
        registros = registros,
        labelTratamento = labelTratamento,
        labelParcela = labelParcela,
        labelPlanta = labelPlanta,
        variaveisAtivas = variaveisAtivas,
        classesModeloExportacao = classesModeloExportacao,
        pontosFormulario = pontosFormulario,
        incluirPercentualClasses = incluirPercentualClasses,
        configRendimento = configRendimento
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

    return salvarArquivoEmDownloadsBreedAI(
        context = context,
        nomeArquivo = nomeFinal,
        mimeType = "text/csv",
        escreverConteudo = { out -> out.write(payload) },
        cacheFallback = exportCacheFile,
        mensagemSucesso = "Planilha salva em Downloads/BreedAI!",
        prefixoErro = "Erro ao exportar CSV"
    )
}

fun exportarPacoteZipBreedAI(
    context: Context,
    registros: List<RegistroColeta>,
    coletaNome: String,
    labelTratamento: String,
    labelParcela: String,
    labelPlanta: String,
    variaveisAtivas: List<VariavelExtra>,
    classesModeloExportacao: List<String> = emptyList(),
    pontosFormulario: List<PontoFormulario> = emptyList(),
    incluirPercentualClasses: Boolean = false,
    configRendimento: ConfigConversaoRendimento? = null
): File? {
    return try {
        val coletaSafe = coletaNome.ifBlank { "Coleta" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val timestamp = System.currentTimeMillis()
        val nomeZip = "BreedAI_Pacote_${coletaSafe}_${timestamp}.zip"
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zipFile = File(dir, nomeZip)

        val csvConteudo = gerarConteudoCsvBreedAI(
            registros = registros,
            labelTratamento = labelTratamento,
            labelParcela = labelParcela,
            labelPlanta = labelPlanta,
            variaveisAtivas = variaveisAtivas,
            classesModeloExportacao = classesModeloExportacao,
            pontosFormulario = pontosFormulario,
            incluirPercentualClasses = incluirPercentualClasses,
            configRendimento = configRendimento
        )
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val csvBytes = bom + csvConteudo.toByteArray(StandardCharsets.UTF_8)

        val classesOrdenadas = (classesModeloExportacao + registros.flatMap { r -> r.caixas.map { it.rotulo } })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .ifEmpty { listOf("objeto") }
        val indicePorClasseLower = classesOrdenadas
            .mapIndexed { idx, nome -> nome.lowercase(Locale.ROOT) to idx }
            .toMap()

        val nomesUsados = mutableSetOf<String>()
        fun nomeUnico(base: String): String {
            val limpo = base.ifBlank { "planta" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            var candidato = limpo
            var n = 2
            while (!nomesUsados.add(candidato.lowercase(Locale.ROOT))) {
                candidato = "${limpo}_$n"
                n++
            }
            return candidato
        }

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("planilha_${coletaSafe}.csv"))
            zos.write(csvBytes)
            zos.closeEntry()

            val resumoCsv = gerarResumoTratamentosCsvBreedAI(
                registros = registros,
                labelTratamento = labelTratamento,
                configRendimento = configRendimento
            )
            if (resumoCsv.isNotBlank()) {
                zos.putNextEntry(ZipEntry("resumo_tratamentos.csv"))
                zos.write(bom + resumoCsv.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            zos.putNextEntry(ZipEntry("classes.txt"))
            zos.write(classesOrdenadas.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            registros.forEach { reg ->
                val baseId = nomeUnico(reg.idCompleto)

                if (reg.caixas.isNotEmpty()) {
                    val yoloTxt = buildString {
                        reg.caixas.forEach { cx ->
                            val clsIdx = indicePorClasseLower[cx.rotulo.trim().lowercase(Locale.ROOT)] ?: 0
                            val w = (cx.normRight - cx.normLeft).coerceIn(0f, 1f)
                            val h = (cx.normBottom - cx.normTop).coerceIn(0f, 1f)
                            val cxNorm = ((cx.normLeft + cx.normRight) / 2f).coerceIn(0f, 1f)
                            val cyNorm = ((cx.normTop + cx.normBottom) / 2f).coerceIn(0f, 1f)
                            appendLine(String.format(Locale.US, "%d %.6f %.6f %.6f %.6f", clsIdx, cxNorm, cyNorm, w, h))
                        }
                    }
                    zos.putNextEntry(ZipEntry("labels_yolo/${baseId}.txt"))
                    zos.write(yoloTxt.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }

                if (reg.uri != Uri.EMPTY) {
                    abrirInputStreamUri(context, reg.uri)?.use { input ->
                        zos.putNextEntry(ZipEntry("fotos/${baseId}.jpg"))
                        input.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }
        }

        salvarArquivoEmDownloadsBreedAI(
            context = context,
            nomeArquivo = nomeZip,
            mimeType = "application/zip",
            escreverConteudo = { out -> zipFile.inputStream().use { it.copyTo(out) } },
            cacheFallback = zipFile,
            mensagemSucesso = "Pacote ZIP salvo em Downloads/BreedAI!",
            prefixoErro = "Erro ao exportar pacote ZIP"
        )
    } catch (e: Exception) {
        mostrarToastSeguro(context, "Erro ao gerar pacote ZIP: ${e.message}", Toast.LENGTH_LONG)
        null
    }
}

internal fun gerarResumoTratamentosCsvBreedAI(
    registros: List<RegistroColeta>,
    labelTratamento: String,
    configRendimento: ConfigConversaoRendimento? = null
): String {
    val ranking = calcularRankingTratamentos(
        registros = registros,
        config = configRendimento ?: ConfigConversaoRendimento(ativo = false)
    )
    if (ranking.isEmpty()) return ""
    val rendOn = configRendimento?.ativo == true
    val colUnid = if (rendOn) nomeColunaUnidadeRendimento(configRendimento!!.unidadeSimbolo) else ""
    return buildString {
        val cols = buildList {
            add("Posicao")
            add(labelTratamento)
            add("Plantas")
            add("Avaliaveis")
            add("Estande_Pct")
            add("Total_Contado")
            add("Aproveitaveis")
            add("Doentes")
            add("Media_Aproveitaveis")
            add("Taxa_Doenca_Pct")
            if (rendOn) {
                add("Kg_Estimado")
                add(colUnid)
                add("${colUnid}_Por_Ha")
            }
        }
        appendLine(cols.joinToString(",") { csvField(it) })
        ranking.forEachIndexed { idx, r ->
            val itensKg = (configRendimento?.itensPorKg ?: 22.0).coerceAtLeast(0.01)
            val kgTot = r.totalAproveitaveis.toDouble() / itensKg
            val vals = buildList<Any> {
                add(idx + 1)
                add(r.tratamento)
                add(r.totalPlantas)
                add(r.plantasValidas)
                add(String.format(Locale.US, "%.1f", r.estandePercentual))
                add(r.totalObjetos)
                add(r.totalAproveitaveis)
                add(r.totalDoentes)
                add(String.format(Locale.US, "%.2f", r.mediaAproveitaveis))
                add(String.format(Locale.US, "%.1f", r.taxaDoencaPercentual))
                if (rendOn) {
                    add(String.format(Locale.US, "%.2f", kgTot))
                    add(String.format(Locale.US, "%.2f", r.unidadesAcumuladas))
                    add(String.format(Locale.US, "%.2f", r.unidadesPorHa))
                }
            }
            appendLine(vals.joinToString(",") { csvField(it) })
        }
    }
}

private fun mostrarToastSeguro(context: Context, mensagem: String, duracao: Int) {
    try {
        val mainLooper = Looper.getMainLooper() ?: return
        if (Looper.myLooper() == mainLooper) {
            Toast.makeText(context, mensagem, duracao).show()
        } else {
            Handler(mainLooper).post {
                try {
                    Toast.makeText(context, mensagem, duracao).show()
                } catch (_: Throwable) {}
            }
        }
    } catch (_: Throwable) {}
}

private fun abrirInputStreamUri(context: Context, uri: Uri): java.io.InputStream? =
    try { context.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
        ?: try { uri.path?.let { p -> File(p).takeIf { it.exists() }?.inputStream() } } catch (_: Exception) { null }

private fun salvarArquivoEmDownloadsBreedAI(
    context: Context,
    nomeArquivo: String,
    mimeType: String,
    escreverConteudo: (java.io.OutputStream) -> Unit,
    cacheFallback: File?,
    mensagemSucesso: String,
    prefixoErro: String
): File? {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nomeArquivo)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/BreedAI")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out -> escreverConteudo(out) }
                mostrarToastSeguro(context, mensagemSucesso, Toast.LENGTH_SHORT)
                return cacheFallback
            }
        }

        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (publicDownloads == null) {
            return cacheFallback
        }
        val downloadDir = File(publicDownloads, "BreedAI")
        if (!downloadDir.exists()) downloadDir.mkdirs()
        val file = File(downloadDir, nomeArquivo)
        FileOutputStream(file).use { out -> escreverConteudo(out) }
        mostrarToastSeguro(context, mensagemSucesso, Toast.LENGTH_SHORT)
        return cacheFallback ?: file
    } catch (e: Exception) {
        mostrarToastSeguro(context, "$prefixoErro: ${e.message}", Toast.LENGTH_LONG)
        return cacheFallback
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
        mostrarToastSeguro(context, "Erro ao compartilhar arquivo: ${e.message}", Toast.LENGTH_LONG)
    }
}

fun compartilharTextoViaAndroid(
    context: Context,
    texto: String,
    tituloChooser: String
) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texto)
        }
        context.startActivity(Intent.createChooser(intent, tituloChooser))
    } catch (e: Exception) {
        mostrarToastSeguro(context, "Erro ao compartilhar resumo: ${e.message}", Toast.LENGTH_LONG)
    }
}
