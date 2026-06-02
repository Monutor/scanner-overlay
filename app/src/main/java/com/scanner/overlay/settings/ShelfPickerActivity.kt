package com.scanner.overlay.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.scanner.overlay.accessibility.ScannerAccessibilityService
import com.scanner.overlay.calibration.SewCalibration
import com.scanner.overlay.scanner.BarcodeDatabase
import com.scanner.overlay.scanner.WarehouseItem
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ShelfPickerActivity : ComponentActivity() {

    @Inject
    lateinit var calibration: SewCalibration

    @Inject
    lateinit var favoritesStore: FavoritesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BarcodeDatabase.init(applicationContext)
        setContent {
            MaterialTheme {
                ShelfPickerScreen(
                    favoritesStore = favoritesStore,
                    onPick = ::onShelfPicked,
                    onDismiss = ::finish
                )
            }
        }
    }

    private fun onShelfPicked(item: WarehouseItem) {
        val service = ScannerAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Включите специальные возможности", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!calibration.isCalibrated) {
            Toast.makeText(this, "Сначала откалибруйте SEW", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Toast.makeText(this, "Штрих полки «${item.name}»…", Toast.LENGTH_SHORT).show()
        service.runSewAutoInput(
            barcode = item.barcode,
            calibration = calibration,
            onResult = { ok, message ->
                Toast.makeText(
                    this@ShelfPickerActivity,
                    if (ok) "Готово" else "Ошибка: $message",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ShelfPickerScreen(
    favoritesStore: FavoritesStore,
    onPick: (WarehouseItem) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allShelves = remember { BarcodeDatabase.getAllShelves() }
    val sections = remember { BarcodeDatabase.getShelfSections() }
    var query by remember { mutableStateOf("") }
    var selectedSection by remember { mutableStateOf<String?>(null) }
    var favoritesVersion by remember { mutableIntStateOf(0) }

    val favorites = remember(favoritesVersion) { favoritesStore.getAll() }
    val q = query.trim().lowercase()
    val searchFilter: (WarehouseItem) -> Boolean = {
        q.isEmpty() || it.name.lowercase().contains(q)
    }
    val sectionFilter: (WarehouseItem) -> Boolean = {
        selectedSection == null || it.section == selectedSection
    }

    val favoritesVisible = favorites.filter(searchFilter)
    val rest = allShelves
        .filter(sectionFilter)
        .filter(searchFilter)
        .filter { it !in favoritesVisible }

    val onToggleFavorite: (WarehouseItem) -> Unit = { item ->
        favoritesStore.toggle(item.barcode)
        favoritesVersion++
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "Выберите полку",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск (например, ПИКАП)") },
                singleLine = true,
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            when {
                allShelves.isEmpty() -> {
                    Text(
                        "Список пуст — перезапустите приложение",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                favoritesVisible.isEmpty() && rest.isEmpty() -> {
                    Text(
                        "Ничего не найдено",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                else -> {
                    if (sections.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            item("chip-all") {
                                FilterChip(
                                    selected = selectedSection == null,
                                    onClick = { selectedSection = null },
                                    label = { Text("Все") }
                                )
                            }
                            items(sections, key = { it }) { section ->
                                FilterChip(
                                    selected = selectedSection == section,
                                    onClick = { selectedSection = section },
                                    label = { Text(section) }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                    ) {
                        if (favoritesVisible.isNotEmpty()) {
                            item("fav-header") {
                                Text(
                                    "Избранное",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            items(favoritesVisible, key = { "fav-${it.barcode}" }) { item ->
                                ShelfRow(
                                    item = item,
                                    isFavorite = true,
                                    onClick = onPick,
                                    onLongClick = onToggleFavorite
                                )
                                HorizontalDivider()
                            }
                            item("shelf-header") {
                                Text(
                                    "Полки",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                        items(rest, key = { "shelf-${it.barcode}" }) { item ->
                            ShelfRow(
                                item = item,
                                isFavorite = false,
                                onClick = onPick,
                                onLongClick = onToggleFavorite
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfRow(
    item: WarehouseItem,
    isFavorite: Boolean,
    onClick: (WarehouseItem) -> Unit,
    onLongClick: (WarehouseItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(item) },
                onLongClick = { onLongClick(item) }
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isFavorite) "\u2605" else "\u2606",
            style = MaterialTheme.typography.titleMedium,
            color = if (isFavorite) Color(0xFFFFA000) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            Text(
                item.barcode,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
