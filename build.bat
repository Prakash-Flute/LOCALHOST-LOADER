@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle was not found. Open this folder in Android Studio or install Gradle 8.9+.
  exit /b 2
)
if not exist app\src\main\jniLibs\arm64-v8a\libnode.so (
  where bash >nul 2>nul
  if errorlevel 1 (
    echo Node support requires scripts/fetch-node-mobile.sh. Run it in WSL or Git Bash first.
    exit /b 3
  )
  bash scripts/fetch-node-mobile.sh
  if errorlevel 1 exit /b 3
)
gradle --no-daemon :app:assembleDebug
if errorlevel 1 exit /b 1
echo APK output: app\build\outputs\apk\debug\app-debug.apk
endlocal
