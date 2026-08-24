param(
    [string]$ReleaseDir = (Split-Path $PSScriptRoot),
    [string[]]$ComposeFiles = @(
        "docker-compose.yml",
        "offline.runtime.override.yml",
        "release.runtime.override.yml",
        "real-business.override.yml"
    )
)

$ErrorActionPreference = "Stop"
Set-Location $ReleaseDir

function Invoke-Compose {
    param([string[]]$Arguments)
    $fileArgs = @()
    foreach ($file in $ComposeFiles) {
        $path = Join-Path $ReleaseDir $file
        if (-not (Test-Path $path)) {
            throw "Compose file missing: $path"
        }
        $fileArgs += @("-f", $path)
    }
    & docker compose @fileArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Wait-Healthy {
    param([string]$Container, [int]$Attempts = 120)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $status = (& docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $Container 2>$null)
        if ($LASTEXITCODE -eq 0 -and $status -eq "healthy") { return }
        Start-Sleep -Seconds 2
    }
    throw "Container did not become healthy: $Container"
}

function Assert-BackendEnvironment {
    param([string[]]$Names)
    foreach ($name in $Names) {
        $probe = 'test -n "$' + $name + '"'
        & docker exec zimu-fulfillment-backend-1 sh -lc $probe
        if ($LASTEXITCODE -ne 0) { throw "Required backend environment is missing: $name" }
    }
}

function Get-DotEnvValue {
    param([string]$Name)
    $envFile = Join-Path $ReleaseDir ".env"
    $line = Get-Content $envFile | Where-Object { $_ -match "^$([regex]::Escape($Name))=" } | Select-Object -Last 1
    if (-not $line) { return $null }
    return ($line -split '=', 2)[1].Trim()
}

function Assert-ConnectorConnection {
    param([string]$Channel, [string[]]$AcceptedCodes = @("OK"))
    $appPort = Get-DotEnvValue "APP_PORT"
    if (-not $appPort) { $appPort = "8088" }
    $username = Get-DotEnvValue "APP_ADMIN_USERNAME"
    if (-not $username) { $username = "zimu-admin" }
    $password = Get-DotEnvValue "APP_ADMIN_PASSWORD"
    if (-not $password) { throw "APP_ADMIN_PASSWORD is missing" }
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${username}:${password}"))
    $headers = @{
        Authorization = "Basic $basic"
        "Idempotency-Key" = "startup-$($Channel.ToLower())-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        "X-Operator" = "system:startup-script"
    }
    $result = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$appPort/api/v1/connectors/$Channel/test-connection" -Headers $headers
    if ($AcceptedCodes -notcontains $result.business_code) {
        throw "Connector $Channel is not live: $($result.business_code) - $($result.message)"
    }
    Write-Host "[zimu] connector live: $Channel ($($result.business_code))"
}

Write-Host "[zimu] starting database and cache"
Invoke-Compose -Arguments @("up", "-d", "postgres", "redis")
Wait-Healthy "zimu-fulfillment-postgres-1"

Write-Host "[zimu] starting REAL backend (no external write is triggered by this script)"
Invoke-Compose -Arguments @("up", "-d", "--no-deps", "backend")
Wait-Healthy "zimu-fulfillment-backend-1"

Assert-BackendEnvironment @(
    "CSX_USERNAME", "CSX_PASSWORD", "CSX_SUPPLIER_CODE",
    "JUFUBAO_USERNAME", "JUFUBAO_PASSWORD",
    "FEIXIANG_USERNAME", "FEIXIANG_PASSWORD",
    "WECOM_ENABLED", "WECOM_BOT_ID", "WECOM_SECRET"
)

$connectorSql = @"
UPDATE app.connector_configs
SET mode='REAL', transport_mode='API', enabled=true,
    lock_version=lock_version+1, updated_at=now()
WHERE source_channel IN ('CAISHIXIAN','JUFUBAO','FEIXIANG','WECOM')
  AND (mode, transport_mode, enabled) IS DISTINCT FROM ('REAL','API',true);
"@
& docker exec zimu-fulfillment-postgres-1 psql -v ON_ERROR_STOP=1 -U fulfillment -d fulfillment_hub -c $connectorSql
if ($LASTEXITCODE -ne 0) { throw "Failed to enable REAL connector configurations" }

Write-Host "[zimu] starting complete business stack"
Invoke-Compose -Arguments @("up", "-d")
Wait-Healthy "zimu-fulfillment-frontend-1"
Wait-Healthy "zimu-fulfillment-nginx-1"

& docker exec zimu-fulfillment-backend-1 sh -lc 'test "$JD_LOP_CLIENT_MODE" = REAL && test "$JD_LOP_WRITE_MODE" = ON'
if ($LASTEXITCODE -ne 0) { throw "REAL write gates are not active" }

Assert-ConnectorConnection "CAISHIXIAN"
Assert-ConnectorConnection "JUFUBAO"
Assert-ConnectorConnection "FEIXIANG"
Assert-ConnectorConnection "WECOM" @("WECOM_CONNECTION_ESTABLISHED")

$appPort = "8088"
$configuredPort = Get-DotEnvValue "APP_PORT"
if ($configuredPort) { $appPort = $configuredPort }
Write-Host "[zimu] READY: http://localhost:$appPort"
Write-Host "[zimu] JD Shipment write gate: REAL + ON"
Write-Host "[zimu] REAL connectors: CAISHIXIAN / JUFUBAO / FEIXIANG / WECOM"
Write-Host "[zimu] ZHONGHUI: deferred (MOCK + write OFF)"
Write-Warning "Gateway password minimum is deployment-controlled. This release explicitly accepts a 6-character password."
& docker ps --filter "name=zimu-fulfillment-" --format "table {{.Names}}`t{{.Status}}`t{{.Ports}}"
