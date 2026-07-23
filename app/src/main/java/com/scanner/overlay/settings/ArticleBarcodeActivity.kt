package com.scanner.overlay.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scanner.overlay.scanner.ArticleBarcodeDatabase
import com.scanner.overlay.scanner.ProductItem
import com.scanner.overlay.util.BarcodeGenerator
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ArticleBarcodeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArticleBarcodeDatabase.init(applicationContext)
        setContent {
            ArticleBarcodeScreen()
        }
    }
}

private sealed interface BarcodeState {
    data object Empty : BarcodeState
    data class Results(val found: List<ProductItem>, val notFound: List<String>) : BarcodeState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleBarcodeScreen() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<BarcodeState>(BarcodeState.Empty) }

    val doSearch: () -> Unit = {
        val codes = query.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (codes.isNotEmpty()) {
            val found = mutableListOf<ProductItem>()
            val notFound = mutableListOf<String>()
            for (code in codes) {
                val item = ArticleBarcodeDatabase.searchByArticleCode(code)
                if (item != null) found.add(item) else notFound.add(code)
            }
            state = BarcodeState.Results(found, notFound)
        }
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedText = result.data?.getStringExtra(QrScannerActivity.EXTRA_SCANNED_TEXT)
                ?: return@rememberLauncherForActivityResult
            val articleCode = extractArticleCode(scannedText) ?: scannedText
            query = articleCode
            doSearch()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Поиск по артикулу") },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Артикул") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    modifier = Modifier.weight(1f)
                )
                FilledIconButton(
                    onClick = doSearch,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Найти")
                }
                IconButton(
                    onClick = {
                        val intent = Intent(context, QrScannerActivity::class.java)
                        qrScannerLauncher.launch(intent)
                    }
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = "Сканировать QR")
                }
            }

            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is BarcodeState.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Введите артикул товара",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is BarcodeState.Results -> {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (s.found.isEmpty() && s.notFound.isEmpty()) {
                            Text(
                                "Нет результатов",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        s.found.forEach { item ->
                            ElevatedCard(
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Артикул: ${item.articleCode}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "ШК: ${item.barcode}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val barcodeBmp = remember(item.barcode) {
                                        BarcodeGenerator.ean13Bitmap(item.barcode, 300, 80)
                                    }
                                    if (barcodeBmp != null) {
                                        Image(
                                            bitmap = barcodeBmp.asImageBitmap(),
                                            contentDescription = "Штрихкод",
                                            modifier = Modifier.fillMaxWidth().height(72.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("barcode", item.barcode))
                                            Toast.makeText(context, "ШК скопирован: ${item.barcode}", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Копировать ШК")
                                    }
                                }
                            }
                        }

                        s.notFound.forEach { code ->
                            Text(
                                "Артикул «$code» не найден",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun extractArticleCode(url: String): String? {
    return Regex("""mvideo\.ru/products/(\d+)""").find(url)?.groupValues?.get(1)
}
