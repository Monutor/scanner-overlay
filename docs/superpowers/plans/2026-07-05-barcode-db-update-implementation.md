# Barcode Database Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add download-and-merge workflow for product barcodes CSV into `ArticleBarcodeDatabase`.

**Architecture:** HTTP download via `HttpURLConnection` (reuse pattern from `AutoUpdateManager`), parse semicolon CSV, diff by `articleCode`, persist extras to `filesDir/barcode_extra.csv`.

**Tech Stack:** Kotlin, `java.net.URL`, coroutines, Compose, SharedPreferences (state only)

---

### Task 1: ArticleBarcodeDatabase — extras support

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/scanner/ArticleBarcodeDatabase.kt`

- [ ] **Step 1: Extract `seenArticleCodes` to class-level field, add `EXTRA_FILE` constant**

```kotlin
const val EXTRA_FILE = "barcode_extra.csv"

private val items = mutableListOf<ProductItem>()
private val seenArticleCodes = HashSet<String>()
private var loaded = false
```

- [ ] **Step 2: Update `init` to call `loadExtra()` after assets**

```kotlin
fun init(context: Context) {
    if (loaded) return
    synchronized(this) {
        if (loaded) return
        loadFromAssets(context)
        loadFromExtra(context)
        loaded = true
    }
}

private fun loadFromAssets(context: Context) {
    try {
        val stream = context.assets.open("barcode-products.csv")
        val reader = BufferedReader(InputStreamReader(stream))
        reader.readLine() // skip header
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val parts = line!!.split(";")
            if (parts.size < 14) continue
            val articleCode = parts[4].trim()
            val name = parts[5].trim()
            val barcode = parts[13].trim()
            if (articleCode.isEmpty() || seenArticleCodes.contains(articleCode)) continue
            seenArticleCodes.add(articleCode)
            items.add(ProductItem(articleCode, name, barcode))
        }
        reader.close()
    } catch (_: Exception) {}
}
```

- [ ] **Step 3: Add `loadFromExtra()`**

```kotlin
private fun loadFromExtra(context: Context) {
    try {
        val file = File(context.filesDir, EXTRA_FILE)
        if (!file.exists()) return
        val reader = BufferedReader(InputStreamReader(FileInputStream(file)))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val parts = line!!.split(";")
            if (parts.size < 14) continue
            val articleCode = parts[4].trim()
            val name = parts[5].trim()
            val barcode = parts[13].trim()
            if (articleCode.isEmpty() || seenArticleCodes.contains(articleCode)) continue
            seenArticleCodes.add(articleCode)
            items.add(ProductItem(articleCode, name, barcode))
        }
        reader.close()
    } catch (_: Exception) {}
}
```

- [ ] **Step 4: Add `mergeExtra()` and `persistExtra()`**

```kotlin
fun mergeExtra(newItems: List<ProductItem>) {
    items.addAll(newItems)
    for (item in newItems) {
        seenArticleCodes.add(item.articleCode)
    }
}

fun persistExtra(context: Context, newItems: List<ProductItem>) {
    try {
        val file = File(context.filesDir, EXTRA_FILE)
        val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true)))
        for (item in newItems) {
            writer.write(";;;;${item.articleCode};${item.name};;;;;;;;${item.barcode};;;;;;;")
            writer.newLine()
        }
        writer.close()
    } catch (_: Exception) {}
}
```

- [ ] **Step 5: Add `searchedByArticleCode` (existing) — no change needed**

```kotlin
fun searchByArticleCode(code: String): ProductItem? {
    return items.firstOrNull { it.articleCode == code }
}
```

---

### Task 2: SettingsViewModel — DbUpdateState + download methods

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add `DbUpdateState` sealed interface in SettingsViewModel**

```kotlin
sealed interface DbUpdateState {
    data object Idle : DbUpdateState
    data object Downloading : DbUpdateState
    data class Ready(val newItems: List<ProductItem>) : DbUpdateState
    data object UpToDate : DbUpdateState
    data class Error(val message: String) : DbUpdateState
}
```

- [ ] **Step 2: Add state + methods**

```kotlin
private val _dbUpdateState = MutableStateFlow<DbUpdateState>(DbUpdateState.Idle)
val dbUpdateState: StateFlow<DbUpdateState> = _dbUpdateState.asStateFlow()

fun downloadBarcodeDb() {
    viewModelScope.launch(Dispatchers.IO) {
        _dbUpdateState.value = DbUpdateState.Downloading
        try {
            val url = URL("https://github.com/Monutor/scanner-overlay/releases/latest/download/barcode-products.csv")
            val text = url.readText()
            val newItems = parseRemoteCsv(text)
            withContext(Dispatchers.Main) {
                if (newItems.isEmpty()) {
                    _dbUpdateState.value = DbUpdateState.UpToDate
                } else {
                    _dbUpdateState.value = DbUpdateState.Ready(newItems)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _dbUpdateState.value = DbUpdateState.Error(e.message ?: "Неизвестная ошибка")
            }
        }
    }
}

private fun parseRemoteCsv(text: String): List<ProductItem> {
    val result = mutableListOf<ProductItem>()
    val seen = HashSet<String>()
    val lines = text.lines()
    for (i in 1 until lines.size) { // skip header
        val line = lines[i].trim()
        if (line.isEmpty()) continue
        val parts = line.split(";")
        if (parts.size < 14) continue
        val articleCode = parts[4].trim()
        val name = parts[5].trim()
        val barcode = parts[13].trim()
        if (articleCode.isEmpty() || seen.contains(articleCode)) continue
        seen.add(articleCode)
        result.add(ProductItem(articleCode, name, barcode))
    }
    return result
}

fun applyBarcodeDbUpdate() {
    val state = _dbUpdateState.value
    if (state !is DbUpdateState.Ready) return
    ArticleBarcodeDatabase.mergeExtra(state.newItems)
    ArticleBarcodeDatabase.persistExtra(app, state.newItems)
    _dbUpdateState.value = DbUpdateState.Idle
}

fun resetBarcodeDbUpdateState() {
    _dbUpdateState.value = DbUpdateState.Idle
}
```

- [ ] **Step 3: Add imports**

```kotlin
import com.scanner.overlay.scanner.ArticleBarcodeDatabase
import com.scanner.overlay.scanner.ProductItem
import java.net.URL
```

---

### Task 3: SettingsScreen — BarcodeDbUpdateCard composable

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt`

- [ ] **Step 1: Add import**

```kotlin
import com.scanner.overlay.scanner.ProductItem
```

- [ ] **Step 2: Add `dbUpdateState` observation in `SettingsScreen` composable (after `updateState`)**

```kotlin
val dbUpdateState by viewModel.dbUpdateState.collectAsState()
```

- [ ] **Step 3: Add `BarcodeDbUpdateCard` call after `UpdateCard`**

```kotlin
BarcodeDbUpdateCard(
    state = dbUpdateState,
    onDownload = { viewModel.downloadBarcodeDb() },
    onApply = { viewModel.applyBarcodeDbUpdate() },
    onDismiss = { viewModel.resetBarcodeDbUpdateState() }
)
```

- [ ] **Step 4: Add `BarcodeDbUpdateCard` composable**

```kotlin
private fun BarcodeDbUpdateCard(
    state: DbUpdateState,
    onDownload: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
            CardHeader(
                title = "База ШК",
                subtitle = "Товарные штрихкоды"
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when (val s = state) {
                    is DbUpdateState.Idle -> {
                        FilledTonalButton(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Загрузить базу ШК товаров", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    is DbUpdateState.Downloading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Загрузка...")
                        }
                    }
                    is DbUpdateState.Ready -> {
                        var showDialog by remember { mutableStateOf(true) }
                        if (showDialog) {
                            AlertDialog(
                                onDismissRequest = { showDialog = false; onDismiss() },
                                title = { Text("Новые ШК (${s.newItems.size})") },
                                text = {
                                    Column(Modifier.verticalScroll(rememberScrollState())) {
                                        s.newItems.forEach { item ->
                                            Text(
                                                "${item.articleCode} — ${item.name}",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                            )
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showDialog = false; onApply() }) {
                                        Text("Добавить")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDialog = false; onDismiss() }) {
                                        Text("Отмена")
                                    }
                                }
                            )
                        }
                    }
                    is DbUpdateState.UpToDate -> {
                        LaunchedEffect(Unit) {
                            delay(2000)
                            onDismiss()
                        }
                        Text(
                            "База актуальна",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is DbUpdateState.Error -> {
                        LaunchedEffect(Unit) {
                            onDismiss()
                        }
                        Text(
                            "Ошибка: ${s.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
```

---

### Task 4: Build & verify

- [ ] **Step 1: Build debug APK**

Run: `.\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install**

Run: `build.ps1 install`
Expected: installed on device
