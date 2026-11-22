package com.igor.breedai.userinterface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igor.breedai.data.TipoVariavelExtra
import com.igor.breedai.data.VariavelExtra

private val VERDE_ESCURO = Color(0xFF1B5E20)
private val VERDE_APP = Color(0xFF2E7D32)

@Composable
fun TelaConfiguracaoEnsaio(
    coletaNome: String,
    onSalvarNomeColeta: (String) -> Unit,
    label1: String,
    label2: String,
    label3: String,
    isNum2: Boolean,
    isNum3: Boolean,
    autoAvancarPlanta: Boolean,
    onSalvarCamposFixos: (String, String, String, Boolean, Boolean, Boolean) -> Unit,
    pontosRotaCount: Int,
    onImportarPlanilha: () -> Unit,
    onLimparPlanilha: () -> Unit,
    onIniciarNovaColeta: () -> Unit,
    variaveisExtras: List<VariavelExtra>,
    onToggleVariavelAtiva: (VariavelExtra, Boolean) -> Unit,
    onAdicionarVariavel: (String, TipoVariavelExtra, List<String>) -> Unit,
    onRemoverVariavel: (VariavelExtra) -> Unit
) {
    var nomeTemp by remember(coletaNome) { mutableStateOf(coletaNome) }
    var l1Temp by remember(label1) { mutableStateOf(label1) }
    var l2Temp by remember(label2) { mutableStateOf(label2) }
    var l3Temp by remember(label3) { mutableStateOf(label3) }
    var num2Temp by remember(isNum2) { mutableStateOf(isNum2) }
    var num3Temp by remember(isNum3) { mutableStateOf(isNum3) }
    var autoAvTemp by remember(autoAvancarPlanta) { mutableStateOf(autoAvancarPlanta) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Identificação da coleta", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                OutlinedTextField(
                    value = nomeTemp,
                    onValueChange = { nomeTemp = it },
                    label = { Text("Nome da coleta ou experimento") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val limpo = nomeTemp.trim()
                            if (limpo.isNotBlank()) onSalvarNomeColeta(limpo)
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                    ) {
                        Text("Salvar nome", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onIniciarNovaColeta,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFB0BEC5)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF455A64))
                    ) {
                        Text("Nova coleta", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Campos fixos (navegação)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                OutlinedTextField(
                    value = l1Temp,
                    onValueChange = { l1Temp = it },
                    label = { Text("Campo 1 (Ex: Tratamento / Clone / Genótipo)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = l2Temp,
                    onValueChange = { l2Temp = it },
                    label = { Text("Campo 2 (Ex: Parcela / Bloco)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = num2Temp, onCheckedChange = { num2Temp = it })
                    Text("Usar contador numérico (+/-) no Campo 2", fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = l3Temp,
                    onValueChange = { l3Temp = it },
                    label = { Text("Campo 3 (Ex: Planta / Repetição)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = num3Temp, onCheckedChange = { num3Temp = it })
                    Text("Usar contador numérico (+/-) no Campo 3", fontSize = 13.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Avançar $l3Temp automaticamente ao salvar", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = autoAvTemp, onCheckedChange = { autoAvTemp = it })
                }
                Button(
                    onClick = {
                        onSalvarCamposFixos(
                            l1Temp.ifBlank { "Tratamento" },
                            l2Temp.ifBlank { "Parcela" },
                            l3Temp.ifBlank { "Planta" },
                            num2Temp,
                            num3Temp,
                            autoAvTemp
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                ) {
                    Text("Salvar campos e navegação", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Planilha de rota (.xlsx ou .csv)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                Text(
                    if (pontosRotaCount > 0) "$pontosRotaCount pontos carregados no Formulário."
                    else "Nenhuma planilha carregada.",
                    fontSize = 12.sp,
                    color = Color(0xFF5F6F61)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onImportarPlanilha,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VERDE_APP)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (pontosRotaCount > 0) "Trocar" else "Importar", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (pontosRotaCount > 0) {
                        OutlinedButton(
                            onClick = onLimparPlanilha,
                            modifier = Modifier.weight(0.75f).heightIn(min = 52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                        ) {
                            Text("Limpar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anotações extras", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = VERDE_ESCURO)
                ConteudoGerenciarVariaveisExtras(
                    variaveis = variaveisExtras,
                    onToggleAtiva = onToggleVariavelAtiva,
                    onAdicionarVariavel = onAdicionarVariavel,
                    onRemoverVariavel = onRemoverVariavel
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeletorDropdownSimples(
    opcoes: List<String>,
    selecionado: String,
    onSelecionadoChange: (String) -> Unit
) {
    var expandido by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expandido,
        onExpandedChange = { expandido = !expandido }
    ) {
        OutlinedTextField(
            value = selecionado,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandido) }
        )
        ExposedDropdownMenu(expanded = expandido, onDismissRequest = { expandido = false }) {
            opcoes.forEach { opcao ->
                DropdownMenuItem(
                    text = { Text(opcao) },
                    onClick = {
                        onSelecionadoChange(opcao)
                        expandido = false
                    }
                )
            }
        }
    }
}
