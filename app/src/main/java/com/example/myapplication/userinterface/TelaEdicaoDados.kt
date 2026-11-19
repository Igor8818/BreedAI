package com.example.myapplication.userinterface

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplication.data.ConfigConversaoRendimento
import com.example.myapplication.data.PontoFormulario
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.TemplateCultura
import com.example.myapplication.data.VariavelExtra
import com.example.myapplication.data.calcularEstimativaPlanta
import com.example.myapplication.data.calcularRankingTratamentos
import com.example.myapplication.data.calcularRendimentoColeta
import com.example.myapplication.data.calcularResumoAgronomico
import com.example.myapplication.data.formatarEstimativaCurta
import com.example.myapplication.data.gerarResumoTextoColeta
import com.example.myapplication.userinterface.util.compartilharArquivoViaAndroid
import com.example.myapplication.userinterface.util.compartilharTextoViaAndroid
import com.example.myapplication.userinterface.util.exportarCSVBreedAI
import com.example.myapplication.userinterface.util.exportarPacoteZipBreedAI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun TelaEdicaoDados(
    registros: List<RegistroColeta>,
    coletaNome: String,
    labelTratamento: String,
    labelParcela: String,
    labelPlanta: String,
    variaveisExtrasConfig: List<VariavelExtra>,
    onAbrirConfigVariaveis: () -> Unit,
    onEditarCaixas: (RegistroColeta) -> Unit,
    onAtualizarDadosRegistro: (RegistroColeta) -> Unit,
    onExcluirRegistro: (RegistroColeta) -> Unit,
    classesModeloExportacao: List<String> = emptyList(),
    pontosFormulario: List<PontoFormulario> = emptyList(),
    incluirPercentualClassesCsv: Boolean = false,
    templateCultura: TemplateCultura = TemplateCultura.FRUTICULTURA,
    configRendimento: ConfigConversaoRendimento = ConfigConversaoRendimento(),
    onAbrirConfigRendimento: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val verde = Color(0xFF2E7D32)

    val classesParaCsv = remember(classesModeloExportacao, registros.toList()) {
        val genericos = setOf("objeto", "cacau", "fruto", "manual", "???")
        (classesModeloExportacao + registros.flatMap { r -> r.caixas.map { it.rotulo } })
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in genericos }
            .distinctBy { it.lowercase() }
    }

    var filtroSituacao by remember { mutableStateOf<SituacaoPlanta?>(null) }
    var textoBusca by remember { mutableStateOf("") }
    var abaVisualizacao by remember { mutableIntStateOf(0) }
    var gerandoZip by remember { mutableStateOf(false) }
    var registroParaExcluir by remember { mutableStateOf<RegistroColeta?>(null) }
    var registroParaEditarDados by remember { mutableStateOf<RegistroColeta?>(null) }

    val rendResumo = remember(registros.toList(), configRendimento) {
        calcularRendimentoColeta(registros, configRendimento)
    }
    val rankingTratamentos = remember(registros.toList(), configRendimento) {
        calcularRankingTratamentos(registros, configRendimento)
    }

    val variaveisAtivas = remember(variaveisExtrasConfig) {
        variaveisExtrasConfig.filter { it.ativa }
    }

    val registrosFiltrados = remember(registros.toList(), filtroSituacao, textoBusca) {
        val base = registros.reversed()
        val porSituacao = if (filtroSituacao == null) base else base.filter { it.situacaoPlanta == filtroSituacao }
        val q = textoBusca.trim()
        if (q.isBlank()) porSituacao else porSituacao.filter { reg ->
            reg.tratamento.contains(q, ignoreCase = true) ||
                reg.parcela.contains(q, ignoreCase = true) ||
                reg.planta.contains(q, ignoreCase = true) ||
                reg.idCompleto.contains(q, ignoreCase = true)
        }
    }

    if (registroParaExcluir != null) {
        AlertDialog(
            onDismissRequest = { registroParaExcluir = null },
            title = { Text("Excluir Registro?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Deseja remover o registro \"${registroParaExcluir!!.idCompleto}\" desta coleta?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val alvo = registroParaExcluir!!
                        registroParaExcluir = null
                        onExcluirRegistro(alvo)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { registroParaExcluir = null }) { Text("Cancelar") }
            }
        )
    }

    if (registroParaEditarDados != null) {
        val reg = registroParaEditarDados!!
        val mapaExtrasEdit = remember(reg) {
            mutableStateMapOf<String, String>().apply { putAll(reg.variaveisExtras) }
        }
        var situacaoEdit by remember(reg) { mutableStateOf(reg.situacaoPlanta) }
        var motivoEdit by remember(reg) { mutableStateOf(reg.motivoNaoAvaliavel.ifBlank { "Pequena" }) }
        val exigeFotoAusente = reg.uri == Uri.EMPTY && situacaoEdit != SituacaoPlanta.NAO_AVALIAVEL

        AlertDialog(
            onDismissRequest = { registroParaEditarDados = null },
            title = { Text("Editar Dados: ${reg.tratamento} (${reg.parcela}-${reg.planta})", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SeletorSituacaoPlantaCard(
                        situacao = situacaoEdit,
                        onSituacaoChange = { situacaoEdit = it },
                        motivoNaoAvaliavel = motivoEdit,
                        onMotivoChange = { motivoEdit = it },
                        rotuloSemUnidades = templateCultura.rotuloSemUnidades,
                        motivosDisponiveis = templateCultura.motivosSugeridos
                    )
                    if (exigeFotoAusente) {
                        Text(
                            "Este registro não possui foto salva (criado como Não avaliável). Colete novamente pela câmera/galeria para mudar para ${situacaoEdit.rotulo}.",
                            fontSize = 11.sp,
                            color = Color(0xFFC62828),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    BlocoVariaveisExtrasCard(
                        variaveisAtivas = variaveisAtivas,
                        valoresAtuais = mapaExtrasEdit,
                        onAbrirConfigVariaveis = {
                            registroParaEditarDados = null
                            onAbrirConfigVariaveis()
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !exigeFotoAusente,
                    onClick = {
                        val zerar = situacaoEdit != SituacaoPlanta.NORMAL
                        val diagNovo = when (situacaoEdit) {
                            SituacaoPlanta.NORMAL -> reg.totalObjetos.toString()
                            SituacaoPlanta.SEM_FRUTOS -> "Sem Frutos (0)"
                            SituacaoPlanta.NAO_AVALIAVEL -> "Não avaliável ($motivoEdit)"
                        }
                        onAtualizarDadosRegistro(
                            reg.copy(
                                situacaoPlanta = situacaoEdit,
                                motivoNaoAvaliavel = if (situacaoEdit == SituacaoPlanta.NAO_AVALIAVEL) motivoEdit else "",
                                diagnostico = diagNovo,
                                totalObjetos = if (zerar) 0 else reg.totalObjetos,
                                caixas = if (zerar) emptyList() else reg.caixas,
                                variaveisExtras = mapaExtrasEdit.toMap()
                            )
                        )
                        registroParaEditarDados = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = verde)
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { registroParaEditarDados = null }) { Text("Cancelar") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val resumo = remember(registros.toList()) { calcularResumoAgronomico(registros) }
        val mediaPorPlanta = String.format(Locale.US, "%.1f", resumo.mediaPorPlantaValida)
        val estandeTexto = "${resumo.estandePercentual.toInt()}%"

        // Card Superior de Resumo do Ensaio + Exportação CSV / ZIP
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IndicadorResumoEnsaio(titulo = "Plantas", valor = "${resumo.totalRegistros}", modifier = Modifier.weight(1f))
                    IndicadorResumoEnsaio(titulo = "Total", valor = "${resumo.totalObjetos}", modifier = Modifier.weight(1f))
                    IndicadorResumoEnsaio(titulo = "Média vivas", valor = mediaPorPlanta, modifier = Modifier.weight(1f))
                    IndicadorResumoEnsaio(titulo = "Estande", valor = estandeTexto, modifier = Modifier.weight(1f))
                }

                if (configRendimento.ativo) {
                    val sym = configRendimento.unidadeSimbolo.trim().ifBlank { "unid" }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF1F8E9),
                        border = BorderStroke(1.dp, Color(0xFFC8E6C9)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (rendResumo.temClassificacaoDoenca && configRendimento.descontarDoencas) {
                                        "Rendimento líquido estimado (sadios)"
                                    } else {
                                        "Rendimento estimado (${configRendimento.itensPorKg.toInt()} itens/kg)"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B5E20),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    onClick = onAbrirConfigRendimento,
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFDCEDC8)
                                ) {
                                    Text(
                                        "Ajustar",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1B5E20),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        formatarEstimativaCurta(rendResumo.unidadesAcumuladas, sym),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF1B5E20)
                                    )
                                    Text("Acumulado", fontSize = 10.sp, color = Color(0xFF5F6F61))
                                }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        formatarEstimativaCurta(rendResumo.kgAcumulado, "kg"),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
                                    Text("Peso seco/est.", fontSize = 10.sp, color = Color(0xFF5F6F61))
                                }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (configRendimento.plantasPorHa > 0 && registros.isNotEmpty()) {
                                            formatarEstimativaCurta(rendResumo.realUnidadesHa, "$sym/ha")
                                        } else "-",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF1B5E20)
                                    )
                                    Text("Projeção/ha", fontSize = 10.sp, color = Color(0xFF5F6F61))
                                }
                            }
                            if (rendResumo.totalDoentes > 0) {
                                Text(
                                    "Perdas por doenças: ${rendResumo.totalDoentes} itens (~${formatarEstimativaCurta(rendResumo.unidadesPerdaDoencas, sym)})",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE65100)
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        onClick = onAbrirConfigRendimento,
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF6F9F6),
                        border = BorderStroke(1.dp, Color(0xFFDCE7DC)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Calcular @ / kg / sacas desta coleta",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF3E4E40)
                            )
                            Text("Configurar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = verde)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFEEF3EE))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            if (registros.isEmpty()) {
                                Toast.makeText(context, "Nenhum registro para exportar.", Toast.LENGTH_SHORT).show()
                            } else {
                                exportarCSVBreedAI(
                                    context = context,
                                    registros = registros,
                                    coletaNome = coletaNome,
                                    labelTratamento = labelTratamento,
                                    labelParcela = labelParcela,
                                    labelPlanta = labelPlanta,
                                    variaveisAtivas = variaveisAtivas,
                                    classesModeloExportacao = classesParaCsv,
                                    pontosFormulario = pontosFormulario,
                                    incluirPercentualClasses = incluirPercentualClassesCsv,
                                    configRendimento = configRendimento
                                )
                            }
                        },
                        enabled = registros.isNotEmpty() && !gerandoZip,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = verde),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("CSV", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            val csvFile = exportarCSVBreedAI(
                                context = context,
                                registros = registros,
                                coletaNome = coletaNome,
                                labelTratamento = labelTratamento,
                                labelParcela = labelParcela,
                                labelPlanta = labelPlanta,
                                variaveisAtivas = variaveisAtivas,
                                classesModeloExportacao = classesParaCsv,
                                pontosFormulario = pontosFormulario,
                                incluirPercentualClasses = incluirPercentualClassesCsv,
                                configRendimento = configRendimento
                            )
                            if (csvFile != null) {
                                compartilharArquivoViaAndroid(
                                    context = context,
                                    arquivo = csvFile,
                                    mimeType = "text/csv",
                                    tituloChooser = "Compartilhar Planilha CSV ($coletaNome)"
                                )
                            }
                        },
                        enabled = registros.isNotEmpty() && !gerandoZip,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = verde),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Planilha", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            val textoResumo = gerarResumoTextoColeta(
                                coletaNome = coletaNome,
                                labelTratamento = labelTratamento,
                                registros = registros,
                                config = configRendimento,
                                templateCultura = templateCultura
                            )
                            compartilharTextoViaAndroid(
                                context = context,
                                texto = textoResumo,
                                tituloChooser = "Enviar Resumo ($coletaNome)"
                            )
                        },
                        enabled = registros.isNotEmpty() && !gerandoZip,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1B5E20)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Resumo", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = {
                        if (registros.isEmpty() || gerandoZip) return@OutlinedButton
                        gerandoZip = true
                        val snapshotRegs = registros.toList()
                        val snapshotPontos = pontosFormulario.toList()
                        coroutineScope.launch(Dispatchers.IO) {
                            val zip = exportarPacoteZipBreedAI(
                                context = context,
                                registros = snapshotRegs,
                                coletaNome = coletaNome,
                                labelTratamento = labelTratamento,
                                labelParcela = labelParcela,
                                labelPlanta = labelPlanta,
                                variaveisAtivas = variaveisAtivas,
                                classesModeloExportacao = classesParaCsv,
                                pontosFormulario = snapshotPontos,
                                incluirPercentualClasses = incluirPercentualClassesCsv,
                                configRendimento = configRendimento
                            )
                            withContext(Dispatchers.Main) {
                                gerandoZip = false
                                if (zip != null) {
                                    compartilharArquivoViaAndroid(
                                        context = context,
                                        arquivo = zip,
                                        mimeType = "application/zip",
                                        tituloChooser = "Compartilhar Pacote Completo ($coletaNome)"
                                    )
                                }
                            }
                        }
                    },
                    enabled = registros.isNotEmpty() && !gerandoZip,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFB8CBB8)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1B5E20)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    if (gerandoZip) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = verde, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Empacotando fotos + CSV + labels...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.FolderZip, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pacote ZIP (CSV + Fotos + Labels YOLO)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }

        if (registros.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEEF3EE), RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    onClick = { abaVisualizacao = 0 },
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (abaVisualizacao == 0) verde else Color.Transparent
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "Por Planta (${registros.size})",
                            fontSize = 12.sp,
                            fontWeight = if (abaVisualizacao == 0) FontWeight.Bold else FontWeight.Medium,
                            color = if (abaVisualizacao == 0) Color.White else Color(0xFF3E4E40),
                            maxLines = 1
                        )
                    }
                }
                Surface(
                    onClick = { abaVisualizacao = 1 },
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (abaVisualizacao == 1) verde else Color.Transparent
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "Por $labelTratamento (${rankingTratamentos.size})",
                            fontSize = 12.sp,
                            fontWeight = if (abaVisualizacao == 1) FontWeight.Bold else FontWeight.Medium,
                            color = if (abaVisualizacao == 1) Color.White else Color(0xFF3E4E40),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (abaVisualizacao == 1 && registros.isNotEmpty()) {
            val maxMedia = (rankingTratamentos.maxOfOrNull { it.mediaAproveitaveis } ?: 1.0).coerceAtLeast(0.01)
            val sym = configRendimento.unidadeSimbolo.trim().ifBlank { "unid" }
            val unidadeLbl = templateCultura.unidadePlural.lowercase(Locale.ROOT)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(rankingTratamentos, key = { _, it -> it.tratamento }) { idx, item ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (idx == 0) Color(0xFFFFD54F) else Color(0xFFE8F5E9),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "#${idx + 1}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF1B5E20)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.tratamento,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color(0xFF1B5E20),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${item.totalPlantas} pl • Estande ${item.estandePercentual.toInt()}% • ${item.totalAproveitaveis} aproveitáveis",
                                        fontSize = 11.sp,
                                        color = Color(0xFF5F6F61),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    "${String.format(Locale.US, "%.1f", item.mediaAproveitaveis).replace('.', ',')} /pl",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = verde
                                )
                            }

                            LinearProgressIndicator(
                                progress = { (item.mediaAproveitaveis / maxMedia).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = if (idx == 0) Color(0xFF2E7D32) else Color(0xFF66BB6A),
                                trackColor = Color(0xFFEEF3EE)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (configRendimento.ativo) {
                                    val unidStr = formatarEstimativaCurta(item.unidadesAcumuladas, sym)
                                    val haStr = if (configRendimento.plantasPorHa > 0) {
                                        " • ${formatarEstimativaCurta(item.unidadesPorHa, "$sym/ha")}"
                                    } else ""
                                    Text(
                                        "≈ $unidStr$haStr",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF1B5E20)
                                    )
                                } else {
                                    Text(
                                        "Total: ${item.totalObjetos} $unidadeLbl",
                                        fontSize = 11.sp,
                                        color = Color(0xFF5F6F61)
                                    )
                                }
                                if (item.totalDoentes > 0) {
                                    Text(
                                        "Doenças: ${item.taxaDoencaPercentual.toInt()}%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE65100)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            return@Column
        }

        if (registros.isNotEmpty()) {
            OutlinedTextField(
                value = textoBusca,
                onValueChange = { textoBusca = it },
                placeholder = { Text("Buscar $labelTratamento, $labelParcela ou $labelPlanta...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = verde, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (textoBusca.isNotEmpty()) {
                        IconButton(onClick = { textoBusca = "" }) {
                            Icon(Icons.Default.Close, "Limpar busca", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Filtros rápidos com rótulos curtos sem cortar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = filtroSituacao == null,
                onClick = { filtroSituacao = null },
                label = { Text("Todas (${registros.size})", fontSize = 11.sp, maxLines = 1) }
            )
            SituacaoPlanta.entries.forEach { sit ->
                val qtd = registros.count { it.situacaoPlanta == sit }
                val rotuloFiltro = when (sit) {
                    SituacaoPlanta.NORMAL -> "Normal"
                    SituacaoPlanta.SEM_FRUTOS -> templateCultura.rotuloSemUnidades
                    SituacaoPlanta.NAO_AVALIAVEL -> "N. Aval."
                }
                FilterChip(
                    selected = filtroSituacao == sit,
                    onClick = { filtroSituacao = if (filtroSituacao == sit) null else sit },
                    label = { Text("$rotuloFiltro ($qtd)", fontSize = 11.sp, maxLines = 1) }
                )
            }
        }

        if (registrosFiltrados.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (registros.isEmpty()) "Nenhuma planta avaliada neste ensaio ainda."
                    else "Nenhum registro encontrado para este filtro.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(registrosFiltrados, key = { "${it.idCompleto}_${it.data}" }) { reg ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (reg.uri != Uri.EMPTY) {
                                AsyncImage(
                                    model = reg.uri,
                                    contentDescription = reg.idCompleto,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Surface(
                                    modifier = Modifier.size(56.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFFFEBEE)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.ReportProblem, null, tint = Color(0xFFC62828), modifier = Modifier.size(28.dp))
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "$labelParcela ${reg.parcela} • $labelPlanta ${reg.planta}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF1B5E20),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "$labelTratamento: ${reg.tratamento}",
                                    fontSize = 12.sp,
                                    color = Color.DarkGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val resumoSituacao = when (reg.situacaoPlanta) {
                                    SituacaoPlanta.NORMAL -> {
                                        val baseCont = "✓ ${reg.totalObjetos} ${templateCultura.unidadePlural.lowercase(Locale.ROOT)}"
                                        if (configRendimento.ativo && reg.totalObjetos > 0) {
                                            val est = calcularEstimativaPlanta(reg.totalObjetos, reg.caixas, configRendimento)
                                            val sym = configRendimento.unidadeSimbolo.trim().ifBlank { "unid" }
                                            "$baseCont • ≈ ${formatarEstimativaCurta(est.unidadesComerciais, sym)}"
                                        } else {
                                            baseCont
                                        }
                                    }
                                    SituacaoPlanta.SEM_FRUTOS -> "${templateCultura.rotuloSemUnidades} (0)"
                                    SituacaoPlanta.NAO_AVALIAVEL -> "Não aval.: ${reg.motivoNaoAvaliavel}"
                                }
                                Text(
                                    resumoSituacao,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = when (reg.situacaoPlanta) {
                                        SituacaoPlanta.NORMAL -> verde
                                        SituacaoPlanta.SEM_FRUTOS -> Color(0xFFE65100)
                                        SituacaoPlanta.NAO_AVALIAVEL -> Color(0xFFC62828)
                                    }
                                )
                                if (reg.variaveisExtras.isNotEmpty()) {
                                    val extrasResumo = reg.variaveisExtras.entries
                                        .filter { it.value.isNotBlank() }
                                        .joinToString(" • ") { "${it.key}: ${it.value}" }
                                    if (extrasResumo.isNotBlank()) {
                                        Text(
                                            extrasResumo,
                                            fontSize = 11.sp,
                                            color = Color(0xFF5F6F61),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (reg.uri != Uri.EMPTY && reg.situacaoPlanta == SituacaoPlanta.NORMAL) {
                                    IconButton(
                                        onClick = { onEditarCaixas(reg) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(Icons.Default.CropFree, "Revisar caixas na foto", tint = verde, modifier = Modifier.size(19.dp))
                                    }
                                }
                                IconButton(
                                    onClick = { registroParaEditarDados = reg },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Edit, "Editar situação/variáveis", tint = Color(0xFF1565C0), modifier = Modifier.size(19.dp))
                                }
                                IconButton(
                                    onClick = { registroParaExcluir = reg },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Delete, "Excluir registro", tint = Color(0xFFC62828), modifier = Modifier.size(19.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndicadorResumoEnsaio(
    titulo: String,
    valor: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            valor,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF1B5E20),
            textAlign = TextAlign.Center
        )
        Text(
            titulo,
            fontSize = 11.sp,
            color = Color(0xFF5F6F61),
            textAlign = TextAlign.Center
        )
    }
}
