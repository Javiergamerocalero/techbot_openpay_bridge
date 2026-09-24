param(
    [int]$TimeoutSeconds = 35,
    [int]$PollMilliseconds = 1000,
    [string]$ExpectedBridgeVersion = "1.2.0",
    [string]$ExpectedRecoveryVersion = "1.0.3"
)

$ErrorActionPreference = "Stop"
$BridgeUrl = "http://127.0.0.1:9091/api/health"
$RecoveryUrl = "http://127.0.0.1:9092/health"

function Wait-JsonHealth([string]$Name, [string]$Url, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastError = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $r = Invoke-RestMethod -Uri $Url -Method GET -TimeoutSec 5
            return $r
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    }
    throw "$Name FAIL: $lastError"
}

function Wait-ServiceRunning([string]$Name, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastStatus = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        $service = Get-Service $Name -ErrorAction Stop
        $lastStatus = $service.Status
        if ($service.Status -eq "Running") {
            return $service
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    }
    throw "$Name no alcanzo Running dentro de $TimeoutSeconds s. Ultimo estado: $lastStatus"
}

Write-Host "=== SERVICIOS ==="
$bridgeService = Wait-ServiceRunning "TotalPosBridge" $TimeoutSeconds
$recoveryService = Wait-ServiceRunning "TechbotOpenpayRecovery" $TimeoutSeconds
$bridgeService, $recoveryService | Select-Object Name,Status,StartType | Format-Table -AutoSize

if ($bridgeService.StartType -ne "Automatic") { throw "TotalPosBridge no esta Automatic." }
if ($recoveryService.StartType -ne "Automatic") { throw "TechbotOpenpayRecovery no esta Automatic." }

Write-Host "=== HEALTH BRIDGE ==="
$bridge = Wait-JsonHealth "TotalPosBridge" $BridgeUrl $TimeoutSeconds
$bridge | Format-List
if ($bridge.status -ne "OK") { throw "Bridge status distinto de OK." }
if ($bridge.sdkInitialized -ne $true) { throw "Bridge SDK no inicializado." }
if ($bridge.bridgeVersion -ne $ExpectedBridgeVersion) {
    throw "Version Bridge inesperada: $($bridge.bridgeVersion); esperada $ExpectedBridgeVersion."
}

Write-Host "=== HEALTH RECOVERY ==="
$recovery = Wait-JsonHealth "TechbotOpenpayRecovery" $RecoveryUrl 10
$recovery | Format-List
if ($recovery.ok -ne $true) { throw "Recovery health no es OK." }
if ($recovery.version -ne $ExpectedRecoveryVersion) {
    throw "Version Recovery inesperada: $($recovery.version); esperada $ExpectedRecoveryVersion."
}

Write-Host "=== PUERTOS ==="
$ports = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in 9091,9092 } |
    Select-Object LocalAddress,LocalPort,OwningProcess
$ports | Format-Table -AutoSize
if (-not ($ports | Where-Object LocalPort -eq 9091)) { throw "Puerto 9091 no esta escuchando." }
if (-not ($ports | Where-Object LocalPort -eq 9092)) { throw "Puerto 9092 no esta escuchando." }

Write-Host "INSTALLATION_HEALTH=OK"
exit 0
