<#
.SYNOPSIS
    Runs automated browser End-to-End smoke test for the AgriTrace web application.
.DESCRIPTION
    Launches headless Google Chrome via CDP, serving the webapp and mock backend APIs,
    exercising the full Farmer -> Carrier -> Retailer product journey, Web Crypto P-256 signing,
    proposal inbox, transaction status tracking, and public trace/QR verification.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$testScript = Join-Path $scriptDir 'Test-BrowserE2E.js'

if (-not (Get-Command 'node' -ErrorAction SilentlyContinue)) {
    throw 'Node.js is required to execute the browser E2E test suite.'
}

Write-Host "Executing AgriTrace Browser E2E Acceptance Test ($testScript)..." -ForegroundColor Cyan
& node $testScript

if ($LASTEXITCODE -ne 0) {
    throw "Browser E2E test failed with exit code $LASTEXITCODE."
}
Write-Host "PASS: All browser E2E acceptance scenarios passed." -ForegroundColor Green
