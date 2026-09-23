param(
    [int]$TimeoutSeconds = 35,
    [int]$PollMilliseconds = 1000
)

$ErrorActionPreference = "Stop"
$BridgeUrl = "http://127.0.0.1:9091/api/health"
$RecoveryUrl = "http://127.0.0.1:9092/health"

function Wait-Http200([string]$Name, [string]$Url, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastError = $null

    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $r = Invoke-WebRequest -UseBasicParsing -Uri $Url -Method GET -TimeoutSec 5
            if ([int]$r.StatusCode -eq 200) {
                Write-Host "$Name OK: HTTP 200"
                Write-Host $r.Content
                return $true
            }
            $lastError = "HTTP $([int]$r.StatusCode)"
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    }

    Write-Host "$Name FAIL: $lastError"
    return $false
}

Write-Host "Verificando TotalPosBridge..."
$bridgeOk = Wait-Http200 "TotalPosBridge" $BridgeUrl $TimeoutSeconds

Write-Host "Verificando TechbotOpenpayRecovery..."
$recoveryOk = Wait-Http200 "TechbotOpenpayRecovery" $RecoveryUrl 10

if ($bridgeOk -and $recoveryOk) {
    Write-Host "INSTALLATION_HEALTH=OK"
    exit 0
}

Write-Host "INSTALLATION_HEALTH=FAIL"
exit 1
