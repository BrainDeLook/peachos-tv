param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$AdbDirectory,
    [string]$Python = 'python',
    [string]$Output = (Join-Path $PSScriptRoot 'dist')
)
$ErrorActionPreference = 'Stop'
$buildDir = Join-Path $Output 'build'
New-Item -ItemType Directory -Force $Output,$buildDir | Out-Null
& $Python -m PyInstaller --noconfirm --clean --windowed --onedir --name PeachOS-TV-Setup --distpath $Output --workpath $buildDir --specpath $buildDir (Join-Path $PSScriptRoot 'wizard.py')
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller failed' }
$bundle = Join-Path $Output 'PeachOS-TV-Setup'
Copy-Item -LiteralPath $Apk -Destination (Join-Path $bundle 'PeachOS-TV.apk')
$platform = Join-Path $bundle 'platform-tools'
New-Item -ItemType Directory -Force $platform | Out-Null
foreach ($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt')) {
    Copy-Item -LiteralPath (Join-Path $AdbDirectory $name) -Destination $platform
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.ru.md') -Destination (Join-Path $bundle 'README.ru.md')
foreach ($name in @('LICENSE','LICENSE-CleanYourTV')) {
    Copy-Item -LiteralPath (Join-Path (Split-Path (Split-Path $PSScriptRoot)) $name) -Destination $bundle
}
Write-Output "Ready: $bundle"
