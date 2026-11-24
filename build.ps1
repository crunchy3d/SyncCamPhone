# Builds SyncCamPhone and (with -Install) installs it on every phone connected via adb.
# Usage:  .\build.ps1            build only
#         .\build.ps1 -Install   build and install on all connected phones
param([switch]$Install)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
           Select-Object -First 1
    if (-not $jdk) { throw 'JDK 17 not found - set JAVA_HOME.' }
    $env:JAVA_HOME = $jdk.FullName
}
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
if (-not (Test-Path 'local.properties')) {
    'sdk.dir=' + ($sdk -replace '\\', '\\' -replace ':', '\:') | Out-File -Encoding ascii 'local.properties'
}

# javac notes (e.g. "uses a deprecated API") go to stderr: do not treat them as errors
$ErrorActionPreference = 'Continue'
.\gradlew.bat assembleDebug 2>&1 | ForEach-Object { "$_" }
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
$apk = 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host "APK: $apk"

if ($Install) {
    $adb = "$sdk\platform-tools\adb.exe"
    $devices = & $adb devices | Select-String '\tdevice$' | ForEach-Object { ($_ -split '\t')[0] }
    if (-not $devices) { throw 'No phone connected (enable USB debugging).' }
    foreach ($d in $devices) {
        Write-Host "Installing on $d ..."
        & $adb -s $d install -r $apk
    }
}
