param(
    [Parameter(Mandatory = $true)][string]$SenderUrl,
    [Parameter(Mandatory = $true)][string]$SenderPfx,
    [Parameter(Mandatory = $true)][string]$CarrierUrl,
    [Parameter(Mandatory = $true)][string]$CarrierPfx,
    [Parameter(Mandatory = $true)][string]$OtherUrl,
    [Parameter(Mandatory = $true)][string]$OtherPfx,
    [string]$ExpectedPendingTransactionId,
    [ValidateRange(30, 1800)][int]$ConvergenceTimeoutSeconds = 180,
    [ValidateRange(2, 120)][int]$PollIntervalSeconds = 10
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function New-NodeIdentity {
    param([string]$Name, [string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Name PKCS#12 file was not found: $Path"
    }
    $securePassword = Read-Host "Password for $Name PKCS#12 identity" -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
            (Resolve-Path -LiteralPath $Path).Path,
            $password,
            [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
        )
        if (-not $certificate.HasPrivateKey) {
            $certificate.Dispose()
            throw "$Name PKCS#12 identity has no private key."
        }
        return [pscustomobject]@{ Name = $Name; Certificate = $certificate }
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
        $password = $null
    }
}

function Invoke-NodeGet {
    param(
        [string]$BaseUrl,
        [Security.Cryptography.X509Certificates.X509Certificate2]$Certificate,
        [string]$Path
    )

    $uri = $BaseUrl.TrimEnd("/") + $Path
    return Invoke-RestMethod -Method Get -Uri $uri -Certificate $Certificate -TimeoutSec 20
}

function Get-NodeSnapshot {
    param(
        [string]$TargetUrl,
        [Security.Cryptography.X509Certificates.X509Certificate2]$SourceCertificate
    )

    $network = Invoke-NodeGet $TargetUrl $SourceCertificate "/api/v1/network"
    $locatorResponse = Invoke-NodeGet $TargetUrl $SourceCertificate "/api/v1/internal/p2p/chain/locator"
    $blocks = @($locatorResponse.data.blocks)
    if ($blocks.Count -eq 0) {
        throw "Node at $TargetUrl returned an empty canonical-chain locator."
    }
    $tip = $blocks[$blocks.Count - 1]
    return [pscustomobject]@{
        NetworkId = [string]$network.data.networkId
        Height = [long]$tip.height
        Hash = [string]$tip.hash
    }
}

function Test-PendingTransaction {
    param(
        [string]$TargetUrl,
        [Security.Cryptography.X509Certificates.X509Certificate2]$SourceCertificate,
        [string]$TransactionId
    )

    $after = $null
    for ($page = 0; $page -lt 100; $page++) {
        $path = "/api/v1/internal/p2p/transactions/pending"
        if ($after) {
            $path += "?after=$after"
        }
        $result = Invoke-NodeGet $TargetUrl $SourceCertificate $path
        foreach ($transaction in @($result.data.transactions)) {
            if ($transaction.transactionId -eq $TransactionId) {
                return $true
            }
        }
        $after = [string]$result.data.nextAfter
        if ([string]::IsNullOrWhiteSpace($after)) {
            return $false
        }
    }
    throw "Pending transaction scan exceeded 100 pages at $TargetUrl."
}

$nodes = @(
    [pscustomobject]@{ Name = "sender"; Url = $SenderUrl; Pfx = $SenderPfx },
    [pscustomobject]@{ Name = "carrier"; Url = $CarrierUrl; Pfx = $CarrierPfx },
    [pscustomobject]@{ Name = "other"; Url = $OtherUrl; Pfx = $OtherPfx }
)
$identities = @{}

try {
    foreach ($node in $nodes) {
        if (-not $node.Url.StartsWith("https://", [StringComparison]::OrdinalIgnoreCase)) {
            throw "$($node.Name) URL must use HTTPS."
        }
        $identities[$node.Name] = New-NodeIdentity $node.Name $node.Pfx
    }

    foreach ($source in $nodes) {
        foreach ($target in $nodes) {
            if ($source.Name -eq $target.Name) {
                continue
            }
            $snapshot = Get-NodeSnapshot $target.Url $identities[$source.Name].Certificate
            Write-Host ("mTLS OK: {0} -> {1}, network={2}, tip={3}/{4}" -f `
                $source.Name, $target.Name, $snapshot.NetworkId, $snapshot.Height, $snapshot.Hash)
        }
    }

    if ($ExpectedPendingTransactionId -and
            $ExpectedPendingTransactionId -notmatch "^[0-9a-f]{64}$") {
        throw "ExpectedPendingTransactionId must be a lowercase 64-character transaction ID."
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($ConvergenceTimeoutSeconds)
    $converged = $false
    do {
        $snapshots = @{}
        foreach ($target in $nodes) {
            $source = @($nodes | Where-Object { $_.Name -ne $target.Name })[0]
            $snapshots[$target.Name] = Get-NodeSnapshot `
                $target.Url $identities[$source.Name].Certificate
        }
        $networkIds = @($snapshots.Values | ForEach-Object { $_.NetworkId } | Select-Object -Unique)
        if ($networkIds.Count -ne 1) {
            throw "The three nodes do not report the same network ID."
        }

        $tipKeys = @($snapshots.Values | ForEach-Object { "$($_.Height):$($_.Hash)" } | Select-Object -Unique)
        $converged = $tipKeys.Count -eq 1
        foreach ($target in $nodes) {
            $snapshot = $snapshots[$target.Name]
            Write-Host ("tip {0}: {1}/{2}" -f $target.Name, $snapshot.Height, $snapshot.Hash)
        }

        if ($ExpectedPendingTransactionId) {
            foreach ($target in $nodes) {
                $source = @($nodes | Where-Object { $_.Name -ne $target.Name })[0]
                if (-not (Test-PendingTransaction `
                        $target.Url `
                        $identities[$source.Name].Certificate `
                        $ExpectedPendingTransactionId)) {
                    $converged = $false
                    Write-Host "Pending transaction has not reached $($target.Name) yet."
                }
            }
        }

        if (-not $converged -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Seconds $PollIntervalSeconds
        }
    } while (-not $converged -and [DateTime]::UtcNow -lt $deadline)

    if (-not $converged) {
        throw "The nodes did not converge before the timeout. Inspect peer registrations, node logs, and fork-choice results."
    }
    Write-Host "PASS: all nodes report the same canonical tip."
    if ($ExpectedPendingTransactionId) {
        Write-Host "PASS: the expected transaction is pending on all three nodes."
    }
}
finally {
    foreach ($identity in $identities.Values) {
        if ($identity.Certificate) {
            $identity.Certificate.Dispose()
        }
    }
}
