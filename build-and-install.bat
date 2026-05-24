@echo off
chcp 65001 >nul
setlocal

if "%JAVA_HOME%"=="" set JAVA_HOME=G:\AndoidStudio\jbr
if "%ANDROID_HOME%"=="" set ANDROID_HOME=G:\AndroidStudioSDK

echo Building project...
call gradlew installDebug

if %errorlevel% equ 0 (
    echo.
    echo Done! APK built and installed.
) else (
    echo.
    echo Build failed. Check errors above.
    pause
)
