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
    val labelsFiltro: List<String> = LABELS_DOENCA_CACAU + listOf("cacau", "fruto"),
    val threshold: Float = 0.30f,
    val maxResultados: Int = 25
) {
    val nomeExibicao: String
        get() = when {
            !modeloInternoPath.isNullOrBlank() && modeloExternoNome.isNotBlank() -> modeloExternoNome
            modeloExternoUri != null && modeloExternoNome.isNotBlank() -> modeloExternoNome
            modeloSelecionado != null -> modeloSelecionado.nomeExibicao
            else -> "Modelo Padrão"
        }

    fun toManifestJson(): String {
        val root = JSONObject().apply {
            put("nomeExibicao", nomeExibicao)
            put("threshold", threshold.toDouble())
            put("maxResultados", maxResultados)
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
                    maxResultados = maxResultados
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
        val maxResultados: Int
    )
}

val MODELOS_EMBUTIDOS = listOf(
    ModelEmbutido(
        nomeArquivo = "detector_doenca.tflite",
        nomeExibicao = "Cacau Doenças (Pré-carregado)",
        descricao = "Modelo multi-classe para diagnóstico fitossanitário de cacau (Sadio, Podridão Parda, Monilíase e Vassoura de Bruxa)",
        labelsDefault = LABELS_DOENCA_CACAU
    ),
    ModelEmbutido(
        nomeArquivo = "detector.tflite",
        nomeExibicao = "Cacau Contagem (Pré-carregado)",
        descricao = "Modelo pré-carregado desenvolvido pela equipe para detecção e contagem geral de frutos de cacau",
        labelsDefault = listOf("cacau", "fruto")
    )
)
