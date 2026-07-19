package com.scanner.overlay.settings

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scanner.overlay.R
import com.scanner.overlay.calibration.SewCalibration
import com.scanner.overlay.scanner.ProductItem
import com.scanner.overlay.scanner.ScanHistoryEntry
import com.scanner.overlay.calibration.SupportedBrowsers
import com.scanner.overlay.update.UpdateInfo

private val BlueFab = Color(0xFF1976D2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isFloatingButtonEnabled by viewModel.isFloatingButtonEnabled.collectAsState()
    val scanTimeoutMs by viewModel.scanTimeoutMs.collectAsState()
    val scanQuality by viewModel.scanQuality.collectAsState()
    val panelEdge by viewModel.panelEdge.collectAsState()
    val btnSize by viewModel.btnSize.collectAsState()
    val panelOpacity by viewModel.panelOpacity.collectAsState()
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
                viewModel.refreshScanHistory()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val updateState by viewModel.updateState.collectAsState()
    val dbManagerState by viewModel.dbManagerState.collectAsState()
    val changeLog by viewModel.changeLog.collectAsState()
    val currentVersion = viewModel.currentVersion
    val sewCalibration by viewModel.sewCalibration.collectAsState()
    val sewTestResult by viewModel.sewTestResult.collectAsState()
    val awaitingSewCalibration by viewModel.awaitingSewCalibration.collectAsState()
    val autoImportSew by viewModel.autoImportSew.collectAsState()
    val tapToFocusEnabled by viewModel.tapToFocusEnabled.collectAsState()
    val autoFocusEnabled by viewModel.autoFocusEnabled.collectAsState()
    val isTtsEnabled by viewModel.isTtsEnabled.collectAsState()
    val scanHistory by viewModel.scanHistory.collectAsState()

    val productImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            var fileName = "import.csv"
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { fileName = it }
                }
            }
            viewModel.importProductFile(context, uri, fileName)
        }
    }

    var showViewDatabaseDialog by remember { mutableStateOf(false) }
    val viewProducts by viewModel.viewProducts.collectAsState()

    val grantedCount = listOf(cameraGranted, overlayGranted, accessibilityGranted).count { it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
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

                ProductDbCard(
                    state = dbManagerState,
                    changeLog = changeLog,
                    onImport = { productImportLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
                    onCheck = { viewModel.checkForProductUpdates() },
                    onViewProducts = {
                        viewModel.refreshViewProducts()
                        showViewDatabaseDialog = true
                    },
                    onClearLog = { viewModel.clearChangeLog() },
                    onDismiss = { viewModel.resetDbManagerState() }
                )

                SectionEyebrow("Поверхность")

                FloatingButtonsCard(
                    isFloatingButtonEnabled = isFloatingButtonEnabled,
                    onToggleFloatingButton = { viewModel.toggleService() },
                    panelEdge = panelEdge,
                    onEdgeChange = { viewModel.setPanelEdge(it) },
                    btnSize = btnSize,
                    onBtnSizeChange = { viewModel.setBtnSize(it) },
                    panelOpacity = panelOpacity,
                    onOpacityChange = { viewModel.setPanelOpacity(it) }
                )

                TtsToggleCard(
                    enabled = isTtsEnabled,
                    onToggle = { viewModel.setTtsEnabled(it) }
                )

                ScanHistoryCard(
                    entries = scanHistory,
                    onClear = { viewModel.clearScanHistory() }
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

                TapToFocusCard(
                    enabled = tapToFocusEnabled,
                    onToggle = { viewModel.setTapToFocusEnabled(it) },
                    autoFocusEnabled = autoFocusEnabled,
                    onAutoFocusToggle = { viewModel.setAutoFocusEnabled(it) }
                )

                SectionEyebrow("Система")

                PermissionsCard(
                    cameraGranted = cameraGranted,
                    overlayGranted = overlayGranted,
                    accessibilityGranted = accessibilityGranted,
                    onOpenCameraSettings = { viewModel.openCameraSettings() },
                    onOpenOverlaySettings = { viewModel.openOverlaySettings() },
                    onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() }
                )

                SectionEyebrow("Интеграция с SEW")

                SewCalibrationCard(
                    calibration = sewCalibration,
                    testResult = sewTestResult,
                    awaiting = awaitingSewCalibration,
                    autoImportSew = autoImportSew,
                    onAutoImportSewToggle = { viewModel.setAutoImportSew(it) },
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
                
                if (showViewDatabaseDialog) {
                    val products = viewProducts
                    ProductViewDialog(
                        products = products,
                        onDismiss = { showViewDatabaseDialog = false }
                    )
                }
                
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
                        imageVector = Icons.Default.QrCode,
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
    onToggleFloatingButton: () -> Unit,
    panelEdge: String,
    onEdgeChange: (String) -> Unit,
    btnSize: Int,
    onBtnSizeChange: (Int) -> Unit,
    panelOpacity: Float,
    onOpacityChange: (Float) -> Unit
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
                subtitle = "Отображается поверх всех приложений"
            )
            FloatingToggleRow(
                color = BlueFab,
                icon = Icons.Default.CenterFocusStrong,
                title = "Панель кнопок",
                subtitle = if (isFloatingButtonEnabled) "Панель с 4 кнопками — сканер, полка, SKU, ШК" else "Скрыта",
                checked = isFloatingButtonEnabled,
                enabled = true,
                onCheckedChange = { onToggleFloatingButton() }
            )
            if (isFloatingButtonEnabled) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Скользящая панель со всеми кнопками. Перетащите в любое место. Нажмите на стрелку, чтобы свернуть.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                EdgeSelector(
                    panelEdge = panelEdge,
                    onEdgeChange = onEdgeChange
                )
                BtnSizeSlider(
                    btnSize = btnSize,
                    onBtnSizeChange = onBtnSizeChange
                )
                OpacitySlider(
                    opacity = panelOpacity,
                    onOpacityChange = onOpacityChange
                )
            }
        }
    }
}

@Composable
private fun BtnSizeSlider(
    btnSize: Int,
    onBtnSizeChange: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Размер кнопок",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("36", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = btnSize.toFloat(),
                onValueChange = { onBtnSizeChange(it.toInt()) },
                valueRange = 36f..70f,
                steps = 33,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text("70", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = "${btnSize}dp",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OpacitySlider(
    opacity: Float,
    onOpacityChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Прозрачность панели",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("30%", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = opacity,
                onValueChange = { onOpacityChange(it) },
                valueRange = 0.15f..1f,
                steps = 16,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text("100%", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = "${(opacity * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EdgeSelector(
    panelEdge: String,
    onEdgeChange: (String) -> Unit
) {
    val options = listOf("left" to "Слева", "right" to "Справа")
    val selectedIndex = options.indexOfFirst { it.first == panelEdge }.coerceAtLeast(0)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Расположение панели",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onEdgeChange(value) },
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
private fun TapToFocusCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    autoFocusEnabled: Boolean,
    onAutoFocusToggle: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CardHeader(
                title = "Камера",
                subtitle = "Поведение при наведении на штрихкод"
            )
            FloatingToggleRow(
                color = BlueFab,
                icon = Icons.Default.CenterFocusStrong,
                title = "Тап для фокуса",
                subtitle = "Коснитесь камеры, чтобы перефокусироваться",
                checked = enabled,
                enabled = true,
                onCheckedChange = onToggle
            )
            FloatingToggleRow(
                color = BlueFab,
                icon = Icons.Default.FlashOn,
                title = "Автофокус каждые 3 сек",
                subtitle = "Камера перефокусируется автоматически в режиме сканирования",
                checked = autoFocusEnabled,
                enabled = enabled,
                onCheckedChange = onAutoFocusToggle
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Полезно, когда штрихкод мелкий или бликует — касание принудительно фокусирует камеру на выбранной точке.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    cameraGranted: Boolean,
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    onOpenCameraSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val allGranted = cameraGranted && overlayGranted && accessibilityGranted
    var expanded by remember { mutableStateOf(!allGranted) }

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
            if (expanded) {
                PermissionRow(
                    icon = Icons.Default.CameraAlt,
                    title = "Камера",
                    subtitle = "Для распознавания штрихкодов",
                    granted = cameraGranted,
                    onClick = onOpenCameraSettings
                )
                DividerRow()
                PermissionRow(
                    icon = Icons.Default.Layers,
                    title = "Поверх приложений",
                    subtitle = "Плавающие кнопки и оверлей",
                    granted = overlayGranted,
                    onClick = onOpenOverlaySettings
                )
                DividerRow()
                PermissionRow(
                    icon = Icons.Default.Accessibility,
                    title = "Спец. возможности",
                    subtitle = "Авто-ввод штрихов в SEW",
                    granted = accessibilityGranted,
                    onClick = onOpenAccessibilitySettings
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Все разрешения выданы",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Показать",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
    autoImportSew: Boolean = false,
    onAutoImportSewToggle: (Boolean) -> Unit = {},
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
                        imageVector = Icons.Default.Tune,
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

            if (calibration.isCalibrated && !awaiting) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                FloatingToggleRow(
                    color = BlueFab,
                    icon = Icons.Default.PlayArrow,
                    title = "Быстрый ввод в SEW",
                    subtitle = "При скане сразу вводить штрихкод без экрана успеха",
                    checked = autoImportSew,
                    enabled = true,
                    onCheckedChange = onAutoImportSewToggle
                )
            }

            if (!awaiting) {
                var helpExpanded by remember { mutableStateOf(!calibration.isCalibrated) }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { helpExpanded = !helpExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Как это работает",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = if (helpExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (helpExpanded) "Свернуть" else "Развернуть",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (helpExpanded) {
                        HelpBlock(
                            text = buildString {
                                if (currentPackageLabel != null) {
                                    append("1. Заранее откройте ").append(currentPackageLabel)
                                        .append(", нажмите «Ручной ввод», уберите клавиатуру.\n")
                                } else {
                                    append("1. Заранее откройте приложение SEW, нажмите «Ручной ввод», уберите клавиатуру.\n")
                                }
                                append("2. Нажмите «Откалибровать» — появится оверлей.\n")
                                append("3. Тапните на «Ручной ввод» (оверлей запомнит позицию).\n")
                                append("4. Тапните на «Готово» (оверлей запомнит позицию).\n")
                                append("5. Проверьте кнопкой «Тест».")
                            }
                        )
                    }
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
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.tertiaryContainer,
                                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                                        )
                                    )
                                )
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
private fun TtsToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        FloatingToggleRow(
            color = Color(0xFF7B1FA2),
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = "Голосовое озвучивание",
            subtitle = if (enabled) "Название полки после сканирования" else "Отключено",
            checked = enabled,
            enabled = true,
            onCheckedChange = onToggle
        )
    }
}

@Composable
private fun ScanHistoryCard(
    entries: List<ScanHistoryEntry>,
    onClear: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "История сканирований",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("Очистить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text(
                    "Пока нет записей",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val dateFormat = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp)
                ) {
                    items(entries) { entry ->
                        HistoryRow(entry, dateFormat)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: ScanHistoryEntry,
    dateFormat: java.text.SimpleDateFormat
) {
    val barcode = entry.barcode
    val shelfName = com.scanner.overlay.scanner.BarcodeDatabase.getByBarcode(barcode)?.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = barcode,
                style = MaterialTheme.typography.bodyMedium
            )
            if (shelfName != null) {
                Text(
                    text = shelfName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = dateFormat.format(java.util.Date(entry.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}





@Composable
private fun ProductDbCard(
    state: DbManagerState,
    changeLog: List<ChangeLogEntry>,
    onImport: () -> Unit,
    onCheck: () -> Unit,
    onViewProducts: () -> Unit,
    onClearLog: () -> Unit,
    onDismiss: () -> Unit
) {
    var showProductsDialog by remember { mutableStateOf<DbManagerState.Applied?>(null) }

    LaunchedEffect(state) {
        if (state is DbManagerState.Applied) {
            showProductsDialog = state
        }
    }

    showProductsDialog?.let { applied ->
        AlertDialog(
            onDismissRequest = { showProductsDialog = null; onDismiss() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
            },
            title = {
                Text("+${applied.count} товаров (${applied.source})")
            },
            text = {
                if (applied.products.isNotEmpty()) {
                    Column {
                        Text(
                            text = "Добавлено:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            applied.products.forEach { product ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = product.name.ifBlank { product.articleCode },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = buildString {
                                                append("Арт: ${product.articleCode}")
                                                if (product.barcode.isNotBlank()) {
                                                    append("  |  ШК: ${product.barcode}")
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        if (applied.count > applied.products.size) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "и ещё ${applied.count - applied.products.size} товаров",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProductsDialog = null; onDismiss() }) {
                    Text("Закрыть")
                }
            }
        )
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
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
                        imageVector = Icons.Default.QrCode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "База ШК товаров",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Импорт · синхронизация",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when (val s = state) {
                    is DbManagerState.Idle -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onImport,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.UploadFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Импорт", style = MaterialTheme.typography.labelLarge)
                                }
                                OutlinedButton(
                                    onClick = onCheck,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Проверить", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            OutlinedButton(
                                onClick = onViewProducts,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Просмотр", style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "База периодически пополняется. Нажимайте «Проверить», чтобы загрузить новые товары.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (changeLog.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Последние изменения",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = onClearLog,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Очистить",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            changeLog.take(3).forEach { entry ->
                                val dateStr = java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault()).apply {
                                    timeZone = java.util.TimeZone.getTimeZone("Europe/Moscow")
                                }.format(java.util.Date(entry.timestamp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dateStr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "+${entry.count} товаров (${entry.source})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                    is DbManagerState.Importing -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Импорт...")
                        }
                    }
                    is DbManagerState.Checking -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Проверка обновлений...")
                        }
                    }
                    is DbManagerState.Applied -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showProductsDialog = s },
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
                            Text("+${s.count} товаров (${s.source})")
                        }
                    }
                    is DbManagerState.Error -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = s.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Закрыть", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductViewDialog(
    products: List<ProductItem>?,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.QrCode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(if (products != null) "База товаров (${products.size} шт.)" else "Загрузка...")
        },
        text = {
            when {
                products == null -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Загрузка базы с GitHub...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                products.isEmpty() -> {
                    Text(
                        text = "База пуста. Выполните импорт или синхронизацию.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                else -> {
                    val sortedProducts = remember(products) {
                        products.sortedBy { it.articleCode }
                    }
                    val filteredProducts = remember(sortedProducts, searchQuery) {
                        if (searchQuery.isBlank()) sortedProducts
                        else {
                            val q = searchQuery.lowercase()
                            sortedProducts.filter {
                                it.articleCode.contains(q, ignoreCase = true) ||
                                it.name.contains(q, ignoreCase = true) ||
                                it.barcode.contains(q, ignoreCase = true)
                            }
                        }
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            placeholder = { Text("Поиск по артикулу, названию или ШК") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            textStyle = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            text = "Найдено: ${filteredProducts.size} из ${products.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            items(filteredProducts.size) { index ->
                                val product = filteredProducts[index]
                                val bgColor = if (index % 2 == 0)
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                else
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp, horizontal = 8.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(bgColor)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = product.articleCode,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (product.barcode.isNotBlank()) {
                                            Text(
                                                text = "ШК: ${product.barcode}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (product.name.isNotBlank()) {
                                        Text(
                                            text = product.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

@Composable
private fun AboutFooter(version: String) {
    val uriHandler = LocalUriHandler.current
    val donateUrl = "https://boosty.to/monutorfullhd/donate"
    val linkColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${stringResource(R.string.app_name)} v$version",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append("Поддержать проект на Boosty")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable { uriHandler.openUri(donateUrl) }
        )
    }
}
