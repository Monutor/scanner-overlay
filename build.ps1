# Use system JAVA_HOME/ANDROID_HOME if set, otherwise use defaults
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "G:\AndoidStudio\jbr" }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "G:\AndroidStudioSDK" }

$ADB_PATH = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"

switch ($args[0]) {
  "install" {
    ./gradlew installDebug
  }
  "apk" {
    ./gradlew assembleDebug
  }
  "run" {
    & $ADB_PATH shell am start -n "com.scanner.overlay/com.scanner.overlay.MainActivity"
  }
  "uninstall" {
    & $ADB_PATH uninstall com.scanner.overlay
  }
  "release" {
    # Build release APK
    Write-Host "Building release APK..." -ForegroundColor Cyan
    ./gradlew assembleRelease
    if ($LASTEXITCODE -ne 0) {
      Write-Host "Build failed!" -ForegroundColor Red
      exit 1
    }

    # Read version info
    $content = Get-Content "app\build.gradle.kts" -Raw
    $matchName = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')
    $matchCode = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
    $versionName = $matchName.Groups[1].Value
    $versionCode = $matchCode.Groups[1].Value

    Write-Host ("Version: v" + $versionName + " (" + $versionCode + ")") -ForegroundColor Cyan

    # Find signed or unsigned APK
    $apkPath = "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apkPath)) {
      $apkPath = "app\build\outputs\apk\release\app-release-unsigned.apk"
    }
    if (-not (Test-Path $apkPath)) {
      Write-Host "APK not found in app\build\outputs\apk\release\" -ForegroundColor Red
      exit 1
    }

    # Create update.json
    $url = "https://github.com/Monutor/scanner-overlay/releases/latest/download/app-release.apk"
    $updateJson = "{"
    $updateJson = $updateJson + '"versionCode":' + $versionCode + ","
    $updateJson = $updateJson + '"versionName":"' + $versionName + '",'
    $updateJson = $updateJson + '"downloadUrl":"' + $url + '",'
    $updateJson = $updateJson + '"releaseNotes":""'
    $updateJson = $updateJson + "}"
    Set-Content -Path "update.json" -Value $updateJson -Encoding UTF8

    # Git tag and push
    $tag = "v" + $versionName
    git tag -a $tag -m ("Release " + $tag)
    if ($LASTEXITCODE -ne 0) {
      Write-Host "Tag creation failed (already exists?)" -ForegroundColor Yellow
    }
    git push origin $tag
    if ($LASTEXITCODE -ne 0) {
      Write-Host "Push failed - check git remote" -ForegroundColor Red
      Remove-Item "update.json"
      exit 1
    }

    # Check gh CLI (try PATH, then known install location)
    $ghPath = (Get-Command "gh" -ErrorAction SilentlyContinue).Source
    if (-not $ghPath) {
      $ghPath = "C:\Program Files\GitHub CLI\gh.exe"
      if (-not (Test-Path $ghPath)) {
        Write-Host "GitHub CLI (gh) not found. Install: winget install GitHub.cli" -ForegroundColor Red
        Write-Host ("Tag " + $tag + " pushed. Upload manually.") -ForegroundColor Yellow
        Remove-Item "update.json"
        exit 1
      }
    }

    # Create GitHub release and upload assets
    $asset1 = $apkPath + "#app-release.apk"
    $asset2 = "update.json#update.json"
    & $ghPath release create $tag --title $tag --notes ("Release " + $tag) $asset1 $asset2

    if ($LASTEXITCODE -eq 0) {
      Write-Host ("Release " + $tag + " published!") -ForegroundColor Green
    } else {
      Write-Host "gh release create failed. Upload manually." -ForegroundColor Yellow
    }

    Remove-Item "update.json"
  }
  "install-release" {
    Write-Host "Building release APK..." -ForegroundColor Cyan
    ./gradlew assembleRelease
    if ($LASTEXITCODE -ne 0) {
      Write-Host "Build failed!" -ForegroundColor Red
      exit 1
    }
    & $ADB_PATH install -r "app\build\outputs\apk\release\app-release.apk"
  }
  default {
    # Try to find the APK in either location
    $apkPath = "app\build\outputs\apk\debug\app-debug.apk"
    if (!(Test-Path $apkPath)) {
      $apkPath = "app\build\outputs\bundle\debug\app-debug.aab"
    }
    & $ADB_PATH install -r $apkPath
  }
}
