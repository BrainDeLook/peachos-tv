param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$AdbDirectory,
    [string]$Python = 'python',
    [string]$Output = (Join-Path $PSScriptRoot 'dist')
)
$ErrorActionPreference = 'Stop'
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
