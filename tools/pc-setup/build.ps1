param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$AdbDirectory,
    [string]$Python = 'python',
    [string]$Output = (Join-Path $PSScriptRoot 'dist'),
    [string]$AirPlayApk,
    [string]$AirPlaySource,
    [string]$AirPlayLicense
)
$ErrorActionPreference = 'Stop'
if (($AirPlayApk -or $AirPlaySource -or $AirPlayLicense) -and -not ($AirPlayApk -and $AirPlaySource -and $AirPlayLicense)) {
    throw 'AirPlay requires -AirPlayApk, -AirPlaySource (complete corresponding GPL source ZIP), and -AirPlayLicense.'
}
$Apk = (Resolve-Path -LiteralPath $Apk).Path
$AdbDirectory = (Resolve-Path -LiteralPath $AdbDirectory).Path
$Output = [System.IO.Path]::GetFullPath($Output)
$buildDir = Join-Path $Output 'build'
$assetDir = Join-Path $buildDir 'portable-assets'
New-Item -ItemType Directory -Force $Output,$buildDir,$assetDir | Out-Null
$bundledApk = Join-Path $assetDir 'PeachOS-TV.apk'
if ($Apk -ne $bundledApk) { Copy-Item -LiteralPath $Apk -Destination $bundledApk -Force }
$repo = Split-Path (Split-Path $PSScriptRoot)
$assets = @('--add-data', "$bundledApk;.", '--add-data', "$(Join-Path $PSScriptRoot 'README.ru.md');.")
if ($AirPlayApk) {
    $AirPlayApk = (Resolve-Path -LiteralPath $AirPlayApk).Path
    $AirPlaySource = (Resolve-Path -LiteralPath $AirPlaySource).Path
    $AirPlayLicense = (Resolve-Path -LiteralPath $AirPlayLicense).Path
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $companionArchive = [System.IO.Compression.ZipFile]::OpenRead($AirPlayApk)
    try {
        if (-not $companionArchive.GetEntry('AndroidManifest.xml') -or -not ($companionArchive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })) {
            throw 'AirPlay APK must contain AndroidManifest.xml and native libraries.'
        }
    } finally { $companionArchive.Dispose() }
    $sourceArchive = [System.IO.Compression.ZipFile]::OpenRead($AirPlaySource)
    try {
        if (-not $sourceArchive.Entries.Count) { throw 'AirPlay corresponding source ZIP is empty.' }
    } finally { $sourceArchive.Dispose() }
    foreach ($entry in @(@($AirPlayApk, 'PeachOS-AirPlay.apk'), @($AirPlaySource, 'PeachOS-AirPlay-source.zip'), @($AirPlayLicense, 'LICENSE-AirPlay.txt'))) {
        $destination = Join-Path $assetDir $entry[1]
        if ($entry[0] -ne $destination) { Copy-Item -LiteralPath $entry[0] -Destination $destination -Force }
        $assets += @('--add-data', "$destination;.")
    }
}
foreach ($name in @('peachos.ico','peachos.png','reference-catalog.json')) {
    $assets += @('--add-data', "$(Join-Path $PSScriptRoot $name);.")
}
foreach ($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt')) {
    $assets += @('--add-data', "$(Join-Path $AdbDirectory $name);platform-tools")
}
foreach ($name in @('LICENSE','LICENSE-CleanYourTV')) {
    $assets += @('--add-data', "$(Join-Path $repo $name);.")
}
& $Python -m PyInstaller --noconfirm --clean --windowed --onefile --icon (Join-Path $PSScriptRoot 'peachos.ico') --name PeachOS-TV-Setup-Portable --distpath $Output --workpath $buildDir --specpath $buildDir @assets (Join-Path $PSScriptRoot 'wizard.py')
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller failed' }
Write-Output "Ready: $(Join-Path $Output 'PeachOS-TV-Setup-Portable.exe')"
