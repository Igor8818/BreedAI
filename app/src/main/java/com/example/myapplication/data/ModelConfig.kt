package com.example.myapplication.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class ModelEmbutido(
    val nomeArquivo: String,
    val nomeExibicao: String,
    val descricao: String,
    val labelsDefault: List<String>
)

val LABELS_DOENCA_CACAU = listOf("PodridaoParda", "Moniliase", "Sadio", "VassouraBruxa")

data class ModelConfig(
    val modeloSelecionado: ModelEmbutido? = null,
    val modeloInternoPath: String? = null,
    val modeloExternoUri: Uri? = null,
    val modeloExternoNome: String = "",
    val labelsFiltro: List<String> = listOf("cacau", "fruto"),
    val threshold: Float = 0.30f,
    val maxResultados: Int = 25,
    val usarModeloSecundario: Boolean = false,
    val modeloSecundarioArquivo: String = "detector_doenca.tflite"
) {
    val nomeExibicao: String
        get() = when {
            !modeloInternoPath.isNullOrBlank() && modeloExternoNome.isNotBlank() -> modeloExternoNome
            modeloExternoUri != null && modeloExternoNome.isNotBlank() -> modeloExternoNome
            usarModeloSecundario && (modeloSelecionado == null || modeloSelecionado.nomeArquivo == "detector_doenca.tflite" || modeloSelecionado.nomeArquivo == "detector.tflite") ->
                "Contagem + Diagnóstico (2 IAs)"
            modeloSelecionado != null -> modeloSelecionado.nomeExibicao
            else -> "Modelo Padrão"
        }

    fun chaveModeloPersistente(): String = when {
        !modeloInternoPath.isNullOrBlank() -> "custom_" + modeloInternoPath.substringAfterLast('/').substringAfterLast('\\')
        modeloExternoUri != null -> "ext_" + modeloExternoNome.trim()
        modeloSelecionado != null -> "builtin_" + modeloSelecionado.nomeExibicao
        else -> "builtin_default"
    }

    fun toManifestJson(): String {
        val root = JSONObject().apply {
            put("nomeExibicao", nomeExibicao)
            put("threshold", threshold.toDouble())
            put("maxResultados", maxResultados)
            put("usarModeloSecundario", usarModeloSecundario)
            put("modeloSecundarioArquivo", modeloSecundarioArquivo)
            put("labelsFiltro", JSONArray().apply {
                labelsFiltro.forEach { put(it) }
            })
        }
        return root.toString(2)
    }

    companion object {
        fun fromManifestJson(jsonStr: String): ManifestData? {
            return try {
                val root = JSONObject(jsonStr)
                val nome = root.optString("nomeExibicao", "").trim()
                val threshold = root.optDouble("threshold", 0.30).toFloat().coerceIn(0.05f, 0.99f)
                val maxResultados = root.optInt("maxResultados", 25).coerceIn(1, 50)
                val usarSecundario = root.optBoolean("usarModeloSecundario", false)
                val modeloSec = root.optString("modeloSecundarioArquivo", "detector_doenca.tflite")
                    .trim()
                    .ifBlank { "detector_doenca.tflite" }
                val arr = root.optJSONArray("labelsFiltro")
                val labels = if (arr != null) {
                    (0 until arr.length()).mapNotNull { arr.optString(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                } else {
                    emptyList()
                }
                ManifestData(
                    nomeExibicao = nome,
                    labelsFiltro = labels,
                    threshold = threshold,
                    maxResultados = maxResultados,
                    usarModeloSecundario = usarSecundario,
                    modeloSecundarioArquivo = modeloSec
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    data class ManifestData(
        val nomeExibicao: String,
        val labelsFiltro: List<String>,
        val threshold: Float,
        val maxResultados: Int,
        val usarModeloSecundario: Boolean = false,
        val modeloSecundarioArquivo: String = "detector_doenca.tflite"
    )
}

val MODELOS_EMBUTIDOS = listOf(
    ModelEmbutido(
        nomeArquivo = "detector.tflite",
        nomeExibicao = "Contagem Geral (Pré-carregado)",
        descricao = "Conta frutos de cacau rapidamente ao vivo na câmera.",
        labelsDefault = listOf("cacau", "fruto")
    ),
    ModelEmbutido(
        nomeArquivo = "detector_doenca.tflite",
        nomeExibicao = "Cacau Doenças (Pré-carregado)",
        descricao = "Conta frutos de cacau e separa automaticamente em Sadio, Podridão Parda, Monilíase e Vassoura de Bruxa.",
        labelsDefault = LABELS_DOENCA_CACAU
    ),
    ModelEmbutido(
        nomeArquivo = "detector_frutas_coco.tflite",
        nomeExibicao = "Detector de Banana (Exemplo)",
        descricao = "Exemplo pronto focado apenas em Banana, simulando um modelo importado de outra cultura.",
        labelsDefault = listOf("banana")
    ),
    ModelEmbutido(
        nomeArquivo = "detector_frutas_coco.tflite",
        nomeExibicao = "Detector de Maçã (Exemplo)",
        descricao = "Exemplo pronto focado apenas em Maçã, demonstrando o app com outro fruto separado.",
        labelsDefault = listOf("apple")
    )
)
