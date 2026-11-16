package com.example.myapplication.userinterface

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.VariavelExtra
import com.example.myapplication.userinterface.util.compartilharArquivoViaAndroid
import com.example.myapplication.userinterface.util.exportarCSVBreedAI
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
    onExcluirRegistro: (RegistroColeta) -> Unit
) {
    val context = LocalContext.current
    val verde = Color(0xFF2E7D32)

    var filtroSituacao by remember { mutableStateOf<SituacaoPlanta?>(null) }
    var registroParaExcluir by remember { mutableStateOf<RegistroColeta?>(null) }
    var registroParaEditarDados by remember { mutableStateOf<RegistroColeta?>(null) }

    val variaveisAtivas = remember(variaveisExtrasConfig) {
        variaveisExtrasConfig.filter { it.ativa }
    }

    val registrosFiltrados = remember(registros.toList(), filtroSituacao) {
        val base = registros.reversed()
        if (filtroSituacao == null) base else base.filter { it.situacaoPlanta == filtroSituacao }
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
                        onMotivoChange = { motivoEdit = it }
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
                ) { Text("SALVAR") }
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
        val totalContado = registros.sumOf { it.totalObjetos }
        val mediaPorPlanta = if (registros.isNotEmpty()) {
            String.format(Locale.US, "%.1f", totalContado.toDouble() / registros.size)
        } else {
            "0.0"
        }

        // Card Superior de Resumo do Ensaio + Exportação CSV
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFDCE7DC))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IndicadorResumoEnsaio(titulo = "Plantas", valor = "${registros.size}", modifier = Modifier.weight(1f))
                    IndicadorResumoEnsaio(titulo = "Total Contado", valor = "$totalContado", modifier = Modifier.weight(1f))
                    IndicadorResumoEnsaio(titulo = "Média", valor = mediaPorPlanta, modifier = Modifier.weight(1f))
                }

                HorizontalDivider(color = Color(0xFFEEF3EE))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    variaveisAtivas = variaveisAtivas
                                )
                            }
                        },
                        enabled = registros.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = verde),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("BAIXAR CSV", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                                variaveisAtivas = variaveisAtivas
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
                        enabled = registros.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = verde),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("COMPARTILHAR CSV", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
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
                    SituacaoPlanta.SEM_FRUTOS -> "S/ Frutos"
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFDCE7DC))
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
                                    SituacaoPlanta.NORMAL -> "✓ ${reg.totalObjetos} frutos/objetos"
                                    SituacaoPlanta.SEM_FRUTOS -> "Sem frutos (0)"
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
