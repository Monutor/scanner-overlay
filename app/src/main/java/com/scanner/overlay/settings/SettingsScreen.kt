package com.scanner.overlay.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scanner.overlay.calibration.SewCalibration
import com.scanner.overlay.update.UpdateInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isFloatingButtonEnabled by viewModel.isFloatingButtonEnabled.collectAsState()
    val scanTimeoutMs by viewModel.scanTimeoutMs.collectAsState()
    val scanQuality by viewModel.scanQuality.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(viewModel.isAccessibilityServiceEnabled())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshServiceState()
                cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessibilityGranted = viewModel.isAccessibilityServiceEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val updateState by viewModel.updateState.collectAsState()
    val currentVersion = viewModel.currentVersion
    val sewCalibration by viewModel.sewCalibration.collectAsState()
    val sewTestResult by viewModel.sewTestResult.collectAsState()
    val awaitingSewCalibration by viewModel.awaitingSewCalibration.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanner Overlay") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ServiceCard(
                isEnabled = isFloatingButtonEnabled,
                onToggle = { viewModel.toggleService() }
            )

            TimeoutCard(
                timeoutMs = scanTimeoutMs,
                onTimeoutChange = { viewModel.updateScanTimeout(it) }
            )

            QualityCard(
                quality = scanQuality,
                onQualityChange = { viewModel.updateScanQuality(it) }
            )

            PermissionsCard(
                cameraGranted = cameraGranted,
                overlayGranted = overlayGranted,
                accessibilityGranted = accessibilityGranted,
                onOpenOverlaySettings = { viewModel.openOverlaySettings() },
                onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() }
            )

            SewCalibrationCard(
                calibration = sewCalibration,
                testResult = sewTestResult,
                awaiting = awaitingSewCalibration,
                installedApps = installedApps,
                currentPackageLabel = installedApps.firstOrNull { it.packageName == sewCalibration.targetPackage }?.label,
                onPickApp = { viewModel.setSewTargetPackage(it) },
                onCalibrate = { viewModel.startSewCalibration() },
                onCancelCalibrate = { viewModel.cancelSewCalibration() },
                onReset = { viewModel.resetSewCalibration() },
                onTest = {
                    viewModel.runSewCalibrationTest()
                }
            )

            UpdateCard(
                state = updateState,
                currentVersion = currentVersion,
                onCheck = { viewModel.checkForUpdate() },
                onDownload = { info -> viewModel.downloadUpdate(info) },
                onDismiss = { viewModel.resetUpdateState() }
            )

            StatusCard(
                floatingButtonEnabled = isFloatingButtonEnabled,
                cameraGranted = cameraGranted,
                overlayGranted = overlayGranted,
                accessibilityGranted = accessibilityGranted
            )
        }
    }
}

@Composable
private fun ServiceCard(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Плавающая кнопка",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (isEnabled) "Отображается поверх всех приложений" else "Скрыта",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeoutCard(
    timeoutMs: Long,
    onTimeoutChange: (Long) -> Unit
) {
    val options = listOf(
        15_000L to "15 сек",
        30_000L to "30 сек",
        45_000L to "45 сек",
        60_000L to "60 сек",
        90_000L to "90 сек",
        120_000L to "120 сек"
    )
    var expanded by remember { mutableStateOf(false) }
    val label = options.find { it.first == timeoutMs }?.second
        ?: "${timeoutMs / 1000} сек"

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Таймаут сканирования",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Время ожидания перед появлением ручного ввода",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    singleLine = true
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { (ms, text) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            onClick = {
                                onTimeoutChange(ms)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityCard(
    quality: Int,
    onQualityChange: (Int) -> Unit
) {
    val options = listOf(
        0 to "Быстро",
        1 to "Стандарт",
        2 to "Максимум"
    )
    var expanded by remember { mutableStateOf(false) }
    val label = options.find { it.first == quality }?.second
        ?: "Стандарт"

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Качество сканирования",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Разрешение камеры",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    singleLine = true
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { (q, text) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            onClick = {
                                onQualityChange(q)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    cameraGranted: Boolean,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Разрешения",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow(
                label = "Камера",
                granted = cameraGranted,
                onClick = {}
            )
            PermissionRow(
                label = "Поверх других приложений",
                granted = overlayGranted,
                onClick = onOpenOverlaySettings
            )
            PermissionRow(
                label = "Специальные возможности",
                granted = accessibilityGranted,
                onClick = onOpenAccessibilitySettings
            )
        }
    }
}

@Composable
private fun StatusCard(
    floatingButtonEnabled: Boolean,
    cameraGranted: Boolean,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Статус",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            StatusRow("Плавающая кнопка", if (floatingButtonEnabled) "Показана" else "Скрыта")
            StatusRow("Камера", if (cameraGranted) "✓" else "✗")
            StatusRow("Overlay", if (overlayGranted) "✓" else "✗")
            StatusRow("Accessibility", if (accessibilityGranted) "✓" else "✗")
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        IconButton(onClick = onClick) {
            Icon(
                imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(value)
    }
}

@Composable
private fun UpdateCard(
    state: UpdateUiState,
    currentVersion: String,
    onCheck: () -> Unit,
    onDownload: (UpdateInfo) -> Unit,
    onDismiss: () -> Unit
) {
    var showDialog by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(state) {
        if (state is UpdateUiState.UpToDate) {
            kotlinx.coroutines.delay(3000)
            onDismiss()
        }
    }

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Обновления",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Текущая версия: v$currentVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                is UpdateUiState.Idle -> {
                    Button(
                        onClick = onCheck,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Проверить обновления")
                    }
                }

                is UpdateUiState.Checking -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Проверка...")
                    }
                }

                is UpdateUiState.UpToDate -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("У вас актуальная версия")
                    }
                }

                is UpdateUiState.Available -> {
                    Text(
                        "Доступна версия v${s.info.versionName}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDialog = s.info },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Что нового")
                        }
                        Button(
                            onClick = { onDownload(s.info) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Установить")
                        }
                    }
                }

                is UpdateUiState.Downloading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Скачивание...")
                    }
                }

                is UpdateUiState.Error -> {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCheck,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Повторить")
                    }
                }
            }
        }
    }

    showDialog?.let { info ->
        AlertDialog(
            onDismissRequest = { showDialog = null },
            title = { Text("v${info.versionName}") },
            text = {
                if (info.releaseNotes.isNotBlank()) {
                    Text(info.releaseNotes)
                } else {
                    Text("Нет описания изменений")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = null
                    onDownload(info)
                }) {
                    Text("Установить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = null }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SewCalibrationCard(
    calibration: SewCalibration,
    testResult: SewTestResult,
    awaiting: Boolean,
    installedApps: List<AppInfo>,
    currentPackageLabel: String?,
    onPickApp: (String) -> Unit,
    onCalibrate: () -> Unit,
    onCancelCalibrate: () -> Unit,
    onReset: () -> Unit,
    onTest: () -> Unit
) {
    var appPickerOpen by remember { mutableStateOf(false) }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Калибровка SEW",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    awaiting -> "Оверлей активен. Сначала на «Ручной ввод» (откроется модалка), затем на «Готово»."
                    currentPackageLabel != null -> "Приложение: $currentPackageLabel"
                    else -> "Не выбрано приложение SEW."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!awaiting) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Как настроить",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        if (currentPackageLabel != null) {
                            append("1. Откройте ").append(currentPackageLabel).append(" и перейдите на страницу сканирования.\n")
                        } else {
                            append("1. Выберите приложение SEW: Chrome, Яндекс.Браузер (обычная версия) или PWA-приложение SEW. Откройте его.\n")
                        }
                        append("2. Нажмите «Откалибровать» ниже.\n")
                        append("3. Нажмите на «Ручной ввод» в SEW — откроется модалка.\n")
                        append("4. Нажмите на «Готово» в модалке.")
                        if (calibration.isCalibrated) {
                            append("\n\nКнопка «Тест»: 5 секунд, чтобы открыть SEW, затем проверка работы.")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { appPickerOpen = true },
                enabled = !awaiting,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (currentPackageLabel != null) "Сменить приложение" else "Выбрать приложение",
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (awaiting) {
                    OutlinedButton(
                        onClick = onCancelCalibrate,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Отмена")
                    }
                } else {
                    Button(
                        onClick = onCalibrate,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (calibration.isCalibrated) "Перекалибровать" else "Откалибровать",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    OutlinedButton(
                        onClick = onTest,
                        enabled = calibration.isCalibrated && !testResult.inProgress,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (testResult.inProgress) "Тест..." else "Тест",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            if (calibration.isCalibrated) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сбросить калибровку")
                }
            }
            if (testResult.steps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                testResult.steps.forEach { step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (step.ok) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (step.ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            step.name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    step.message?.takeIf { !step.ok && it.isNotBlank() && it != "Ожидание..." }?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 24.dp)
                        )
                    }
                }
            }
        }
    }

    if (appPickerOpen) {
        AppPickerSheet(
            apps = installedApps,
            currentPackage = calibration.targetPackage,
            onPick = { pkg ->
                onPickApp(pkg)
                appPickerOpen = false
            },
            onDismiss = { appPickerOpen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    apps: List<AppInfo>,
    currentPackage: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
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
                "Выберите приложение SEW",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск") },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val selected = app.packageName == currentPackage
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(app.packageName) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1
                            )
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
