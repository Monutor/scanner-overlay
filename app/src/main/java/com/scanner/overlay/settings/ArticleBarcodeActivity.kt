package com.scanner.overlay.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.scanner.overlay.scanner.ArticleBarcodeDatabase
import com.scanner.overlay.scanner.ProductItem
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
    data object NotFound : BarcodeState
    data class Found(val item: ProductItem) : BarcodeState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleBarcodeScreen() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<BarcodeState>(BarcodeState.Empty) }

    val doSearch: () -> Unit = {
        val q = query.trim()
        if (q.isNotEmpty()) {
            val result = ArticleBarcodeDatabase.searchByArticleCode(q)
            state = if (result != null) BarcodeState.Found(result) else BarcodeState.NotFound
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    modifier = Modifier.weight(1f)
                )
                FilledIconButton(
                    onClick = doSearch
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Найти")
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

                is BarcodeState.NotFound -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Товар с артикулом «$query» не найден",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                is BarcodeState.Found -> {
                    val item = s.item
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
            }
        }
    }
}
