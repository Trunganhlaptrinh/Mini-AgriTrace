[CmdletBinding()]
param([string]$ConfigPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\nodes.psd1'),[ValidateRange(5,120)][int]$InterruptionSeconds=10,[ValidateRange(30,300)][int]$RecoveryTimeoutSeconds=180)
$ErrorActionPreference='Stop';$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path;$scripts=Join-Path $root 'scripts';$cfg=Import-PowerShellDataFile $ConfigPath;$a=$cfg.Nodes|Where-Object Id -eq 'A';$b=$cfg.Nodes|Where-Object Id -eq 'B';$c=$cfg.Nodes|Where-Object Id -eq 'C'
function Listening([int]$p){[bool](Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue|Select-Object -First 1)}
function WaitC{$end=[DateTime]::UtcNow.AddSeconds($RecoveryTimeoutSeconds);do{if((Listening $c.AppPort)-and(Listening $c.P2pPort)){return};Start-Sleep 2}while([DateTime]::UtcNow -lt $end);throw 'Node C did not recover.'}
$down=$false
try{
 foreach($p in @($a.AppPort,$a.P2pPort,$b.AppPort,$b.P2pPort,$c.AppPort,$c.P2pPort)){if(-not(Listening $p)){throw "Node listener missing before test: $p"}}
 & (Join-Path $scripts 'local-3node\Stop-Local3Node.ps1') -Node C -TomcatOnly -ConfigPath $ConfigPath;if($LASTEXITCODE -ne 0){throw 'Node C Tomcat stop failed'};$down=$true;Start-Sleep $InterruptionSeconds
 foreach($p in @($a.AppPort,$a.P2pPort,$b.AppPort,$b.P2pPort,$a.DatabasePort,$b.DatabasePort,$c.DatabasePort)){if(-not(Listening $p)){throw "Expected remaining listener unavailable during interruption: $p"}}
 if((Listening $c.AppPort)-or(Listening $c.P2pPort)){throw 'Node C Tomcat still listening during interruption'}
 Write-Host 'PASS: A/B app and P2P listeners remained up; all three isolated DB listeners remained up while C Tomcat was interrupted.'
 & (Join-Path $scripts 'local-3node\Start-Local3Node.ps1') -Node C -ConfigPath $ConfigPath;if($LASTEXITCODE -ne 0){throw 'Node C restart failed'};WaitC;$down=$false
 & (Join-Path $scripts 'acceptance\Test-MultiNodeP2P.ps1') -SenderUrl "https://127.0.0.1:$($a.P2pPort)/AgriTrace" -SenderPfx $a.P2pKeyStorePath -SenderPfxPasswordFile $a.P2pKeyStorePasswordFile -SenderTrustStore $a.P2pClientTrustStorePath -SenderTrustPasswordFile $a.P2pClientTrustStorePasswordFile -CarrierUrl "https://127.0.0.1:$($b.P2pPort)/AgriTrace" -CarrierPfx $b.P2pKeyStorePath -CarrierPfxPasswordFile $b.P2pKeyStorePasswordFile -CarrierTrustStore $b.P2pClientTrustStorePath -CarrierTrustPasswordFile $b.P2pClientTrustStorePasswordFile -OtherUrl "https://127.0.0.1:$($c.P2pPort)/AgriTrace" -OtherPfx $c.P2pKeyStorePath -OtherPfxPasswordFile $c.P2pKeyStorePasswordFile -OtherTrustStore $c.P2pClientTrustStorePath -OtherTrustPasswordFile $c.P2pClientTrustStorePasswordFile -ConvergenceTimeoutSeconds $RecoveryTimeoutSeconds -PollIntervalSeconds 5
 if($LASTEXITCODE -ne 0){throw 'P2P recovery/convergence acceptance failed'};Write-Host 'PASS: Node C recovered; six mTLS paths and canonical chain convergence passed.'
}finally{if($down -and -not(Listening $c.AppPort)){Write-Warning 'Attempting to restore Node C Tomcat.';& (Join-Path $scripts 'local-3node\Start-Local3Node.ps1') -Node C -ConfigPath $ConfigPath}}
