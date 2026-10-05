param(
    [Parameter(Mandatory = $true)][string]$SenderUrl,
    [Parameter(Mandatory = $true)][string]$SenderPfx,
    [Parameter(Mandatory = $true)][string]$SenderTrustStore,
    [Parameter(Mandatory = $true)][string]$CarrierUrl,
    [Parameter(Mandatory = $true)][string]$CarrierPfx,
    [Parameter(Mandatory = $true)][string]$CarrierTrustStore,
    [Parameter(Mandatory = $true)][string]$OtherUrl,
    [Parameter(Mandatory = $true)][string]$OtherPfx,
    [Parameter(Mandatory = $true)][string]$OtherTrustStore,
    [string]$SenderPfxPasswordFile,
    [string]$SenderTrustPasswordFile,
    [string]$CarrierPfxPasswordFile,
    [string]$CarrierTrustPasswordFile,
    [string]$OtherPfxPasswordFile,
    [string]$OtherTrustPasswordFile,
    [string]$ExpectedPendingTransactionId,
    [ValidateRange(30, 1800)][int]$ConvergenceTimeoutSeconds = 180,
    [ValidateRange(2, 120)][int]$PollIntervalSeconds = 10
)

$ErrorActionPreference = "Stop"

function Read-StorePassword {
    param([string]$Name, [string]$PasswordFile)

    if ($PasswordFile) {
        if (-not (Test-Path -LiteralPath $PasswordFile -PathType Leaf)) {
            throw "$Name password file was not found: $PasswordFile"
        }
        $value = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $PasswordFile).Path).TrimEnd("`r", "`n")
        if ([string]::IsNullOrEmpty($value)) {
            throw "$Name password file is empty."
        }
        return $value
    }

    $securePassword = Read-Host "Password for $Name" -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
        $securePassword.Dispose()
    }
}

function New-NodeIdentity {
    param(
        [string]$Name,
        [string]$Path,
        [string]$PasswordFile,
        [string]$TrustStorePath,
        [string]$TrustPasswordFile
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Name PKCS#12 file was not found: $Path"
    }
    if (-not (Test-Path -LiteralPath $TrustStorePath -PathType Leaf)) {
        throw "$Name outbound truststore was not found: $TrustStorePath"
    }

    $password = Read-StorePassword "$Name PKCS#12 identity" $PasswordFile
    $trustPassword = $null
    $certificate = $null
    $trustCertificates = $null
    $handler = $null
    $client = $null
    try {
        $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
            (Resolve-Path -LiteralPath $Path).Path,
            $password,
            # Windows Schannel cannot use this PFX's ephemeral key handle as a client credential.
            # UserKeySet without PersistKeySet keeps the imported key tied to this certificate lifetime.
            [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::UserKeySet
        )
        if (-not $certificate.HasPrivateKey) {
            throw "$Name PKCS#12 identity has no private key."
        }

        $trustPassword = Read-StorePassword "$Name outbound truststore" $TrustPasswordFile
        $trustCertificates = [Security.Cryptography.X509Certificates.X509Certificate2Collection]::new()
        $trustCertificates.Import(
            (Resolve-Path -LiteralPath $TrustStorePath).Path,
            $trustPassword,
            [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
        )
        if ($trustCertificates.Count -eq 0) {
            throw "$Name outbound truststore contains no trust anchors."
        }

        # Use the node's configured Java outbound trust anchors. The local demo CA is not
        # installed in Windows Root; custom-root validation still checks chain, EKU and hostname.
        # Java's default trust manager does not enable revocation checking, and this local CA
        # publishes no revocation service.
        $chainPolicy = [Security.Cryptography.X509Certificates.X509ChainPolicy]::new()
        $chainPolicy.TrustMode = [Security.Cryptography.X509Certificates.X509ChainTrustMode]::CustomRootTrust
        $chainPolicy.RevocationMode = [Security.Cryptography.X509Certificates.X509RevocationMode]::NoCheck
        foreach ($trustCertificate in $trustCertificates) {
            $chainPolicy.CustomTrustStore.Add($trustCertificate) | Out-Null
        }
        $chainPolicy.ApplicationPolicy.Add([Security.Cryptography.Oid]::new("1.3.6.1.5.5.7.3.1")) | Out-Null

        $handler = [System.Net.Http.SocketsHttpHandler]::new()
        $handler.AllowAutoRedirect = $false
        $handler.SslOptions.ClientCertificates = [Security.Cryptography.X509Certificates.X509CertificateCollection]::new()
        $handler.SslOptions.ClientCertificates.Add($certificate) | Out-Null
        $handler.SslOptions.CertificateChainPolicy = $chainPolicy
        $client = [System.Net.Http.HttpClient]::new($handler)
        $client.Timeout = [TimeSpan]::FromSeconds(20)

        return [pscustomobject]@{
            Name = $Name
            Certificate = $certificate
            TrustCertificates = $trustCertificates
            Handler = $handler
            Client = $client
        }
    }
    finally {
        $password = $null
        $trustPassword = $null
        if (-not $client) {
            if ($handler) { $handler.Dispose() }
            if ($certificate) { $certificate.Dispose() }
            if ($trustCertificates) { $trustCertificates.Dispose() }
        }
    }
}

function Invoke-NodeGet {
    param(
        [string]$BaseUrl,
        [object]$Identity,
        [string]$Path
    )

    $uri = $BaseUrl.TrimEnd("/") + $Path
    $response = $null
    try {
        $response = $Identity.Client.GetAsync($uri).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "GET $uri returned HTTP $([int]$response.StatusCode)."
        }
        return $body | ConvertFrom-Json
    }
    finally {
        if ($response) { $response.Dispose() }
    }
}

function Get-NodeSnapshot {
    param(
        [string]$TargetUrl,
        [object]$SourceIdentity
    )

    $network = Invoke-NodeGet $TargetUrl $SourceIdentity "/api/v1/network"
    $locatorResponse = Invoke-NodeGet $TargetUrl $SourceIdentity "/api/v1/internal/p2p/chain/locator"
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
        [object]$SourceIdentity,
        [string]$TransactionId
    )

    $after = $null
    for ($page = 0; $page -lt 100; $page++) {
        $path = "/api/v1/internal/p2p/transactions/pending"
        if ($after) {
            $path += "?after=$after"
        }
        $result = Invoke-NodeGet $TargetUrl $SourceIdentity $path
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
    [pscustomobject]@{ Name = "sender"; Url = $SenderUrl; Pfx = $SenderPfx; TrustStore = $SenderTrustStore; PfxPasswordFile = $SenderPfxPasswordFile; TrustPasswordFile = $SenderTrustPasswordFile },
    [pscustomobject]@{ Name = "carrier"; Url = $CarrierUrl; Pfx = $CarrierPfx; TrustStore = $CarrierTrustStore; PfxPasswordFile = $CarrierPfxPasswordFile; TrustPasswordFile = $CarrierTrustPasswordFile },
    [pscustomobject]@{ Name = "other"; Url = $OtherUrl; Pfx = $OtherPfx; TrustStore = $OtherTrustStore; PfxPasswordFile = $OtherPfxPasswordFile; TrustPasswordFile = $OtherTrustPasswordFile }
)
$identities = @{}

try {
    foreach ($node in $nodes) {
        if (-not $node.Url.StartsWith("https://", [StringComparison]::OrdinalIgnoreCase)) {
            throw "$($node.Name) URL must use HTTPS."
        }
        $identities[$node.Name] = New-NodeIdentity `
            $node.Name $node.Pfx $node.PfxPasswordFile $node.TrustStore $node.TrustPasswordFile
    }

    foreach ($source in $nodes) {
        foreach ($target in $nodes) {
            if ($source.Name -eq $target.Name) {
                continue
            }
            $snapshot = Get-NodeSnapshot $target.Url $identities[$source.Name]
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
                $target.Url $identities[$source.Name]
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
                        $identities[$source.Name] `
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
        if ($identity.Client) { $identity.Client.Dispose() }
        if ($identity.Certificate) { $identity.Certificate.Dispose() }
        if ($identity.TrustCertificates) {
            foreach ($trustCertificate in $identity.TrustCertificates) {
                $trustCertificate.Dispose()
            }
            $identity.TrustCertificates.Clear()
        }
    }
}
