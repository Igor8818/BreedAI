package com.igor.breedai.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.ModelConfig
import com.igor.breedai.data.PontoFormulario
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.data.TipoVariavelExtra
import com.igor.breedai.data.VariavelExtra
import com.igor.breedai.data.calcularRendimentoColeta
import com.igor.breedai.data.formatarEstimativaCurta
import com.igor.breedai.formatarDiagnosticoRegistro
import com.igor.breedai.userinterface.CaixaAuditavel
import com.igor.breedai.userinterface.util.PersistenciaLocalRegistros
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal fun reconciliarFormularioComRegistros(
    pontos: List<PontoFormulario>,
    registros: List<RegistroColeta>
): List<PontoFormulario> {
    return pontos.map { ponto ->
        val idCompleto = "${ponto.tratamento}_P${ponto.parcela}_Pl${ponto.planta}"
        val reg = registros.firstOrNull { it.idCompleto.equals(idCompleto, ignoreCase = true) }
        if (reg != null) {
            val resumo = when (reg.situacaoPlanta) {
                SituacaoPlanta.NORMAL -> "${reg.totalObjetos} obj."
                SituacaoPlanta.SEM_FRUTOS -> "Sem Frutos (0)"
                SituacaoPlanta.NAO_AVALIAVEL -> "N. Avaliável (${reg.motivoNaoAvaliavel})"
            }
            ponto.copy(coletado = true, resumoResultado = resumo)
        } else {
            ponto.copy(coletado = false, resumoResultado = "")
        }
    }
}

data class BreedAIUiState(
    val coletaId: String = "coleta_padrao",
    val templateId: String = "fruticultura",
    val coletaNome: String = "Coleta Campo 01",
    val labelTratamento: String = "Tratamento",
    val labelParcela: String = "Parcela",
    val labelPlanta: String = "Planta",
    val tipoNumParcela: Boolean = true,
    val tipoNumPlanta: Boolean = true,
    val autoAvancarPlanta: Boolean = true,
    val configRendimento: ConfigConversaoRendimento = ConfigConversaoRendimento(),
    val registros: List<RegistroColeta> = emptyList(),
    val formularioPontos: List<PontoFormulario> = emptyList(),
    val variaveisExtras: List<VariavelExtra> = emptyList(),
    val coletasSalvas: List<PersistenciaLocalRegistros.ResumoColetaSalva> = emptyList(),
    val historicoTratamentos: List<String> = emptyList(),
    val modelConfig: ModelConfig = ModelConfig(),
    val classificacoesAtivasModelo: List<String>? = null,
    val latAtual: Double = 0.0,
    val lngAtual: Double = 0.0,
    val mostrarTutorialPrimeiraVez: Boolean = false
) {
    val templateAtual: TemplateCultura get() = TemplateCultura.fromId(templateId)
    val gpsAtivo: Boolean get() = latAtual != 0.0 || lngAtual != 0.0
    val variaveisAtivas: List<VariavelExtra> get() = variaveisExtras.filter { it.ativa }
}

class BreedAIViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context get() = getApplication<Application>()

    private val _uiState = MutableStateFlow(BreedAIUiState())
    val uiState: StateFlow<BreedAIUiState> = _uiState.asStateFlow()

    init {
        carregarEstadoInicial()
    }

    private fun carregarEstadoInicial() {
        val modelCfg = PersistenciaLocalRegistros.carregarConfiguracaoModelo(appContext)
        val classif = PersistenciaLocalRegistros.carregarClassificacoesModelo(
            appContext, modelCfg.chaveModeloPersistente()
        )
        val cfg = PersistenciaLocalRegistros.carregarConfigColeta(appContext)
        val varsCarregadas = PersistenciaLocalRegistros.carregarVariaveisExtras(appContext)
        val tutorialVisto = PersistenciaLocalRegistros.isTutorialVisto(appContext)

        _uiState.value = construirEstadoDaColetaAtiva(
            base = BreedAIUiState(
                variaveisExtras = varsCarregadas,
                modelConfig = modelCfg,
                classificacoesAtivasModelo = classif,
                mostrarTutorialPrimeiraVez = !tutorialVisto
            ),
            cfg = cfg
        )
    }

    private fun construirEstadoDaColetaAtiva(
        base: BreedAIUiState,
        cfg: PersistenciaLocalRegistros.ConfigColetaLocal
    ): BreedAIUiState {
        val salvos = PersistenciaLocalRegistros.carregarRegistros(appContext)
        val (_, formSalvo) = PersistenciaLocalRegistros.carregarFormulario(appContext)
        val pontosReconciliados = reconciliarFormularioComRegistros(formSalvo, salvos)
        val historico = construirHistoricoTratamentos(salvos, pontosReconciliados)
        val varsSincronizadas = sincronizarVariaveisComColeta(base.variaveisExtras, cfg.coletaNome)
        val coletas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)

        return base.copy(
            coletaId = cfg.coletaId,
            templateId = cfg.templateId,
            coletaNome = cfg.coletaNome,
            labelTratamento = cfg.labelTratamento,
            labelParcela = cfg.labelParcela,
            labelPlanta = cfg.labelPlanta,
            tipoNumParcela = cfg.tipoNumParcela,
            tipoNumPlanta = cfg.tipoNumPlanta,
            autoAvancarPlanta = cfg.autoAvancarPlanta,
            configRendimento = cfg.toConfigRendimento(),
            registros = salvos,
            formularioPontos = pontosReconciliados,
            variaveisExtras = varsSincronizadas,
            coletasSalvas = coletas,
            historicoTratamentos = historico
        )
    }

    private fun construirHistoricoTratamentos(
        registros: List<RegistroColeta>,
        pontos: List<PontoFormulario>
    ): List<String> {
        val tratamentosVistos = LinkedHashSet<String>()
        registros.map { it.tratamento.trim() }.filter { it.isNotBlank() }.forEach {
            if (tratamentosVistos.size < 40) tratamentosVistos.add(it)
        }
        pontos.map { it.tratamento.trim() }.filter { it.isNotBlank() }.forEach {
            if (tratamentosVistos.size < 40) tratamentosVistos.add(it)
        }
        return tratamentosVistos.toList()
    }

    private fun sincronizarVariaveisComColeta(
        varsAtual: List<VariavelExtra>,
        coletaNome: String
    ): List<VariavelExtra> {
        val salvas = PersistenciaLocalRegistros.carregarVariaveisAtivasPorColeta(appContext, coletaNome)
            ?: return varsAtual
        return varsAtual.map { v ->
            val deveAtivar = salvas.contains(v.id)
            if (v.ativa != deveAtivar) v.copy(ativa = deveAtivar) else v
        }
    }

    private fun salvarMetadadosColeta(state: BreedAIUiState) {
        PersistenciaLocalRegistros.salvarConfigColeta(
            appContext,
            PersistenciaLocalRegistros.ConfigColetaLocal(
                coletaId = state.coletaId,
                templateId = state.templateId,
                coletaNome = state.coletaNome,
                labelTratamento = state.labelTratamento,
                labelParcela = state.labelParcela,
                labelPlanta = state.labelPlanta,
                tipoNumParcela = state.tipoNumParcela,
                tipoNumPlanta = state.tipoNumPlanta,
                autoAvancarPlanta = state.autoAvancarPlanta,
                rendimentoAtivo = state.configRendimento.ativo,
                rendimentoPresetId = state.configRendimento.presetId,
                rendimentoUnidade = state.configRendimento.unidadeSimbolo,
                rendimentoItensPorKg = state.configRendimento.itensPorKg,
                rendimentoKgPorUnidade = state.configRendimento.kgPorUnidade,
                rendimentoPlantasPorHa = state.configRendimento.plantasPorHa,
                rendimentoDescontarDoencas = state.configRendimento.descontarDoencas
            )
        )
    }

    fun marcarTutorialVisto() {
        PersistenciaLocalRegistros.marcarTutorialVisto(appContext)
        _uiState.update { it.copy(mostrarTutorialPrimeiraVez = false) }
    }

    fun abrirTutorial() {
        _uiState.update { it.copy(mostrarTutorialPrimeiraVez = true) }
    }

    fun atualizarLocalizacao(lat: Double, lng: Double) {
        _uiState.update { it.copy(latAtual = lat, lngAtual = lng) }
    }

    fun atualizarListaColetas() {
        val coletas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)
        _uiState.update { it.copy(coletasSalvas = coletas) }
    }

    fun salvarConfigRendimento(novaRend: ConfigConversaoRendimento) {
        val novoState = _uiState.value.copy(configRendimento = novaRend)
        salvarMetadadosColeta(novoState)
        _uiState.value = novoState
    }

    fun salvarAutoAvancarPlanta(autoAvancar: Boolean) {
        val novoState = _uiState.value.copy(autoAvancarPlanta = autoAvancar)
        salvarMetadadosColeta(novoState)
        _uiState.value = novoState
    }

    fun salvarCamposFixos(
        l1: String,
        l2: String,
        l3: String,
        n2: Boolean,
        n3: Boolean,
        autoAv: Boolean
    ) {
        val novoState = _uiState.value.copy(
            labelTratamento = l1,
            labelParcela = l2,
            labelPlanta = l3,
            tipoNumParcela = n2,
            tipoNumPlanta = n3,
            autoAvancarPlanta = autoAv
        )
        salvarMetadadosColeta(novoState)
        _uiState.value = novoState
    }

    fun renomearColeta(novoNome: String): Boolean {
        val novo = novoNome.trim()
        if (novo.isBlank()) return false

        val atual = _uiState.value
        val regsAtualizados = atual.registros.map { it.copy(coletaNome = novo) }
        PersistenciaLocalRegistros.salvarRegistros(appContext, regsAtualizados)
        if (atual.formularioPontos.isNotEmpty()) {
            PersistenciaLocalRegistros.salvarFormulario(appContext, atual.formularioPontos, novo)
        }
        val atualizado = atual.copy(coletaNome = novo, registros = regsAtualizados)
        salvarMetadadosColeta(atualizado)
        PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
            appContext, novo, atualizado.variaveisAtivas.map { it.id }
        )
        val coletas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)
        _uiState.value = atualizado.copy(coletasSalvas = coletas)
        return true
    }

    fun criarNovaColeta(
        nome: String,
        templateId: String,
        manterPlanilhaAtual: Boolean,
        iniciarSomenteContagem: Boolean
    ): String {
        val nomeFinal = nome.trim().ifBlank { "Coleta 01" }
        val novaCfg = PersistenciaLocalRegistros.criarNovaColetaIsolada(
            context = appContext,
            nome = nomeFinal,
            templateId = templateId,
            manterPlanilhaAtual = manterPlanilhaAtual
        )

        var novoState = construirEstadoDaColetaAtiva(_uiState.value, novaCfg)
        if (iniciarSomenteContagem) {
            val varsDesativadas = novoState.variaveisExtras.map {
                if (it.ativa) it.copy(ativa = false) else it
            }
            PersistenciaLocalRegistros.salvarVariaveisExtras(appContext, varsDesativadas)
            PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(appContext, nomeFinal, emptyList())
            novoState = novoState.copy(variaveisExtras = varsDesativadas)
        } else {
            PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
                appContext, nomeFinal, novoState.variaveisAtivas.map { it.id }
            )
        }
        _uiState.value = novoState.copy(
            coletasSalvas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)
        )
        return nomeFinal
    }

    fun alternarColeta(coletaId: String): String? {
        val cfgCarregada = PersistenciaLocalRegistros.alternarColetaAtiva(appContext, coletaId)
            ?: return null
        _uiState.value = construirEstadoDaColetaAtiva(_uiState.value, cfgCarregada)
        return cfgCarregada.coletaNome
    }

    fun excluirColeta(coletaId: String) {
        PersistenciaLocalRegistros.excluirColetaSalva(appContext, coletaId)
        val novaAtiva = PersistenciaLocalRegistros.carregarConfigColeta(appContext)
        _uiState.value = construirEstadoDaColetaAtiva(_uiState.value, novaAtiva)
    }

    fun alternarVariavelNaColeta(variavel: VariavelExtra, ativa: Boolean) {
        val atual = _uiState.value
        val novasVars = atual.variaveisExtras.map {
            if (it.id == variavel.id) it.copy(ativa = ativa) else it
        }
        PersistenciaLocalRegistros.salvarVariaveisExtras(appContext, novasVars)
        PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
            appContext, atual.coletaNome, novasVars.filter { it.ativa }.map { it.id }
        )
        _uiState.value = atual.copy(variaveisExtras = novasVars)
    }

    fun adicionarVariavel(nome: String, tipo: TipoVariavelExtra, opcoes: List<String>) {
        val atual = _uiState.value
        val novaVar = VariavelExtra(
            id = "var_${UUID.randomUUID().toString().take(8)}",
            nome = nome,
            tipo = tipo,
            opcoes = opcoes,
            ativa = true,
            ordem = atual.variaveisExtras.size + 1
        )
        val novasVars = atual.variaveisExtras + novaVar
        PersistenciaLocalRegistros.salvarVariaveisExtras(appContext, novasVars)
        PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
            appContext, atual.coletaNome, novasVars.filter { it.ativa }.map { it.id }
        )
        _uiState.value = atual.copy(variaveisExtras = novasVars)
    }

    fun removerVariavel(variavel: VariavelExtra) {
        val atual = _uiState.value
        val novasVars = atual.variaveisExtras.filterNot { it.id == variavel.id }
        PersistenciaLocalRegistros.salvarVariaveisExtras(appContext, novasVars)
        PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(
            appContext, atual.coletaNome, novasVars.filter { it.ativa }.map { it.id }
        )
        _uiState.value = atual.copy(variaveisExtras = novasVars)
    }

    fun ativarModoSomenteContagem() {
        val atual = _uiState.value
        val novasVars = atual.variaveisExtras.map {
            if (it.ativa) it.copy(ativa = false) else it
        }
        PersistenciaLocalRegistros.salvarVariaveisExtras(appContext, novasVars)
        PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(appContext, atual.coletaNome, emptyList())
        _uiState.value = atual.copy(variaveisExtras = novasVars)
    }

    fun aplicarPlanilhaPontos(pontos: List<PontoFormulario>) {
        val atual = _uiState.value
        val reconciliados = reconciliarFormularioComRegistros(pontos, atual.registros)
        PersistenciaLocalRegistros.salvarFormulario(appContext, reconciliados, atual.coletaNome)
        val coletas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)
        val historico = construirHistoricoTratamentos(atual.registros, reconciliados)
        _uiState.value = atual.copy(
            formularioPontos = reconciliados,
            coletasSalvas = coletas,
            historicoTratamentos = historico
        )
    }

    fun carregarPlanilhaExemplo() {
        val atual = _uiState.value
        val tratExemplos = atual.templateAtual.exemplosTratamento
        val pontosExemplo = mutableListOf<PontoFormulario>()
        var ord = 1
        for ((pIdx, trat) in tratExemplos.withIndex()) {
            val parcela = (pIdx + 1).toString()
            for (pl in 1..4) {
                pontosExemplo.add(
                    PontoFormulario(
                        tratamento = trat,
                        parcela = parcela,
                        planta = pl.toString(),
                        ordem = ord++
                    )
                )
            }
        }
        aplicarPlanilhaPontos(pontosExemplo)
    }

    fun limparPlanilha() {
        PersistenciaLocalRegistros.limparFormulario(appContext)
        val coletas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)
        _uiState.update { it.copy(formularioPontos = emptyList(), coletasSalvas = coletas) }
    }

    fun salvarOuAtualizarRegistro(
        uri: Uri,
        tratamento: String,
        parcela: String,
        planta: String,
        situacao: SituacaoPlanta,
        motivoNaoAval: String,
        totalObjetos: Int,
        extras: Map<String, String>,
        caixas: List<CaixaAuditavel>
    ): String? {
        val atual = _uiState.value
        val data = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(Date())
        val idCompleto = "${tratamento}_P${parcela}_Pl${planta}"
        val tratLimpo = tratamento.trim()

        val novoHistorico = if (tratLimpo.isNotBlank() &&
            atual.historicoTratamentos.size < 40 &&
            atual.historicoTratamentos.none { it.equals(tratLimpo, ignoreCase = true) }
        ) {
            atual.historicoTratamentos + tratLimpo
        } else {
            atual.historicoTratamentos
        }

        val diagTexto = formatarDiagnosticoRegistro(
            situacao = situacao,
            motivoNaoAval = motivoNaoAval,
            totalObjetos = totalObjetos,
            caixas = caixas
        )
        val novoRegistro = RegistroColeta(
            uri = uri,
            idCompleto = idCompleto,
            coletaNome = atual.coletaNome,
            tratamento = tratamento,
            parcela = parcela,
            planta = planta,
            situacaoPlanta = situacao,
            motivoNaoAvaliavel = if (situacao == SituacaoPlanta.NAO_AVALIAVEL) motivoNaoAval else "",
            diagnostico = diagTexto,
            data = data,
            lat = atual.latAtual,
            lng = atual.lngAtual,
            totalObjetos = if (situacao == SituacaoPlanta.NORMAL) totalObjetos else 0,
            variaveisExtras = extras.filterValues { it.isNotBlank() },
            caixas = if (situacao == SituacaoPlanta.NORMAL) caixas else emptyList()
        )

        val novosRegistros = atual.registros
            .filterNot { it.idCompleto.equals(idCompleto, ignoreCase = true) } + novoRegistro
        PersistenciaLocalRegistros.salvarRegistros(appContext, novosRegistros)

        val novosPontos = reconciliarFormularioComRegistros(atual.formularioPontos, novosRegistros)
        if (novosPontos.isNotEmpty()) {
            PersistenciaLocalRegistros.salvarFormulario(appContext, novosPontos, atual.coletaNome)
        }
        val coletas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)

        _uiState.value = atual.copy(
            registros = novosRegistros,
            formularioPontos = novosPontos,
            historicoTratamentos = novoHistorico,
            coletasSalvas = coletas
        )

        if (situacao == SituacaoPlanta.NORMAL) {
            val totObjColeta = novosRegistros
                .filter { it.situacaoPlanta == SituacaoPlanta.NORMAL }
                .sumOf { it.totalObjetos }
            val msgExtra = if (atual.configRendimento.ativo) {
                val rend = calcularRendimentoColeta(novosRegistros, atual.configRendimento)
                val sym = atual.configRendimento.unidadeSimbolo.trim().ifBlank { "unid" }
                " • Coleta: $totObjColeta (≈ ${formatarEstimativaCurta(rend.unidadesAcumuladas, sym)})"
            } else {
                " • Total coleta: $totObjColeta"
            }
            return "Salvo: $totalObjetos$msgExtra"
        }
        return null
    }

    fun atualizarDadosRegistro(atualizado: RegistroColeta): Boolean {
        val atual = _uiState.value
        val idx = atual.registros.indexOfFirst { it.idCompleto.equals(atualizado.idCompleto, ignoreCase = true) }
        if (idx < 0) return false

        val novosRegistros = atual.registros.toMutableList().apply { this[idx] = atualizado }
        PersistenciaLocalRegistros.salvarRegistros(appContext, novosRegistros)
        val novosPontos = reconciliarFormularioComRegistros(atual.formularioPontos, novosRegistros)
        if (novosPontos.isNotEmpty()) {
            PersistenciaLocalRegistros.salvarFormulario(appContext, novosPontos, atual.coletaNome)
        }
        val coletas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)
        _uiState.value = atual.copy(
            registros = novosRegistros,
            formularioPontos = novosPontos,
            coletasSalvas = coletas
        )
        return true
    }

    fun atualizarCaixasRegistro(
        idCompleto: String,
        totalObj: Int,
        caixasNovas: List<CaixaAuditavel>
    ): Int? {
        val atual = _uiState.value
        val idx = atual.registros.indexOfFirst { it.idCompleto.equals(idCompleto, ignoreCase = true) }
        if (idx < 0) return null

        val regAtual = atual.registros[idx]
        val situacaoFinal = regAtual.situacaoPlanta
        val totalFinal = if (situacaoFinal == SituacaoPlanta.NORMAL) totalObj else 0
        val caixasFinais = if (situacaoFinal == SituacaoPlanta.NORMAL) caixasNovas else emptyList()
        val regAtualizado = regAtual.copy(
            diagnostico = formatarDiagnosticoRegistro(
                situacao = situacaoFinal,
                motivoNaoAval = regAtual.motivoNaoAvaliavel,
                totalObjetos = totalFinal,
                caixas = caixasFinais
            ),
            totalObjetos = totalFinal,
            caixas = caixasFinais
        )
        val novosRegistros = atual.registros.toMutableList().apply { this[idx] = regAtualizado }
        PersistenciaLocalRegistros.salvarRegistros(appContext, novosRegistros)
        val novosPontos = reconciliarFormularioComRegistros(atual.formularioPontos, novosRegistros)
        if (novosPontos.isNotEmpty()) {
            PersistenciaLocalRegistros.salvarFormulario(appContext, novosPontos, atual.coletaNome)
        }
        val coletas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)
        _uiState.value = atual.copy(
            registros = novosRegistros,
            formularioPontos = novosPontos,
            coletasSalvas = coletas
        )
        return totalFinal
    }

    fun excluirRegistro(reg: RegistroColeta) {
        val atual = _uiState.value
        val novosRegistros = atual.registros - reg
        PersistenciaLocalRegistros.salvarRegistros(appContext, novosRegistros)
        val novosPontos = reconciliarFormularioComRegistros(atual.formularioPontos, novosRegistros)
        if (novosPontos.isNotEmpty()) {
            PersistenciaLocalRegistros.salvarFormulario(appContext, novosPontos, atual.coletaNome)
        }
        val coletas = PersistenciaLocalRegistros.listarColetasSalvas(appContext)
        _uiState.value = atual.copy(
            registros = novosRegistros,
            formularioPontos = novosPontos,
            coletasSalvas = coletas
        )
    }

    fun salvarModelo(novaConfig: ModelConfig) {
        PersistenciaLocalRegistros.salvarConfiguracaoModelo(appContext, novaConfig)
        val classif = PersistenciaLocalRegistros.carregarClassificacoesModelo(
            appContext, novaConfig.chaveModeloPersistente()
        )
        _uiState.update {
            it.copy(modelConfig = novaConfig, classificacoesAtivasModelo = classif)
        }
    }

    fun atualizarLabelsFiltroModelo(classesSelecionadas: List<String>) {
        val novaConfig = _uiState.value.modelConfig.copy(labelsFiltro = classesSelecionadas)
        PersistenciaLocalRegistros.salvarConfiguracaoModelo(appContext, novaConfig)
        _uiState.update { it.copy(modelConfig = novaConfig) }
    }

    fun atualizarClassificacoesAtivas(novasCls: List<String>) {
        PersistenciaLocalRegistros.salvarClassificacoesModelo(
            appContext, _uiState.value.modelConfig.chaveModeloPersistente(), novasCls
        )
        _uiState.update { it.copy(classificacoesAtivasModelo = novasCls) }
    }
}
