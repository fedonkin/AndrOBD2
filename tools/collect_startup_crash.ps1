# Read-only diagnostic collection. Does not clear logcat or application data.
param([string]$Serial = "")
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$Package = "com.fr3ts0n.ecu.gui.androbd"

try {
    $SdkPaths = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $Properties = Join-Path $Root "local.properties"
    if (Test-Path $Properties) {
        foreach ($Line in Get-Content $Properties) {
            if ($Line -match '^\s*sdk\.dir\s*=\s*(.+)$') {
                $SdkPaths += ($Matches[1].Trim() -replace '\\:', ':' -replace '\\\\', '\')
            }
        }
    }
    if ($env:LOCALAPPDATA) { $SdkPaths += (Join-Path $env:LOCALAPPDATA "Android\Sdk") }
    $SdkPaths += (Join-Path $Root ".gradle\local-tools\android-sdk")
    $SdkPaths += "C:\Android\Sdk"
    $Adb = $null
    foreach ($Sdk in $SdkPaths) {
        if (!$Sdk) { continue }
        $Candidate = Join-Path $Sdk "platform-tools\adb.exe"
        if (Test-Path $Candidate) { $Adb = $Candidate; break }
    }
    if (!$Adb) {
        $Command = Get-Command adb -ErrorAction SilentlyContinue
        if ($Command) { $Adb = $Command.Source }
    }
    if (!$Adb) { throw "ADB not found. Install Android SDK Platform-Tools or set ANDROID_HOME." }

    # Native stderr remains visible; it is not treated as report data.
    $Devices = @(& $Adb devices)
    if ($LASTEXITCODE -ne 0) { throw "ADB cannot list devices." }
    $Ready = @($Devices | ForEach-Object {
        if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
    })
    if ($Serial) {
        if ($Ready -notcontains $Serial) { throw "Selected device is not authorized/connected." }
    } elseif ($Ready.Count -eq 1) {
        $Serial = $Ready[0]
    } else {
        $Devices | Write-Host
        throw "Connect ONE phone, enable USB debugging and accept authorization. For multiple devices use -Serial."
    }

    $Report = @("AndrOBD2 startup crash report", "Collected: $(Get-Date -Format o)")
    $Report += "Android: $(& $Adb -s $Serial shell getprop ro.build.version.release)"
    $Report += "API: $(& $Adb -s $Serial shell getprop ro.build.version.sdk)"
    $Report += "Model: $(& $Adb -s $Serial shell getprop ro.product.model)"
    $Report += "Installed package: $Package"
    $Report += @(& $Adb -s $Serial shell dumpsys package $Package |
        Select-String 'versionCode=|versionName=|lastUpdateTime=' | ForEach-Object { $_.Line.Trim() })
    $Report += ""
    $Report += "AndroidRuntime errors from crash buffer (may also include other apps):"
    $Crash = @(& $Adb -s $Serial logcat -b crash -d -v threadtime "AndroidRuntime:E" "*:S")
    if ($LASTEXITCODE -ne 0) { throw "Cannot read the Android crash buffer." }
    $Report += $Crash
    if (!(($Crash -join "`n").Contains($Package))) {
        $Report += "No matching app process found. Reproduce the crash and run this collector again."
        Write-Warning "No AndrOBD process in crash buffer. Open the app, reproduce the crash, then run again."
    }
    $Folder = Join-Path $Root "diagnostics"
    New-Item -ItemType Directory -Force -Path $Folder | Out-Null
    $Output = Join-Path $Folder ("startup-crash-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".txt")
    [IO.File]::WriteAllLines($Output, [string[]]$Report, (New-Object Text.UTF8Encoding($true)))
    Write-Host "Saved: $Output"
    Write-Host "Review the report before sharing: exception messages can include private paths or data."
    Write-Host "Also specify whether the installed APK is debug or release."
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
