package com.example.myapplication

import com.example.myapplication.userinterface.util.ImportadorPlanilhaUniversal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CsvParsingUnitTest {

    @Test
    fun `deve limpar BOM e detectar delimitador ponto-e-virgula em UTF-8`() {
        val csv = "\uFEFFTratamento;Parcela;Planta;Observação\n" +
            "Clone-01;Bloco A;P01;Frutos vigorosos\n" +
            "Clone-02;Bloco A;P02;Alta carga\n"

        val planilha = ImportadorPlanilhaUniversal.lerPlanilhaDeBytes(csv.toByteArray(StandardCharsets.UTF_8))

        assertEquals("CSV", planilha.formato)
        assertEquals(listOf("Tratamento", "Parcela", "Planta", "Observação"), planilha.cabecalho)
        assertEquals(2, planilha.linhas.size)
        assertEquals(listOf("Clone-01", "Bloco A", "P01", "Frutos vigorosos"), planilha.linhas[0])
    }

    @Test
    fun `deve decodificar CSV salvo em Windows-1252 com acentuacao brasileira`() {
        val win1252 = Charset.forName("windows-1252")
        val conteudo = "Tratamento;Parcela;Planta;Anotação\n" +
            "Híbrido São João;Parcela Única;Árvore 01;Produção excelente\n"

        val planilha = ImportadorPlanilhaUniversal.lerPlanilhaDeBytes(conteudo.toByteArray(win1252))

        assertEquals("CSV", planilha.formato)
        assertEquals("Anotação", planilha.cabecalho[3])
        assertEquals("Híbrido São João", planilha.linhas[0][0])
        assertEquals("Parcela Única", planilha.linhas[0][1])
        assertEquals("Árvore 01", planilha.linhas[0][2])
        assertEquals("Produção excelente", planilha.linhas[0][3])
    }

    @Test
    fun `deve preservar campos entre aspas com virgulas e tabs corretamente`() {
        val linhaCsv = "\"Híbrido A, sel. 1\",P-01,\"Planta \"\"Especial\"\"\",12.5"
        val cols = ImportadorPlanilhaUniversal.fatiarLinhaCsv(linhaCsv, ',')

        assertEquals(4, cols.size)
        assertEquals("Híbrido A, sel. 1", cols[0])
        assertEquals("P-01", cols[1])
        assertEquals("Planta \"Especial\"", cols[2])
        assertEquals("12.5", cols[3])

        val tsv = "Cultivar\tBloco\tPlanta\nSoja-RR\tB1\t10\n"
        val planilhaTsv = ImportadorPlanilhaUniversal.lerPlanilhaDeBytes(tsv.toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("Cultivar", "Bloco", "Planta"), planilhaTsv.cabecalho)
        assertEquals(listOf("Soja-RR", "B1", "10"), planilhaTsv.linhas[0])
    }

    @Test
    fun `deve importar XLSX nativo com sharedStrings numeros normalizados e sugerir mapeamento`() {
        val xlsxBytes = criarXlsxEmMemoria()
        assertTrue(ImportadorPlanilhaUniversal.isXlsxMagicBytes(xlsxBytes))

        val planilha = ImportadorPlanilhaUniversal.lerPlanilhaDeBytes(xlsxBytes)

        assertEquals("XLSX", planilha.formato)
        assertEquals(listOf("Cultivar", "Bloco", "Planta", "Setor"), planilha.cabecalho)
        assertEquals(2, planilha.linhas.size)
        assertEquals(listOf("Genótipo A", "1", "101", "Norte"), planilha.linhas[0])
        assertEquals(listOf("Genótipo B", "2", "102", "Sul"), planilha.linhas[1])

        val map = ImportadorPlanilhaUniversal.sugerirMapeamento(planilha.cabecalho)
        assertTrue(map.completo)
        assertEquals(0, map.idxTratamento)
        assertEquals(1, map.idxParcela)
        assertEquals(2, map.idxPlanta)

        val pontos = ImportadorPlanilhaUniversal.extrairPontosFormulario(
            planilha = planilha,
            idxTratamento = map.idxTratamento,
            idxParcela = map.idxParcela,
            idxPlanta = map.idxPlanta
        )
        assertEquals(2, pontos.size)
        assertEquals("Genótipo A", pontos[0].tratamento)
        assertEquals("1", pontos[0].parcela)
        assertEquals("101", pontos[0].planta)
        assertEquals("Norte", pontos[0].colunasPlanilha["Setor"])
        assertEquals("Genótipo B", pontos[1].tratamento)
        assertEquals("2", pontos[1].parcela)
        assertEquals("102", pontos[1].planta)
    }

    private fun criarXlsxEmMemoria(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            val sst = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="7" uniqueCount="7">
                  <si><t>Cultivar</t></si>
                  <si><t>Bloco</t></si>
                  <si><t>Planta</t></si>
                  <si><t>Setor</t></si>
                  <si><t>Genótipo A</t></si>
                  <si><t>Norte</t></si>
                  <si><t>Genótipo B</t></si>
                </sst>
            """.trimIndent()
            zip.write(sst.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val sheet = """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="s"><v>0</v></c>
                      <c r="B1" t="s"><v>1</v></c>
                      <c r="C1" t="s"><v>2</v></c>
                      <c r="D1" t="s"><v>3</v></c>
                    </row>
                    <row r="2">
                      <c r="A2" t="s"><v>4</v></c>
                      <c r="B2"><v>1.0</v></c>
                      <c r="C2"><v>101.0</v></c>
                      <c r="D2" t="s"><v>5</v></c>
                    </row>
                    <row r="3">
                      <c r="A3" t="s"><v>6</v></c>
                      <c r="B3"><v>2</v></c>
                      <c r="C3"><v>102</v></c>
                      <c r="D3" t="inlineStr"><is><t>Sul</t></is></c>
                    </row>
                  </sheetData>
                </worksheet>
            """.trimIndent()
            zip.write(sheet.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
