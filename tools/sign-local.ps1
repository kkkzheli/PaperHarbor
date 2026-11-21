param(
    [Parameter(Mandatory=$true)][string]$Keystore,
    [Parameter(Mandatory=$true)][string]$Alias,
    [string]$BuildTools,
    [string]$Unsigned,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (!$Unsigned) { $Unsigned = Join-Path $root 'app/build/outputs/apk/release/app-release-unsigned.apk' }
if (!$Output) { $Output = Join-Path $root 'artifacts/PaperHarbor-0.1.0-release.apk' }
if (!$BuildTools) {
    $sdk = $env:ANDROID_HOME
    if (!$sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $BuildTools = (Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).FullName
}
if (!(Test-Path -LiteralPath $Keystore -PathType Leaf)) { throw 'Keystore not found. Create and back it up privately first.' }
if (!(Test-Path -LiteralPath $Unsigned -PathType Leaf)) { throw 'Unsigned APK not found. Run assembleRelease or pass -Unsigned.' }
if (Test-Path -LiteralPath $Output) { throw 'Output already exists. Choose a new -Output path.' }
$null = New-Item -ItemType Directory -Path (Split-Path -Parent ([IO.Path]::GetFullPath($Output))) -Force
$aligned = Join-Path ([IO.Path]::GetTempPath()) (([guid]::NewGuid()).ToString() + '.apk')
try {
    & (Join-Path $BuildTools 'zipalign.exe') -p -f 4 $Unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
    & (Join-Path $BuildTools 'apksigner.bat') sign --ks $Keystore --ks-key-alias $Alias --out $Output $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed.' }
    & (Join-Path $BuildTools 'apksigner.bat') verify --verbose $Output
    if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
    Get-FileHash -Algorithm SHA256 -LiteralPath $Output
} finally { if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned } }
