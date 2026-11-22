package com.igor.breedai

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.igor.breedai.data.ConfigConversaoRendimento
import com.igor.breedai.data.LABELS_DOENCA_CACAU
import com.igor.breedai.data.MODELOS_EMBUTIDOS
import com.igor.breedai.data.ModelConfig
import com.igor.breedai.data.PontoFormulario
import com.igor.breedai.data.PresetRendimento
import com.igor.breedai.data.RegistroColeta
import com.igor.breedai.data.SituacaoPlanta
import com.igor.breedai.data.TemplateCultura
import com.igor.breedai.userinterface.AlcaCaixa
import com.igor.breedai.userinterface.CaixaAuditavel
import com.igor.breedai.userinterface.DetEstilizada
import com.igor.breedai.userinterface.VolumeShutterBridge
import com.igor.breedai.userinterface.ajustarCantoCaixaAuditavel
import com.igor.breedai.userinterface.corParaCaixa
import com.igor.breedai.userinterface.corParaLabel
import com.igor.breedai.userinterface.detectarAlcaCaixaSelecionada
import com.igor.breedai.userinterface.fundirDeteccoesComSnapping
import com.igor.breedai.userinterface.iouCaixasNorm
import com.igor.breedai.userinterface.moverCaixaAuditavel
import com.igor.breedai.userinterface.redimensionarCaixaAuditavel
import com.igor.breedai.userinterface.resolverToqueEmCaixaExistente
import com.igor.breedai.userinterface.rotuloAmigavel
import com.igor.breedai.userinterface.selecionarIndiceCaixaNoToque
import com.igor.breedai.userinterface.util.ImportadorPlanilhaUniversal
import com.igor.breedai.userinterface.util.ObjectDetectorHelper
import com.igor.breedai.userinterface.util.PersistenciaLocalRegistros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ComprehensivePersistenceImportAndAITest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * SharedPreferences em memória para testar persistência real em disco sem depender de Robolectric.
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key] = values?.toSet() }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removed.add(key); pending.remove(key) }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true; pending.clear(); removed.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                removed.forEach { data.remove(it) }
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun criarContextoComDiscoReal(): Context {
        val filesDir = tempFolder.newFolder("files_${System.nanoTime()}")
        val prefsMap = mutableMapOf<String, FakeSharedPreferences>()
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.filesDir).thenReturn(filesDir)
        Mockito.`when`(ctx.getSharedPreferences(anyString(), anyInt())).thenAnswer { inv ->
            val nome = inv.getArgument<String>(0)
            prefsMap.getOrPut(nome) { FakeSharedPreferences() }
        }
        return ctx
    }

    private fun mockUri(s: String = "file:///foto.jpg"): Uri {
        val u = Mockito.mock(Uri::class.java)
        Mockito.`when`(u.toString()).thenReturn(s)
        return u
    }

    // ── 1. PERSISTÊNCIA MULTI-COLETAS, RENDIMENTO POR COLETA & REGRESSÃO BUG #1 ──

    @Test
    fun `persistencia multi-coletas isola registros, planilhas e rendimento por coleta`() {
        val ctx = criarContextoComDiscoReal()

        // Coleta 1 (Fruticultura -> @)
        val cfg1 = PersistenciaLocalRegistros.criarNovaColetaIsolada(
            context = ctx,
            nome = "Safra Cacau",
            templateId = TemplateCultura.FRUTICULTURA.id,
            manterPlanilhaAtual = false
        )
        assertTrue(cfg1.rendimentoAtivo)
        assertEquals("@", cfg1.rendimentoUnidade)

        val reg1 = RegistroColeta(
            uri = mockUri(),
            idCompleto = "G1_P1_Pl1",
            coletaNome = "Safra Cacau",
            tratamento = "G1",
            parcela = "1",
            planta = "1",
            situacaoPlanta = SituacaoPlanta.NORMAL,
            diagnostico = "15",
            data = "19/11/2026 09:00:00",
            lat = -14.79,
            lng = -39.23,
            totalObjetos = 15
        )
        PersistenciaLocalRegistros.salvarRegistros(ctx, listOf(reg1))
        PersistenciaLocalRegistros.salvarFormulario(
            ctx,
            listOf(PontoFormulario("G1", "1", "1", coletado = true, resumoResultado = "15 obj.", ordem = 1)),
            "Safra Cacau"
        )

        // Personaliza rendimento da coleta 1
        PersistenciaLocalRegistros.salvarConfigColeta(
            ctx,
            cfg1.copy(rendimentoItensPorKg = 25.0, rendimentoPlantasPorHa = 800)
        )

        // Coleta 2 (Grãos -> sc), clonando planilha (deve zerar coletado=false)
        val cfg2 = PersistenciaLocalRegistros.criarNovaColetaIsolada(
            context = ctx,
            nome = "Milho Safrinha",
            templateId = TemplateCultura.GRAOS.id,
            manterPlanilhaAtual = true
        )
        assertNotEquals(cfg1.coletaId, cfg2.coletaId)
        assertEquals("sc", cfg2.rendimentoUnidade)
        assertEquals(60.0, cfg2.rendimentoKgPorUnidade, 0.001)
        assertEquals(0, PersistenciaLocalRegistros.carregarRegistros(ctx).size)

        val (_, formClonado) = PersistenciaLocalRegistros.carregarFormulario(ctx)
        assertEquals(1, formClonado.size)
        assertFalse(formClonado[0].coletado)
        assertEquals("", formClonado[0].resumoResultado)

        // Alterna de volta para Coleta 1 e valida preservação total
        val reaberta1 = PersistenciaLocalRegistros.alternarColetaAtiva(ctx, cfg1.coletaId)
        assertNotNull(reaberta1)
        assertEquals("Safra Cacau", reaberta1!!.coletaNome)
        assertEquals(25.0, reaberta1.rendimentoItensPorKg, 0.001)
        assertEquals(800, reaberta1.rendimentoPlantasPorHa)
        assertEquals(1, PersistenciaLocalRegistros.carregarRegistros(ctx).size)
        assertEquals(15, PersistenciaLocalRegistros.carregarRegistros(ctx)[0].totalObjetos)
    }

    @Test
    fun `excluirColetaSalva na coleta ativa nao ressuscita a coleta excluida e alterna corretamente`() {
        val ctx = criarContextoComDiscoReal()

        val cfgA = PersistenciaLocalRegistros.criarNovaColetaIsolada(ctx, "Coleta A", "fruticultura", false)
        PersistenciaLocalRegistros.salvarRegistros(
            ctx,
            listOf(
                RegistroColeta(mockUri(), "A_P1_Pl1", "Coleta A", "A", "1", "1", SituacaoPlanta.NORMAL, "", "10", "19/11/2026 08:00:00", 0.0, 0.0, 10)
            )
        )
        val cfgB = PersistenciaLocalRegistros.criarNovaColetaIsolada(ctx, "Coleta B", "graos", false)
        assertEquals(cfgB.coletaId, PersistenciaLocalRegistros.carregarConfigColeta(ctx).coletaId)

        // Exclui a coleta ATIVA (Coleta B)
        PersistenciaLocalRegistros.excluirColetaSalva(ctx, cfgB.coletaId)

        val restantes = PersistenciaLocalRegistros.listarColetasSalvas(ctx)
        assertFalse("Coleta B excluída não pode ressuscitar!", restantes.any { it.id == cfgB.coletaId })
        assertTrue(restantes.any { it.id == cfgA.coletaId })

        val ativaAposExclusao = PersistenciaLocalRegistros.carregarConfigColeta(ctx)
        assertEquals(cfgA.coletaId, ativaAposExclusao.coletaId)
        assertEquals("Coleta A", ativaAposExclusao.coletaNome)
        assertEquals(1, PersistenciaLocalRegistros.carregarRegistros(ctx).size)

        // Agora exclui também a última coleta restante -> deve criar uma nova "Coleta Campo 01" limpa
        PersistenciaLocalRegistros.excluirColetaSalva(ctx, cfgA.coletaId)
        val novaPadrao = PersistenciaLocalRegistros.carregarConfigColeta(ctx)
        assertNotEquals(cfgA.coletaId, novaPadrao.coletaId)
        assertEquals("Coleta Campo 01", novaPadrao.coletaNome)
        assertEquals(0, PersistenciaLocalRegistros.carregarRegistros(ctx).size)
        val listaFinal = PersistenciaLocalRegistros.listarColetasSalvas(ctx)
        assertFalse(listaFinal.any { it.id == cfgA.coletaId })
        assertFalse(listaFinal.any { it.id == cfgB.coletaId })
    }

    @Test
    fun `persistencia de modelo, variaveis ativas e classificacoes deduplica e faz round-trip`() {
        val ctx = criarContextoComDiscoReal()

        val cfgModelo = ModelConfig(
            modeloSelecionado = MODELOS_EMBUTIDOS[2], // Banana
            labelsFiltro = listOf("banana"),
            threshold = 0.22f,
            maxResultados = 80,
            usarModeloSecundario = false,
            incluirPercentualClassesCsv = true
        )
        PersistenciaLocalRegistros.salvarConfiguracaoModelo(ctx, cfgModelo)
        val lida = PersistenciaLocalRegistros.carregarConfiguracaoModelo(ctx)
        assertEquals("Detector de Banana (Exemplo)", lida.modeloSelecionado?.nomeExibicao)
        assertEquals(listOf("banana"), lida.labelsFiltro)
        assertEquals(0.22f, lida.threshold, 0.001f)
        assertEquals(80, lida.maxResultados)
        assertTrue(lida.incluirPercentualClassesCsv)

        // Classificações personalizadas por modelo (deduplica case-insensitive)
        val key = cfgModelo.chaveModeloPersistente()
        PersistenciaLocalRegistros.salvarClassificacoesModelo(ctx, key, listOf("Sadio", "sadio", "  Madura ", ""))
        assertEquals(listOf("Sadio", "Madura"), PersistenciaLocalRegistros.carregarClassificacoesModelo(ctx, key))
        PersistenciaLocalRegistros.limparClassificacoesModelo(ctx, key)
        assertNull(PersistenciaLocalRegistros.carregarClassificacoesModelo(ctx, key))

        // Variáveis ativas isoladas por nome da coleta
        PersistenciaLocalRegistros.salvarVariaveisAtivasPorColeta(ctx, "Coleta Norte", listOf("v1", "v2", "v1"))
        assertEquals(setOf("v1", "v2"), PersistenciaLocalRegistros.carregarVariaveisAtivasPorColeta(ctx, "Coleta Norte"))
        assertNull(PersistenciaLocalRegistros.carregarVariaveisAtivasPorColeta(ctx, "Outra Coleta"))
    }

    @Test
    fun `deserializarRegistrosDeJson trata legado incompleto e limita coordenadas fora de 0 a 1`() {
        val jsonLegado = """
            [
              {
                "uri": "",
                "idCompleto": "LEG_P1_Pl1",
                "coletaNome": "Antiga",
                "tratamento": "LEG",
                "parcela": "1",
                "planta": "1",
                "diagnostico": "2",
                "data": "01/01/2025 10:00:00",
                "totalObjetos": 1,
                "caixas": [
                  {"classe": "cacau", "x": -0.2, "y": 0.9, "largura": 0.5, "altura": 0.4}
                ]
              }
            ]
        """.trimIndent()

        val lista = PersistenciaLocalRegistros.deserializarRegistrosDeJson(jsonLegado)
        assertEquals(1, lista.size)
        assertEquals(SituacaoPlanta.NORMAL, lista[0].situacaoPlanta)
        assertEquals(1, lista[0].caixas.size)
        val cx = lista[0].caixas[0]
        assertEquals("cacau", cx.rotulo)
        assertTrue(cx.normLeft in 0f..1f)
        assertTrue(cx.normTop in 0f..1f)
        assertTrue(cx.normRight in 0f..1f)
        assertTrue(cx.normBottom in 0f..1f)
        assertEquals(emptyList<RegistroColeta>(), PersistenciaLocalRegistros.deserializarRegistrosDeJson("   "))
    }

    // ── 2. IMPORTADOR UNIVERSAL DE PLANILHAS (.XLSX REAL + CSV + SEGURANÇA ZIP) ──

    private fun gerarXlsxMinimoEmMemoria(): ByteArray {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
              <si><t>Variedade</t></si>
              <si><t>Bloco</t></si>
              <si><t>Planta</t></si>
              <si><t>Genótipo 01</t></si>
            </sst>
        """.trimIndent()
        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1" t="s"><v>1</v></c>
                  <c r="C1" t="s"><v>2</v></c>
                  <c r="D1" t="inlineStr"><is><t>Setor</t></is></c>
                </row>
                <row r="2">
                  <c r="A2" t="s"><v>3</v></c>
                  <c r="B2"><v>2.0</v></c>
                  <c r="C2"><v>10.00</v></c>
                  <c r="D2" t="inlineStr"><is><t>Sul</t></is></c>
                </row>
                <row r="3">
                  <c r="A3" t="inlineStr"><is><t>Genótipo 02</t></is></c>
                  <c r="B3"><v>3</v></c>
                  <c r="C3"><v>1</v></c>
                  <c r="D3" t="b"><v>1</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zos.write(sharedStringsXml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheet1Xml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    @Test
    fun `importador le arquivo XLSX real com sharedStrings, inlineStr, booleano e normalizacao numerica`() {
        val xlsxBytes = gerarXlsxMinimoEmMemoria()
        assertTrue(ImportadorPlanilhaUniversal.isXlsxMagicBytes(xlsxBytes))

        val lida = ImportadorPlanilhaUniversal.lerPlanilhaDeBytes(xlsxBytes)
        assertEquals("XLSX", lida.formato)
        assertEquals(listOf("Variedade", "Bloco", "Planta", "Setor"), lida.cabecalho)
        assertEquals(2, lida.linhas.size)
        assertEquals(listOf("Genótipo 01", "2", "10", "Sul"), lida.linhas[0])
        assertEquals(listOf("Genótipo 02", "3", "1", "TRUE"), lida.linhas[1])

        val map = ImportadorPlanilhaUniversal.sugerirMapeamento(lida.cabecalho)
        assertTrue(map.completo)
        assertEquals(0, map.idxTratamento)
        assertEquals(1, map.idxParcela)
        assertEquals(2, map.idxPlanta)

        val pontos = ImportadorPlanilhaUniversal.extrairPontosFormulario(lida, map.idxTratamento, map.idxParcela, map.idxPlanta)
        assertEquals(2, pontos.size)
        assertEquals("Sul", pontos[0].colunasPlanilha["Setor"])
        assertEquals("TRUE", pontos[1].colunasPlanilha["Setor"])
    }

    @Test
    fun `importador protege contra zip-slip, zip-bomb e decodifica Windows-1252 e delimitadores`() {
        assertFalse(ImportadorPlanilhaUniversal.isSafeZipEntryName("../evil.xml"))
        assertFalse(ImportadorPlanilhaUniversal.isSafeZipEntryName("xl/../../etc/passwd"))
        assertFalse(ImportadorPlanilhaUniversal.isSafeZipEntryName("/abs/path"))
        assertFalse(ImportadorPlanilhaUniversal.isSafeZipEntryName("C:\\temp\\a.xml"))
        assertTrue(ImportadorPlanilhaUniversal.isSafeZipEntryName("xl/worksheets/sheet1.xml"))

        val grande = ByteArray(1024) { 65 }
        assertNull(ImportadorPlanilhaUniversal.readZipEntryBounded(ByteArrayInputStream(grande), 512))
        assertEquals(1024, ImportadorPlanilhaUniversal.readZipEntryBounded(ByteArrayInputStream(grande), 2048)?.size)

        // Windows-1252 com acentos + ponto e vírgula
        val csvWin1252 = "Cultivar;Repetição;Árvore\r\nMaçã Gala;1;2\r\n".toByteArray(Charset.forName("windows-1252"))
        val lida = ImportadorPlanilhaUniversal.lerPlanilhaDeBytes(csvWin1252)
        assertEquals("CSV", lida.formato)
        assertEquals("Maçã Gala", lida.linhas[0][0])
        val map = ImportadorPlanilhaUniversal.sugerirMapeamento(lida.cabecalho)
        assertTrue(map.completo)

        // Índices de colunas do Excel (A=0, Z=25, AA=26)
        assertEquals(0, ImportadorPlanilhaUniversal.extrairIndiceColunaExcel("A1"))
        assertEquals(25, ImportadorPlanilhaUniversal.extrairIndiceColunaExcel("Z99"))
        assertEquals(26, ImportadorPlanilhaUniversal.extrairIndiceColunaExcel("AA2"))
    }

    // ── 3. ASSETS REAIS .TFLITE, PARSERS DE LABELS & FUNÇÕES DE REVISÃO ──

    private fun localizarPastaAssets(): File {
        val candidatos = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("C:/Users/igor/Downloads/BreedAI/app/src/main/assets")
        )
        return candidatos.first { it.exists() && it.isDirectory }
    }

    @Test
    fun `todos os modelos embutidos existem nos assets com assinatura TFL3 valida e labels corretos`() {
        val assetsDir = localizarPastaAssets()
        assertTrue("Pasta de assets deve existir", assetsDir.exists())

        MODELOS_EMBUTIDOS.forEach { modelo ->
            val arq = File(assetsDir, modelo.nomeArquivo)
            assertTrue("Asset ausente: ${modelo.nomeArquivo}", arq.exists() && arq.length() > 1024L)
            val header = arq.inputStream().use { inp ->
                val buf = ByteArray(16)
                val n = inp.read(buf)
                buf.copyOf(n.coerceAtLeast(0))
            }
            assertTrue("Assinatura TFL3 inválida em ${modelo.nomeArquivo}", ObjectDetectorHelper.isTfliteFlatBuffer(header))
        }

        val labelsDoenca = ObjectDetectorHelper.extractLabelsFromFile(File(assetsDir, "detector_doenca.tflite"))
        assertEquals(LABELS_DOENCA_CACAU, labelsDoenca)

        val labelsFrutas = ObjectDetectorHelper.extractLabelsFromFile(File(assetsDir, "detector_frutas_coco.tflite"))
        assertTrue("detector_frutas_coco deve conter banana", labelsFrutas.any { it.equals("banana", ignoreCase = true) })
        assertTrue("detector_frutas_coco deve conter apple", labelsFrutas.any { it.equals("apple", ignoreCase = true) })
    }

    @Test
    fun `parseLabelsDeTextoOuYaml suporta YAML inline, YAML mapa, YAML lista e TXT simples`() {
        val yamlInline = "nc: 3\nnames: ['Sadio', 'Vassoura', 'Podridão']"
        assertEquals(listOf("Sadio", "Vassoura", "Podridão"), ObjectDetectorHelper.parseLabelsDeTextoOuYaml(yamlInline, "data.yaml"))

        val yamlMapa = """
            names:
              0: PodridaoParda
              1: Moniliase
              2: Sadio
        """.trimIndent()
        assertEquals(listOf("PodridaoParda", "Moniliase", "Sadio"), ObjectDetectorHelper.parseLabelsDeTextoOuYaml(yamlMapa, "data.yml"))

        val yamlLista = """
            names:
              - banana
              - apple
        """.trimIndent()
        assertEquals(listOf("banana", "apple"), ObjectDetectorHelper.parseLabelsDeTextoOuYaml(yamlLista, "classes.yaml"))

        val txt = "# comentario\nfruto\nfolha\n"
        assertEquals(listOf("fruto", "folha"), ObjectDetectorHelper.parseLabelsDeTextoOuYaml(txt, "labels.txt"))
    }

    @Test
    fun `formatarDiagnosticoRegistro preserva resumo de classes, Sem Frutos e Nao Avaliavel`() {
        val genericas = listOf(CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "cacau"))
        assertEquals("1", formatarDiagnosticoRegistro(SituacaoPlanta.NORMAL, "", 1, genericas))

        val doencas = listOf(
            CaixaAuditavel(0.1f, 0.1f, 0.2f, 0.2f, rotulo = "Sadio"),
            CaixaAuditavel(0.3f, 0.3f, 0.4f, 0.4f, rotulo = "Sadio"),
            CaixaAuditavel(0.5f, 0.5f, 0.6f, 0.6f, rotulo = "VassouraBruxa")
        )
        val diagDoencas = formatarDiagnosticoRegistro(SituacaoPlanta.NORMAL, "", 3, doencas)
        assertTrue(diagDoencas.startsWith("3 ("))
        assertTrue(diagDoencas.contains("Sadio: 2"))
        assertTrue(diagDoencas.contains("Vassoura: 1"))

        assertEquals("Sem Frutos (0)", formatarDiagnosticoRegistro(SituacaoPlanta.SEM_FRUTOS, "", 0, emptyList()))
        assertEquals("Não avaliável (Morta)", formatarDiagnosticoRegistro(SituacaoPlanta.NAO_AVALIAVEL, "Morta", 0, emptyList()))
    }

    @Test
    fun `funcoes geometricas e de auditoria da TelaRevisaoCaptura funcionam sem sair dos limites`() {
        val c1 = CaixaAuditavel(0.10f, 0.10f, 0.30f, 0.30f, rotulo = "Sadio", manual = false)
        val c2 = CaixaAuditavel(0.25f, 0.10f, 0.45f, 0.30f, rotulo = "VassouraBruxa", manual = false)

        // Toque em cacho denso escolhe o centro mais próximo
        assertEquals(0, selecionarIndiceCaixaNoToque(listOf(c1, c2), 0.18f, 0.20f))
        assertEquals(1, selecionarIndiceCaixaNoToque(listOf(c1, c2), 0.38f, 0.20f))
        assertEquals(-1, selecionarIndiceCaixaNoToque(listOf(c1, c2), 0.90f, 0.90f))

        // Segundo toque cicla classe quando multiclasse
        val classes = listOf("Sadio", "VassouraBruxa", "PodridaoParda")
        assertEquals(0 to "Sadio", resolverToqueEmCaixaExistente(-1, 0, "Sadio", classes, "Sadio"))
        assertEquals(0 to "VassouraBruxa", resolverToqueEmCaixaExistente(0, 0, "Sadio", classes, "Sadio"))

        // Alças de canto, corpo e fora
        assertEquals(AlcaCaixa.CANTO_SE, detectarAlcaCaixaSelecionada(c1, 0.10f, 0.10f, 0.04f, 0.04f))
        assertEquals(AlcaCaixa.CANTO_ID, detectarAlcaCaixaSelecionada(c1, 0.30f, 0.30f, 0.04f, 0.04f))
        assertEquals(AlcaCaixa.CORPO, detectarAlcaCaixaSelecionada(c1, 0.20f, 0.20f, 0.04f, 0.04f))
        assertEquals(AlcaCaixa.NENHUMA, detectarAlcaCaixaSelecionada(c1, 0.90f, 0.90f, 0.04f, 0.04f))

        // Mover, redimensionar e ajustar canto nunca ultrapassam [0, 1]
        val movida = moverCaixaAuditavel(c1, 2.0f, -2.0f)
        assertEquals(0.80f, movida.normLeft, 0.001f)
        assertEquals(1.00f, movida.normRight, 0.001f)
        assertEquals(0.00f, movida.normTop, 0.001f)

        val redim = redimensionarCaixaAuditavel(c1, 10f)
        assertTrue(redim.normLeft >= 0f && redim.normRight <= 1f && redim.largura <= 0.951f)

        val canto = ajustarCantoCaixaAuditavel(c1, AlcaCaixa.CANTO_SE, 0.99f, 0.99f)
        assertTrue(canto.normLeft <= canto.normRight - 0.029f)

        // Coordinate snapping preserva caixas manuais, bloqueia excluídas e alinha caixas IA
        val manual = CaixaAuditavel(0.60f, 0.60f, 0.80f, 0.80f, rotulo = "Sadio", manual = true)
        val excluida = CaixaAuditavel(0.02f, 0.02f, 0.12f, 0.12f, rotulo = "cacau", manual = false)
        val estaticas = listOf(
            DetEstilizada(0.11f, 0.11f, 0.31f, 0.31f, 0.95f, "PodridaoParda"), // casa com c1
            DetEstilizada(0.02f, 0.02f, 0.12f, 0.12f, 0.90f, "Sadio"),         // deve ser bloqueada por excluida
            DetEstilizada(0.61f, 0.61f, 0.81f, 0.81f, 0.92f, "VassouraBruxa")  // deve preservar manual
        )
        val fundidas = fundirDeteccoesComSnapping(listOf(c1, manual), estaticas, listOf(excluida))
        assertEquals(2, fundidas.size)
        assertEquals("PodridaoParda", fundidas[0].rotulo)
        assertEquals("Sadio", fundidas[1].rotulo)
        assertTrue(fundidas[1].manual)

        // Rótulos amigáveis, IoU e cores determinísticas
        assertEquals("Podridão", rotuloAmigavel("PodridaoParda"))
        assertEquals("Maçã", rotuloAmigavel("apple"))
        assertEquals("Objeto", rotuloAmigavel("  "))
        assertEquals(1f, iouCaixasNorm(0.1f, 0.1f, 0.3f, 0.3f, 0.1f, 0.1f, 0.3f, 0.3f), 0.001f)
        assertEquals(0f, iouCaixasNorm(0.1f, 0.1f, 0.2f, 0.2f, 0.5f, 0.5f, 0.6f, 0.6f), 0.001f)
        assertEquals(corParaLabel("Sadio"), corParaCaixa("Sadio", false, listOf("Sadio")))
    }

    @Test
    fun `VolumeShutterBridge registra, dispara e limpa callback com seguranca`() {
        var disparos = 0
        try {
            VolumeShutterBridge.onShutter = {
                disparos++
                true
            }
            assertEquals(true, VolumeShutterBridge.onShutter?.invoke())
            assertEquals(1, disparos)
        } finally {
            VolumeShutterBridge.onShutter = null
        }
        assertNull(VolumeShutterBridge.onShutter)
    }
}
