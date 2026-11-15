# BreedAI — Fenotipagem Agronômica em Campo com Visão Computacional (Edge AI)

**BreedAI** é um aplicativo Android 100% offline para fenotipagem e coleta de dados em campo com detecção de objetos por Visão Computacional (TensorFlow Lite / YOLO), inspirado na ergonomia do **Field Book** para melhoramento genético e experimentação agrícola multi-culturas.

> **Evolução generalista:** Derivado da base de Edge AI desenvolvida para cacaueiros, o **BreedAI** remove acoplamentos de backend e métricas específicas de uma única cultura para permitir o uso com **qualquer espécie ou modelo `.tflite` customizado**, mantendo o detector pré-carregado (`assets/detector.tflite`) pronto para uso imediato.

---

## Principais Funcionalidades

### 1. Navegação Superior Direta (estilo Field Book)
- Sem menu hambúrguer: navegação por abas superiores (`PrimaryTabRow`) abrindo diretamente na aba **Formulário**:
  1. **Modelo IA**: escolha do modelo embutido (`Cacau (Pré-carregado)`), importação permanente de `.tflite` ou pacotes `.zip`, exportação `.zip` (`model.tflite` + `model_config.json`), edição de rótulos (`labelsFiltro`), limiar de confiança e máximo de detecções.
  2. **Formulário** *(aba inicial)*: importação universal de planilhas `.xlsx` (Excel nativo) e `.csv`/`.tsv`, com **Tratamento / Parcela / Planta** fixos no topo para navegação rápida em campo.
  3. **Coleta Livre**: contadores incrementais com **Parcela / Planta / Tratamento** fixos no topo, rótulos personalizáveis (`Clone`, `Bloco`, `Árvore`, etc.) e ordem direta: Identificação -> Situação da Planta -> Variáveis Extras -> Câmera/Galeria.
  4. **Edição Dados**: filtros por situação da planta, revisão das marcações na imagem, edição de variáveis extras, exclusão e exportação/compartilhamento de CSV.
- Cabeçalho persistente com nome editável da **Coleta** (sem perda de dados), botão **+ Nova** para iniciar novas coletas e indicador limpo do modelo ativo.

### 2. Importação Universal de Planilhas (`.xlsx` e `.csv`/`.tsv`)
- **`ImportadorPlanilhaUniversal.kt`**:
  - Leitura nativa de **Excel `.xlsx`** via `ZipInputStream` + `SAXParserFactory` (compatível com Android `minSdk 24+`).
  - Detecção automática de codificação (**UTF-8** com/sem BOM e **Windows-1252**) e delimitadores (`;`, `,`, `\t`, `|`).
  - Mapeamento automático de colunas (`Tratamento`, `Parcela`, `Planta`) com diálogo manual quando os cabeçalhos forem customizados.

### 3. Situação da Planta & Variáveis Extras Configuráveis
- **Situação da Planta (`SituacaoPlanta`)**:
  - **Normal**: exige foto (Câmera ou Galeria) + contagem e revisão das caixas da IA.
  - **Sem Frutos**: exige foto comprobatória da planta com contagem registrada em `0`.
  - **Não avaliável**: motivos `Pequena`, `Morta`, `Errada` ou `Cavalo` (dispensa foto e registra contagem `0`).
- **Variáveis Extras (`VariavelExtra`)**:
  - Pré-cadastradas e alternáveis por coleta: `Cor do Fruto` (Lista), `Nota de Vírus` (1–5) e `Diâmetro do Tronco (cm)` (Numérico).
  - Criação livre de novas variáveis do tipo **Numérico**, **Texto** ou **Lista de Opções**, refletidas automaticamente como colunas no CSV exportado.

### 4. Pipeline Edge AI Geométrico (`ObjectDetectorHelper` & `TelaRevisaoCaptura`)
- Inferência direta em `Interpreter` com suporte a modelos **YOLO** e **SSD**, aplicando **Letterbox isométrico** (sem distorção de proporção).
- **Snapping geométrico pós-captura** (`fundirDeteccoesComSnapping`) sobre a foto estática para eliminar desalinhamento entre preview e obturador.
- Revisão humana por toque com seleção do centro mais próximo (`selecionarIndiceCaixaNoToque`), redimensionamento (`-` / `+`), exclusão anti-ressurreição, adição manual com 1 toque e troca rápida de rótulo quando houver múltiplas classes.

### 5. Armazenamento e Exportação 100% Offline
- Persistência local atômica via `AtomicFile` (`registros_breedai_local.json`, `formulario_breedai.json`, `variaveis_extras_breedai.json`).
- Exportação de CSV com BOM UTF-8 (`MediaStore.Downloads` em `Download/BreedAI` + compartilhamento via `FileProvider`) contendo todas as variáveis extras ativas e coordenadas das detecções.

---

## Estrutura do Projeto (`app/src/main/java/com/example/myapplication/`)

- `MainActivity.kt` — Shell principal (`BreedAIApp`), navegação `PrimaryTabRow`, gerenciamento de coleta e abas `TelaFormularioFieldBook` e `TelaColetaLivreFieldBook`.
- `data/`
  - `ModelConfig.kt` — Configuração de modelo ativo e manifesto JSON (`.zip`).
  - `RegistroColeta.kt` — Entidades `RegistroColeta`, `SituacaoPlanta` e `VariavelExtra`.
  - `PontoFormulario.kt` — Linha importada do formulário de campo.
- `userinterface/`
  - `TelaConfiguracaoModelo.kt` — Seleção, importação permanente e exportação `.zip` de modelos `.tflite`.
  - `TelaRevisaoCaptura.kt` — Auditoria geométrica interativa das caixas pós-foto.
  - `TelaEdicaoDados.kt` — Consulta, filtros, edição e exportação CSV.
  - `ComponentesFieldBook.kt` — Câmera fullscreen, seletor de situação da planta e variáveis extras.
  - `util/ObjectDetectorHelper.kt` — Engine TFLite (Letterbox, decoding multi-escala YOLO e NMS).
  - `util/ImportadorPlanilhaUniversal.kt` — Parser offline `.xlsx` / `.csv` / `.tsv`.
  - `util/PersistenciaLocalRegistros.kt` — Armazenamento atômico JSON e preferências locais.
  - `util/FileUtils.kt` — Geração CSV dinâmica e compartilhamento via `FileProvider`.
  - `util/CameraUtils.kt` — Pipeline CameraX `4:3` e overlay em tempo real.

---

## Build e Testes

Requisitos: Android Studio / JDK 17+ (`JAVA_HOME`) e Android SDK (`compileSdk 36`, `minSdk 24`).

```bash
# Executar todos os testes unitários e compilar o APK de debug
./gradlew testDebugUnitTest assembleDebug
```

Suítes unitárias incluídas em `app/src/test/java/com/example/myapplication/`:
- `CsvParsingUnitTest` — `.xlsx` nativo via XML/ZIP, CSV UTF-8/BOM, Windows-1252 e delimitadores.
- `LetterboxAndGeometryUnitTest` — Letterbox isométrico, projeção inversa, IoU, snapping e redimensionamento.
- `ModelAndStateUnitTest` — Manifesto `.zip`, `SituacaoPlanta`, `VariavelExtra` e regras de integridade.
- `DomainConcordanceIntegrationTest` — Jornada completa offline (coleta -> JSON -> CSV dinâmico).
- `YoloDetectionAndScaleUnitTest` — Decodificação de tensores YOLO e assinatura binária de `assets/detector.tflite`.
