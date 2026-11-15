package com.example.myapplication.data

import android.net.Uri
import com.example.myapplication.userinterface.CaixaAuditavel

/**
 * Situação agronômica da planta no momento da coleta em campo:
 * - NORMAL: planta viva com frutos/objetos (exige foto + contagem IA)
 * - SEM_FRUTOS: planta viva normal, porém sem frutos (exige foto como comprovação; contagem = 0)
 * - NAO_AVALIAVEL: planta pequena, morta, errada ou cavalo (dispensa foto; contagem = 0)
 */
enum class SituacaoPlanta(val rotulo: String, val rotuloCurto: String) {
    NORMAL("Normal", "Normal"),
    SEM_FRUTOS("Sem Frutos", "Sem Frutos"),
    NAO_AVALIAVEL("Não avaliável", "N. Avaliável")
}

val MOTIVOS_NAO_AVALIAVEL = listOf("Pequena", "Morta", "Errada", "Cavalo")

enum class TipoVariavelExtra(val rotulo: String) {
    NUMERICO("Numérico"),
    TEXTO("Texto"),
    LISTA_OPCOES("Lista de Opções")
}

data class VariavelExtra(
    val id: String,
    val nome: String,
    val tipo: TipoVariavelExtra = TipoVariavelExtra.TEXTO,
    val opcoes: List<String> = emptyList(),
    val ativa: Boolean = true,
    val ordem: Int = 0
)

fun variaveisExtrasIniciais(): List<VariavelExtra> = listOf(
    VariavelExtra(
        id = "var_cor_fruto",
        nome = "Cor do Fruto",
        tipo = TipoVariavelExtra.LISTA_OPCOES,
        opcoes = listOf("Verde", "Amarelo", "Roxo", "Vermelho"),
        ativa = false,
        ordem = 1
    ),
    VariavelExtra(
        id = "var_nota_virus",
        nome = "Nota de Vírus",
        tipo = TipoVariavelExtra.LISTA_OPCOES,
        opcoes = listOf("1", "2", "3", "4", "5"),
        ativa = false,
        ordem = 2
    ),
    VariavelExtra(
        id = "var_diametro_tronco",
        nome = "Diâmetro do Tronco (cm)",
        tipo = TipoVariavelExtra.NUMERICO,
        opcoes = emptyList(),
        ativa = false,
        ordem = 3
    )
)

data class RegistroColeta(
    val uri: Uri,
    val idCompleto: String,
    val coletaNome: String,
    val tratamento: String,
    val parcela: String,
    val planta: String,
    val situacaoPlanta: SituacaoPlanta = SituacaoPlanta.NORMAL,
    val motivoNaoAvaliavel: String = "",
    val diagnostico: String,
    val data: String,
    val lat: Double,
    val lng: Double,
    val totalObjetos: Int = 0,
    val variaveisExtras: Map<String, String> = emptyMap(),
    val caixas: List<CaixaAuditavel> = emptyList()
) {
    /**
     * Garante integridade matemática entre situação da planta, contagem e caixas auditadas.
     */
    fun isConcordante(): Boolean {
        if (situacaoPlanta == SituacaoPlanta.NAO_AVALIAVEL || situacaoPlanta == SituacaoPlanta.SEM_FRUTOS) {
            return totalObjetos == 0 && caixas.isEmpty()
        }
        if (caixas.isNotEmpty()) {
            return caixas.size == totalObjetos
        }
        return totalObjetos >= 0
    }
}
