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
foreach($n in $template.Nodes){ $n.CatalinaBase=Join-Path $stateRoot ('tomcat\node-'+$n.Id.ToLowerInvariant()) }
$out=Join-Path $stateRoot 'nodes.psd1'
if(Test-Path $out){ throw "External node config already exists; review it instead of overwriting: $out" }
# Serialize a data-only PowerShell configuration; do not copy secrets or key files.
$quote={param($v) ([string]$v).Replace("'","''")}
$lines=@('@{',' SchemaVersion = 1',(" CatalinaHome = '{0}'" -f (& $quote $CatalinaHome)), (" StateRoot = '{0}'" -f (& $quote $stateRoot)),' Nodes = @(')
foreach($n in $template.Nodes){
  $fields=@('Id','Role','AppPort','P2pPort','DatabasePort','ShutdownPort','PeerId','OrganizationId','CatalinaBase','P2pKeyStorePath','AppServerKeyStorePath','AppServerKeyStorePasswordFile','P2pServerKeyStorePath','P2pServerKeyStorePasswordFile','P2pClientTrustStorePath','P2pClientTrustStorePassword','JavaPeerTrustStorePath','JavaPeerTrustStorePassword')
  $parts=foreach($f in $fields){ "$f='$(& $quote $n[$f])'" }
  $lines += '  @{ '+($parts -join '; ')+' }'
}
$lines += ' )','}'
[IO.File]::WriteAllLines($out,$lines,[Text.UTF8Encoding]::new($false))
Write-Host "Created local configuration only: $out"
Write-Host 'No database, certificate, private key, or Tomcat process was created or started.'
