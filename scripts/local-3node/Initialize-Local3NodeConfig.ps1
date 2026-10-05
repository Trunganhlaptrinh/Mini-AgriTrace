param([string]$CatalinaHome)
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$stateRoot=Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node'
if([string]::IsNullOrWhiteSpace($CatalinaHome)){ throw 'Supply the installed Tomcat 10.1 CATALINA_HOME path; this script does not start Tomcat.' }
if(-not (Test-Path (Join-Path $CatalinaHome 'bin\catalina.bat'))){ throw 'CATALINA_HOME must contain bin\catalina.bat.' }
if([IO.Path]::GetFullPath($stateRoot).StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase)){ throw 'External state path must be outside the repository.' }
New-Item -ItemType Directory -Force -Path $stateRoot,(Join-Path $stateRoot 'secrets'),(Join-Path $stateRoot 'tomcat'),(Join-Path $stateRoot 'identity'),(Join-Path $stateRoot 'trust') | Out-Null
$template=Import-PowerShellDataFile (Join-Path $PSScriptRoot 'nodes.example.psd1')
$template.CatalinaHome=$CatalinaHome
$template.StateRoot=$stateRoot
foreach($n in $template.Nodes){ $id=$n.Id.ToLowerInvariant(); $n.P2pKeyStorePath=Join-Path $stateRoot ('identity\node-'+$id+'-peer.p12'); $n.AppServerKeyStorePath=Join-Path $stateRoot ('identity\node-'+$id+'-server.p12'); $n.AppServerKeyStorePasswordFile=Join-Path $stateRoot ('secrets\node-'+$id+'-server-password.txt'); $n.P2pServerKeyStorePath=$n.AppServerKeyStorePath; $n.P2pServerKeyStorePasswordFile=$n.AppServerKeyStorePasswordFile; $n.CatalinaBase=Join-Path $stateRoot ('tomcat\node-'+$id); $n.P2pKeyStorePasswordFile=Join-Path $stateRoot ('secrets\node-'+$id+'-peer-password.txt'); $n.P2pClientTrustStorePath=Join-Path $stateRoot ('trust\node-'+$id+'-peer-trust.p12'); $n.P2pClientTrustStorePasswordFile=Join-Path $stateRoot ('secrets\node-'+$id+'-peer-trust-password.txt'); $n.JavaPeerTrustStorePath=Join-Path $stateRoot ('trust\node-'+$id+'-server-trust.p12'); $n.JavaPeerTrustStorePasswordFile=Join-Path $stateRoot ('secrets\node-'+$id+'-server-trust-password.txt') }
$out=Join-Path $stateRoot 'nodes.psd1'
if(Test-Path $out){ throw "External node config already exists; review it instead of overwriting: $out" }
# Serialize a data-only PowerShell configuration; do not copy secrets or key files.
$quote={param($v) ([string]$v).Replace("'","''")}
$lines=@('@{',' SchemaVersion = 1',(" CatalinaHome = '{0}'" -f (& $quote $CatalinaHome)), (" StateRoot = '{0}'" -f (& $quote $stateRoot)),' Nodes = @(')
foreach($n in $template.Nodes){
  $fields=@('Id','Role','AppPort','P2pPort','DatabasePort','ShutdownPort','PeerId','OrganizationId','CatalinaBase','P2pKeyStorePath','P2pKeyStorePasswordFile','AppServerKeyStorePath','AppServerKeyStorePasswordFile','P2pServerKeyStorePath','P2pServerKeyStorePasswordFile','P2pClientTrustStorePath','P2pClientTrustStorePasswordFile','JavaPeerTrustStorePath','JavaPeerTrustStorePasswordFile')
  $parts=foreach($f in $fields){ "$f='$(& $quote $n[$f])'" }
  $lines += '  @{ '+($parts -join '; ')+' }'
}
$lines += ' )','}'
[IO.File]::WriteAllLines($out,$lines,[Text.UTF8Encoding]::new($false))
Write-Host "Created local configuration only: $out"
Write-Host 'No database, certificate, private key, or Tomcat process was created or started.'
