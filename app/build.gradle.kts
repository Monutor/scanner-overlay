import java.util.Properties

/**
 * GitHub PAT from local.properties. Never committed (see .gitignore).
 *
 * SECURITY: the token is baked into BuildConfig only for the `debug` build type.
 * A string constant inside BuildConfig is trivially extracted from an APK with jadx,
 * and this app ships with write-capable publish helpers, so release builds must not
 * carry it. Release publishes via the `gh` CLI, which keeps its own credentials,
 * so nothing in the release flow depends on the baked token.
 */
val githubToken: String = run {
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.exists()) {
        ""
    } else {
        val p = Properties()
        propsFile.reader().use { p.load(it) }
        p.getProperty("github.token", "")
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.scanner.overlay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scanner.overlay"
        minSdk = 26
        targetSdk = 36
        versionCode = 32
        versionName = "1.21.0"
        // Release ships anonymous (read-only) GitHub access; see `debug` below.
        buildConfigField("String", "GITHUB_TOKEN", "\"\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            val localProps = rootProject.file("local.properties")
            if (localProps.exists()) {
                val props = Properties()
                props.load(localProps.inputStream())
                storePassword = props.getProperty("release.storePassword", "")
                keyPassword = props.getProperty("release.keyPassword", "")
            }
            keyAlias = "scanner"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            // Debug-only: enables the write-capable publish helpers for local DB pushes.
            buildConfigField("String", "GITHUB_TOKEN", "\"$githubToken\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
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
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
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
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
}
