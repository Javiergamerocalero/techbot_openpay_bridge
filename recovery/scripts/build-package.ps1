# Arma el entregable instalable del Openpay Recovery Service.
#
#   cd recovery
#   mvn clean package
#   powershell -ExecutionPolicy Bypass -File scripts\build-package.ps1
#
# Deja TechbotOpenpayRecovery-v<version>.zip en recovery\dist\ y muestra su
# SHA256, que es lo que se le pasa a quien lo instala para que verifique.

$ErrorActionPreference = 'Stop'

$recoveryDir = Split-Path -Parent $PSScriptRoot
$version = '1.0.1'
$nombre = "TechbotOpenpayRecovery-v$version"
$dist = Join-Path $recoveryDir 'dist'
$staging = Join-Path $dist $nombre

$jar = Join-Path $recoveryDir 'target\openpay-recovery-service.jar'
if (-not (Test-Path $jar)) {
    throw "No existe $jar. Compilar primero con: mvn clean package"
}

if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging -Force | Out-Null

Copy-Item $jar $staging
Copy-Item (Join-Path $recoveryDir 'recovery.properties.example') $staging
Copy-Item (Join-Path $PSScriptRoot 'install-recovery-service.bat') $staging
Copy-Item (Join-Path $PSScriptRoot 'uninstall-recovery-service.bat') $staging
Copy-Item (Join-Path $PSScriptRoot 'README-INSTALL.txt') $staging

# nssm.exe se incluye solo si esta disponible; si no, el README explica de
# donde lo toma el instalador.
$nssm = Join-Path $PSScriptRoot 'nssm.exe'
if (Test-Path $nssm) {
    Copy-Item $nssm $staging
    Write-Host 'nssm.exe incluido en el paquete'
} else {
    Write-Host 'nssm.exe no incluido: el instalador lo buscara en C:\bridge\nssm.exe o en el PATH'
}

$zip = Join-Path $dist "$nombre.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zip -Force
Remove-Item $staging -Recurse -Force

$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
$tamano = [math]::Round((Get-Item $zip).Length / 1KB, 1)

Write-Host ''
Write-Host "paquete: $zip"
Write-Host "tamano:  $tamano KB"
Write-Host "sha256:  $hash"
