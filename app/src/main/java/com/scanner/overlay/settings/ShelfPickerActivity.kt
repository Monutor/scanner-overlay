package com.scanner.overlay.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.scanner.overlay.scanner.ScanHistoryEntry
import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.scanner.overlay.accessibility.ScannerAccessibilityService
import com.scanner.overlay.calibration.SewCalibration
import com.scanner.overlay.scanner.BarcodeDatabase
import com.scanner.overlay.scanner.WarehouseItem
import com.scanner.overlay.util.toastAtBottom
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ShelfPickerActivity : ComponentActivity() {

    @Inject
    lateinit var favoritesStore: FavoritesStore

    private lateinit var prefs: android.content.SharedPreferences
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("scanner_prefs", MODE_PRIVATE)
        BarcodeDatabase.init(applicationContext)
        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                val langResult = textToSpeech?.setLanguage(java.util.Locale.forLanguageTag("ru-RU")) ?: -1
                android.util.Log.d("ShelfPickerActivity", "TTS ready, setLanguage=$langResult")
            } else {
                android.util.Log.w("ShelfPickerActivity", "TTS init failed: status=$status")
            }
        }
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
        android.util.Log.d("ShelfPickerActivity", "onShelfPicked: name=${item.name} barcode=${item.barcode}")
        val service = ScannerAccessibilityService.instance
        if (service == null) {
            android.util.Log.w("ShelfPickerActivity", "AccessibilityService.instance is null")
            toastAtBottom("Включите специальные возможности", Toast.LENGTH_LONG)
            return
        }
        val cal = buildSewCalibration()
        if (!cal.isCalibrated) {
            android.util.Log.w("ShelfPickerActivity", "Calibration not set")
            toastAtBottom("Сначала откалибруйте SEW", Toast.LENGTH_LONG)
            return
        }
        val targetPkg = cal.targetPackage
        if (targetPkg.isEmpty()) {
            android.util.Log.w("ShelfPickerActivity", "targetPackage is empty")
            toastAtBottom("Сначала откалибруйте SEW", Toast.LENGTH_LONG)
            return
        }
        android.util.Log.d("ShelfPickerActivity", "Starting runSewAutoInput barcode=${item.barcode} pkg=$targetPkg")
        toastAtBottom("Штрих полки «${item.name}»…", Toast.LENGTH_SHORT)
        ScanHistoryEntry.add(prefs, item.barcode)
        val spoke = speakShelfName(item.name)
        service.runSewAutoInput(
            barcode = item.barcode,
            calibration = cal,
            onResult = { ok, message ->
                android.util.Log.d("ShelfPickerActivity", "runSewAutoInput result: ok=$ok message=$message")
                toastAtBottom(
                    if (ok) "Готово" else "Ошибка: $message",
                    Toast.LENGTH_LONG
                )
            }
        )
        if (spoke) {
            mainHandler.postDelayed({ if (!isFinishing) finish() }, 5000L)
        } else {
            if (!isFinishing) finish()
        }
    }

    private fun speakShelfName(name: String): Boolean {
        if (!prefs.getBoolean("tts_enabled", false)) {
            android.util.Log.d("ShelfPickerActivity", "TTS skipped: tts_enabled=false")
            return false
        }
        if (!ttsReady) {
            android.util.Log.w("ShelfPickerActivity", "TTS skipped: not ready")
            return false
        }
        android.util.Log.d("ShelfPickerActivity", "TTS speak: $name")
        try {
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onDone(utteranceId: String?) {
                    mainHandler.post { if (!isFinishing) finish() }
                }
                override fun onError(utteranceId: String?) {
                    mainHandler.post { if (!isFinishing) finish() }
                }
                override fun onStart(utteranceId: String?) {}
            })
            textToSpeech?.speak(name, TextToSpeech.QUEUE_FLUSH, null, "shelf_pick")
            return true
        } catch (e: Exception) {
            android.util.Log.e("ShelfPickerActivity", "TTS speak error", e)
            return false
        }
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    private fun buildSewCalibration(): SewCalibration {
        return SewCalibration(
            targetPackage = prefs.getString("sew_target_package", "") ?: "",
            openModal = android.graphics.Point(
                prefs.getInt("sew_open_modal_x", 0),
                prefs.getInt("sew_open_modal_y", 0)
            ),
            confirm = android.graphics.Point(
                prefs.getInt("sew_confirm_x", 0),
                prefs.getInt("sew_confirm_y", 0)
            )
        )
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
    val qNorm = q.replace("-", "")
    val searchFilter: (WarehouseItem) -> Boolean = {
        q.isEmpty() || it.name.lowercase().replace("-", "").contains(qNorm)
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
                placeholder = { Text("Поиск") },
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
                            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp)
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
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(item) },
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongClick(item)
                }
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
