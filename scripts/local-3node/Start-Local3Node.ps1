param(
    [ValidateSet('A','B','C','All')][string]$Node='All',
    [switch]$DatabaseOnly,
    [string]$ConfigPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\nodes.psd1')
)
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$config=Import-PowerShellDataFile $ConfigPath
$selected=if($Node -eq 'All'){$config.Nodes}else{@($config.Nodes|Where-Object Id -eq $Node)}
if(-not $selected){throw "No configured node matches $Node"}
$war=Join-Path $repoRoot 'target\AgriTrace.war'
if(-not $DatabaseOnly){
  if(-not (Test-Path $war)){throw 'Build target\AgriTrace.war before starting Tomcat.'}
  foreach($n in $selected){
    $base=[IO.Path]::GetFullPath($n.CatalinaBase)
    if(-not (Test-Path (Join-Path $base 'conf\server.xml')) -or -not (Test-Path (Join-Path $base 'webapps\AgriTrace.war'))){throw "Tomcat base for node $($n.Id) is not prepared. No services started."}
    foreach($f in $n.AppServerKeyStorePath,$n.P2pServerKeyStorePath,$n.P2pClientTrustStorePath,$n.P2pKeyStorePath,$n.JavaPeerTrustStorePath){if(-not (Test-Path -LiteralPath $f)){throw "Node $($n.Id) identity/trust prerequisite missing. No services started."}}
  }
}
$composeFile=Join-Path $PSScriptRoot 'compose.yaml'
$composeEnv=Join-Path $config.StateRoot 'compose.env'
if(-not (Test-Path $composeEnv)){throw "External Compose env missing. Run New-Local3NodeSecrets.ps1 first: $composeEnv"}
$envLines=Get-Content -LiteralPath $composeEnv
foreach($line in $envLines){if($line -match '^([^=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
if(-not $env:AGRITRACE_LOCAL3NODE_SECRET_DIR){throw 'AGRITRACE_LOCAL3NODE_SECRET_DIR is missing from external compose.env.'}
foreach($nodeConfig in $selected){
  foreach($kind in 'app','root'){
    $secret=Join-Path $env:AGRITRACE_LOCAL3NODE_SECRET_DIR ("node-{0}-{1}-password.txt" -f $nodeConfig.Id.ToLowerInvariant(),$kind)
    if(-not (Test-Path -LiteralPath $secret)){throw "Missing external DB secret for node $($nodeConfig.Id)."}
  }
  $socketPorts=@($nodeConfig.DatabasePort)
  if(-not $DatabaseOnly){$socketPorts+=@($nodeConfig.AppPort,$nodeConfig.P2pPort,$nodeConfig.ShutdownPort)}
  foreach($port in $socketPorts){
    $listener=Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if($listener){$owner=Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)" -ErrorAction SilentlyContinue; throw "Port $port for node $($nodeConfig.Id) is already in use by PID $($listener.OwningProcess) ($($owner.Name)). Nothing was stopped."}
  }
}
$serviceNames=@($selected|ForEach-Object { 'node-'+$_.Id.ToLowerInvariant()+'-db' })
& docker compose --env-file $composeEnv -f $composeFile up -d --wait @serviceNames
if($LASTEXITCODE -ne 0){throw 'Isolated local MySQL containers did not become healthy.'}
if($DatabaseOnly){Write-Host "Started only the isolated local database service(s): $($serviceNames -join ', ')"; exit 0}
foreach($n in $selected){
  $base=[IO.Path]::GetFullPath($n.CatalinaBase)
  $server=Join-Path $base 'conf\server.xml'
  if(-not (Test-Path $server) -or -not (Test-Path (Join-Path $base 'webapps\AgriTrace.war'))){throw "Tomcat base for node $($n.Id) is not prepared. Run Prepare-TomcatBases.ps1 after provisioning identities."}
  $dbPasswordFile=Join-Path $env:AGRITRACE_LOCAL3NODE_SECRET_DIR ("node-{0}-app-password.txt" -f $n.Id.ToLowerInvariant())
  $dbPassword=[IO.File]::ReadAllText($dbPasswordFile).Trim()
  $p2pPlain=[IO.File]::ReadAllText($n.P2pKeyStorePasswordFile).Trim()
  $javaTrustPassword=[IO.File]::ReadAllText($n.JavaPeerTrustStorePasswordFile).Trim()
  $env:AGRITRACE_DB_URL="jdbc:mysql://127.0.0.1:$($n.DatabasePort)/agritrace?serverTimezone=UTC"
  $env:AGRITRACE_DB_USERNAME='agritrace_app'; $env:AGRITRACE_DB_PASSWORD=$dbPassword
  $env:AGRITRACE_P2P_PEER_ID=$n.PeerId; $env:AGRITRACE_P2P_KEYSTORE_PATH=$n.P2pKeyStorePath; $env:AGRITRACE_P2P_KEYSTORE_PASSWORD=$p2pPlain
  $env:JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$($n.JavaPeerTrustStorePath) -Djavax.net.ssl.trustStorePassword=$javaTrustPassword -Djavax.net.ssl.trustStoreType=PKCS12"
  $env:CATALINA_HOME=$config.CatalinaHome; $env:CATALINA_BASE=$base
  try{
    $proc=Start-Process -FilePath "$env:ComSpec" -ArgumentList @('/c','call',('"'+(Join-Path $config.CatalinaHome 'bin\catalina.bat')+'"'),'start') -WorkingDirectory $config.CatalinaHome -WindowStyle Hidden -PassThru
    Write-Host "Requested Tomcat start for node $($n.Id) (launcher PID $($proc.Id))."
  }finally{
    foreach($name in 'AGRITRACE_DB_PASSWORD','AGRITRACE_P2P_KEYSTORE_PASSWORD','JAVA_TOOL_OPTIONS','AGRITRACE_DB_URL','AGRITRACE_DB_USERNAME','AGRITRACE_P2P_PEER_ID','AGRITRACE_P2P_KEYSTORE_PATH','CATALINA_HOME','CATALINA_BASE'){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
    $dbPassword=$null;$p2pPlain=$null;$javaTrustPassword=$null
  }
}
Write-Host 'Tomcat start was requested only for selected local nodes; verify status/logs before use.'
