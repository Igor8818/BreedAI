package com.igor.breedai.userinterface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igor.breedai.data.ModelConfig
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.data.VariavelExtra

private val VERDE_ESCURO = Color(0xFF1B5E20)
private val VERDE_APP = Color(0xFF2E7D32)

@Composable
internal fun TutorialItemStep(titulo: String, descricao: String) {
    Surface(
        color = Color(0xFFF3F8F3),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFFDCE7DC)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = VERDE_ESCURO)
            Text(descricao, fontSize = 12.sp, color = Color(0xFF3E4E40), lineHeight = 16.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaInicioPassoAPasso(
    coletaNome: String,
    templateAtual: TemplateCultura,
    totalColetasSalvas: Int,
    totalRegistros: Int,
    totalPontosFormulario: Int,
    pontosColetadosFormulario: Int = 0,
    rendimentoResumoTexto: String? = null,
    onAbrirConfigRendimento: () -> Unit = {},
    modelConfig: ModelConfig,
    variaveisAtivas: List<VariavelExtra>,
    todasVariaveis: List<VariavelExtra>,
    classesExportacao: List<String>,
    onRenomearColeta: () -> Unit,
    onNovaColeta: () -> Unit,
    onAbrirMinhasColetas: () -> Unit,
    onAtivarSomenteContagem: () -> Unit,
    onAlternarVariavel: (VariavelExtra, Boolean) -> Unit,
    onGerenciarVariaveis: () -> Unit,
    onIrParaModelo: () -> Unit,
    onIrParaFormulario: () -> Unit,
    onIrParaLivre: () -> Unit,
    onIrParaDados: () -> Unit,
    onCarregarExemplo: () -> Unit,
    onAbrirTutorial: () -> Unit,
    onAbrirSobreOffline: () -> Unit
) {
    var mostrarAjudaAnotacoes by remember { mutableStateOf(false) }

    if (mostrarAjudaAnotacoes) {
        AlertDialog(
            onDismissRequest = { mostrarAjudaAnotacoes = false },
            title = { Text("Anotações da coleta", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text(
                    "Modo rápido salva e exporta somente a contagem feita pela IA na foto. Para anotar campos extras (ex: Vírus, Cor, Diâmetro), toque em um chip ou em Gerenciar.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { mostrarAjudaAnotacoes = false },
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) { Text("Entendi") }
            }
        )
    }

    val classesAmigaveis = remember(modelConfig.labelsFiltro, classesExportacao) {
        val origem = if (modelConfig.labelsFiltro.isNotEmpty()) modelConfig.labelsFiltro else classesExportacao
        origem.map { traduzirLabelDisplay(it) }.distinct()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Card da Coleta Ativa
        Surface(
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF164A1B), Color(0xFF2E7D32))
                        )
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "COLETA ATIVA",
                            color = Color(0xFFA5D6A7),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Surface(
                            color = Color.White.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Spa,
                                    null,
                                    tint = Color(0xFFC8E6C9),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    templateAtual.nomeExibicao,
                                    color = Color(0xFFE8F5E9),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Campo clicável de edição rápida do nome da coleta
                    Surface(
                        onClick = onRenomearColeta,
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.32f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    coletaNome,
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Toque para editar o nome da coleta",
                                    color = Color(0xFFC8E6C9),
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White.copy(alpha = 0.22f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Editar", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Métricas + Progresso da rota
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircleOutline,
                                        null,
                                        tint = Color(0xFF69F0AE),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        "$totalRegistros plantas salvas",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Surface(
                                color = Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Route,
                                        null,
                                        tint = Color(0xFFC8E6C9),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        if (totalPontosFormulario > 0) {
                                            "$pontosColetadosFormulario/$totalPontosFormulario na rota"
                                        } else {
                                            "Sem planilha"
                                        },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (totalPontosFormulario > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (pontosColetadosFormulario.toFloat() / totalPontosFormulario.toFloat()).coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF69F0AE),
                                trackColor = Color.White.copy(alpha = 0.20f)
                            )
                        }

                        if (!rendimentoResumoTexto.isNullOrBlank()) {
                            Surface(
                                onClick = onAbrirConfigRendimento,
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Rendimento estimado: $rendimentoResumoTexto",
                                        color = Color(0xFFFFE082),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "Ajustar",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Botões de gestão de coletas
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onAbrirMinhasColetas,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.FolderCopy, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Trocar ($totalColetasSalvas)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Button(
                            onClick = onNovaColeta,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = VERDE_ESCURO
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Nova coleta", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }

        // 2. Iniciar coleta em campo — Dois cartões visuais lado a lado
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Iniciar coleta em campo",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = VERDE_ESCURO,
                modifier = Modifier.padding(horizontal = 2.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ElevatedCard(
                    onClick = onIrParaFormulario,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F5E9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Route, null, tint = VERDE_APP, modifier = Modifier.size(22.dp))
                        }
                        Text(
                            "Modo Rota",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_ESCURO,
                            maxLines = 1
                        )
                        Text(
                            if (totalPontosFormulario > 0) {
                                "$pontosColetadosFormulario de $totalPontosFormulario avaliadas"
                            } else {
                                "Seguir planilha .xlsx/.csv"
                            },
                            fontSize = 11.sp,
                            color = Color(0xFF5F6F61),
                            maxLines = 2
                        )
                        Text(
                            "Abrir rota →",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_APP
                        )
                    }
                }

                ElevatedCard(
                    onClick = onIrParaLivre,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F5E9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoCamera, null, tint = VERDE_ESCURO, modifier = Modifier.size(22.dp))
                        }
                        Text(
                            "Coleta Livre",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_ESCURO,
                            maxLines = 1
                        )
                        Text(
                            "Fotografar e contar direto",
                            fontSize = 11.sp,
                            color = Color(0xFF5F6F61),
                            maxLines = 2
                        )
                        Text(
                            "Abrir câmera →",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_APP
                        )
                    }
                }
            }

            if (totalPontosFormulario == 0) {
                Surface(
                    onClick = onCarregarExemplo,
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFE8F5E9),
                    border = BorderStroke(1.dp, Color(0xFFC8E6C9)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayCircleOutline,
                            null,
                            tint = VERDE_APP,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Sem planilha? Carregar rota de exemplo (12 plantas)",
                            fontSize = 12.sp,
                            color = VERDE_ESCURO,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. Configuração rápida (IA e Anotações)
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onIrParaModelo() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFE8F5E9),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, null, tint = VERDE_APP, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Modelo de IA • Certeza ${(modelConfig.threshold * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = Color(0xFF5F6F61)
                        )
                        Text(
                            modelConfig.nomeExibicao,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = VERDE_ESCURO,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (classesAmigaveis.isEmpty()) "Reconhecendo todas as classes do modelo"
                            else "Contando: ${classesAmigaveis.take(4).joinToString(", ")}",
                            fontSize = 11.sp,
                            color = Color(0xFF5F6F61),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        onClick = onIrParaModelo,
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFE8F5E9)
                    ) {
                        Text(
                            "Trocar",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = VERDE_ESCURO,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFFEEF3EE))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Anotações extras", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = VERDE_ESCURO)
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFF1F8E9)
                        ) {
                            Text(
                                if (variaveisAtivas.isEmpty()) "Só contagem" else "${variaveisAtivas.size} ativa(s)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = VERDE_APP,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        IconButton(
                            onClick = { mostrarAjudaAnotacoes = true },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(Icons.Default.Info, "Ajuda sobre anotações", tint = Color(0xFF5F6F61), modifier = Modifier.size(15.dp))
                        }
                    }
                    TextButton(onClick = onGerenciarVariaveis, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text("Gerenciar", fontSize = 12.sp, color = VERDE_APP, fontWeight = FontWeight.Bold)
                    }
                }

                val chipColors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFFE8F5E9),
                    selectedLabelColor = VERDE_ESCURO
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = variaveisAtivas.isEmpty(),
                            onClick = onAtivarSomenteContagem,
                            colors = chipColors,
                            label = { Text("Somente contagem IA", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                    items(todasVariaveis) { variavel ->
                        FilterChip(
                            selected = variavel.ativa,
                            onClick = { onAlternarVariavel(variavel, !variavel.ativa) },
                            colors = chipColors,
                            label = { Text(variavel.nome, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // 4. Dados salvos e Exportação
        ElevatedCard(
            onClick = onIrParaDados,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFE8F5E9),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.TableChart, null, tint = VERDE_ESCURO, modifier = Modifier.size(20.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Dados salvos e exportação",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = VERDE_ESCURO
                    )
                    Text(
                        "$totalRegistros planta(s) registrada(s) • Exportar CSV ou ZIP",
                        fontSize = 11.sp,
                        color = Color(0xFF5F6F61),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = VERDE_APP)
            }
        }

        // 5. Rodapé
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onAbrirTutorial) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, null, modifier = Modifier.size(15.dp), tint = VERDE_APP)
                Spacer(Modifier.width(4.dp))
                Text("Tutorial rápido", fontSize = 12.sp, color = VERDE_APP)
            }
            TextButton(onClick = onAbrirSobreOffline) {
                Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(15.dp), tint = Color(0xFF4E6E52))
                Spacer(Modifier.width(4.dp))
                Text("Privacidade 100% offline", fontSize = 12.sp, color = Color(0xFF4E6E52))
            }
        }
    }
}
