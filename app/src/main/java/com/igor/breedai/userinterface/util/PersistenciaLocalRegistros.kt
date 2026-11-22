package com.igor.breedai.userinterface.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.util.AtomicFile
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.MODELOS_EMBUTIDOS
import com.igor.breedai.data.ModelConfig
import com.igor.breedai.data.PontoFormulario
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.data.TipoVariavelExtra
import com.igor.breedai.data.VariavelExtra
import com.igor.breedai.data.variaveisExtrasIniciais
import com.igor.breedai.userinterface.CaixaAuditavel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object PersistenciaLocalRegistros {

    private const val FILE_REGISTROS = "registros_breedai_local.json"
    private const val FILE_FORMULARIO = "formulario_breedai.json"
    private const val FILE_VARIAVEIS = "variaveis_extras_breedai.json"
    private const val DIR_COLETAS = "coletas"
    private const val DIR_MODELOS = "modelos_ia"
    private val fileLock = Any()

    data class ResumoColetaSalva(
        val id: String,
        val nome: String,
        val templateId: String,
        val totalRegistros: Int,
        val totalPontosRota: Int,
        val atualizadoEm: Long
    )

    data class ModeloImportadoLocal(
        val path: String,
        val nomeExibicao: String,
        val tamanhoBytes: Long,
        val modificadoEm: Long
    )

    private fun safeColetaId(coletaId: String): String =
        coletaId.trim().ifBlank { "coleta_padrao" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun arquivoColetaRelativo(coletaId: String, sufixo: String): String =
        "$DIR_COLETAS/${safeColetaId(coletaId)}_$sufixo"

    private fun garantirDiretorioColetas(context: Context) {
        try {
            File(context.filesDir, DIR_COLETAS).mkdirs()
        } catch (_: Exception) {}
    }

    private fun migrarLegadoSeNecessario(context: Context, coletaId: String) {
        if (safeColetaId(coletaId) != "coleta_padrao") return
        garantirDiretorioColetas(context)
        val regColeta = File(context.filesDir, arquivoColetaRelativo(coletaId, "registros.json"))
        val regLegado = File(context.filesDir, FILE_REGISTROS)
        if (!regColeta.exists() && regLegado.exists() && regLegado.length() > 0L) {
            try {
                regLegado.copyTo(regColeta, overwrite = true)
            } catch (_: Exception) {}
        }
        val formColeta = File(context.filesDir, arquivoColetaRelativo(coletaId, "formulario.json"))
        val formLegado = File(context.filesDir, FILE_FORMULARIO)
        if (!formColeta.exists() && formLegado.exists() && formLegado.length() > 0L) {
            try {
                formLegado.copyTo(formColeta, overwrite = true)
            } catch (_: Exception) {}
        }
    }

    // ── REGISTROS DE COLETA (100% offline com AtomicFile + Multi-Coletas) ──

    fun salvarRegistros(context: Context, registros: List<RegistroColeta>) {
        val snapshot = registros.toList()
        val json = serializarRegistrosParaJson(snapshot).toString()
        val cfg = carregarConfigColeta(context)
        garantirDiretorioColetas(context)
        escreverAtomico(context, arquivoColetaRelativo(cfg.coletaId, "registros.json"), json)
        escreverAtomico(context, FILE_REGISTROS, json)
        salvarMetaColetaArquivo(context, cfg)
    }

    fun carregarRegistros(context: Context): List<RegistroColeta> {
        val cfg = carregarConfigColeta(context)
        migrarLegadoSeNecessario(context, cfg.coletaId)
        val json = lerAtomico(context, arquivoColetaRelativo(cfg.coletaId, "registros.json"))
            ?: lerAtomico(context, FILE_REGISTROS)
            ?: return emptyList()
        return try {
            deserializarRegistrosDeJson(json)
        } catch (e: Exception) {
            Log.e("PersistenciaLocal", "Erro ao deserializar registros: ${e.message}", e)
            emptyList()
        }
    }

    fun serializarRegistrosParaJson(registros: List<RegistroColeta>): JSONArray {
        val root = JSONArray()
        for (reg in registros) {
            val extrasObj = JSONObject()
            reg.variaveisExtras.forEach { (k, v) -> extrasObj.put(k, v) }

            val obj = JSONObject().apply {
                put("uri", reg.uri.toString())
                put("idCompleto", reg.idCompleto)
                put("coletaNome", reg.coletaNome)
                put("tratamento", reg.tratamento)
                put("parcela", reg.parcela)
                put("planta", reg.planta)
                put("situacaoPlanta", reg.situacaoPlanta.name)
                put("motivoNaoAvaliavel", reg.motivoNaoAvaliavel)
                put("diagnostico", reg.diagnostico)
                put("data", reg.data)
                put("lat", reg.lat)
                put("lng", reg.lng)
                put("totalObjetos", reg.totalObjetos)
                put("variaveisExtras", extrasObj)
                put("caixas", serializarCaixasJsonArray(reg.caixas))
            }
            root.put(obj)
        }
        return root
    }

    fun deserializarRegistrosDeJson(jsonString: String): List<RegistroColeta> {
        if (jsonString.isBlank()) return emptyList()
        val arr = JSONArray(jsonString)
        val lista = mutableListOf<RegistroColeta>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uriStr = o.optString("uri", "")
            val caixasArr = o.optJSONArray("caixas") ?: JSONArray()
            val caixas = mutableListOf<CaixaAuditavel>()
            for (j in 0 until caixasArr.length()) {
                val c = caixasArr.getJSONObject(j)
                val x = c.optDouble("x", 0.0).toFloat()
                val y = c.optDouble("y", 0.0).toFloat()
                val w = c.optDouble("largura", 0.1).toFloat()
                val h = c.optDouble("altura", 0.1).toFloat()
                val rotulo = c.optString("rotulo", c.optString("classe", "Objeto"))
                caixas.add(
                    CaixaAuditavel(
                        normLeft = x.coerceIn(0f, 1f),
                        normTop = y.coerceIn(0f, 1f),
                        normRight = (x + w).coerceIn(0f, 1f),
                        normBottom = (y + h).coerceIn(0f, 1f),
                        rotulo = rotulo.ifBlank { "Objeto" },
                        manual = c.optBoolean("manual", false),
                        confianca = c.optDouble("confianca", 0.9).toFloat()
                    )
                )
            }

            val extrasMap = linkedMapOf<String, String>()
            val extrasObj = o.optJSONObject("variaveisExtras")
            if (extrasObj != null) {
                val keys = extrasObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extrasMap[key] = extrasObj.optString(key, "")
                }
            }

            val situacaoName = o.optString("situacaoPlanta", SituacaoPlanta.NORMAL.name)
            val situacao = SituacaoPlanta.entries.firstOrNull { it.name == situacaoName }
                ?: SituacaoPlanta.NORMAL

            lista.add(
                RegistroColeta(
                    uri = safeParseUri(uriStr),
                    idCompleto = o.optString("idCompleto"),
                    coletaNome = o.optString("coletaNome"),
                    tratamento = o.optString("tratamento"),
                    parcela = o.optString("parcela"),
                    planta = o.optString("planta"),
                    situacaoPlanta = situacao,
                    motivoNaoAvaliavel = o.optString("motivoNaoAvaliavel", ""),
                    diagnostico = o.optString("diagnostico"),
                    data = o.optString("data"),
                    lat = o.optDouble("lat", 0.0),
                    lng = o.optDouble("lng", 0.0),
                    totalObjetos = o.optInt("totalObjetos", 0),
                    variaveisExtras = extrasMap,
                    caixas = caixas
                )
            )
        }
        return lista
    }

    fun serializarCaixasJsonArray(caixas: List<CaixaAuditavel>): JSONArray {
        val arr = JSONArray()
        for (cx in caixas) {
            arr.put(
                JSONObject().apply {
                    put("rotulo", cx.rotulo)
                    put("confianca", cx.confianca.toDouble())
                    put("x", cx.normLeft.toDouble())
                    put("y", cx.normTop.toDouble())
                    put("largura", (cx.normRight - cx.normLeft).toDouble())
                    put("altura", (cx.normBottom - cx.normTop).toDouble())
                    put("manual", cx.manual)
                }
            )
        }
        return arr
    }

    fun serializarCaixasParaCsv(caixas: List<CaixaAuditavel>): String {
        if (caixas.isEmpty()) return ""
        return serializarCaixasJsonArray(caixas).toString()
    }

    // ── FORMULÁRIO / PLANILHA ATIVA (AtomicFile) ──

    fun salvarFormulario(context: Context, pontos: List<PontoFormulario>, coletaNome: String) {
        val snapshot = pontos.toList()
        val arr = JSONArray()
        for ((idx, item) in snapshot.withIndex()) {
            val colsObj = JSONObject()
            item.colunasPlanilha.forEach { (k, v) -> colsObj.put(k, v) }
            arr.put(
                JSONObject().apply {
                    put("ordem", if (item.ordem > 0) item.ordem else (idx + 1))
                    put("tratamento", item.tratamento)
                    put("parcela", item.parcela)
                    put("planta", item.planta)
                    put("coletado", item.coletado)
                    put("resumoResultado", item.resumoResultado)
                    put("colunasPlanilha", colsObj)
                }
            )
        }
        val root = JSONObject().apply {
            put("coletaNome", coletaNome)
            put("atualizadoEm", System.currentTimeMillis())
            put("itens", arr)
        }
        val json = root.toString()
        val cfg = carregarConfigColeta(context)
        garantirDiretorioColetas(context)
        escreverAtomico(context, arquivoColetaRelativo(cfg.coletaId, "formulario.json"), json)
        escreverAtomico(context, FILE_FORMULARIO, json)
        salvarMetaColetaArquivo(context, cfg)
    }

    fun limparFormulario(context: Context) {
        val cfg = carregarConfigColeta(context)
        synchronized(fileLock) {
            try {
                File(context.filesDir, FILE_FORMULARIO).delete()
                File(context.filesDir, "$FILE_FORMULARIO.bak").delete()
                val arqColeta = arquivoColetaRelativo(cfg.coletaId, "formulario.json")
                File(context.filesDir, arqColeta).delete()
                File(context.filesDir, "$arqColeta.bak").delete()
            } catch (_: Exception) {}
        }
    }

    fun carregarFormulario(context: Context): Pair<String, List<PontoFormulario>> {
        val cfg = carregarConfigColeta(context)
        migrarLegadoSeNecessario(context, cfg.coletaId)
        val texto = (lerAtomico(context, arquivoColetaRelativo(cfg.coletaId, "formulario.json"))
            ?: lerAtomico(context, FILE_FORMULARIO))?.trim() ?: return "" to emptyList()
        if (texto.isBlank()) return "" to emptyList()
        return try {
            val root = JSONObject(texto)
            val coletaNome = root.optString("coletaNome", "")
            val arr = root.optJSONArray("itens") ?: JSONArray()
            val lista = mutableListOf<PontoFormulario>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tratamento = o.optString("tratamento", "").trim()
                val parcela = o.optString("parcela", "1").trim().ifBlank { "1" }
                val planta = o.optString("planta", "${i + 1}").trim().ifBlank { "${i + 1}" }
                val colsMap = linkedMapOf<String, String>()
                val colsObj = o.optJSONObject("colunasPlanilha")
                if (colsObj != null) {
                    val keys = colsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        colsMap[k] = colsObj.optString(k, "")
                    }
                }
                if (tratamento.isNotBlank()) {
                    lista.add(
                        PontoFormulario(
                            tratamento = tratamento,
                            parcela = parcela,
                            planta = planta,
                            coletado = o.optBoolean("coletado", false),
                            resumoResultado = o.optString("resumoResultado", ""),
                            ordem = o.optInt("ordem", i + 1).coerceAtLeast(1),
                            colunasPlanilha = colsMap
                        )
                    )
                }
            }
            coletaNome to lista
        } catch (e: Exception) {
            Log.e("PersistenciaLocal", "Erro ao carregar formulário: ${e.message}", e)
            "" to emptyList()
        }
    }

    // ── VARIÁVEIS EXTRAS ESTILO FIELD BOOK (AtomicFile) ──

    fun salvarVariaveisExtras(context: Context, variaveis: List<VariavelExtra>) {
        val json = serializarVariaveisExtrasJson(variaveis).toString()
        escreverAtomico(context, FILE_VARIAVEIS, json)
    }

    fun carregarVariaveisExtras(context: Context): List<VariavelExtra> {
        val texto = lerAtomico(context, FILE_VARIAVEIS)
        if (texto.isNullOrBlank()) {
            val iniciais = variaveisExtrasIniciais()
            salvarVariaveisExtras(context, iniciais)
            return iniciais
        }
        return try {
            deserializarVariaveisExtrasJson(texto)
        } catch (_: Exception) {
            variaveisExtrasIniciais()
        }
    }

    fun serializarVariaveisExtrasJson(variaveis: List<VariavelExtra>): JSONArray {
        val arr = JSONArray()
        variaveis.forEachIndexed { idx, v ->
            arr.put(
                JSONObject().apply {
                    put("id", v.id)
                    put("nome", v.nome)
                    put("tipo", v.tipo.name)
                    put("ativa", v.ativa)
                    put("ordem", if (v.ordem > 0) v.ordem else idx + 1)
                    put("opcoes", JSONArray().apply { v.opcoes.forEach { put(it) } })
                }
            )
        }
        return arr
    }

    fun deserializarVariaveisExtrasJson(jsonString: String): List<VariavelExtra> {
        if (jsonString.isBlank()) return emptyList()
        val arr = JSONArray(jsonString)
        val lista = mutableListOf<VariavelExtra>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tipo = TipoVariavelExtra.entries.firstOrNull { it.name == o.optString("tipo") }
                ?: TipoVariavelExtra.TEXTO
            val opsArr = o.optJSONArray("opcoes") ?: JSONArray()
            val ops = (0 until opsArr.length()).mapNotNull { opsArr.optString(it)?.takeIf { s -> s.isNotBlank() } }
            val nome = o.optString("nome", "").trim()
            if (nome.isNotBlank()) {
                lista.add(
                    VariavelExtra(
                        id = o.optString("id", "var_$i"),
                        nome = nome,
                        tipo = tipo,
                        opcoes = ops,
                        ativa = o.optBoolean("ativa", true),
                        ordem = o.optInt("ordem", i + 1)
                    )
                )
            }
        }
        return lista
    }

    // ── CONFIGURAÇÃO DE MODELO IA & METADADOS DA COLETA ──

    private const val PREFS_MODELO = "breedai_model_prefs"
    private const val KEY_MODELO_ARQUIVO = "modelo_arquivo"
    private const val KEY_MODELO_NOME = "modelo_nome_exibicao"
    private const val KEY_INTERNO_PATH = "modelo_interno_path"
    private const val KEY_EXTERNO_URI = "externo_uri"
    private const val KEY_EXTERNO_NOME = "externo_nome"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_MAX_RESULTADOS = "max_resultados"
    private const val KEY_LABELS = "labels_filtro"
    private const val KEY_USAR_MODELO_SECUNDARIO = "usar_modelo_secundario"
    private const val KEY_MODELO_SECUNDARIO_ARQUIVO = "modelo_secundario_arquivo"
    private const val KEY_INCLUIR_PCT_CSV = "incluir_percentual_classes_csv"

    fun salvarConfiguracaoModelo(context: Context, config: ModelConfig) {
        try {
            val prefs = context.getSharedPreferences(PREFS_MODELO, Context.MODE_PRIVATE)
            val labelsJson = JSONArray().apply {
                config.labelsFiltro.forEach { put(it) }
            }.toString()

            val edit = prefs.edit()
                .putString(KEY_MODELO_ARQUIVO, config.modeloSelecionado?.nomeArquivo)
                .putString(KEY_MODELO_NOME, config.modeloSelecionado?.nomeExibicao)
                .putString(KEY_INTERNO_PATH, config.modeloInternoPath)
                .putString(KEY_EXTERNO_URI, config.modeloExternoUri?.toString())
                .putString(KEY_EXTERNO_NOME, config.modeloExternoNome)
                .putFloat(KEY_THRESHOLD, config.threshold)
                .putInt(KEY_MAX_RESULTADOS, config.maxResultados)
                .putString(KEY_LABELS, labelsJson)
                .putBoolean(KEY_USAR_MODELO_SECUNDARIO, config.usarModeloSecundario)
                .putString(KEY_MODELO_SECUNDARIO_ARQUIVO, config.modeloSecundarioArquivo)
                .putBoolean(KEY_INCLUIR_PCT_CSV, config.incluirPercentualClassesCsv)
            if (!config.modeloInternoPath.isNullOrBlank() && config.modeloExternoNome.isNotBlank()) {
                val nomeArq = File(config.modeloInternoPath).name
                edit.putString("import_name_$nomeArq", config.modeloExternoNome.trim())
            }
            edit.apply()
        } catch (e: Exception) {
            Log.e("PersistenciaLocal", "Erro ao salvar configuracao do modelo: ${e.message}")
        }
    }

    fun salvarNomeModeloImportado(context: Context, path: String, nomeExibicao: String) {
        try {
            val nomeArq = File(path).name
            context.getSharedPreferences(PREFS_MODELO, Context.MODE_PRIVATE).edit()
                .putString("import_name_$nomeArq", nomeExibicao.trim())
                .apply()
        } catch (_: Exception) {}
    }

    fun listarModelosImportados(context: Context): List<ModeloImportadoLocal> {
        return try {
            val dir = File(context.filesDir, DIR_MODELOS)
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            val prefs = context.getSharedPreferences(PREFS_MODELO, Context.MODE_PRIVATE)
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".tflite", ignoreCase = true) && it.length() > 16L }
                ?.map { arq ->
                    val nomeSalvo = prefs.getString("import_name_${arq.name}", null)
                    val nomeAmigavel = nomeSalvo?.takeIf { it.isNotBlank() }
                        ?: arq.nameWithoutExtension.replace(Regex("_\\d{10,}$"), "")
                    ModeloImportadoLocal(
                        path = arq.absolutePath,
                        nomeExibicao = nomeAmigavel,
                        tamanhoBytes = arq.length(),
                        modificadoEm = arq.lastModified()
                    )
                }
                ?.sortedByDescending { it.modificadoEm }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun excluirModeloImportado(context: Context, path: String): Boolean {
        return try {
            val arq = File(path)
            val deleted = arq.exists() && arq.delete()
            if (deleted) {
                context.getSharedPreferences(PREFS_MODELO, Context.MODE_PRIVATE).edit()
                    .remove("import_name_${arq.name}")
                    .apply()
            }
            deleted
        } catch (_: Exception) {
            false
        }
    }

    fun carregarConfiguracaoModelo(context: Context): ModelConfig {
        return try {
            val prefs = context.getSharedPreferences(PREFS_MODELO, Context.MODE_PRIVATE)
            val threshold = prefs.getFloat(KEY_THRESHOLD, 0.30f)
            val maxResultados = prefs.getInt(KEY_MAX_RESULTADOS, 25)
            val modeloArquivo = prefs.getString(KEY_MODELO_ARQUIVO, null)
            val modeloNome = prefs.getString(KEY_MODELO_NOME, null)
            val internoPath = prefs.getString(KEY_INTERNO_PATH, null)?.takeIf { File(it).exists() }
            val externoUriStr = prefs.getString(KEY_EXTERNO_URI, null)
            val externoNome = prefs.getString(KEY_EXTERNO_NOME, "") ?: ""
            val labelsJson = prefs.getString(KEY_LABELS, null)

            val labelsPadrao = listOf("cacau", "fruto")
            val labels = if (!labelsJson.isNullOrBlank()) {
                try {
                    val arr = JSONArray(labelsJson)
                    (0 until arr.length()).map { arr.getString(it) }
                } catch (_: Exception) {
                    labelsPadrao
                }
            } else {
                labelsPadrao
            }

            val usaCustom = !internoPath.isNullOrBlank() || !externoUriStr.isNullOrBlank()
            val modeloEmbutido = if (!usaCustom) {
                MODELOS_EMBUTIDOS.firstOrNull {
                    !modeloNome.isNullOrBlank() && it.nomeExibicao == modeloNome && it.nomeArquivo == modeloArquivo
                }
                    ?: MODELOS_EMBUTIDOS.firstOrNull { it.nomeArquivo == modeloArquivo }
                    ?: MODELOS_EMBUTIDOS.firstOrNull()
            } else {
                null
            }

            val labelsEfetivas = labels

            val externoUri = if (internoPath.isNullOrBlank() && !externoUriStr.isNullOrBlank()) {
                safeParseUri(externoUriStr)
            } else null
            val usarModeloSecundario = prefs.getBoolean(KEY_USAR_MODELO_SECUNDARIO, false)
            val modeloSecundarioArquivo = prefs.getString(KEY_MODELO_SECUNDARIO_ARQUIVO, "detector_doenca.tflite")
                ?.takeIf { it.isNotBlank() }
                ?: "detector_doenca.tflite"
            val incluirPctCsv = prefs.getBoolean(KEY_INCLUIR_PCT_CSV, false)
            val thresholdCalibrado = if (!usaCustom && threshold == 0.30f && modeloEmbutido != null && modeloEmbutido.thresholdSugerido < 0.30f) {
                modeloEmbutido.thresholdSugerido
            } else {
                threshold
            }

            ModelConfig(
                modeloSelecionado = modeloEmbutido,
                modeloInternoPath = internoPath,
                modeloExternoUri = externoUri,
                modeloExternoNome = externoNome,
                labelsFiltro = labelsEfetivas,
                threshold = thresholdCalibrado,
                maxResultados = maxResultados.coerceIn(1, 300),
                usarModeloSecundario = usarModeloSecundario,
                modeloSecundarioArquivo = modeloSecundarioArquivo,
                incluirPercentualClassesCsv = incluirPctCsv
            )
        } catch (e: Exception) {
            Log.e("PersistenciaLocal", "Erro ao carregar configuracao do modelo: ${e.message}")
            ModelConfig(modeloSelecionado = MODELOS_EMBUTIDOS.firstOrNull())
        }
    }

    private const val PREFS_COLETA = "breedai_coleta_prefs"

    data class ConfigColetaLocal(
        val coletaNome: String = "Coleta Campo 01",
        val labelTratamento: String = "Tratamento",
        val labelParcela: String = "Parcela",
        val labelPlanta: String = "Planta",
        val tipoNumParcela: Boolean = true,
        val tipoNumPlanta: Boolean = true,
        val autoAvancarPlanta: Boolean = true,
        val coletaId: String = "coleta_padrao",
        val templateId: String = "fruticultura",
        val rendimentoAtivo: Boolean = true,
        val rendimentoPresetId: String = "cacau_arroba",
        val rendimentoUnidade: String = "@",
        val rendimentoItensPorKg: Double = 22.0,
        val rendimentoKgPorUnidade: Double = 15.0,
        val rendimentoPlantasPorHa: Int = 1111,
        val rendimentoDescontarDoencas: Boolean = true
    ) {
        fun toConfigRendimento(): ConfigConversaoRendimento = ConfigConversaoRendimento(
            ativo = rendimentoAtivo,
            presetId = rendimentoPresetId,
            unidadeSimbolo = rendimentoUnidade,
            itensPorKg = rendimentoItensPorKg.coerceAtLeast(0.01),
            kgPorUnidade = rendimentoKgPorUnidade.coerceAtLeast(0.01),
            plantasPorHa = rendimentoPlantasPorHa.coerceAtLeast(0),
            descontarDoencas = rendimentoDescontarDoencas
        )
    }

    fun salvarConfigColeta(context: Context, cfg: ConfigColetaLocal) {
        try {
            val idLimpo = safeColetaId(cfg.coletaId)
            val normalizada = cfg.copy(coletaId = idLimpo)
            context.getSharedPreferences(PREFS_COLETA, Context.MODE_PRIVATE).edit()
                .putString("coleta_id", normalizada.coletaId)
                .putString("template_id", normalizada.templateId)
                .putString("coleta_nome", normalizada.coletaNome)
                .putString("label_tratamento", normalizada.labelTratamento)
                .putString("label_parcela", normalizada.labelParcela)
                .putString("label_planta", normalizada.labelPlanta)
                .putBoolean("num_parcela", normalizada.tipoNumParcela)
                .putBoolean("num_planta", normalizada.tipoNumPlanta)
                .putBoolean("auto_avancar_planta", normalizada.autoAvancarPlanta)
                .putBoolean("rendimento_ativo", normalizada.rendimentoAtivo)
                .putString("rendimento_preset_id", normalizada.rendimentoPresetId)
                .putString("rendimento_unidade", normalizada.rendimentoUnidade)
                .putFloat("rendimento_itens_por_kg", normalizada.rendimentoItensPorKg.toFloat())
                .putFloat("rendimento_kg_por_unid", normalizada.rendimentoKgPorUnidade.toFloat())
                .putInt("rendimento_plantas_ha", normalizada.rendimentoPlantasPorHa)
                .putBoolean("rendimento_descontar_doencas", normalizada.rendimentoDescontarDoencas)
                .apply()
            salvarMetaColetaArquivo(context, normalizada)
        } catch (_: Exception) {}
    }

    fun carregarConfigColeta(context: Context): ConfigColetaLocal {
        return try {
            val prefs = context.getSharedPreferences(PREFS_COLETA, Context.MODE_PRIVATE)
            val nomeBruto = prefs.getString("coleta_nome", "Coleta Campo 01") ?: "Coleta Campo 01"
            val nomeLimpo = if (nomeBruto == "Coleta_Campo_01" || nomeBruto.isBlank()) {
                "Coleta Campo 01"
            } else {
                nomeBruto
            }
            val tplId = prefs.getString("template_id", "fruticultura") ?: "fruticultura"
            val fallbackRend = ConfigConversaoRendimento.paraTemplate(TemplateCultura.fromId(tplId))
            ConfigColetaLocal(
                coletaId = safeColetaId(prefs.getString("coleta_id", "coleta_padrao") ?: "coleta_padrao"),
                templateId = tplId,
                coletaNome = nomeLimpo,
                labelTratamento = prefs.getString("label_tratamento", "Tratamento") ?: "Tratamento",
                labelParcela = prefs.getString("label_parcela", "Parcela") ?: "Parcela",
                labelPlanta = prefs.getString("label_planta", "Planta") ?: "Planta",
                tipoNumParcela = prefs.getBoolean("num_parcela", true),
                tipoNumPlanta = prefs.getBoolean("num_planta", true),
                autoAvancarPlanta = prefs.getBoolean("auto_avancar_planta", true),
                rendimentoAtivo = prefs.getBoolean("rendimento_ativo", fallbackRend.ativo),
                rendimentoPresetId = prefs.getString("rendimento_preset_id", fallbackRend.presetId) ?: fallbackRend.presetId,
                rendimentoUnidade = prefs.getString("rendimento_unidade", fallbackRend.unidadeSimbolo) ?: fallbackRend.unidadeSimbolo,
                rendimentoItensPorKg = prefs.getFloat("rendimento_itens_por_kg", fallbackRend.itensPorKg.toFloat()).toDouble(),
                rendimentoKgPorUnidade = prefs.getFloat("rendimento_kg_por_unid", fallbackRend.kgPorUnidade.toFloat()).toDouble(),
                rendimentoPlantasPorHa = prefs.getInt("rendimento_plantas_ha", fallbackRend.plantasPorHa),
                rendimentoDescontarDoencas = prefs.getBoolean("rendimento_descontar_doencas", fallbackRend.descontarDoencas)
            )
        } catch (_: Exception) {
            ConfigColetaLocal()
        }
    }

    private fun salvarMetaColetaArquivo(context: Context, cfg: ConfigColetaLocal) {
        try {
            garantirDiretorioColetas(context)
            val obj = JSONObject().apply {
                put("coletaId", safeColetaId(cfg.coletaId))
                put("coletaNome", cfg.coletaNome)
                put("templateId", cfg.templateId)
                put("labelTratamento", cfg.labelTratamento)
                put("labelParcela", cfg.labelParcela)
                put("labelPlanta", cfg.labelPlanta)
                put("tipoNumParcela", cfg.tipoNumParcela)
                put("tipoNumPlanta", cfg.tipoNumPlanta)
                put("autoAvancarPlanta", cfg.autoAvancarPlanta)
                put("rendimentoAtivo", cfg.rendimentoAtivo)
                put("rendimentoPresetId", cfg.rendimentoPresetId)
                put("rendimentoUnidade", cfg.rendimentoUnidade)
                put("rendimentoItensPorKg", cfg.rendimentoItensPorKg)
                put("rendimentoKgPorUnidade", cfg.rendimentoKgPorUnidade)
                put("rendimentoPlantasPorHa", cfg.rendimentoPlantasPorHa)
                put("rendimentoDescontarDoencas", cfg.rendimentoDescontarDoencas)
                put("atualizadoEm", System.currentTimeMillis())
            }
            escreverAtomico(context, arquivoColetaRelativo(cfg.coletaId, "meta.json"), obj.toString())
        } catch (_: Exception) {}
    }

    private fun lerMetaColetaArquivo(context: Context, coletaId: String): Pair<ConfigColetaLocal, Long>? {
        val raw = lerAtomico(context, arquivoColetaRelativo(coletaId, "meta.json")) ?: return null
        return try {
            val o = JSONObject(raw)
            val tplId = o.optString("templateId", "fruticultura").ifBlank { "fruticultura" }
            val fallbackRend = ConfigConversaoRendimento.paraTemplate(TemplateCultura.fromId(tplId))
            val cfg = ConfigColetaLocal(
                coletaId = safeColetaId(o.optString("coletaId", coletaId)),
                templateId = tplId,
                coletaNome = o.optString("coletaNome", "Coleta").ifBlank { "Coleta" },
                labelTratamento = o.optString("labelTratamento", "Tratamento").ifBlank { "Tratamento" },
                labelParcela = o.optString("labelParcela", "Parcela").ifBlank { "Parcela" },
                labelPlanta = o.optString("labelPlanta", "Planta").ifBlank { "Planta" },
                tipoNumParcela = o.optBoolean("tipoNumParcela", true),
                tipoNumPlanta = o.optBoolean("tipoNumPlanta", true),
                autoAvancarPlanta = o.optBoolean("autoAvancarPlanta", true),
                rendimentoAtivo = o.optBoolean("rendimentoAtivo", fallbackRend.ativo),
                rendimentoPresetId = o.optString("rendimentoPresetId", fallbackRend.presetId).ifBlank { fallbackRend.presetId },
                rendimentoUnidade = o.optString("rendimentoUnidade", fallbackRend.unidadeSimbolo),
                rendimentoItensPorKg = o.optDouble("rendimentoItensPorKg", fallbackRend.itensPorKg).coerceAtLeast(0.01),
                rendimentoKgPorUnidade = o.optDouble("rendimentoKgPorUnidade", fallbackRend.kgPorUnidade).coerceAtLeast(0.01),
                rendimentoPlantasPorHa = o.optInt("rendimentoPlantasPorHa", fallbackRend.plantasPorHa).coerceAtLeast(0),
                rendimentoDescontarDoencas = o.optBoolean("rendimentoDescontarDoencas", fallbackRend.descontarDoencas)
            )
            cfg to o.optLong("atualizadoEm", System.currentTimeMillis())
        } catch (_: Exception) {
            null
        }
    }

    fun listarColetasSalvas(context: Context): List<ResumoColetaSalva> =
        listarColetasInterno(context = context, salvarAtiva = true)

    private fun listarColetasInterno(
        context: Context,
        salvarAtiva: Boolean,
        ignorarId: String? = null
    ): List<ResumoColetaSalva> {
        val ativa = carregarConfigColeta(context)
        if (salvarAtiva) {
            migrarLegadoSeNecessario(context, ativa.coletaId)
            salvarMetaColetaArquivo(context, ativa)
        }

        val dir = File(context.filesDir, DIR_COLETAS)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val ignoradoLimpo = ignorarId?.let { safeColetaId(it) }
        val ids = dir.listFiles()
            ?.mapNotNull { f ->
                when {
                    f.name.endsWith("_meta.json") -> f.name.removeSuffix("_meta.json")
                    f.name.endsWith("_registros.json") -> f.name.removeSuffix("_registros.json")
                    else -> null
                }
            }
            ?.filter { it != ignoradoLimpo }
            ?.distinct()
            .orEmpty()

        return ids.mapNotNull { id ->
            val metaPar = lerMetaColetaArquivo(context, id)
            val cfg = metaPar?.first ?: (if (salvarAtiva && id == ativa.coletaId) ativa else null) ?: return@mapNotNull null
            val regs = lerAtomico(context, arquivoColetaRelativo(id, "registros.json"))?.let {
                runCatching { JSONArray(it).length() }.getOrDefault(0)
            } ?: 0
            val pontos = lerAtomico(context, arquivoColetaRelativo(id, "formulario.json"))?.let {
                runCatching { JSONObject(it).optJSONArray("itens")?.length() ?: 0 }.getOrDefault(0)
            } ?: 0
            val ts = metaPar?.second ?: File(dir, "${id}_meta.json").lastModified()
            ResumoColetaSalva(
                id = id,
                nome = cfg.coletaNome,
                templateId = cfg.templateId,
                totalRegistros = regs,
                totalPontosRota = pontos,
                atualizadoEm = ts
            )
        }.sortedByDescending { it.atualizadoEm }
    }

    fun criarNovaColetaIsolada(
        context: Context,
        nome: String,
        templateId: String,
        manterPlanilhaAtual: Boolean,
        salvarAtualAntes: Boolean = true
    ): ConfigColetaLocal {
        val atual = carregarConfigColeta(context)
        if (salvarAtualAntes) {
            migrarLegadoSeNecessario(context, atual.coletaId)
            salvarMetaColetaArquivo(context, atual)
        }

        val formAtual = if (manterPlanilhaAtual) carregarFormulario(context).second else emptyList()
        var novoId = "col_${System.currentTimeMillis()}"
        garantirDiretorioColetas(context)
        while (File(context.filesDir, arquivoColetaRelativo(novoId, "meta.json")).exists()) {
            novoId = "col_${System.currentTimeMillis()}_${(10..99).random()}"
        }

        val tplIdLimpo = templateId.ifBlank { "fruticultura" }
        val rendTemplate = ConfigConversaoRendimento.paraTemplate(TemplateCultura.fromId(tplIdLimpo))
        val novaCfg = atual.copy(
            coletaId = novoId,
            templateId = tplIdLimpo,
            coletaNome = nome.trim().ifBlank { "Coleta 01" },
            rendimentoAtivo = rendTemplate.ativo,
            rendimentoPresetId = rendTemplate.presetId,
            rendimentoUnidade = rendTemplate.unidadeSimbolo,
            rendimentoItensPorKg = rendTemplate.itensPorKg,
            rendimentoKgPorUnidade = rendTemplate.kgPorUnidade,
            rendimentoPlantasPorHa = rendTemplate.plantasPorHa,
            rendimentoDescontarDoencas = rendTemplate.descontarDoencas
        )
        salvarConfigColeta(context, novaCfg)
        escreverAtomico(context, arquivoColetaRelativo(novoId, "registros.json"), "[]")
        escreverAtomico(context, FILE_REGISTROS, "[]")

        if (manterPlanilhaAtual && formAtual.isNotEmpty()) {
            val pontosLimpos = formAtual.map { it.copy(coletado = false, resumoResultado = "") }
            salvarFormulario(context, pontosLimpos, novaCfg.coletaNome)
        } else {
            limparFormulario(context)
        }
        return novaCfg
    }

    fun alternarColetaAtiva(
        context: Context,
        coletaId: String,
        salvarAtualAntes: Boolean = true
    ): ConfigColetaLocal? {
        if (salvarAtualAntes) {
            val atual = carregarConfigColeta(context)
            salvarMetaColetaArquivo(context, atual)
        }

        val idLimpo = safeColetaId(coletaId)
        val meta = lerMetaColetaArquivo(context, idLimpo)?.first ?: return null
        salvarConfigColeta(context, meta)

        val regJson = lerAtomico(context, arquivoColetaRelativo(idLimpo, "registros.json")) ?: "[]"
        escreverAtomico(context, FILE_REGISTROS, regJson)

        val formJson = lerAtomico(context, arquivoColetaRelativo(idLimpo, "formulario.json"))
        if (formJson != null) {
            escreverAtomico(context, FILE_FORMULARIO, formJson)
        } else {
            synchronized(fileLock) {
                try {
                    File(context.filesDir, FILE_FORMULARIO).delete()
                    File(context.filesDir, "$FILE_FORMULARIO.bak").delete()
                } catch (_: Exception) {}
            }
        }
        return meta
    }

    fun excluirColetaSalva(context: Context, coletaId: String) {
        val idLimpo = safeColetaId(coletaId)
        val ativa = carregarConfigColeta(context)
        synchronized(fileLock) {
            listOf("registros.json", "formulario.json", "meta.json").forEach { sufixo ->
                val rel = arquivoColetaRelativo(idLimpo, sufixo)
                try {
                    File(context.filesDir, rel).delete()
                    File(context.filesDir, "$rel.bak").delete()
                } catch (_: Exception) {}
            }
            if (ativa.coletaId == idLimpo) {
                listOf(FILE_REGISTROS, "$FILE_REGISTROS.bak", FILE_FORMULARIO, "$FILE_FORMULARIO.bak").forEach { arq ->
                    try { File(context.filesDir, arq).delete() } catch (_: Exception) {}
                }
            }
        }
        if (ativa.coletaId == idLimpo) {
            val restantes = listarColetasInterno(context = context, salvarAtiva = false, ignorarId = idLimpo)
            val proximo = restantes.firstOrNull()
            if (proximo != null) {
                alternarColetaAtiva(context, proximo.id, salvarAtualAntes = false)
            } else {
                criarNovaColetaIsolada(
                    context = context,
                    nome = "Coleta Campo 01",
                    templateId = "fruticultura",
                    manterPlanilhaAtual = false,
                    salvarAtualAntes = false
                )
            }
        }
    }

    fun isTutorialVisto(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_COLETA, Context.MODE_PRIVATE)
                .getBoolean("tutorial_visto", false)
        } catch (_: Exception) {
            false
        }
    }

    fun marcarTutorialVisto(context: Context) {
        try {
            context.getSharedPreferences(PREFS_COLETA, Context.MODE_PRIVATE).edit()
                .putBoolean("tutorial_visto", true)
                .apply()
        } catch (_: Exception) {}
    }

    fun salvarVariaveisAtivasPorColeta(context: Context, coletaNome: String, idsAtivos: List<String>) {
        try {
            val chave = "vars_ativas_" + coletaNome.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")
            val json = JSONArray().apply { idsAtivos.distinct().forEach { put(it) } }.toString()
            context.getSharedPreferences(PREFS_COLETA, Context.MODE_PRIVATE).edit()
                .putString(chave, json)
                .apply()
        } catch (_: Exception) {}
    }

    fun carregarVariaveisAtivasPorColeta(context: Context, coletaNome: String): Set<String>? {
        return try {
            val chave = "vars_ativas_" + coletaNome.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")
            val prefs = context.getSharedPreferences(PREFS_COLETA, Context.MODE_PRIVATE)
            if (!prefs.contains(chave)) return null
            val raw = prefs.getString(chave, "[]") ?: "[]"
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf { s -> s.isNotBlank() } }.toSet()
        } catch (_: Exception) {
            null
        }
    }

    private const val PREFS_CLASSIFICACOES = "breedai_classificacoes_prefs"

    fun salvarClassificacoesModelo(context: Context, modelKey: String, classificacoes: List<String>) {
        try {
            val limpas = classificacoes.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            val json = JSONArray().apply { limpas.forEach { put(it) } }.toString()
            context.getSharedPreferences(PREFS_CLASSIFICACOES, Context.MODE_PRIVATE).edit()
                .putString("cls_$modelKey", json)
                .apply()
        } catch (_: Exception) {}
    }

    fun carregarClassificacoesModelo(context: Context, modelKey: String): List<String>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_CLASSIFICACOES, Context.MODE_PRIVATE)
            val key = "cls_$modelKey"
            if (!prefs.contains(key)) return null
            val raw = prefs.getString(key, "[]") ?: "[]"
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it)?.trim()?.takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            null
        }
    }

    fun limparClassificacoesModelo(context: Context, modelKey: String) {
        try {
            context.getSharedPreferences(PREFS_CLASSIFICACOES, Context.MODE_PRIVATE).edit()
                .remove("cls_$modelKey")
                .apply()
        } catch (_: Exception) {}
    }

    // ── UTILITÁRIOS ATÔMICOS & TESTABILIDADE JVM ──

    private fun escreverAtomico(context: Context, fileName: String, conteudo: String) {
        synchronized(fileLock) {
            val baseFile = File(context.filesDir, fileName)
            baseFile.parentFile?.mkdirs()
            val atomicFile = AtomicFile(baseFile)
            var fos: FileOutputStream? = null
            try {
                fos = atomicFile.startWrite()
                fos.write(conteudo.toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(fos)
            } catch (e: Exception) {
                Log.e("PersistenciaLocal", "Erro em escrita atômica ($fileName): ${e.message}", e)
                if (fos != null) {
                    atomicFile.failWrite(fos)
                }
            }
        }
    }

    private fun lerAtomico(context: Context, fileName: String): String? {
        synchronized(fileLock) {
            val baseFile = File(context.filesDir, fileName)
            val backupFile = File(context.filesDir, "$fileName.bak")
            val atomicFile = AtomicFile(baseFile)
            if (!baseFile.exists() && !backupFile.exists()) return null

            return try {
                val bytes = atomicFile.readFully()
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e("PersistenciaLocal", "Erro ao ler $fileName: ${e.message}", e)
                try {
                    if (baseFile.exists() && baseFile.length() > 0) {
                        val emerg = File(context.filesDir, "${fileName}_corrompido_${System.currentTimeMillis()}.json")
                        baseFile.copyTo(emerg, overwrite = true)
                    }
                } catch (_: Exception) {}
                null
            }
        }
    }

    private fun safeParseUri(uriStr: String): Uri {
        try {
            val u = if (uriStr.isNotBlank()) Uri.parse(uriStr) else Uri.EMPTY
            if (u != null) return u
            if (Uri.EMPTY != null) return Uri.EMPTY
        } catch (_: Throwable) {}

        return try {
            val mockitoClass = Class.forName("org.mockito.Mockito")
            val mockMethod = mockitoClass.getMethod("mock", Class::class.java)
            mockMethod.invoke(null, Uri::class.java) as Uri
        } catch (_: Throwable) {
            try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                val unsafe = theUnsafeField.get(null)
                val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                allocateMethod.invoke(unsafe, Uri::class.java) as Uri
            } catch (_: Throwable) {
                error("Não foi possível instanciar android.net.Uri no ambiente atual")
            }
        }
    }
}
