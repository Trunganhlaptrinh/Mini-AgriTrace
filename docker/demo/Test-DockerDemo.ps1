[CmdletBinding()]
param(
    [string]$NodeAUrl = "https://localhost:8443/AgriTrace",
    [string]$NodeBUrl = "https://localhost:8444/AgriTrace",
    [string]$NodeCUrl = "https://localhost:8445/AgriTrace",
    [string]$P2pAUrl = "https://localhost:9443/AgriTrace",
    [string]$AdminAPassword = "AdminA@123456",
    [string]$AdminBPassword = "AdminB@123456",
    [string]$AdminCPassword = "AdminC@123456"
)

$ErrorActionPreference = "Stop"

# Allow untrusted SSL for local self-signed demo certs if .NET is used
[System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " AgriTrace Docker 3-Node Acceptance Test Runner (PowerShell)" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$results = @()

function Add-TestResult([string]$Category, [string]$TestName, [bool]$Passed, [string]$Details = "") {
    $script:results += [PSCustomObject]@{
        Category = $Category
        Test     = $TestName
        Status   = if ($Passed) { "PASS" } else { "FAIL" }
        Details  = $Details
    }
    if ($Passed) {
        Write-Host "  [PASS] $TestName" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] $TestName : $Details" -ForegroundColor Red
    }
}

function Invoke-Curl {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Url,
        [string]$Method = "GET",
        [string]$Body = $null,
        [hashtable]$Headers = @{}
    )
    $curlArgs = @("-sk", "-X", $Method)
    foreach ($k in $Headers.Keys) {
        $curlArgs += @("-H", "$($k): $($Headers[$k])")
    }
    if ($null -ne $Body -and $Body -ne "") {
        $curlArgs += @("-d", $Body)
    }
    $curlArgs += $Url
    $output = & curl.exe @curlArgs 2>$null
    return ($output -join "`n")
}

function Get-CurlStatusCode {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Url
    )
    $code = & curl.exe -sk -o "NUL" -w "%{http_code}" $Url 2>$null
    return [string]$code
}

# 1. Wait for services to respond
Write-Host "`n[1/5] Checking Node Reachability..." -ForegroundColor Yellow
$nodes = @(
    @{ Name = "Node A (Farmer)"; Url = $NodeAUrl },
    @{ Name = "Node B (Carrier)"; Url = $NodeBUrl },
    @{ Name = "Node C (Retailer)"; Url = $NodeCUrl }
)

foreach ($node in $nodes) {
    Write-Host "  Waiting for $($node.Name) at $($node.Url) ... " -NoNewline
    $ready = $false
    $statusCode = ""
    for ($i = 0; $i -lt 30; $i++) {
        $statusCode = Get-CurlStatusCode -Url "$($node.Url)/"
        if ($statusCode -eq "200" -or $statusCode -eq "302") {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if ($ready) {
        Write-Host "UP (HTTP $statusCode)" -ForegroundColor Green
        Add-TestResult "Reachability" "$($node.Name) HTTPS endpoint reachable" $true "HTTP $statusCode"
    } else {
        Write-Host "TIMEOUT" -ForegroundColor Red
        Add-TestResult "Reachability" "$($node.Name) HTTPS endpoint reachable" $false "Failed to respond within 60s (status: $statusCode)"
    }
}

# 2. Verify Consortium Network Identity
Write-Host "`n[2/5] Verifying Consortium Network Identity..." -ForegroundColor Yellow
foreach ($node in $nodes) {
    try {
        $raw = Invoke-Curl -Url "$($node.Url)/api/v1/network"
        $json = $raw | ConvertFrom-Json
        $netId = $json.data.networkId
        $match = ($netId -eq "agritrace-docker-demo-v1")
        Add-TestResult "Consortium" "$($node.Name) networkId verification" $match "networkId: $netId"
    } catch {
        Add-TestResult "Consortium" "$($node.Name) networkId verification" $false $_.Exception.Message
    }
}

# 3. Authenticate local administrator accounts
Write-Host "`n[3/5] Testing Local Administrator Authentication..." -ForegroundColor Yellow
$adminCredentials = @(
    @{ Name = "Node A"; Url = $NodeAUrl; User = "admin-a"; Pass = $AdminAPassword },
    @{ Name = "Node B"; Url = $NodeBUrl; User = "admin-b"; Pass = $AdminBPassword },
    @{ Name = "Node C"; Url = $NodeCUrl; User = "admin-c"; Pass = $AdminCPassword }
)

foreach ($cred in $adminCredentials) {
    try {
        $body = "{`"username`":`"$($cred.User)`",`"password`":`"$($cred.Pass)`"}"
        $raw = Invoke-Curl -Url "$($cred.Url)/api/v1/auth/login" -Method "POST" -Body $body -Headers @{ "Content-Type" = "application/json" }
        $json = $raw | ConvertFrom-Json
        $role = $json.data.role
        $match = ($role -eq "ADMIN")
        Add-TestResult "Authentication" "$($cred.Name) login for $($cred.User)" $match "role: $role"
    } catch {
        Add-TestResult "Authentication" "$($cred.Name) login for $($cred.User)" $false $_.Exception.Message
    }
}

# 4. Verify P2P mTLS Port Security
Write-Host "`n[4/5] Testing P2P mTLS Port Security..." -ForegroundColor Yellow
$p2pCode = Get-CurlStatusCode -Url "$P2pAUrl/api/v1/internal/p2p/blocks"
# curl fails handshake or returns 000, 401, 403
if ($p2pCode -eq "000" -or $p2pCode -eq "401" -or $p2pCode -eq "403" -or [string]::IsNullOrEmpty($p2pCode)) {
    Add-TestResult "Security" "P2P port rejects non-mTLS requests" $true "Correctly refused client without certificate (status: $p2pCode)"
} else {
    Add-TestResult "Security" "P2P port rejects non-mTLS requests" $false "Unexpectedly succeeded with HTTP $p2pCode"
}

# 5. Public Trace QR generation
Write-Host "`n[5/5] Testing Public QR Trace Endpoint..." -ForegroundColor Yellow
try {
    $qrOutput = Invoke-Curl -Url "$NodeAUrl/api/v1/public/trace-qr/DEMO-BATCH-001"
    $isSvg = ($qrOutput -match "<svg")
    Add-TestResult "Public API" "Public trace QR SVG endpoint returns 200 with SVG data" $isSvg "Contains SVG: $isSvg"
} catch {
    Add-TestResult "Public API" "Public trace QR SVG endpoint returns 200 with SVG data" $false $_.Exception.Message
}

# Summary
Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host " Acceptance Test Results Summary:" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
$results | Format-Table -AutoSize

$failedCount = ($results | Where-Object { $_.Status -eq "FAIL" }).Count
if ($failedCount -gt 0) {
    Write-Host "`nAcceptance test FAILED: $failedCount checks failed." -ForegroundColor Red
    exit 1
} else {
    Write-Host "`nAll acceptance tests PASSED successfully." -ForegroundColor Green
    exit 0
}
