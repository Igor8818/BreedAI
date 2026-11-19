package com.example.myapplication.data

import android.net.Uri
import com.example.myapplication.userinterface.CaixaAuditavel
import java.text.Normalizer
import java.util.Locale

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
        exemplosTratamento = listOf("Genótipo 01", "Genótipo 02", "Genótipo 03")
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

/**
 * Presets prontos de conversão agronômica da contagem para rendimento comercial.
 */
enum class PresetRendimento(
    val id: String,
    val nomeExibicao: String,
    val unidadeSimbolo: String,
    val unidadeNome: String,
    val itensPorKg: Double,
    val kgPorUnidade: Double,
    val plantasPorHa: Int,
    val ativoPorPadrao: Boolean
) {
    CACAU_ARROBA("cacau_arroba", "Cacau (@ de 15 kg)", "@", "Arrobas (@)", 22.0, 15.0, 1111, true),
    FRUTAS_CAIXA("frutas_caixa", "Frutas (Caixa 20 kg)", "cx", "Caixas (20 kg)", 6.0, 20.0, 625, true),
    GRAOS_SACA("graos_saca", "Grãos (Saca 60 kg)", "sc", "Sacas (60 kg)", 5.5, 60.0, 60000, true),
    PESO_KG("peso_kg", "Somente Peso (kg)", "kg", "Quilos (kg)", 10.0, 1.0, 1000, true),
    PERSONALIZADO("personalizado", "Personalizado", "@", "Unidade", 22.0, 15.0, 1111, true),
    DESATIVADO("desativado", "Somente contagem", "", "Contagem", 22.0, 15.0, 1111, false);

    companion object {
        fun fromId(id: String?): PresetRendimento =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CACAU_ARROBA
    }
}

data class ConfigConversaoRendimento(
    val ativo: Boolean = true,
    val presetId: String = PresetRendimento.CACAU_ARROBA.id,
    val unidadeSimbolo: String = "@",
    val itensPorKg: Double = 22.0,
    val kgPorUnidade: Double = 15.0,
    val plantasPorHa: Int = 1111,
    val descontarDoencas: Boolean = true
) {
    companion object {
        fun paraTemplate(template: TemplateCultura): ConfigConversaoRendimento = when (template) {
            TemplateCultura.FRUTICULTURA -> dePreset(PresetRendimento.CACAU_ARROBA)
            TemplateCultura.GRAOS -> dePreset(PresetRendimento.GRAOS_SACA)
            TemplateCultura.GERAL, TemplateCultura.SEMENTES_LAB -> dePreset(PresetRendimento.DESATIVADO)
        }

        fun dePreset(preset: PresetRendimento): ConfigConversaoRendimento = ConfigConversaoRendimento(
            ativo = preset.ativoPorPadrao,
            presetId = preset.id,
            unidadeSimbolo = preset.unidadeSimbolo,
            itensPorKg = preset.itensPorKg,
            kgPorUnidade = preset.kgPorUnidade,
            plantasPorHa = preset.plantasPorHa,
            descontarDoencas = true
        )
    }
}

private fun normalizarTextoAgronomico(texto: String): String {
    val nfd = Normalizer.normalize(texto.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
    return nfd.replace(Regex("\\p{M}+"), "")
}

internal fun isRotuloPerdaDoenca(rotulo: String): Boolean {
    val norm = normalizarTextoAgronomico(rotulo)
    if (norm.isEmpty()) return false
    return norm.contains("vassoura") ||
        norm.contains("podrid") ||
        norm.contains("monili") ||
        norm.contains("doent") ||
        norm.contains("descarte") ||
        norm.contains("nao avaliavel") ||
        norm.contains("podre") ||
        norm.contains("necrose")
}

fun contarAproveitaveisEDoentes(
    caixas: List<CaixaAuditavel>,
    totalObjetos: Int,
    descontarDoencas: Boolean = true
): Pair<Int, Int> {
    if (!descontarDoencas || caixas.isEmpty()) {
        return totalObjetos.coerceAtLeast(0) to 0
    }
    val doentes = caixas.count { isRotuloPerdaDoenca(it.rotulo) }
    val baseTotal = maxOf(caixas.size, totalObjetos)
    val aproveitaveis = (baseTotal - doentes).coerceAtLeast(0)
    return aproveitaveis to doentes
}

data class EstimativaUnica(
    val aproveitaveis: Int,
    val doentes: Int,
    val kgEstimado: Double,
    val unidadesComerciais: Double
)

fun calcularEstimativaPlanta(
    totalObjetos: Int,
    caixas: List<CaixaAuditavel>,
    config: ConfigConversaoRendimento
): EstimativaUnica {
    val (aprov, doentes) = contarAproveitaveisEDoentes(
        caixas = caixas,
        totalObjetos = totalObjetos,
        descontarDoencas = config.descontarDoencas
    )
    val itensPorKg = config.itensPorKg.coerceAtLeast(0.01)
    val kgPorUnid = config.kgPorUnidade.coerceAtLeast(0.01)
    val kg = aprov.toDouble() / itensPorKg
    val unid = kg / kgPorUnid
    return EstimativaUnica(
        aproveitaveis = aprov,
        doentes = doentes,
        kgEstimado = kg,
        unidadesComerciais = unid
    )
}

data class ResumoRendimentoColeta(
    val totalAproveitaveis: Int,
    val totalDoentes: Int,
    val kgAcumulado: Double,
    val unidadesAcumuladas: Double,
    val kgPerdaDoencas: Double,
    val unidadesPerdaDoencas: Double,
    val mediaAproveitaveisPlantaViva: Double,
    val potencialKgHa: Double,
    val realKgHa: Double,
    val realUnidadesHa: Double,
    val perdaUnidadesHa: Double,
    val estandePercentual: Double,
    val temClassificacaoDoenca: Boolean
)

/**
 * Calcula rendimento acumulado e projeção por hectare descontando perdas por doenças
 * e evitando inflar a média por plantas não avaliáveis.
 */
fun calcularRendimentoColeta(
    registros: List<RegistroColeta>,
    config: ConfigConversaoRendimento
): ResumoRendimentoColeta {
    val plantasValidas = registros.count { it.situacaoPlanta != SituacaoPlanta.NAO_AVALIAVEL }
    val estande = if (registros.isNotEmpty()) {
        (plantasValidas * 100.0) / registros.size.toDouble()
    } else {
        100.0
    }

    var totalAproveitaveis = 0
    var totalDoentes = 0
    var temClassificacaoDoenca = false

    registros.filter { it.situacaoPlanta == SituacaoPlanta.NORMAL }.forEach { reg ->
        val (aprov, doent) = contarAproveitaveisEDoentes(
            caixas = reg.caixas,
            totalObjetos = reg.totalObjetos,
            descontarDoencas = config.descontarDoencas
        )
        totalAproveitaveis += aprov
        totalDoentes += doent
        if (doent > 0 || reg.caixas.any { c ->
                val n = normalizarTextoAgronomico(c.rotulo)
                n == "sadio" || isRotuloPerdaDoenca(c.rotulo)
            }
        ) {
            temClassificacaoDoenca = true
        }
    }

    val itensPorKg = config.itensPorKg.coerceAtLeast(0.01)
    val kgPorUnid = config.kgPorUnidade.coerceAtLeast(0.01)
    val plHa = config.plantasPorHa.coerceAtLeast(0)

    val kgAcumulado = totalAproveitaveis.toDouble() / itensPorKg
    val unidadesAcumuladas = kgAcumulado / kgPorUnid
    val kgPerda = totalDoentes.toDouble() / itensPorKg
    val unidadesPerda = kgPerda / kgPorUnid

    val mediaAprov = if (plantasValidas > 0) totalAproveitaveis.toDouble() / plantasValidas.toDouble() else 0.0
    val potencialKgHa = if (plHa > 0) (mediaAprov / itensPorKg) * plHa.toDouble() else 0.0
    val realKgHa = potencialKgHa * (estande / 100.0)
    val realUnidadesHa = realKgHa / kgPorUnid
    val perdaUnidadesHa = if (plantasValidas > 0 && plHa > 0) {
        (((totalDoentes.toDouble() / plantasValidas.toDouble()) / itensPorKg) * plHa.toDouble() * (estande / 100.0)) / kgPorUnid
    } else {
        0.0
    }

    return ResumoRendimentoColeta(
        totalAproveitaveis = totalAproveitaveis,
        totalDoentes = totalDoentes,
        kgAcumulado = kgAcumulado,
        unidadesAcumuladas = unidadesAcumuladas,
        kgPerdaDoencas = kgPerda,
        unidadesPerdaDoencas = unidadesPerda,
        mediaAproveitaveisPlantaViva = mediaAprov,
        potencialKgHa = potencialKgHa,
        realKgHa = realKgHa,
        realUnidadesHa = realUnidadesHa,
        perdaUnidadesHa = perdaUnidadesHa,
        estandePercentual = estande,
        temClassificacaoDoenca = temClassificacaoDoenca
    )
}

data class ResumoTratamentoRanking(
    val tratamento: String,
    val totalPlantas: Int,
    val plantasValidas: Int,
    val totalObjetos: Int,
    val totalAproveitaveis: Int,
    val totalDoentes: Int,
    val mediaAproveitaveis: Double,
    val estandePercentual: Double,
    val taxaDoencaPercentual: Double,
    val unidadesAcumuladas: Double,
    val unidadesPorHa: Double
)

fun calcularRankingTratamentos(
    registros: List<RegistroColeta>,
    config: ConfigConversaoRendimento
): List<ResumoTratamentoRanking> {
    if (registros.isEmpty()) return emptyList()
    return registros
        .groupBy { it.tratamento.trim().ifBlank { "Sem tratamento" } }
        .map { (trat, grupo) ->
            val resumo = calcularRendimentoColeta(grupo, config)
            val plantasValidas = grupo.count { it.situacaoPlanta != SituacaoPlanta.NAO_AVALIAVEL }
            val totalObjetos = grupo.filter { it.situacaoPlanta == SituacaoPlanta.NORMAL }.sumOf { it.totalObjetos }
            val somaAprovEDoentes = (resumo.totalAproveitaveis + resumo.totalDoentes)
            val baseTaxaDoenca = maxOf(totalObjetos, somaAprovEDoentes)
            val taxaDoenca = if (baseTaxaDoenca > 0) {
                (resumo.totalDoentes * 100.0 / baseTaxaDoenca.toDouble()).coerceIn(0.0, 100.0)
            } else {
                0.0
            }
            ResumoTratamentoRanking(
                tratamento = trat,
                totalPlantas = grupo.size,
                plantasValidas = plantasValidas,
                totalObjetos = totalObjetos,
                totalAproveitaveis = resumo.totalAproveitaveis,
                totalDoentes = resumo.totalDoentes,
                mediaAproveitaveis = resumo.mediaAproveitaveisPlantaViva,
                estandePercentual = resumo.estandePercentual,
                taxaDoencaPercentual = taxaDoenca,
                unidadesAcumuladas = resumo.unidadesAcumuladas,
                unidadesPorHa = resumo.realUnidadesHa
            )
        }
        .sortedWith(
            compareByDescending<ResumoTratamentoRanking> { it.mediaAproveitaveis }
                .thenByDescending { it.totalAproveitaveis }
                .thenBy { it.tratamento.lowercase(Locale.ROOT) }
        )
}

fun formatarEstimativaCurta(valor: Double, simbolo: String): String {
    val limpo = if (valor.isNaN() || valor.isInfinite()) 0.0 else valor
    val fmt = if (kotlin.math.abs(limpo) < 10.0) {
        String.format(Locale.US, "%.2f", limpo)
    } else {
        String.format(Locale.US, "%.1f", limpo)
    }.replace('.', ',')
    val sym = simbolo.trim()
    return if (sym.isNotEmpty()) "$fmt $sym" else fmt
}

fun gerarResumoTextoColeta(
    coletaNome: String,
    labelTratamento: String,
    registros: List<RegistroColeta>,
    config: ConfigConversaoRendimento,
    templateCultura: TemplateCultura
): String = buildString {
    val resumoAgro = calcularResumoAgronomico(registros)
    val rend = calcularRendimentoColeta(registros, config)
    val unidItens = templateCultura.unidadePlural.lowercase(Locale.ROOT)
    val estandePct = "${resumoAgro.estandePercentual.toInt()}%"
    val mediaTxt = String.format(Locale.US, "%.1f", resumoAgro.mediaPorPlantaValida).replace('.', ',')

    appendLine("*BreedAI • Resumo da Coleta*")
    appendLine("Coleta: $coletaNome")
    appendLine("Plantas avaliadas: ${resumoAgro.totalRegistros} (Estande: $estandePct)")
    appendLine("Total contado: ${resumoAgro.totalObjetos} $unidItens (Média: $mediaTxt/planta)")

    if (config.ativo) {
        val sym = config.unidadeSimbolo.trim().ifBlank { "unid" }
        val unidTot = formatarEstimativaCurta(rend.unidadesAcumuladas, sym)
        val kgTot = formatarEstimativaCurta(rend.kgAcumulado, "kg")
        val rotuloLiq = if (rend.temClassificacaoDoenca && config.descontarDoencas) "líquido (sadios)" else "estimado"
        appendLine("Rendimento $rotuloLiq: $unidTot ($kgTot)")
        if (config.plantasPorHa > 0) {
            appendLine("Projeção/ha (${config.plantasPorHa} pl/ha): ${formatarEstimativaCurta(rend.realUnidadesHa, "$sym/ha")}")
        }
        if (rend.totalDoentes > 0) {
            val perdaUnid = formatarEstimativaCurta(rend.unidadesPerdaDoencas, sym)
            appendLine("Perdas por doenças: ${rend.totalDoentes} $unidItens (~$perdaUnid)")
        }
    }

    val ranking = calcularRankingTratamentos(registros, config)
    if (ranking.size > 1) {
        appendLine()
        appendLine("*Top $labelTratamento:*")
        ranking.take(5).forEachIndexed { idx, item ->
            val mediaTrat = String.format(Locale.US, "%.1f", item.mediaAproveitaveis).replace('.', ',')
            val extraHa = if (config.ativo && config.plantasPorHa > 0) {
                val sym = config.unidadeSimbolo.trim().ifBlank { "unid" }
                " • ${formatarEstimativaCurta(item.unidadesPorHa, "$sym/ha")}"
            } else {
                ""
            }
            appendLine("${idx + 1}. ${item.tratamento}: $mediaTrat $unidItens/pl$extraHa (${item.totalPlantas} pl)")
        }
    }
}.trim()

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
