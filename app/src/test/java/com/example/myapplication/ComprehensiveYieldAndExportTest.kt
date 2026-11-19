package com.example.myapplication

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.myapplication.data.ConfigConversaoRendimento
import com.example.myapplication.data.PontoFormulario
import com.example.myapplication.data.PresetRendimento
import com.example.myapplication.data.RegistroColeta
import com.example.myapplication.data.SituacaoPlanta
import com.example.myapplication.data.TemplateCultura
import com.example.myapplication.data.TipoVariavelExtra
import com.example.myapplication.data.VariavelExtra
import com.example.myapplication.data.calcularEstimativaPlanta
import com.example.myapplication.data.calcularRankingTratamentos
import com.example.myapplication.data.calcularRendimentoColeta
import com.example.myapplication.data.calcularResumoAgronomico
import com.example.myapplication.data.contarAproveitaveisEDoentes
import com.example.myapplication.data.formatarEstimativaCurta
import com.example.myapplication.data.gerarResumoTextoColeta
import com.example.myapplication.data.isRotuloPerdaDoenca
import com.example.myapplication.userinterface.CaixaAuditavel
import com.example.myapplication.userinterface.util.csvField
import com.example.myapplication.userinterface.util.exportarPacoteZipBreedAI
import com.example.myapplication.userinterface.util.gerarConteudoCsvBreedAI
import com.example.myapplication.userinterface.util.gerarResumoTratamentosCsvBreedAI
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

class ComprehensiveYieldAndExportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun mockUri(path: String = "file:///tmp/foto.jpg"): Uri {
        val uri = Mockito.mock(Uri::class.java)
        Mockito.`when`(uri.toString()).thenReturn(path)
        if (path.startsWith("file://")) {
            Mockito.`when`(uri.path).thenReturn(path.removePrefix("file://"))
        }
        return uri
    }

    private fun reg(
        tratamento: String,
        parcela: String = "1",
        planta: String = "1",
        situacao: SituacaoPlanta = SituacaoPlanta.NORMAL,
        totalObjetos: Int = 0,
        caixas: List<CaixaAuditavel> = emptyList(),
        coletaNome: String = "Ensaio_01",
        motivo: String = "",
        uri: Uri = mockUri(),
        extras: Map<String, String> = emptyMap(),
        idCustom: String? = null
    ) = RegistroColeta(
        uri = uri,
        idCompleto = idCustom ?: "${tratamento}_P${parcela}_Pl$planta",
        coletaNome = coletaNome,
        tratamento = tratamento,
        parcela = parcela,
        planta = planta,
        situacaoPlanta = situacao,
        motivoNaoAvaliavel = motivo,
        diagnostico = "",
        data = "19/11/2026 10:00:00",
        lat = -14.79,
        lng = -39.23,
        totalObjetos = totalObjetos,
        variaveisExtras = extras,
        caixas = caixas
    )

    private fun caixa(rotulo: String, manual: Boolean = false, l: Float = 0.1f, t: Float = 0.1f) =
        CaixaAuditavel(l, t, l + 0.15f, t + 0.15f, rotulo = rotulo, manual = manual, confianca = 0.9f)

    // ── 1. TEMPLATES & PRESETS DE RENDIMENTO ──

    @Test
    fun `template fruticultura nao deve conter clones especificos do CEPEC`() {
        val exemplos = TemplateCultura.FRUTICULTURA.exemplosTratamento
        assertEquals(3, exemplos.size)
        val bloqueados = listOf("CCN-51", "PS-1319", "CEPEC-2002", "CEPEC")
        exemplos.forEach { ex ->
            bloqueados.forEach { b ->
                assertFalse("Clone específico não deveria aparecer: $ex", ex.contains(b, ignoreCase = true))
            }
        }
    }

    @Test
    fun `presets e mapeamento por template devem cobrir todas as culturas`() {
        assertEquals(PresetRendimento.CACAU_ARROBA, PresetRendimento.fromId("cacau_arroba"))
        assertEquals(PresetRendimento.GRAOS_SACA, PresetRendimento.fromId("GRAOS_SACA"))
        assertEquals(PresetRendimento.DESATIVADO, PresetRendimento.fromId("desativado"))
        assertEquals(PresetRendimento.CACAU_ARROBA, PresetRendimento.fromId("desconhecido"))
        assertEquals(PresetRendimento.CACAU_ARROBA, PresetRendimento.fromId(null))

        val cfgFrut = ConfigConversaoRendimento.paraTemplate(TemplateCultura.FRUTICULTURA)
        assertTrue(cfgFrut.ativo)
        assertEquals("@", cfgFrut.unidadeSimbolo)
        assertEquals(22.0, cfgFrut.itensPorKg, 0.001)
        assertEquals(15.0, cfgFrut.kgPorUnidade, 0.001)
        assertEquals(1111, cfgFrut.plantasPorHa)

        val cfgGraos = ConfigConversaoRendimento.paraTemplate(TemplateCultura.GRAOS)
        assertTrue(cfgGraos.ativo)
        assertEquals("sc", cfgGraos.unidadeSimbolo)
        assertEquals(60.0, cfgGraos.kgPorUnidade, 0.001)

        assertFalse(ConfigConversaoRendimento.paraTemplate(TemplateCultura.GERAL).ativo)
        assertFalse(ConfigConversaoRendimento.paraTemplate(TemplateCultura.SEMENTES_LAB).ativo)
    }

    // ── 2. RECONHECIMENTO DE DOENÇAS & APROVEITÁVEIS ──

    @Test
    fun `isRotuloPerdaDoenca reconhece acentos e variacoes mas preserva frutos sadios e genericos`() {
        val perdas = listOf(
            "PodridaoParda", "Podridão Parda", "PODRIDÃO", "VassouraBruxa", "Vassoura de Bruxa",
            "Moniliase", "Monilíase", "Doente", "Descarte", "Não Avaliável", "Nao Avaliavel", "Podre", "Necrose"
        )
        perdas.forEach { lbl ->
            assertTrue("Deveria classificar como perda: $lbl", isRotuloPerdaDoenca(lbl))
        }

        val aproveitaveis = listOf("Sadio", "sadio", "cacau", "fruto", "objeto", "banana", "apple", "Manual", "", "   ")
        aproveitaveis.forEach { lbl ->
            assertFalse("Não deveria classificar como perda: $lbl", isRotuloPerdaDoenca(lbl))
        }
    }

    @Test
    fun `contarAproveitaveisEDoentes trata caixas vazias, contagem negativa e tudo doente`() {
        assertEquals(15 to 0, contarAproveitaveisEDoentes(emptyList(), 15, descontarDoencas = true))
        assertEquals(0 to 0, contarAproveitaveisEDoentes(emptyList(), -5, descontarDoencas = true))

        val todasDoentes = listOf(caixa("VassouraBruxa"), caixa("PodridaoParda"), caixa("Moniliase"))
        assertEquals(0 to 3, contarAproveitaveisEDoentes(todasDoentes, 3, descontarDoencas = true))
        assertEquals(3 to 0, contarAproveitaveisEDoentes(todasDoentes, 3, descontarDoencas = false))
    }

    @Test
    fun `calcularEstimativaPlanta protege contra zeros e negativos na configuracao`() {
        val cfgQuebrada = ConfigConversaoRendimento(
            ativo = true,
            unidadeSimbolo = "@",
            itensPorKg = 0.0,
            kgPorUnidade = -10.0,
            plantasPorHa = -5,
            descontarDoencas = true
        )
        val est = calcularEstimativaPlanta(10, listOf(caixa("Sadio")), cfgQuebrada)
        assertFalse(est.kgEstimado.isNaN())
        assertFalse(est.kgEstimado.isInfinite())
        assertFalse(est.unidadesComerciais.isNaN())
        assertFalse(est.unidadesComerciais.isInfinite())
        assertTrue(est.kgEstimado > 0.0)
        assertTrue(est.unidadesComerciais > 0.0)
    }

    @Test
    fun `calcularEstimativaPlanta valida presets caixa 20kg, saca 60kg e peso kg`() {
        val estCx = calcularEstimativaPlanta(
            totalObjetos = 120,
            caixas = emptyList(),
            config = ConfigConversaoRendimento.dePreset(PresetRendimento.FRUTAS_CAIXA)
        )
        assertEquals(20.0, estCx.kgEstimado, 0.001)
        assertEquals(1.0, estCx.unidadesComerciais, 0.001)

        val estSc = calcularEstimativaPlanta(
            totalObjetos = 330,
            caixas = emptyList(),
            config = ConfigConversaoRendimento.dePreset(PresetRendimento.GRAOS_SACA)
        )
        assertEquals(60.0, estSc.kgEstimado, 0.001)
        assertEquals(1.0, estSc.unidadesComerciais, 0.001)

        val estKg = calcularEstimativaPlanta(
            totalObjetos = 50,
            caixas = emptyList(),
            config = ConfigConversaoRendimento.dePreset(PresetRendimento.PESO_KG)
        )
        assertEquals(5.0, estKg.kgEstimado, 0.001)
        assertEquals(5.0, estKg.unidadesComerciais, 0.001)
    }

    // ── 3. RENDIMENTO DA COLETA & EDGE CASES AGRONÔMICOS ──

    @Test
    fun `calcularRendimentoColeta em lista vazia, todas nao avaliaveis e todas sem frutos nao gera NaN`() {
        val cfg = ConfigConversaoRendimento.dePreset(PresetRendimento.CACAU_ARROBA)

        val vazio = calcularRendimentoColeta(emptyList(), cfg)
        assertEquals(0, vazio.totalAproveitaveis)
        assertEquals(100.0, vazio.estandePercentual, 0.001)
        assertEquals(0.0, vazio.realUnidadesHa, 0.001)
        assertFalse(vazio.temClassificacaoDoenca)

        val todasNA = listOf(
            reg("T1", "1", "1", SituacaoPlanta.NAO_AVALIAVEL, 0, motivo = "Morta"),
            reg("T1", "1", "2", SituacaoPlanta.NAO_AVALIAVEL, 0, motivo = "Falha")
        )
        val resNA = calcularRendimentoColeta(todasNA, cfg)
        assertEquals(0.0, resNA.estandePercentual, 0.001)
        assertEquals(0.0, resNA.mediaAproveitaveisPlantaViva, 0.001)
        assertEquals(0.0, resNA.realUnidadesHa, 0.001)
        assertEquals(0.0, resNA.perdaUnidadesHa, 0.001)

        val todasSem = listOf(
            reg("T1", "1", "1", SituacaoPlanta.SEM_FRUTOS, 0),
            reg("T1", "1", "2", SituacaoPlanta.SEM_FRUTOS, 0)
        )
        val resSem = calcularRendimentoColeta(todasSem, cfg)
        assertEquals(100.0, resSem.estandePercentual, 0.001)
        assertEquals(0.0, resSem.mediaAproveitaveisPlantaViva, 0.001)
        assertEquals(0.0, resSem.realUnidadesHa, 0.001)
    }

    @Test
    fun `calcularRendimentoColeta com plantasPorHa zero zera projecao por hectare mas mantem acumulado`() {
        val cfg = ConfigConversaoRendimento.dePreset(PresetRendimento.CACAU_ARROBA).copy(plantasPorHa = 0)
        val regs = listOf(reg("T1", "1", "1", SituacaoPlanta.NORMAL, 330))
        val res = calcularRendimentoColeta(regs, cfg)
        assertEquals(15.0, res.kgAcumulado, 0.001)
        assertEquals(1.0, res.unidadesAcumuladas, 0.001)
        assertEquals(0.0, res.potencialKgHa, 0.001)
        assertEquals(0.0, res.realKgHa, 0.001)
        assertEquals(0.0, res.realUnidadesHa, 0.001)
        assertEquals(0.0, res.perdaUnidadesHa, 0.001)
    }

    @Test
    fun `calcularResumoAgronomico confere contagem de situacoes e estande`() {
        val regs = listOf(
            reg("G1", "1", "1", SituacaoPlanta.NORMAL, 20),
            reg("G1", "1", "2", SituacaoPlanta.SEM_FRUTOS, 0),
            reg("G1", "1", "3", SituacaoPlanta.NAO_AVALIAVEL, 0, motivo = "Pequena"),
            reg("G1", "1", "4", SituacaoPlanta.NORMAL, 10)
        )
        val r = calcularResumoAgronomico(regs)
        assertEquals(4, r.totalRegistros)
        assertEquals(3, r.plantasValidas)
        assertEquals(1, r.plantasNaoAvaliaveis)
        assertEquals(30, r.totalObjetos)
        assertEquals(10.0, r.mediaPorPlantaValida, 0.001)
        assertEquals(75.0, r.estandePercentual, 0.001)
    }

    // ── 4. RANKING DE TRATAMENTOS & FORMATAÇÃO WHATSAPP ──

    @Test
    fun `calcularRankingTratamentos desempata por total aproveitavel e ordem alfabetica e agrupa vazio`() {
        val cfg = ConfigConversaoRendimento.dePreset(PresetRendimento.CACAU_ARROBA)
        val regs = listOf(
            reg("   ", "1", "1", SituacaoPlanta.NORMAL, 5),
            reg("Beta", "1", "1", SituacaoPlanta.NORMAL, 20),
            reg("Alfa", "1", "1", SituacaoPlanta.NORMAL, 20),
            reg("Gama", "1", "1", SituacaoPlanta.NORMAL, 20),
            reg("Gama", "1", "2", SituacaoPlanta.NORMAL, 20)
        )
        val ranking = calcularRankingTratamentos(regs, cfg)
        assertEquals(4, ranking.size)
        // Gama, Alfa e Beta têm média 20.0 -> Gama tem total 40 (ganha), depois Alfa antes de Beta
        assertEquals("Gama", ranking[0].tratamento)
        assertEquals("Alfa", ranking[1].tratamento)
        assertEquals("Beta", ranking[2].tratamento)
        assertEquals("Sem tratamento", ranking[3].tratamento)
    }

    @Test
    fun `formatarEstimativaCurta protege NaN e Infinity e alterna casas decimais em 10`() {
        assertEquals("0,00 @", formatarEstimativaCurta(Double.NaN, "@"))
        assertEquals("0,00 @", formatarEstimativaCurta(Double.POSITIVE_INFINITY, "@"))
        assertEquals("0,00 @", formatarEstimativaCurta(Double.NEGATIVE_INFINITY, "@"))
        assertEquals("9,95 @", formatarEstimativaCurta(9.95, "@"))
        assertEquals("10,0 @", formatarEstimativaCurta(10.0, "@"))
        assertEquals("42,3 kg", formatarEstimativaCurta(42.34, "kg"))
        assertEquals("15,0", formatarEstimativaCurta(15.0, "  "))
    }

    @Test
    fun `gerarResumoTextoColeta respeita rendimento desligado, tratamento unico e limite Top 5`() {
        val cfgOff = ConfigConversaoRendimento.dePreset(PresetRendimento.DESATIVADO)
        val regs1 = listOf(reg("Unico", "1", "1", SituacaoPlanta.NORMAL, 12))
        val txtOff = gerarResumoTextoColeta("Teste Off", "Genótipo", regs1, cfgOff, TemplateCultura.FRUTICULTURA)
        assertTrue(txtOff.contains("Total contado: 12"))
        assertFalse(txtOff.contains("Rendimento"))
        assertFalse(txtOff.contains("*Top"))

        val cfgOnSemHa = ConfigConversaoRendimento.dePreset(PresetRendimento.CACAU_ARROBA).copy(plantasPorHa = 0)
        val regs7 = (1..7).map { i -> reg("G0$i", "1", "1", SituacaoPlanta.NORMAL, i * 10) }
        val txt7 = gerarResumoTextoColeta("Ensaio 7", "Genótipo", regs7, cfgOnSemHa, TemplateCultura.FRUTICULTURA)
        assertTrue(txt7.contains("Rendimento estimado:"))
        assertFalse(txt7.contains("Projeção/ha"))
        assertTrue(txt7.contains("1. G07:"))
        assertTrue(txt7.contains("5. G03:"))
        assertFalse(txt7.contains("6. G02:"))
    }

    // ── 5. CSV, RFC 4180, RESUMO TRATAMENTOS & EXPORTAÇÃO ZIP EM THREAD DE BACKGROUND ──

    @Test
    fun `csvField escapa virgulas, aspas duplas, quebras de linha e nulos`() {
        assertEquals("", csvField(null))
        assertEquals("simples", csvField("simples"))
        assertEquals("\"com, virgula\"", csvField("com, virgula"))
        assertEquals("\"com \"\"aspas\"\"\"", csvField("com \"aspas\""))
        assertEquals("\"linha1\nlinha2\"", csvField("linha1\nlinha2"))
    }

    @Test
    fun `gerarConteudoCsvBreedAI trata simbolos de unidade, situacoes especiais e colisao de colunas`() {
        val pontos = listOf(
            PontoFormulario(
                tratamento = "G1",
                parcela = "1",
                planta = "1",
                colunasPlanilha = mapOf(
                    "Area" to "Norte",
                    "Kg_Estimado" to "COLISAO_IGNORADA",
                    "Total_Contado" to "COLISAO_IGNORADA"
                )
            )
        )
        val regs = listOf(
            reg("G1", "1", "1", SituacaoPlanta.NORMAL, 20, caixas = List(20) { caixa("Sadio") }),
            reg("G1", "1", "2", SituacaoPlanta.SEM_FRUTOS, 0),
            reg("G1", "1", "3", SituacaoPlanta.NAO_AVALIAVEL, 0, motivo = "Morta")
        )

        val cfgCaixa = ConfigConversaoRendimento.dePreset(PresetRendimento.FRUTAS_CAIXA)
        val csvCx = gerarConteudoCsvBreedAI(
            registros = regs,
            labelTratamento = "Genótipo",
            labelParcela = "Parcela",
            labelPlanta = "Planta",
            variaveisAtivas = listOf(VariavelExtra("v1", "Nota", TipoVariavelExtra.TEXTO, ativa = true)),
            pontosFormulario = pontos,
            configRendimento = cfgCaixa
        )
        val linhas = csvCx.trim().lines()
        assertEquals(4, linhas.size)
        // Coluna extra legítima entra, colisões com colunas reservadas não duplicam
        assertTrue(linhas[0].contains("Area"))
        assertEquals(1, linhas[0].split(",").count { it == "Kg_Estimado" })
        assertTrue(linhas[0].contains("Aproveitaveis,Kg_Estimado,Unid_Estimada_cx"))
        // SEM_FRUTOS e NAO_AVALIAVEL devem sair com 0,0.00,0.00
        assertTrue(linhas[2].contains(",0,0.00,0.00,"))
        assertTrue(linhas[3].contains(",0,0.00,0.00,"))

        // Símbolo vazio gera nome seguro Unid_Estimada
        val csvSimboloVazio = gerarConteudoCsvBreedAI(
            registros = listOf(regs[0]),
            labelTratamento = "T",
            labelParcela = "P",
            labelPlanta = "Pl",
            variaveisAtivas = emptyList(),
            configRendimento = cfgCaixa.copy(unidadeSimbolo = "   ")
        )
        assertTrue(csvSimboloVazio.lines()[0].contains("Aproveitaveis,Kg_Estimado,Unid_Estimada,"))

        // Rendimento desligado omite colunas
        val csvOff = gerarConteudoCsvBreedAI(
            registros = listOf(regs[0]),
            labelTratamento = "T",
            labelParcela = "P",
            labelPlanta = "Pl",
            variaveisAtivas = emptyList(),
            configRendimento = cfgCaixa.copy(ativo = false)
        )
        assertFalse(csvOff.lines()[0].contains("Kg_Estimado"))
    }

    @Test
    fun `gerarResumoTratamentosCsvBreedAI gera ranking com e sem rendimento`() {
        val regs = listOf(
            reg("T1", "1", "1", SituacaoPlanta.NORMAL, 22, caixas = List(22) { caixa("Sadio") }),
            reg("T2", "1", "1", SituacaoPlanta.NORMAL, 44, caixas = List(40) { caixa("Sadio") } + List(4) { caixa("Vassoura") })
        )
        val csvOn = gerarResumoTratamentosCsvBreedAI(
            registros = regs,
            labelTratamento = "Clone",
            configRendimento = ConfigConversaoRendimento.dePreset(PresetRendimento.CACAU_ARROBA)
        )
        val lOn = csvOn.trim().lines()
        assertEquals(3, lOn.size)
        assertTrue(lOn[0].contains("Posicao,Clone,Plantas,Avaliaveis,Estande_Pct,Total_Contado,Aproveitaveis,Doentes,Media_Aproveitaveis,Taxa_Doenca_Pct,Kg_Estimado,Unid_Estimada_Arrobas,Unid_Estimada_Arrobas_Por_Ha"))
        assertTrue(lOn[1].startsWith("1,T2,1,1,100.0,44,40,4,40.00,"))

        val csvOff = gerarResumoTratamentosCsvBreedAI(regs, "Clone", ConfigConversaoRendimento(ativo = false))
        assertFalse(csvOff.lines()[0].contains("Kg_Estimado"))
    }

    @Test
    fun `exportarPacoteZipBreedAI executa em thread background sem crash de Toast e gera ZIP completo valido`() {
        val cacheDir = tempFolder.newFolder("cache")
        val fotoFile = tempFolder.newFile("planta_teste.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4))
        }

        val resolver = Mockito.mock(ContentResolver::class.java)
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.cacheDir).thenReturn(cacheDir)
        Mockito.`when`(ctx.contentResolver).thenReturn(resolver)

        val fotoUri = mockUri("file://${fotoFile.absolutePath}")
        val regs = listOf(
            reg(
                tratamento = "GenA",
                parcela = "1",
                planta = "1",
                totalObjetos = 2,
                caixas = listOf(caixa("Sadio", l = 0.1f, t = 0.2f), caixa("VassouraBruxa", l = 0.5f, t = 0.5f)),
                uri = fotoUri,
                idCustom = "GenA_P1_Pl1"
            ),
            // Segundo registro com mesmo idCompleto para testar deduplicação nomeUnico (_2) sem ZipException
            reg(
                tratamento = "GenA",
                parcela = "1",
                planta = "1",
                totalObjetos = 1,
                caixas = listOf(caixa("Sadio", l = 0.2f, t = 0.2f)),
                uri = fotoUri,
                idCustom = "GenA_P1_Pl1"
            )
        )

        val zipRef = AtomicReference<File?>()
        val erroRef = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                val res = exportarPacoteZipBreedAI(
                    context = ctx,
                    registros = regs,
                    coletaNome = "Safra Teste 2026",
                    labelTratamento = "Genótipo",
                    labelParcela = "Parcela",
                    labelPlanta = "Planta",
                    variaveisAtivas = emptyList(),
                    classesModeloExportacao = listOf("Sadio", "VassouraBruxa"),
                    configRendimento = ConfigConversaoRendimento.dePreset(PresetRendimento.CACAU_ARROBA)
                )
                zipRef.set(res)
            } catch (t: Throwable) {
                erroRef.set(t)
            }
        }
        worker.start()
        worker.join(10_000L)

        assertEquals(null, erroRef.get())
        val zipFile = zipRef.get()
        assertNotNull("ZIP deveria ter sido gerado em cacheDir/exports", zipFile)
        assertTrue(zipFile!!.exists() && zipFile.length() > 0L)

        val entradas = linkedMapOf<String, ByteArray>()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entradas[entry.name] = zis.readBytes()
                entry = zis.nextEntry
            }
        }

        assertTrue(entradas.containsKey("planilha_Safra_Teste_2026.csv"))
        assertTrue(entradas.containsKey("resumo_tratamentos.csv"))
        assertTrue(entradas.containsKey("classes.txt"))
        assertTrue(entradas.containsKey("labels_yolo/GenA_P1_Pl1.txt"))
        assertTrue(entradas.containsKey("labels_yolo/GenA_P1_Pl1_2.txt"))
        assertTrue(entradas.containsKey("fotos/GenA_P1_Pl1.jpg"))
        assertTrue(entradas.containsKey("fotos/GenA_P1_Pl1_2.jpg"))

        // Confirma UTF-8 BOM + colunas de rendimento
        val csvBytes = entradas["planilha_Safra_Teste_2026.csv"]!!
        assertEquals(0xEF.toByte(), csvBytes[0])
        assertEquals(0xBB.toByte(), csvBytes[1])
        assertEquals(0xBF.toByte(), csvBytes[2])
        val csvTexto = String(csvBytes, 3, csvBytes.size - 3, StandardCharsets.UTF_8)
        assertTrue(csvTexto.contains("Unid_Estimada_Arrobas"))

        val resumoTexto = String(entradas["resumo_tratamentos.csv"]!!, 3, entradas["resumo_tratamentos.csv"]!!.size - 3, StandardCharsets.UTF_8)
        assertTrue(resumoTexto.contains("Unid_Estimada_Arrobas_Por_Ha"))
        assertTrue(resumoTexto.contains("GenA"))

        val classesTxt = String(entradas["classes.txt"]!!, StandardCharsets.UTF_8).lines()
        assertEquals(listOf("Sadio", "VassouraBruxa"), classesTxt)

        val yoloLinhas = String(entradas["labels_yolo/GenA_P1_Pl1.txt"]!!, StandardCharsets.UTF_8).trim().lines()
        assertEquals(2, yoloLinhas.size)
        assertTrue(yoloLinhas[0].startsWith("0 "))
        assertTrue(yoloLinhas[1].startsWith("1 "))

        assertArrayEquals(fotoFile.readBytes(), entradas["fotos/GenA_P1_Pl1.jpg"])
    }
}
