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

/**
 * Perfis de cultura para adaptar rótulos, unidades e exemplos ao ensaio sem perder generalidade.
 */
enum class TemplateCultura(
    val id: String,
    val nomeExibicao: String,
    val unidadePlural: String,
    val rotuloSemUnidades: String,
    val motivosSugeridos: List<String>,
    val exemplosTratamento: List<String>
) {
    GERAL(
        id = "geral",
        nomeExibicao = "Geral / Multi-culturas",
        unidadePlural = "Objetos",
        rotuloSemUnidades = "Sem Objetos",
        motivosSugeridos = listOf("Morta", "Falha", "Pequena", "Bordadura"),
        exemplosTratamento = listOf("T-01", "T-02", "T-03")
    ),
    FRUTICULTURA(
        id = "fruticultura",
        nomeExibicao = "Fruticultura / Cacau / Perenes",
        unidadePlural = "Frutos",
        rotuloSemUnidades = "Sem Frutos",
        motivosSugeridos = listOf("Pequena", "Morta", "Errada", "Cavalo"),
        exemplosTratamento = listOf("CCN-51", "PS-1319", "CEPEC-2002")
    ),
    GRAOS(
        id = "graos",
        nomeExibicao = "Grãos / Espigas / Vagens",
        unidadePlural = "Vagens/Espigas",
        rotuloSemUnidades = "Sem Produção",
        motivosSugeridos = listOf("Falha", "Acamada", "Quebrada", "Doente"),
        exemplosTratamento = listOf("Híbrido-01", "Híbrido-02", "Testemunha")
    ),
    SEMENTES_LAB(
        id = "sementes",
        nomeExibicao = "Sementes / Folhas / Bandeja",
        unidadePlural = "Itens",
        rotuloSemUnidades = "Zero Itens",
        motivosSugeridos = listOf("Danificada", "Ausente", "Descarte"),
        exemplosTratamento = listOf("Amostra-01", "Amostra-02", "Amostra-03")
    );

    companion object {
        fun fromId(id: String?): TemplateCultura =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FRUTICULTURA
    }
}

data class ResumoAgronomicoEnsaio(
    val totalRegistros: Int,
    val plantasValidas: Int,
    val plantasNaoAvaliaveis: Int,
    val totalObjetos: Int,
    val mediaPorPlantaValida: Double,
    val estandePercentual: Double
)

/**
 * Calcula estatísticas agronômicas separando plantas avaliáveis (Normal + Sem Frutos)
 * de falhas/mortas (Não avaliável), evitando distorção na média fenotípica.
 */
fun calcularResumoAgronomico(registros: List<RegistroColeta>): ResumoAgronomicoEnsaio {
    val totalRegistros = registros.size
    val plantasValidas = registros.count { it.situacaoPlanta != SituacaoPlanta.NAO_AVALIAVEL }
    val plantasNaoAvaliaveis = totalRegistros - plantasValidas
    val totalObjetos = registros
        .filter { it.situacaoPlanta == SituacaoPlanta.NORMAL }
        .sumOf { it.totalObjetos }
    val media = if (plantasValidas > 0) totalObjetos.toDouble() / plantasValidas.toDouble() else 0.0
    val estande = if (totalRegistros > 0) (plantasValidas * 100.0) / totalRegistros.toDouble() else 100.0
    return ResumoAgronomicoEnsaio(
        totalRegistros = totalRegistros,
        plantasValidas = plantasValidas,
        plantasNaoAvaliaveis = plantasNaoAvaliaveis,
        totalObjetos = totalObjetos,
        mediaPorPlantaValida = media,
        estandePercentual = estande
    )
}

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
