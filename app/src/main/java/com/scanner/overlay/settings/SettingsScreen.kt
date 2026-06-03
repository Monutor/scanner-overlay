package com.scanner.overlay.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.scanner.overlay.calibration.SupportedBrowsers
import com.scanner.overlay.update.UpdateInfo

private val BlueFab = Color(0xFF1976D2)
private val OrangeFab = Color(0xFFFB8C00)

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
    val shelfPickerEnabled by viewModel.shelfPickerEnabled.collectAsState()

    val grantedCount = listOf(cameraGranted, overlayGranted, accessibilityGranted).count { it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Scanner Overlay",
                        fontWeight = FontWeight.Medium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusHero(
                    isActive = isFloatingButtonEnabled,
                    version = currentVersion,
                    grantedCount = grantedCount,
                    cameraGranted = cameraGranted,
                    overlayGranted = overlayGranted,
                    accessibilityGranted = accessibilityGranted,
                    updateAvailable = updateState is UpdateUiState.Available
                )

                SectionEyebrow("Поверхность")

                FloatingButtonsCard(
                    isFloatingButtonEnabled = isFloatingButtonEnabled,
                    isShelfPickerEnabled = shelfPickerEnabled,
                    isShelfPickerAvailable = isFloatingButtonEnabled && sewCalibration.isCalibrated,
                    onToggleFloatingButton = { viewModel.toggleService() },
                    onToggleShelfPicker = { viewModel.setShelfPickerEnabled(it) }
                )

                SectionEyebrow("Сканирование")

                ScanTimeoutCard(
                    timeoutMs = scanTimeoutMs,
                    onTimeoutChange = { viewModel.updateScanTimeout(it) }
                )

                ScanQualityCard(
                    quality = scanQuality,
                    onQualityChange = { viewModel.updateScanQuality(it) }
                )

                SectionEyebrow("Система")

                PermissionsCard(
                    cameraGranted = cameraGranted,
                    overlayGranted = overlayGranted,
                    accessibilityGranted = accessibilityGranted,
                    onOpenOverlaySettings = { viewModel.openOverlaySettings() },
                    onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() }
                )

                SectionEyebrow("Интеграция с SEW")

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
                    onTest = { viewModel.runSewCalibrationTest() }
                )

                UpdateCard(
                    state = updateState,
                    currentVersion = currentVersion,
                    onCheck = { viewModel.checkForUpdate() },
                    onDownload = { info -> viewModel.downloadUpdate(info) },
                    onDismiss = { viewModel.resetUpdateState() }
                )

                Spacer(Modifier.height(8.dp))
                AboutFooter(version = currentVersion)
            }
        }
    }
}

@Composable
private fun StatusHero(
    isActive: Boolean,
    version: String,
    grantedCount: Int,
    cameraGranted: Boolean,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    updateAvailable: Boolean
) {
    ElevatedCard(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isActive) "Сканер активен" else "Сканер выключен",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "v$version · $grantedCount из 3 разрешений",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    label = "Камера",
                    granted = cameraGranted
                )
                StatusPill(
                    label = "Overlay",
                    granted = overlayGranted
                )
                StatusPill(
                    label = "A11y",
                    granted = accessibilityGranted
                )
                if (updateAvailable) {
                    StatusPill(
                        label = "Апдейт",
                        granted = false
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    granted: Boolean
) {
    val containerColor = if (granted) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (granted) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = CircleShape,
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(contentColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SectionEyebrow(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun FloatingButtonsCard(
    isFloatingButtonEnabled: Boolean,
    isShelfPickerEnabled: Boolean,
    isShelfPickerAvailable: Boolean,
    onToggleFloatingButton: () -> Unit,
    onToggleShelfPicker: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            CardHeader(
                title = "Плавающие кнопки",
                subtitle = "Отображаются поверх всех приложений"
            )
            FloatingToggleRow(
                color = BlueFab,
                icon = Icons.Default.Refresh,
                title = "Сканер",
                subtitle = if (isFloatingButtonEnabled) "Синяя кнопка — открывает камеру" else "Скрыта",
                checked = isFloatingButtonEnabled,
                enabled = true,
                onCheckedChange = { onToggleFloatingButton() }
            )
            DividerRow()
            FloatingToggleRow(
                color = OrangeFab,
                icon = Icons.Default.Refresh,
                title = "Выбор полки",
                subtitle = when {
                    !isFloatingButtonEnabled -> "Сначала включите «Сканер»"
                    !isShelfPickerAvailable -> "Сначала откалибруйте SEW"
                    isShelfPickerEnabled -> "Оранжевая кнопка — список полок с поиском"
                    else -> "Оранжевая кнопка скрыта"
                },
                checked = isShelfPickerEnabled,
                enabled = isShelfPickerAvailable,
                onCheckedChange = onToggleShelfPicker
            )
            if (isShelfPickerAvailable || !isFloatingButtonEnabled) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Открывает список полок с поиском. Выбор автоматически вводит штрих в SEW — удобно, когда в задании указана конкретная ячейка. Если ячейка не указана («любая полка в зоне»), отсканируйте штрих вручную как обычно.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingToggleRow(
    color: Color,
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanTimeoutCard(
    timeoutMs: Long,
    onTimeoutChange: (Long) -> Unit
) {
    val options = listOf(
        15_000L to "15",
        30_000L to "30",
        45_000L to "45",
        60_000L to "60",
        90_000L to "90",
        120_000L to "120"
    )
    val selectedIndex = options.indexOfFirst { it.first == timeoutMs }.coerceAtLeast(0)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            CardHeader(
                title = "Таймаут сканирования",
                subtitle = "Ожидание перед ручным вводом, секунды"
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (ms, label) ->
                        SegmentedButton(
                            selected = index == selectedIndex,
                            onClick = { onTimeoutChange(ms) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = options.size
                            ),
                            icon = {}
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanQualityCard(
    quality: Int,
    onQualityChange: (Int) -> Unit
) {
    val options = listOf(
        0 to "Быстро",
        1 to "Стандарт",
        2 to "Максимум"
    )
    val selectedIndex = options.indexOfFirst { it.first == quality }.coerceAtLeast(0)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            CardHeader(
                title = "Качество камеры",
                subtitle = "Разрешение распознавания"
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (q, label) ->
                        SegmentedButton(
                            selected = index == selectedIndex,
                            onClick = { onQualityChange(q) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = options.size
                            ),
                            icon = {}
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
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
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            CardHeader(
                title = "Разрешения",
                subtitle = "Доступ к системе"
            )
            PermissionRow(
                icon = Icons.Default.Refresh,
                title = "Камера",
                subtitle = "Для распознавания штрихкодов",
                granted = cameraGranted,
                onClick = {}
            )
            DividerRow()
            PermissionRow(
                icon = Icons.Default.Refresh,
                title = "Поверх приложений",
                subtitle = "Плавающие кнопки и оверлей",
                granted = overlayGranted,
                onClick = onOpenOverlaySettings
            )
            DividerRow()
            PermissionRow(
                icon = Icons.Default.Refresh,
                title = "Спец. возможности",
                subtitle = "Авто-ввод штрихов в SEW",
                granted = accessibilityGranted,
                onClick = onOpenAccessibilitySettings
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Дано",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            FilledTonalButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Открыть",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
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

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Калибровка SEW",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when {
                            awaiting -> "Идёт захват тапов…"
                            currentPackageLabel != null -> "$currentPackageLabel · 2 точки"
                            else -> "Не выбрано приложение"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (calibration.isCalibrated) {
                    StatusPill(label = "Готово", granted = true)
                }
            }

            if (!awaiting) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    HelpBlock(
                        text = buildString {
                            if (currentPackageLabel != null) {
                                append("1. Откройте ").append(currentPackageLabel)
                                    .append(", дождитесь кнопки «Ручной ввод».\n")
                            } else {
                                append("1. Выберите приложение SEW и откройте страницу сканирования.\n")
                            }
                            append("2. Нажмите «Откалибровать», тапните на «Ручной ввод».\n")
                            append("3. Откройте модалку вручную, тапните на «Готово».\n")
                            append("4. Проверьте кнопкой «Тест».")
                        }
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilledTonalButton(
                    onClick = { appPickerOpen = true },
                    enabled = !awaiting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (currentPackageLabel != null) "Сменить приложение" else "Выбрать приложение",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (!awaiting) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCalibrate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (calibration.isCalibrated) "Перекалибровать" else "Откалибровать",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = onTest,
                        enabled = calibration.isCalibrated && !testResult.inProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (testResult.inProgress) "Тест..." else "Тест",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancelCalibrate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Отменить калибровку", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            if (calibration.isCalibrated && !awaiting) {
                TextButton(
                    onClick = onReset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "Сбросить калибровку",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (testResult.steps.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                                tint = if (step.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = step.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        step.message?.takeIf { !step.ok && it.isNotBlank() && it != "Ожидание..." }?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (appPickerOpen) {
        BrowserPickerSheet(
            currentPackage = calibration.targetPackage,
            onPick = { pkg ->
                onPickApp(pkg)
                appPickerOpen = false
            },
            onDismiss = { appPickerOpen = false }
        )
    }
}

@Composable
private fun HelpBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserPickerSheet(
    currentPackage: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val browsers = remember { SupportedBrowsers.getInstalled(context) }
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
                "Выберите браузер для SEW",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Поддерживаются только Яндекс Браузер и Google Chrome",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (browsers.isEmpty()) {
                Text(
                    "Не найдено ни Яндекс Браузера, ни Google Chrome. Установите один из них.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                Column {
                    browsers.forEach { browser ->
                        val selected = browser.packageName == currentPackage
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    else Color.Transparent
                                )
                                .clickable { onPick(browser.packageName) }
                                .padding(vertical = 14.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                browser.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            Text(
                                browser.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CardHeader(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DividerRow() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
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

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            CardHeader(
                title = "Обновления",
                subtitle = "Текущая: v$currentVersion"
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when (val s = state) {
                    is UpdateUiState.Idle -> {
                        FilledTonalButton(
                            onClick = onCheck,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Проверить обновления", style = MaterialTheme.typography.labelLarge)
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
                            Text("Проверка…")
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
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("У вас актуальная версия")
                        }
                    }
                    is UpdateUiState.Available -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(14.dp)
                        ) {
                            Column {
                                Text(
                                    text = "Доступна v${s.info.versionName}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = s.info.releaseNotes.ifBlank { "Исправления и улучшения" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                                    maxLines = 2
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showDialog = s.info },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Что нового", style = MaterialTheme.typography.labelLarge)
                            }
                            Button(
                                onClick = { onDownload(s.info) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Установить", style = MaterialTheme.typography.labelLarge)
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
                            Text("Скачивание…")
                        }
                    }
                    is UpdateUiState.Error -> {
                        Text(
                            text = s.message,
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
                            Text("Повторить", style = MaterialTheme.typography.labelLarge)
                        }
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

@Composable
private fun AboutFooter(version: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Scanner Overlay v$version",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "github.com/Monutor/scanner-overlay",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
