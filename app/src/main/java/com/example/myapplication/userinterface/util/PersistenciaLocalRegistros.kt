package com.example.myapplication.userinterface.util

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import com.example.myapplication.data.MODELOS_EMBUTIDOS
import com.example.myapplication.data.ModelConfig
import com.example.myapplication.data.PontoFormulario
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.TipoVariavelExtra
import com.example.myapplication.data.VariavelExtra
import com.example.myapplication.data.variaveisExtrasIniciais
import com.example.myapplication.userinterface.CaixaAuditavel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object PersistenciaLocalRegistros {

    private const val FILE_REGISTROS = "registros_breedai_local.json"
    private const val FILE_FORMULARIO = "formulario_breedai.json"
    private const val FILE_VARIAVEIS = "variaveis_extras_breedai.json"
    private val fileLock = Any()

    // ── REGISTROS DE COLETA (100% offline com AtomicFile) ──

    fun salvarRegistros(context: Context, registros: List<RegistroColeta>) {
        val snapshot = registros.toList()
        escreverAtomico(context, FILE_REGISTROS, serializarRegistrosParaJson(snapshot).toString())
    }

    fun carregarRegistros(context: Context): List<RegistroColeta> {
        val json = lerAtomico(context, FILE_REGISTROS) ?: return emptyList()
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
        escreverAtomico(context, FILE_FORMULARIO, root.toString())
    }

    fun limparFormulario(context: Context) {
        synchronized(fileLock) {
            try {
                File(context.filesDir, FILE_FORMULARIO).delete()
                File(context.filesDir, "$FILE_FORMULARIO.bak").delete()
            } catch (_: Exception) {}
        }
    }

    fun carregarFormulario(context: Context): Pair<String, List<PontoFormulario>> {
        val texto = lerAtomico(context, FILE_FORMULARIO)?.trim() ?: return "" to emptyList()
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
    private const val KEY_INTERNO_PATH = "modelo_interno_path"
    private const val KEY_EXTERNO_URI = "externo_uri"
    private const val KEY_EXTERNO_NOME = "externo_nome"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_MAX_RESULTADOS = "max_resultados"
    private const val KEY_LABELS = "labels_filtro"

    fun salvarConfiguracaoModelo(context: Context, config: ModelConfig) {
        try {
            val prefs = context.getSharedPreferences(PREFS_MODELO, Context.MODE_PRIVATE)
            val labelsJson = JSONArray().apply {
                config.labelsFiltro.forEach { put(it) }
            }.toString()

            prefs.edit()
                .putString(KEY_MODELO_ARQUIVO, config.modeloSelecionado?.nomeArquivo)
                .putString(KEY_INTERNO_PATH, config.modeloInternoPath)
                .putString(KEY_EXTERNO_URI, config.modeloExternoUri?.toString())
                .putString(KEY_EXTERNO_NOME, config.modeloExternoNome)
                .putFloat(KEY_THRESHOLD, config.threshold)
                .putInt(KEY_MAX_RESULTADOS, config.maxResultados)
                .putString(KEY_LABELS, labelsJson)
                .apply()
        } catch (e: Exception) {
            Log.e("PersistenciaLocal", "Erro ao salvar configuracao do modelo: ${e.message}")
        }
    }

    fun carregarConfiguracaoModelo(context: Context): ModelConfig {
        return try {
            val prefs = context.getSharedPreferences(PREFS_MODELO, Context.MODE_PRIVATE)
            val threshold = prefs.getFloat(KEY_THRESHOLD, 0.30f)
            val maxResultados = prefs.getInt(KEY_MAX_RESULTADOS, 25)
            val modeloArquivo = prefs.getString(KEY_MODELO_ARQUIVO, null)
            val internoPath = prefs.getString(KEY_INTERNO_PATH, null)?.takeIf { File(it).exists() }
            val externoUriStr = prefs.getString(KEY_EXTERNO_URI, null)
            val externoNome = prefs.getString(KEY_EXTERNO_NOME, "") ?: ""
            val labelsJson = prefs.getString(KEY_LABELS, null)

            val labels = if (!labelsJson.isNullOrBlank()) {
                try {
                    val arr = JSONArray(labelsJson)
                    (0 until arr.length()).map { arr.getString(it) }
                } catch (_: Exception) {
                    listOf("cacau", "fruto")
                }
            } else {
                listOf("cacau", "fruto")
            }

            val usaCustom = !internoPath.isNullOrBlank() || !externoUriStr.isNullOrBlank()
            val modeloEmbutido = if (!usaCustom) {
                MODELOS_EMBUTIDOS.firstOrNull { it.nomeArquivo == modeloArquivo }
                    ?: MODELOS_EMBUTIDOS.firstOrNull()
            } else {
                null
            }

            val externoUri = if (internoPath.isNullOrBlank() && !externoUriStr.isNullOrBlank()) {
                safeParseUri(externoUriStr)
            } else null

            ModelConfig(
                modeloSelecionado = modeloEmbutido,
                modeloInternoPath = internoPath,
                modeloExternoUri = externoUri,
                modeloExternoNome = externoNome,
                labelsFiltro = labels,
                threshold = threshold,
                maxResultados = maxResultados
            )
        } catch (e: Exception) {
            Log.e("PersistenciaLocal", "Erro ao carregar configuracao do modelo: ${e.message}")
            ModelConfig(modeloSelecionado = MODELOS_EMBUTIDOS.firstOrNull())
        }
    }

    private const val PREFS_COLETA = "breedai_coleta_prefs"

    data class ConfigColetaLocal(
        val coletaNome: String = "Coleta_Campo_01",
        val labelTratamento: String = "Tratamento",
        val labelParcela: String = "Parcela",
        val labelPlanta: String = "Planta",
        val tipoNumParcela: Boolean = true,
        val tipoNumPlanta: Boolean = true
    )

    fun salvarConfigColeta(context: Context, cfg: ConfigColetaLocal) {
        try {
            context.getSharedPreferences(PREFS_COLETA, Context.MODE_PRIVATE).edit()
                .putString("coleta_nome", cfg.coletaNome)
                .putString("label_tratamento", cfg.labelTratamento)
                .putString("label_parcela", cfg.labelParcela)
                .putString("label_planta", cfg.labelPlanta)
                .putBoolean("num_parcela", cfg.tipoNumParcela)
                .putBoolean("num_planta", cfg.tipoNumPlanta)
                .apply()
        } catch (_: Exception) {}
    }

    fun carregarConfigColeta(context: Context): ConfigColetaLocal {
        return try {
            val prefs = context.getSharedPreferences(PREFS_COLETA, Context.MODE_PRIVATE)
            ConfigColetaLocal(
                coletaNome = prefs.getString("coleta_nome", "Coleta_Campo_01") ?: "Coleta_Campo_01",
                labelTratamento = prefs.getString("label_tratamento", "Tratamento") ?: "Tratamento",
                labelParcela = prefs.getString("label_parcela", "Parcela") ?: "Parcela",
                labelPlanta = prefs.getString("label_planta", "Planta") ?: "Planta",
                tipoNumParcela = prefs.getBoolean("num_parcela", true),
                tipoNumPlanta = prefs.getBoolean("num_planta", true)
            )
        } catch (_: Exception) {
            ConfigColetaLocal()
        }
    }

    // ── UTILITÁRIOS ATÔMICOS & TESTABILIDADE JVM ──

    private fun escreverAtomico(context: Context, fileName: String, conteudo: String) {
        synchronized(fileLock) {
            val baseFile = File(context.filesDir, fileName)
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
