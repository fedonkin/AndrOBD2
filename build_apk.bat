@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem ============================================================================
rem  AndrOBD2 - Автоматическая сборка APK для Windows
rem  Использование:
rem    build_apk.bat                 - сборка Debug APK (готов к установке)
rem    build_apk.bat release         - сборка оптимизированного Release APK
rem    build_apk.bat clean           - полная пересборка с очисткой кэша проекта
rem    build_apk.bat test            - запуск модульных тестов + сборка APK
rem    build_apk.bat install         - сборка и установка на телефон через ADB
rem    build_apk.bat --no-pause      - без ожидания нажатия клавиши в конце
rem ============================================================================

set "ORIG_CP="
for /f "tokens=2 delims=:." %%A in ('chcp') do set "ORIG_CP=%%A"
if defined ORIG_CP set "ORIG_CP=!ORIG_CP: =!"
chcp 65001 >nul 2>&1

set "PROJECT_DIR=%~dp0"
pushd "%PROJECT_DIR%" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Не удалось перейти в каталог проекта: "%PROJECT_DIR%"
    exit /b 1
)

set "BUILD_TYPE=debug"
set "CLEAN_TASK="
set "TEST_TASK="
set "DO_INSTALL=0"
set "NO_PAUSE=0"
if /i "%CI%"=="true" set "NO_PAUSE=1"

:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="debug" (
    set "BUILD_TYPE=debug"
) else if /i "%~1"=="release" (
    set "BUILD_TYPE=release"
) else if /i "%~1"=="clean" (
    set "CLEAN_TASK=clean"
) else if /i "%~1"=="test" (
    set "TEST_TASK=:androbd:testDebugUnitTest"
) else if /i "%~1"=="install" (
    set "DO_INSTALL=1"
) else if /i "%~1"=="--no-pause" (
    set "NO_PAUSE=1"
) else if /i "%~1"=="-h" (
    goto :show_help
) else if /i "%~1"=="--help" (
    goto :show_help
) else if "%~1"=="/?" (
    goto :show_help
) else (
    echo [WARN] Неизвестный аргумент игнорируется: %~1
)
shift
goto :parse_args

:args_done
echo ============================================================================
echo   AndrOBD2 - Автоматическая сборка APK ^(!BUILD_TYPE!^)
echo ============================================================================
echo.

if not exist "%PROJECT_DIR%gradlew.bat" (
    echo [ERROR] Файл gradlew.bat не найден в "%PROJECT_DIR%".
    goto :fail
)

rem ----------------------------------------------------------------------------
rem 1. Проверка подмодуля plugin (AndrOBD-libplugin)
rem ----------------------------------------------------------------------------
echo [1/5] Проверка модуля plugin...
call :ensure_plugin_submodule
if errorlevel 1 (
    echo [ERROR] Не удалось загрузить подмодуль plugin. Проверьте подключение к интернету.
    goto :fail
)
echo       OK: plugin\build.gradle найден.

rem ----------------------------------------------------------------------------
rem 2. Поиск JDK 17 / 21+ (или автозагрузка портативного JDK, если Java нет)
rem ----------------------------------------------------------------------------
echo [2/5] Поиск установленного JDK ^(поддерживаются JDK 17 и JDK 21^)...
set "FOUND_JDK="
set "FOUND_JDK_VER="

if defined JAVA_HOME call :try_jdk "!JAVA_HOME!"

if not defined FOUND_JDK (
    for /f "delims=" %%J in ('where java.exe 2^>nul') do (
        if not defined FOUND_JDK call :try_java_exe "%%~fJ"
    )
)

if not defined FOUND_JDK call :scan_jdk_dir "!ProgramFiles!\Java"
if not defined FOUND_JDK call :scan_jdk_dir "!ProgramFiles!\Eclipse Adoptium"
if not defined FOUND_JDK call :scan_jdk_dir "!LOCALAPPDATA!\Programs\Eclipse Adoptium"
if not defined FOUND_JDK call :scan_jdk_dir "!ProgramFiles!\Microsoft"
if not defined FOUND_JDK call :scan_jdk_dir "!ProgramFiles!\OpenJDK"
if not defined FOUND_JDK call :scan_jdk_dir "!ProgramFiles!\BellSoft"
if not defined FOUND_JDK call :scan_jdk_dir "!ProgramFiles!\Zulu"
if not defined FOUND_JDK call :scan_jdk_dir "!ProgramFiles!\Amazon Corretto"
if not defined FOUND_JDK call :scan_jdk_dir "!USERPROFILE!\.jdks"
if not defined FOUND_JDK call :scan_jdk_dir "C:\Java"

if not defined FOUND_JDK call :try_jdk "!ProgramFiles!\Android\Android Studio\jbr"
if not defined FOUND_JDK call :try_jdk "!ProgramFiles!\Android\Android Studio\jre"
if not defined FOUND_JDK call :try_jdk "!LOCALAPPDATA!\Programs\Android Studio\jbr"
if not defined FOUND_JDK call :try_jdk "%PROJECT_DIR%.gradle\local-tools\jdk-17"

if not defined FOUND_JDK (
    echo       Установленный JDK 17/21 не найден. Автоматическая загрузка портативного JDK...
    call :bootstrap_jdk
)

if not defined FOUND_JDK (
    echo [ERROR] Не удалось найти или загрузить JDK ^(17 или 21^).
    echo         Укажите путь к вашему JDK 21 в переменной среды JAVA_HOME и повторите запуск.
    goto :fail
)

set "JAVA_HOME=!FOUND_JDK!"
set "PATH=!JAVA_HOME!\bin;!PATH!"
echo       OK: JDK !FOUND_JDK_VER! ^("!JAVA_HOME!"^)

rem ----------------------------------------------------------------------------
rem 3. Поиск или подготовка Android SDK и local.properties
rem ----------------------------------------------------------------------------
echo [3/5] Проверка Android SDK...
set "FOUND_SDK="
set "NEED_WRITE_LOCAL_PROPS=1"

call :read_local_properties_sdk

if not defined FOUND_SDK if defined ANDROID_HOME call :try_sdk "!ANDROID_HOME!"
if not defined FOUND_SDK if defined ANDROID_SDK_ROOT call :try_sdk "!ANDROID_SDK_ROOT!"
if not defined FOUND_SDK call :try_sdk "!LOCALAPPDATA!\Android\Sdk"
if not defined FOUND_SDK call :try_sdk "!USERPROFILE!\AppData\Local\Android\Sdk"
if not defined FOUND_SDK call :try_sdk "C:\Android\Sdk"
if not defined FOUND_SDK call :try_sdk "C:\Android\sdk"
if not defined FOUND_SDK call :try_sdk "!ProgramFiles!\Android\Sdk"
if not defined FOUND_SDK call :try_sdk "!ProgramFiles(x86)!\Android\android-sdk"
if not defined FOUND_SDK call :try_sdk "%PROJECT_DIR%.gradle\local-tools\android-sdk"

if not defined FOUND_SDK (
    echo       Android SDK не найден. Автоматическая загрузка Android Command-Line Tools...
    call :bootstrap_sdk
)

if not defined FOUND_SDK (
    echo [ERROR] Не удалось найти или подготовить Android SDK.
    echo         Установите Android Studio или укажите переменную среды ANDROID_HOME.
    goto :fail
)

set "ANDROID_HOME=!FOUND_SDK!"
set "ANDROID_SDK_ROOT=!FOUND_SDK!"

call :ensure_sdk_licenses "!FOUND_SDK!"
if "!NEED_WRITE_LOCAL_PROPS!"=="1" call :write_local_properties

echo       OK: Android SDK ^("!FOUND_SDK!"^)

rem ----------------------------------------------------------------------------
rem 4. Запуск сборки Gradle
rem ----------------------------------------------------------------------------
if /i "!BUILD_TYPE!"=="release" (
    set "ASSEMBLE_TASK=:androbd:assembleRelease"
) else (
    set "ASSEMBLE_TASK=:androbd:assembleDebug"
)

echo [4/5] Сборка проекта Gradle ^(!CLEAN_TASK! !ASSEMBLE_TASK! !TEST_TASK!^)...
echo.

if exist "%PROJECT_DIR%.gradle\configuration-cache" (
    rmdir /s /q "%PROJECT_DIR%.gradle\configuration-cache" >nul 2>&1
)
call "%PROJECT_DIR%gradlew.bat" --no-configuration-cache !CLEAN_TASK! !ASSEMBLE_TASK! !TEST_TASK! --stacktrace
if errorlevel 1 (
    echo.
    echo [ERROR] Сборка Gradle завершилась с ошибкой. Проверьте журнал выше.
    goto :fail
)

rem ----------------------------------------------------------------------------
rem 5. Копирование и подготовка итогового APK
rem ----------------------------------------------------------------------------
echo.
echo [5/5] Подготовка готового APK файла...
set "OUT_DIR=%PROJECT_DIR%build\apk"
if not exist "!OUT_DIR!" mkdir "!OUT_DIR!"

set "FINAL_APK="

if /i "!BUILD_TYPE!"=="debug" (
    call :collect_debug_apk
) else (
    call :collect_release_apk
)

if not defined FINAL_APK goto :fail
if not exist "!FINAL_APK!" goto :fail

for %%A in ("!FINAL_APK!") do set "APK_SIZE=%%~zA"
set /a APK_SIZE_KB=!APK_SIZE! / 1024

echo.
echo ============================================================================
echo   СБОРКА УСПЕШНО ЗАВЕРШЕНА!
echo   Готовый APK: "!FINAL_APK!"
echo   Размер:      !APK_SIZE_KB! КБ
echo ============================================================================

if "!DO_INSTALL!"=="1" call :install_via_adb

set "EXIT_CODE=0"
goto :finish

:show_help
echo Использование: build_apk.bat [debug^|release] [clean] [test] [install] [--no-pause]
echo.
echo   debug       Сборка подписанного Debug APK ^(по умолчанию, готов к установке на телефон^)
echo   release     Сборка сжатого Release APK ^(R8/ProGuard^) с автоподписью для установки
echo   clean       Очистка предыдущих артефактов сборки перед компиляцией
echo   test        Запуск unit-тестов перед сборкой APK
echo   install     Автоматическая установка готового APK на телефон через adb install -r
echo   --no-pause  Не ожидать нажатия клавиши по завершении работы скрипта
set "EXIT_CODE=0"
goto :finish

:fail
echo.
echo ============================================================================
echo   СБОРКА ПРЕРВАНА ИЗ-ЗА ОШИБКИ
echo ============================================================================
set "EXIT_CODE=1"
goto :finish

:finish
popd >nul 2>&1
if "!NO_PAUSE!"=="0" (
    echo.
    pause
)
if defined ORIG_CP chcp !ORIG_CP! >nul 2>&1
endlocal & exit /b %EXIT_CODE%

rem ============================================================================
rem  Вспомогательные подпрограммы
rem ============================================================================

:ensure_plugin_submodule
if exist "%PROJECT_DIR%plugin\build.gradle" exit /b 0
echo       Модуль plugin не инициализирован. Загрузка AndrOBD-libplugin...
where git.exe >nul 2>&1
if errorlevel 1 goto :download_plugin_zip
if exist "%PROJECT_DIR%.git" (
    git submodule update --init --recursive
)
if exist "%PROJECT_DIR%plugin\build.gradle" exit /b 0
if exist "%PROJECT_DIR%plugin" rmdir /s /q "%PROJECT_DIR%plugin"
git clone https://github.com/fr3ts0n/AndrOBD-libplugin.git "%PROJECT_DIR%plugin"
if not errorlevel 1 (
    git -C "%PROJECT_DIR%plugin" checkout 4ff67cd71c4c6ee30eee6743c20746abbe5e789f >nul 2>&1
)
if exist "%PROJECT_DIR%plugin\build.gradle" exit /b 0

:download_plugin_zip
echo       Скачивание архива модуля plugin через PowerShell...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; " ^
    "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; " ^
    "$zip = Join-Path $env:TEMP 'androbd_plugin.zip'; " ^
    "$tmp = Join-Path $env:TEMP 'androbd_plugin_extract'; " ^
    "if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }; " ^
    "Invoke-WebRequest -Uri 'https://codeload.github.com/fr3ts0n/AndrOBD-libplugin/zip/4ff67cd71c4c6ee30eee6743c20746abbe5e789f' -OutFile $zip -UseBasicParsing; " ^
    "Expand-Archive -Path $zip -DestinationPath $tmp -Force; " ^
    "$src = Get-ChildItem -Path $tmp -Directory | Select-Object -First 1; " ^
    "$dst = Join-Path $env:PROJECT_DIR 'plugin'; " ^
    "if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }; " ^
    "Move-Item -Path $src.FullName -Destination $dst; " ^
    "Remove-Item -Force $zip -ErrorAction SilentlyContinue; " ^
    "Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue"
if exist "%PROJECT_DIR%plugin\build.gradle" exit /b 0
exit /b 1

:try_java_exe
set "CAND_JAVA_EXE=%~1"
if "!CAND_JAVA_EXE!"=="" exit /b 1
if not exist "!CAND_JAVA_EXE!" exit /b 1
set "JPROPS_TMP=%TEMP%\androbd_javahome.txt"
del /q "!JPROPS_TMP!" >nul 2>&1
"!CAND_JAVA_EXE!" -XshowSettings:properties -version > "!JPROPS_TMP!" 2>&1
if not exist "!JPROPS_TMP!" exit /b 1
set "EXTRACTED_HOME="
for /f "usebackq tokens=2 delims==" %%H in (`findstr /c:"java.home =" "!JPROPS_TMP!"`) do (
    set "EXTRACTED_HOME=%%H"
)
del /q "!JPROPS_TMP!" >nul 2>&1
if not defined EXTRACTED_HOME exit /b 1
for /f "tokens=* delims= " %%T in ("!EXTRACTED_HOME!") do set "EXTRACTED_HOME=%%T"
call :try_jdk "!EXTRACTED_HOME!"
exit /b !ERRORLEVEL!

:scan_jdk_dir
set "SCAN_ROOT=%~1"
if "!SCAN_ROOT!"=="" exit /b 1
if not exist "!SCAN_ROOT!" exit /b 1
for /d %%D in ("!SCAN_ROOT!\*") do (
    if not defined FOUND_JDK call :try_jdk "%%~fD"
)
exit /b 0

:try_jdk
if defined FOUND_JDK exit /b 0
set "CAND_JDK=%~1"
if "!CAND_JDK!"=="" exit /b 1
if not exist "!CAND_JDK!\bin\java.exe" exit /b 1
if not exist "!CAND_JDK!\bin\javac.exe" exit /b 1
set "JVER_TMP=%TEMP%\androbd_javaver.txt"
del /q "!JVER_TMP!" >nul 2>&1
"!CAND_JDK!\bin\java.exe" -XshowSettings:properties -version > "!JVER_TMP!" 2>&1
if not exist "!JVER_TMP!" exit /b 1
set "J_SPEC="
for /f "usebackq tokens=2 delims==" %%V in (`findstr /c:"java.specification.version" "!JVER_TMP!"`) do (
    set "J_SPEC=%%V"
)
del /q "!JVER_TMP!" >nul 2>&1
if not defined J_SPEC exit /b 1
set "J_SPEC=!J_SPEC: =!"
if "!J_SPEC:~0,2!"=="1." exit /b 1
set /a J_MAJOR=!J_SPEC! 2>nul
if !J_MAJOR! GEQ 17 if !J_MAJOR! LEQ 24 (
    set "FOUND_JDK=!CAND_JDK!"
    set "FOUND_JDK_VER=!J_MAJOR!"
    exit /b 0
)
exit /b 1

:bootstrap_jdk
set "LOCAL_JDK_DIR=%PROJECT_DIR%.gradle\local-tools\jdk-17"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; " ^
    "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; " ^
    "$zip = Join-Path $env:TEMP 'temurin_jdk17_win64.zip'; " ^
    "$tmp = Join-Path $env:TEMP 'temurin_jdk17_extract'; " ^
    "$dst = $env:LOCAL_JDK_DIR; " ^
    "if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }; " ^
    "Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' -OutFile $zip -UseBasicParsing; " ^
    "Expand-Archive -Path $zip -DestinationPath $tmp -Force; " ^
    "$inner = Get-ChildItem -Path $tmp -Directory | Select-Object -First 1; " ^
    "$parent = Split-Path -Parent $dst; " ^
    "if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }; " ^
    "if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }; " ^
    "Move-Item -Path $inner.FullName -Destination $dst; " ^
    "Remove-Item -Force $zip -ErrorAction SilentlyContinue; " ^
    "Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue"
if errorlevel 1 exit /b 1
call :try_jdk "!LOCAL_JDK_DIR!"
exit /b !ERRORLEVEL!

:read_local_properties_sdk
if not exist "%PROJECT_DIR%local.properties" exit /b 0
set "PARSED_SDK_TMP=%TEMP%\androbd_sdk_path.txt"
del /q "!PARSED_SDK_TMP!" >nul 2>&1
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$p = Join-Path $env:PROJECT_DIR 'local.properties'; " ^
    "foreach ($line in Get-Content $p) { " ^
    "  if ($line -match '^\s*sdk\.dir\s*=\s*(.+)$') { " ^
    "    $v = $Matches[1].Trim() -replace '\\:', ':' -replace '\\\\', '\' -replace '/', '\'; " ^
    "    if (Test-Path $v) { [IO.File]::WriteAllText($env:PARSED_SDK_TMP, $v, (New-Object Text.UTF8Encoding($false))); break } " ^
    "  } " ^
    "}"
if exist "!PARSED_SDK_TMP!" (
    set /p FOUND_SDK=<"!PARSED_SDK_TMP!"
    del /q "!PARSED_SDK_TMP!" >nul 2>&1
    if defined FOUND_SDK set "NEED_WRITE_LOCAL_PROPS=0"
)
exit /b 0

:write_local_properties
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$sdk = $env:ANDROID_HOME -replace '\\', '/' -replace ':', '\:'; " ^
    "$out = Join-Path $env:PROJECT_DIR 'local.properties'; " ^
    "[IO.File]::WriteAllText($out, \"sdk.dir=$sdk`r`n\", (New-Object Text.UTF8Encoding($false)))"
echo       Создан файл local.properties.
exit /b 0

:try_sdk
if defined FOUND_SDK exit /b 0
set "CAND_SDK=%~1"
if "!CAND_SDK!"=="" exit /b 1
if exist "!CAND_SDK!\platforms" (
    set "FOUND_SDK=!CAND_SDK!"
    exit /b 0
)
if exist "!CAND_SDK!\platform-tools" (
    set "FOUND_SDK=!CAND_SDK!"
    exit /b 0
)
if exist "!CAND_SDK!\cmdline-tools" (
    set "FOUND_SDK=!CAND_SDK!"
    exit /b 0
)
exit /b 1

:bootstrap_sdk
set "LOCAL_SDK_DIR=%PROJECT_DIR%.gradle\local-tools\android-sdk"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; " ^
    "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; " ^
    "$zip = Join-Path $env:TEMP 'android_cmdline_tools.zip'; " ^
    "$tmp = Join-Path $env:TEMP 'android_cmdline_extract'; " ^
    "$sdk = $env:LOCAL_SDK_DIR; " ^
    "$latest = Join-Path $sdk 'cmdline-tools\latest'; " ^
    "if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }; " ^
    "Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip -UseBasicParsing; " ^
    "Expand-Archive -Path $zip -DestinationPath $tmp -Force; " ^
    "$inner = Join-Path $tmp 'cmdline-tools'; " ^
    "$parent = Split-Path -Parent $latest; " ^
    "if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }; " ^
    "if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }; " ^
    "Move-Item -Path $inner -Destination $latest; " ^
    "New-Item -ItemType Directory -Path (Join-Path $sdk 'platforms') -Force | Out-Null; " ^
    "Remove-Item -Force $zip -ErrorAction SilentlyContinue; " ^
    "Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue"
if errorlevel 1 exit /b 1
call :try_sdk "!LOCAL_SDK_DIR!"
exit /b !ERRORLEVEL!

:ensure_sdk_licenses
set "SDK_ROOT_DIR=%~1"
if exist "!SDK_ROOT_DIR!\licenses\android-sdk-license" exit /b 0
set "SDKMANAGER_BAT="
if exist "!SDK_ROOT_DIR!\cmdline-tools\latest\bin\sdkmanager.bat" (
    set "SDKMANAGER_BAT=!SDK_ROOT_DIR!\cmdline-tools\latest\bin\sdkmanager.bat"
) else (
    for /d %%V in ("!SDK_ROOT_DIR!\cmdline-tools\*") do (
        if exist "%%~fV\bin\sdkmanager.bat" set "SDKMANAGER_BAT=%%~fV\bin\sdkmanager.bat"
    )
)
if defined SDKMANAGER_BAT call :accept_licenses_ps
if not exist "!SDK_ROOT_DIR!\licenses\android-sdk-license" (
    if not exist "!SDK_ROOT_DIR!\licenses" mkdir "!SDK_ROOT_DIR!\licenses"
    (
        echo 8933bad161af4178b1185d1a37fbf41ea5269c55
        echo d56f5187479451eabf01fb78af6dfcb131a6481e
        echo 24333f8a63b6825ea9c5514f83c2829b004d1fee
        echo 85435445a95c234340d05367a999a69d7b46701c
    ) > "!SDK_ROOT_DIR!\licenses\android-sdk-license"
)
exit /b 0

:accept_licenses_ps
echo       Принятие лицензий Android SDK...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$yes = ('y' + [Environment]::NewLine) * 40; " ^
    "$yes | & $env:SDKMANAGER_BAT \"--sdk_root=$env:SDK_ROOT_DIR\" '--licenses' | Out-Null" >nul 2>&1
exit /b 0

:collect_debug_apk
set "RAW_APK=%PROJECT_DIR%androbd\build\outputs\apk\debug\androbd-debug.apk"
if not exist "!RAW_APK!" (
    for /r "%PROJECT_DIR%androbd\build\outputs\apk\debug" %%F in (*.apk) do (
        if not exist "!RAW_APK!" set "RAW_APK=%%~fF"
    )
)
if not exist "!RAW_APK!" (
    echo [ERROR] Скомпилированный Debug APK не найден.
    exit /b 1
)
set "FINAL_APK=!OUT_DIR!\AndrOBD2-debug.apk"
copy /y "!RAW_APK!" "!FINAL_APK!" >nul
exit /b 0

:collect_release_apk
set "RAW_SIGNED=%PROJECT_DIR%androbd\build\outputs\apk\release\androbd-release.apk"
set "RAW_UNSIGNED=%PROJECT_DIR%androbd\build\outputs\apk\release\androbd-release-unsigned.apk"
if exist "!RAW_SIGNED!" (
    set "FINAL_APK=!OUT_DIR!\AndrOBD2-release.apk"
    copy /y "!RAW_SIGNED!" "!FINAL_APK!" >nul
    exit /b 0
)
if not exist "!RAW_UNSIGNED!" (
    echo [ERROR] Скомпилированный Release APK не найден.
    exit /b 1
)
copy /y "!RAW_UNSIGNED!" "!OUT_DIR!\AndrOBD2-release-unsigned.apk" >nul
call :sign_release_apk "!RAW_UNSIGNED!" "!OUT_DIR!\AndrOBD2-release-signed.apk"
if exist "!OUT_DIR!\AndrOBD2-release-signed.apk" (
    set "FINAL_APK=!OUT_DIR!\AndrOBD2-release-signed.apk"
) else (
    set "FINAL_APK=!OUT_DIR!\AndrOBD2-release-unsigned.apk"
    echo [WARN] Подписанный APK не создан ^(apksigner не найден^). Создан неподписанный файл.
)
exit /b 0

:sign_release_apk
set "IN_UNSIGNED=%~1"
set "OUT_SIGNED=%~2"
set "APKSIGNER_BAT="
set "ZIPALIGN_EXE="
for /d %%B in ("!FOUND_SDK!\build-tools\*") do (
    if exist "%%~fB\apksigner.bat" set "APKSIGNER_BAT=%%~fB\apksigner.bat"
    if exist "%%~fB\zipalign.exe" set "ZIPALIGN_EXE=%%~fB\zipalign.exe"
)
if not defined APKSIGNER_BAT exit /b 1

set "ALIGNED_TMP=%TEMP%\androbd_aligned_tmp.apk"
if defined ZIPALIGN_EXE (
    "!ZIPALIGN_EXE!" -f 4 "!IN_UNSIGNED!" "!ALIGNED_TMP!" >nul 2>&1
    if errorlevel 1 copy /y "!IN_UNSIGNED!" "!ALIGNED_TMP!" >nul
) else (
    copy /y "!IN_UNSIGNED!" "!ALIGNED_TMP!" >nul
)

if defined KEYSTORE_FILE if exist "!KEYSTORE_FILE!" (
    echo       Подпись Release APK пользовательским ключом "!KEYSTORE_FILE!"...
    call "!APKSIGNER_BAT!" sign --ks "!KEYSTORE_FILE!" --ks-key-alias "!KEY_ALIAS!" --ks-pass "pass:!KEYSTORE_PASSWORD!" --key-pass "pass:!KEY_PASSWORD!" --out "!OUT_SIGNED!" "!ALIGNED_TMP!"
    del /q "!ALIGNED_TMP!" >nul 2>&1
    exit /b !ERRORLEVEL!
)

set "DBG_KS=!USERPROFILE!\.android\debug.keystore"
if not exist "!DBG_KS!" (
    if not exist "!USERPROFILE!\.android" mkdir "!USERPROFILE!\.android"
    "!JAVA_HOME!\bin\keytool.exe" -genkeypair -v -keystore "!DBG_KS!" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >nul 2>&1
)
if exist "!DBG_KS!" (
    echo       Подпись Release APK тестовым ключом для установки на телефон...
    call "!APKSIGNER_BAT!" sign --ks "!DBG_KS!" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "!OUT_SIGNED!" "!ALIGNED_TMP!"
)
del /q "!ALIGNED_TMP!" >nul 2>&1
exit /b 0

:install_via_adb
echo.
echo [ADB] Установка APK на подключённый телефон...
set "ADB_EXE=!FOUND_SDK!\platform-tools\adb.exe"
if not exist "!ADB_EXE!" set "ADB_EXE=adb.exe"
"!ADB_EXE!" install -r "!FINAL_APK!"
if errorlevel 1 (
    echo [WARN] Не удалось установить APK через ADB. Убедитесь, что телефон подключён и включена отладка по USB.
) else (
    echo [ADB] Приложение успешно установлено на устройство!
)
exit /b 0
