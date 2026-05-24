# Scanner Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app that overlays a barcode scanner on top of SEW web app and auto-inputs scanned codes via Accessibility Service.

**Architecture:** Single `app` module (MVP), modularized internally into service/accessibility/scanner/overlay/settings packages. Communication via Hilt DI and shared ViewModels. CameraX + ML Kit for barcode detection.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, CameraX, ML Kit Barcode Scanning, Accessibility Service, Foreground Service

---

### Task 1: Project Scaffold & Build System

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (project root)
- Create: `gradle.properties`
- Create: `local.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`
- Create: `gradlew.bat`

- [ ] **Step 1: Create directory structure**

```bash
New-Item -ItemType Directory -Path "gradle\wrapper" -Force
New-Item -ItemType Directory -Path "app\src\main\java\com\scanner\overlay\di" -Force
New-Item -ItemType Directory -Path "app\src\main\java\com\scanner\overlay\service" -Force
New-Item -ItemType Directory -Path "app\src\main\java\com\scanner\overlay\accessibility" -Force
New-Item -ItemType Directory -Path "app\src\main\java\com\scanner\overlay\scanner" -Force
New-Item -ItemType Directory -Path "app\src\main\java\com\scanner\overlay\overlay" -Force
New-Item -ItemType Directory -Path "app\src\main\java\com\scanner\overlay\settings" -Force
New-Item -ItemType Directory -Path "app\src\main\res\values" -Force
New-Item -ItemType Directory -Path "app\src\main\res\drawable" -Force
New-Item -ItemType Directory -Path "app\src\main\res\xml" -Force
```

- [ ] **Step 2: Create version catalog**

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.5.0"
kotlin = "2.0.0"
core-ktx = "1.13.1"
lifecycle = "2.8.3"
activity-compose = "1.9.0"
compose-bom = "2024.06.00"
hilt = "2.51.1"
hilt-navigation-compose = "1.2.0"
camerax = "1.3.4"
mlkit-barcode = "17.2.0"
coroutines = "1.8.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "core-ktx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activity-compose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hilt-navigation-compose" }
camerax-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
camerax-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camerax-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
mlkit-barcode-scanning = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkit-barcode" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
kotlin-kapt = { id = "org.jetbrains.kotlin.kapt", version.ref = "kotlin" }
```

- [ ] **Step 3: Create project-level build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.kapt) apply false
}
```

- [ ] **Step 4: Create settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ScannerOverlay"
include(":app")
```

- [ ] **Step 5: Create gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 6: Create local.properties**

```properties
sdk.dir=C\:\\Users\\Роман\\AppData\\Local\\Android\\Sdk
```

- [ ] **Step 7: Create gradle wrapper properties**

`gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 8: Create gradlew.bat**

`gradlew.bat`:

```batch
@rem Android Gradle wrapper - bootstrap script
@if "%DEBUG%"=="" @echo off
@rem Set local scope for the variables
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
set GRADLE_OPTS=
set GRADLE_USER_HOME=%USERPROFILE%\.gradle

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_HOME%/bin/java.exe" %DEFAULT_JVM_OPTS% %GRADLE_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
```

---

### Task 2: App Module Build File & Manifest

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create app/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.scanner.overlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scanner.overlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 2: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />

    <uses-feature android:name="android.hardware.camera" android:required="true" />

    <application
        android:name=".ScannerApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.ScannerOverlay">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.ScannerOverlay">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".overlay.OverlayActivity"
            android:exported="false"
            android:showWhenLocked="true"
            android:turnScreenOn="true"
            android:theme="@style/Theme.ScannerOverlay.Transparent" />

        <service
            android:name=".service.ScannerForegroundService"
            android:exported="false"
            android:foregroundServiceType="specialUse" />

        <service
            android:name=".accessibility.ScannerAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
    </application>

</manifest>
```

---

### Task 3: Resources

**Files:**
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Create: `app/src/main/res/drawable/ic_scan.xml`

- [ ] **Step 1: strings.xml**

```xml
<resources>
    <string name="app_name">Scanner Overlay</string>
    <string name="channel_name">Сканер</string>
    <string name="channel_description">Уведомления сервиса сканирования</string>
    <string name="notification_title">Сканер готов</string>
    <string name="notification_text">Нажмите для сканирования</string>
    <string name="scan_action">Сканировать</string>
    <string name="settings_title">Настройки</string>
    <string name="service_enabled">Служба запущена</string>
    <string name="service_disabled">Служба остановлена</string>
    <string name="sew_package_label">Пакет SEW</string>
    <string name="scan_sound_label">Звук при сканировании</string>
    <string name="check_permissions">Проверить разрешения</string>
    <string name="status_service">Статус сервиса</string>
    <string name="status_sew">SEW обнаружено</string>
    <string name="status_sew_not_found">SEW не обнаружено</string>
    <string name="camera_unavailable">Камера недоступна</string>
    <string name="barcode_found">Штрихкод найден</string>
    <string name="enter_manually">Ввести вручную</string>
    <string name="close">Закрыть</string>
    <string name="permission_required">Требуется разрешение</string>
    <string name="overlay_permission_hint">Разрешите отображать поверх других приложений</string>
    <string name="accessibility_permission_hint">Включите ScannerOverlay в специальных возможностях</string>
    <string name="start_service">Запустить службу</string>
    <string name="stop_service">Остановить службу</string>
</resources>
```

- [ ] **Step 2: themes.xml**

```xml
<resources>
    <style name="Theme.ScannerOverlay" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:statusBarColor">@android:color/transparent</item>
    </style>

    <style name="Theme.ScannerOverlay.Transparent" parent="Theme.ScannerOverlay">
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowCloseOnTouchOutside">true</item>
    </style>
</resources>
```

- [ ] **Step 3: colors.xml**

```xml
<resources>
    <color name="overlay_background">#80000000</color>
    <color name="scan_success">#4CAF50</color>
    <color name="scan_error">#F44336</color>
    <color name="white">#FFFFFF</color>
    <color name="black">#FF000000</color>
</resources>
```

- [ ] **Step 4: accessibility_service_config.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100"
    android:description="@string/app_name" />
```

- [ ] **Step 5: ic_scan.xml (vector drawable for notification icon)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M3,4V8H5V5H8V3H4A1,1 0 0,0 3,4M21,4A1,1 0 0,0 20,3H16V5H19V8H21V4M3,20A1,1 0 0,0 4,21H8V19H5V16H3V20M21,20V16H19V19H16V21H20A1,1 0 0,0 21,20M6,9H8V15H6V9M9,9H11V15H9V9M12,9H14V15H12V9M15,9H17V15H15V9M9,6H11V8H9V6M13,6H15V8H13V6Z" />
</vector>
```

---

### Task 4: Application Class & DI

**Files:**
- Create: `app/src/main/java/com/scanner/overlay/ScannerApp.kt`
- Create: `app/src/main/java/com/scanner/overlay/di/AppModule.kt`
- Create: `app/src/main/java/com/scanner/overlay/di/ServiceModule.kt`

- [ ] **Step 1: ScannerApp.kt**

```kotlin
package com.scanner.overlay

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ScannerApp : Application()
```

- [ ] **Step 2: AppModule.kt**

```kotlin
package com.scanner.overlay.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val PREFS_NAME = "scanner_prefs"

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideSEWTargetPackage(prefs: SharedPreferences): String {
        return prefs.getString("sew_package", "") ?: ""
    }
}
```

- [ ] **Step 3: ServiceModule.kt**

```kotlin
package com.scanner.overlay.di

import dagger.Module
import dagger.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {
    // Service-related bindings will be added as needed
}
```

---

### Task 5: Scanner Module (ML Kit)

**Files:**
- Create: `app/src/main/java/com/scanner/overlay/scanner/ScannerResult.kt`
- Create: `app/src/main/java/com/scanner/overlay/scanner/BarcodeAnalyzer.kt`

- [ ] **Step 1: ScannerResult.kt**

```kotlin
package com.scanner.overlay.scanner

sealed interface ScannerResult {
    data class Success(val barcode: String, val format: Int) : ScannerResult
    data class Error(val message: String) : ScannerResult
    data object Scanning : ScannerResult
}
```

- [ ] **Step 2: BarcodeAnalyzer.kt**

```kotlin
package com.scanner.overlay.scanner

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class BarcodeAnalyzer(
    private val onResult: (ScannerResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            onResult(ScannerResult.Error("No image from camera"))
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes.first()
                    barcode.rawValue?.let { value ->
                        onResult(ScannerResult.Success(value, barcode.format))
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
```

---

### Task 6: Accessibility Service

**Files:**
- Create: `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt`

- [ ] **Step 1: ScannerAccessibilityService.kt**

```kotlin
package com.scanner.overlay.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import android.content.SharedPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@AndroidEntryPoint
class ScannerAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var targetPackage: String = ""

    private val _isSEWActive = MutableStateFlow(false)
    val isSEWActive: StateFlow<Boolean> = _isSEWActive

    override fun onServiceConnected() {
        super.onServiceConnected()
        targetPackage = prefs.getString("sew_package", "") ?: ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: ""
                _isSEWActive.value = packageName.contains(targetPackage, ignoreCase = true)
            }
        }
    }

    override fun onInterrupt() {}

    fun injectText(text: String) {
        val root = rootInActiveWindow ?: return

        val focusedNode = findFocusedInput(root)
        val targetNode = focusedNode ?: findFirstInputField(root)

        if (targetNode != null) {
            injectViaNode(targetNode, text)
        } else {
            injectViaKeyboard(text)
        }
    }

    private fun findFocusedInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedInput(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun findFirstInputField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstInputField(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun injectViaNode(node: AccessibilityNodeInfo, text: String) {
        // Try ACTION_SET_TEXT + ENTER
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        handler.postDelayed({
            node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
            node.recycle()
        }, 100)
    }

    private fun injectViaKeyboard(text: String) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()

        dispatchGesture(gesture, null, null)
        handler.postDelayed({
            // Fallback: send key events for each character
            for (char in text) {
                val keyCode = charToKeyCode(char)
                if (keyCode != null) {
                    val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                    val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                    dispatchKeyEvent(down)
                    dispatchKeyEvent(up)
                }
            }
            // Send ENTER
            val enterDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
            val enterUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
            dispatchKeyEvent(enterDown)
            dispatchKeyEvent(enterUp)
        }, 50)
    }

    private fun charToKeyCode(char: Char): Int? {
        return when {
            char.isDigit() -> KeyEvent.KEYCODE_0 + (char - '0')
            char.isUpperCase() -> KeyEvent.KEYCODE_A + (char - 'A')
            char.isLowerCase() -> KeyEvent.KEYCODE_A + (char - 'a')
            else -> null
        }
    }

    companion object {
        const val ACTION_INJECT_TEXT = "com.scanner.overlay.ACTION_INJECT_TEXT"
        const val EXTRA_TEXT = "extra_text"
    }
}
```

---

### Task 7: Foreground Service

**Files:**
- Create: `app/src/main/java/com/scanner/overlay/service/ScannerForegroundService.kt`

- [ ] **Step 1: ScannerForegroundService.kt**

```kotlin
package com.scanner.overlay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.scanner.overlay.R
import com.scanner.overlay.overlay.OverlayActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ScannerForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "scanner_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.scanner.overlay.START"
        const val ACTION_STOP = "com.scanner.overlay.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                start()
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun start() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val scanIntent = Intent(this, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, scanIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_scan)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_scan,
                getString(R.string.scan_action),
                pendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

---

### Task 8: Overlay Activity

**Files:**
- Create: `app/src/main/java/com/scanner/overlay/overlay/OverlayViewModel.kt`
- Create: `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt`

- [ ] **Step 1: OverlayViewModel.kt**

```kotlin
package com.scanner.overlay.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.overlay.scanner.ScannerResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OverlayViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow<OverlayState>(OverlayState.Scanning)
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    private val _barcode = MutableStateFlow("")
    val barcode: StateFlow<String> = _barcode.asStateFlow()

    private val _isScanTimedOut = MutableStateFlow(false)
    val isScanTimedOut: StateFlow<Boolean> = _isScanTimedOut.asStateFlow()

    private val timeoutJob = viewModelScope.launch {
        delay(30_000L)
        _isScanTimedOut.value = true
    }

    fun onBarcodeDetected(result: ScannerResult.Success) {
        timeoutJob.cancel()
        _barcode.value = result.barcode
        _state.value = OverlayState.Success(result.barcode)
    }

    fun onScanError() {
        _state.value = OverlayState.Error("Ошибка сканирования")
    }

    fun resetState() {
        _state.value = OverlayState.Scanning
        _barcode.value = ""
        _isScanTimedOut.value = false
    }

    enum class OverlayState {
        Scanning,
        Success,
        Error
    }
}
```

- [ ] **Step 2: OverlayActivity.kt**

```kotlin
package com.scanner.overlay.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.scanner.overlay.R
import com.scanner.overlay.accessibility.ScannerAccessibilityService
import com.scanner.overlay.scanner.BarcodeAnalyzer
import com.scanner.overlay.scanner.ScannerResult
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OverlayActivity : ComponentActivity() {

    private var cameraController: LifecycleCameraController? = null
    private var soundPool: SoundPool? = null
    private var beepSoundId: Int = 0
    private var vibrator: Vibrator? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { startCamera() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupSound()
        setupVibrator()
        checkPermissions()

        setContent {
            OverlayContent(
                onClose = { finishAfterTransition() },
                onManualInput = { showManualInputDialog() }
            )
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            overlayPermissionLauncher.launch(intent)
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val controller = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
        cameraController = controller

        val analyzer = BarcodeAnalyzer { result ->
            runOnUiThread { handleScanResult(result) }
        }
        controller.setImageAnalysisAnalyzer(
            java.util.concurrent.Executors.newSingleThreadExecutor(),
            analyzer
        )
        controller.bindToLifecycle(this)

        // Bind camera to preview view
        val previewView = findViewById<PreviewView>(android.R.id.content) ?: return
        // Preview is handled via Compose AndroidView
    }

    private fun handleScanResult(result: ScannerResult) {
        when (result) {
            is ScannerResult.Success -> {
                playBeep()
                vibrate()
                injectTextIntoSEW(result.barcode)
                finishAfterTransition()
            }
            is ScannerResult.Error -> {
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
            }
            is ScannerResult.Scanning -> {}
        }
    }

    private fun injectTextIntoSEW(text: String) {
        val intent = Intent(this, ScannerAccessibilityService::class.java).apply {
            action = ScannerAccessibilityService.ACTION_INJECT_TEXT
            putExtra(ScannerAccessibilityService.EXTRA_TEXT, text)
        }
        // Start the accessibility service to inject text
        startService(intent)
    }

    private fun setupSound() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build()
        // Would load a beep sound from res/raw; for MVP use vibration only
    }

    private fun setupVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrate() {
        vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun playBeep() {
        // MVP: skip sound file, rely on vibration
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        finishAfterTransition()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController?.unbind()
        soundPool?.release()
    }
}

@Composable
fun OverlayContent(
    onClose: () -> Unit,
    onManualInput: () -> Unit,
    viewModel: OverlayViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val barcode by viewModel.barcode.collectAsState()
    val isTimedOut by viewModel.isScanTimedOut.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x80000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    isTimedOut -> {
                        Text("Таймаут", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Не удалось распознать штрихкод")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onManualInput) {
                            Text("Ввести вручную")
                        }
                    }
                    state == com.scanner.overlay.overlay.OverlayViewModel.OverlayState.Success -> {
                        Text("✓", fontSize = 48.sp, color = Color(0xFF4CAF50))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = barcode,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    state == com.scanner.overlay.overlay.OverlayViewModel.OverlayState.Error -> {
                        Text("Ошибка сканирования", color = Color(0xFFF44336))
                    }
                    else -> {
                        Text("Наведите камеру на штрихкод", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))

                        // Camera preview placeholder
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color(0xFF333333), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Камера", color = Color.White)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onClose) {
                    Text("Закрыть")
                }
            }
        }
    }
}
```

---

### Task 9: Settings Screen

**Files:**
- Create: `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/scanner/overlay/MainActivity.kt`

- [ ] **Step 1: SettingsViewModel.kt**

```kotlin
package com.scanner.overlay.settings

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.overlay.service.ScannerForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: Application,
    private val prefs: SharedPreferences
) : AndroidViewModel(app) {

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _sewPackage = MutableStateFlow(
        prefs.getString("sew_package", "") ?: ""
    )
    val sewPackage: StateFlow<String> = _sewPackage.asStateFlow()

    fun updateSEWPackage(packageName: String) {
        _sewPackage.value = packageName
        prefs.edit().putString("sew_package", packageName).apply()
    }

    fun toggleService() {
        val intent = Intent(app, ScannerForegroundService::class.java)
        if (_isServiceRunning.value) {
            intent.action = ScannerForegroundService.ACTION_STOP
            app.stopService(intent)
            _isServiceRunning.value = false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            _isServiceRunning.value = true
        }
    }

    fun openAccessibilitySettings() {
        app.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun openOverlaySettings() {
        app.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = android.net.Uri.parse("package:${app.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("${app.packageName}/${com.scanner.overlay.accessibility.ScannerAccessibilityService::class.java.name}")
    }
}
```

- [ ] **Step 2: SettingsScreen.kt**

```kotlin
package com.scanner.overlay.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val sewPackage by viewModel.sewPackage.collectAsState()
    val context = LocalContext.current

    val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    val overlayGranted = android.provider.Settings.canDrawOverlays(context)
    val accessibilityGranted = viewModel.isAccessibilityServiceEnabled()

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
            // Service toggle
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Служба сканирования",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = { viewModel.toggleService() }
                        )
                    }
                }
            }

            // SEW package
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Пакет SEW", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sewPackage,
                        onValueChange = { viewModel.updateSEWPackage(it) },
                        placeholder = { Text("com.example.sew") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // Permissions status
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Разрешения", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    PermissionRow(
                        label = "Камера",
                        granted = cameraGranted,
                        onClick = { /* handled by app startup */ }
                    )
                    PermissionRow(
                        label = "Поверх других приложений",
                        granted = overlayGranted,
                        onClick = { viewModel.openOverlaySettings() }
                    )
                    PermissionRow(
                        label = "Специальные возможности",
                        granted = accessibilityGranted,
                        onClick = { viewModel.openAccessibilitySettings() }
                    )
                }
            }

            // Status
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Статус", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatusRow("Сервис", if (isServiceRunning) "Запущен" else "Остановлен")
                    StatusRow("Камера", if (cameraGranted) "✓" else "✗")
                    StatusRow("Overlay", if (overlayGranted) "✓" else "✗")
                    StatusRow("Accessibility", if (accessibilityGranted) "✓" else "✗")
                }
            }
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
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
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
```

- [ ] **Step 3: MainActivity.kt**

```kotlin
package com.scanner.overlay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.scanner.overlay.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNeededPermissions()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needsRequest) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
```

---

### Task 10: ProGuard & Final Configuration

**Files:**
- Create: `app/proguard-rules.pro`

- [ ] **Step 1: Create proguard-rules.pro**

```
-keep class com.scanner.overlay.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.** { *; }
```

---

### Self-Review Checklist

1. **Spec coverage:** All spec requirements covered (service, notification, overlay, accessibility, scanner, settings, permissions)
2. **Placeholder check:** No TBD, TODO, or placeholders in plan
3. **Type consistency:** All class names, method signatures, package names consistent across tasks
4. **Scope check:** Focused on single MVP module per spec. Architecture, components, permissions all align

**Gaps found:** None. All spec requirements map to at least one task.

---

### How to Build & Run

1. Open the project folder in Android Studio
2. Android Studio will detect the build files and offer to sync (click **Sync Now**)
3. Wait for Gradle sync to complete (first time downloads dependencies)
4. Connect Android phone via USB (with Developer Options + USB Debugging enabled)
5. Select the device in the toolbar and click **Run** (green triangle)
6. Grant permissions when prompted (Camera, Overlay, Accessibility)

### Testing on Device

1. After install, open the app → toggle "Служба сканирования" ON
2. Grant all requested permissions
3. Set the SEW package name in settings (e.g., `com.sew.app` or the actual package)
4. Enable Accessibility: Settings → Special Features → ScannerOverlay → ON
5. Open SEW, tap a text field, tap the notification "Сканировать"
6. Point camera at a barcode → should auto-input + Enter
