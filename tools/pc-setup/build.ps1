param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$AdbDirectory,
    [string]$Python = 'python',
    [string]$Output = (Join-Path $PSScriptRoot 'dist')
)
$ErrorActionPreference = 'Stop'
$buildDir = Join-Path $Output 'build'
New-Item -ItemType Directory -Force $Output,$buildDir | Out-Null
$repo = Split-Path (Split-Path $PSScriptRoot)
$assets = @('--add-data', "$Apk;.", '--add-data', "$(Join-Path $PSScriptRoot 'README.ru.md');.")
foreach ($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt')) {
    $assets += @('--add-data', "$(Join-Path $AdbDirectory $name);platform-tools")
}
foreach ($name in @('LICENSE','LICENSE-CleanYourTV')) {
    $assets += @('--add-data', "$(Join-Path $repo $name);.")
}
& $Python -m PyInstaller --noconfirm --clean --windowed --onefile --name PeachOS-TV-Setup-Portable --distpath $Output --workpath $buildDir --specpath $buildDir @assets (Join-Path $PSScriptRoot 'wizard.py')
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller failed' }
Write-Output "Ready: $(Join-Path $Output 'PeachOS-TV-Setup-Portable.exe')"
