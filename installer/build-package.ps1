param(
    [string]$Version = "1.0.0",
    [string]$NssmPath = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$InstallerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $InstallerDir
$DistRoot = Join-Path $RepoRoot "dist"
$StageDir = Join-Path $DistRoot "TECHBOT-Openpay-Bridge-$Version"
$ZipPath = Join-Path $DistRoot "TECHBOT-Openpay-Bridge-$Version.zip"
$HashPath = "$ZipPath.sha256.txt"

function Require-File([string]$Path, [string]$Label) {
    if (-not (Test-Path $Path -PathType Leaf)) {
        throw "$Label no encontrado: $Path"
    }
}

function Resolve-Nssm([string]$ExplicitPath) {
    if ($ExplicitPath) {
        Require-File $ExplicitPath "nssm.exe"
        return (Resolve-Path $ExplicitPath).Path
    }

    $Candidates = @(
        (Join-Path $InstallerDir "nssm.exe"),
        "C:\bridge\nssm.exe"
    )

    foreach ($Candidate in $Candidates) {
        if (Test-Path $Candidate -PathType Leaf) {
            return (Resolve-Path $Candidate).Path
        }
    }

    $Cmd = Get-Command nssm.exe -ErrorAction SilentlyContinue
    if ($Cmd) { return $Cmd.Source }

    throw "No se encontro nssm.exe. Use -NssmPath <ruta> o coloque nssm.exe en installer/ o C:\bridge."
}

if (-not $SkipBuild) {
    Write-Host "[1/6] Compilando TotalPosBridge..."
    Push-Location $RepoRoot
    try {
        & mvn -q clean package
        if ($LASTEXITCODE -ne 0) { throw "Fallo Maven al compilar TotalPosBridge." }
    }
    finally { Pop-Location }

    Write-Host "[2/6] Compilando y probando TechbotOpenpayRecovery..."
    Push-Location (Join-Path $RepoRoot "recovery")
    try {
        & mvn -q clean test package
        if ($LASTEXITCODE -ne 0) { throw "Fallo Maven al compilar/probar Recovery." }
    }
    finally { Pop-Location }
}
else {
    Write-Host "[1/6] Build omitido por -SkipBuild."
    Write-Host "[2/6] Usando JARs existentes."
}

$BridgeJar = Join-Path $RepoRoot "target\totalpos-bridge.jar"
$RecoveryJar = Join-Path $RepoRoot "recovery\target\openpay-recovery-service.jar"
$BridgeExample = Join-Path $RepoRoot "application.yaml.example"
$RecoveryExample = Join-Path $RepoRoot "recovery\recovery.properties.example"
$InstallBat = Join-Path $InstallerDir "install-openpay-bridge.bat"
$UpdateBat = Join-Path $InstallerDir "update-openpay-bridge.bat"
$UninstallBat = Join-Path $InstallerDir "uninstall-openpay-bridge.bat"
$Readme = Join-Path $InstallerDir "README.md"

Require-File $BridgeJar "TotalPosBridge JAR"
Require-File $RecoveryJar "Recovery JAR"
Require-File $BridgeExample "application.yaml.example"
Require-File $RecoveryExample "recovery.properties.example"
Require-File $InstallBat "install-openpay-bridge.bat"
Require-File $UpdateBat "update-openpay-bridge.bat"
Require-File $UninstallBat "uninstall-openpay-bridge.bat"
Require-File $Readme "README.md"

Write-Host "[3/6] Resolviendo NSSM..."
$ResolvedNssm = Resolve-Nssm $NssmPath

Write-Host "[4/6] Preparando staging..."
if (Test-Path $StageDir) { Remove-Item $StageDir -Recurse -Force }
if (-not (Test-Path $DistRoot)) { New-Item $DistRoot -ItemType Directory | Out-Null }
New-Item $StageDir -ItemType Directory | Out-Null

Copy-Item $BridgeJar (Join-Path $StageDir "totalpos-bridge.jar")
Copy-Item $RecoveryJar (Join-Path $StageDir "openpay-recovery-service.jar")
Copy-Item $BridgeExample (Join-Path $StageDir "application.yaml.example")
Copy-Item $RecoveryExample (Join-Path $StageDir "recovery.properties.example")
Copy-Item $InstallBat $StageDir
Copy-Item $UpdateBat $StageDir
Copy-Item $UninstallBat $StageDir
Copy-Item $Readme $StageDir
Copy-Item $ResolvedNssm (Join-Path $StageDir "nssm.exe")

$Manifest = @"
TECHBOT Openpay Bridge
Package-Version: $Version
Generated-UTC: $([DateTime]::UtcNow.ToString("o"))
Bridge-Service: TotalPosBridge
Bridge-Port: 9091
Recovery-Service: TechbotOpenpayRecovery
Recovery-Port: 9092
Java-Minimum: 17
"@
Set-Content -Path (Join-Path $StageDir "PACKAGE-MANIFEST.txt") -Value $Manifest -Encoding UTF8

Write-Host "[5/6] Generando ZIP..."
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Compress-Archive -Path (Join-Path $StageDir "*") -DestinationPath $ZipPath -CompressionLevel Optimal

Write-Host "[6/6] Calculando SHA-256..."
$Hash = Get-FileHash -Algorithm SHA256 $ZipPath
$HashLine = "$($Hash.Hash)  $([IO.Path]::GetFileName($ZipPath))"
Set-Content -Path $HashPath -Value $HashLine -Encoding ASCII

Write-Host ""
Write-Host "Paquete generado correctamente:"
Write-Host "  ZIP:    $ZipPath"
Write-Host "  SHA256: $($Hash.Hash)"
Write-Host "  Hash:   $HashPath"
