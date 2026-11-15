package com.example.myapplication.data

data class PontoFormulario(
    val tratamento: String,
    val parcela: String,
    val planta: String,
    var coletado: Boolean = false,
    var resumoResultado: String = "",
    val ordem: Int = 0,
    val colunasPlanilha: Map<String, String> = emptyMap()
)
