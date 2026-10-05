param([string]$ConfigPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\nodes.psd1'),[switch]$UpdateArtifacts)
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$config=Import-PowerShellDataFile $ConfigPath
$war=Join-Path $repoRoot 'target\AgriTrace.war'
if(-not (Test-Path $war)){ throw 'Build target\AgriTrace.war before preparing Tomcat bases.' }
$mysqlDriver=Join-Path $repoRoot 'target\AgriTrace\WEB-INF\lib\mysql-connector-j-8.4.0.jar'
if(-not (Test-Path $mysqlDriver)){ throw 'The built WAR is missing MySQL Connector/J 8.4.0.' }
if(-not (Test-Path (Join-Path $config.CatalinaHome 'bin\catalina.bat'))){ throw 'Configured CATALINA_HOME is not a Tomcat installation.' }
$serverTemplate=Get-Content -Raw (Join-Path $PSScriptRoot 'server.xml.template')
foreach($n in $config.Nodes){
 foreach($field in 'AppServerKeyStorePath','AppServerKeyStorePasswordFile','P2pServerKeyStorePath','P2pServerKeyStorePasswordFile','P2pClientTrustStorePath','P2pClientTrustStorePasswordFile','P2pKeyStorePath','P2pKeyStorePasswordFile','JavaPeerTrustStorePath','JavaPeerTrustStorePasswordFile'){
  if(-not (Test-Path -LiteralPath $n[$field])){ throw "Node $($n.Id) prerequisite file is missing: $field" }
 }
 $trustPassword=[IO.File]::ReadAllText($n.P2pClientTrustStorePasswordFile).Trim()
 if([string]::IsNullOrWhiteSpace($trustPassword)){throw 'P2P truststore password file is empty.'}
 $base=[IO.Path]::GetFullPath($n.CatalinaBase)
 if($base.StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase)){ throw 'Every CATALINA_BASE must be outside the repository.' }
 foreach($dir in 'conf','logs','temp','webapps','work'){ New-Item -ItemType Directory -Force -Path (Join-Path $base $dir) | Out-Null }
 $server=Join-Path $base 'conf\server.xml'
  $serverExists=Test-Path $server
  if($serverExists -and -not $UpdateArtifacts){ throw "Refusing to overwrite existing Tomcat config: $server" }
 $template=$serverTemplate
 $sourceConf=Join-Path $config.CatalinaHome 'conf'
 foreach($item in Get-ChildItem -LiteralPath $sourceConf -File){
  if($item.Name -eq 'server.xml'){continue}
  $target=Join-Path (Join-Path $base 'conf') $item.Name
  if(-not (Test-Path $target)){Copy-Item -LiteralPath $item.FullName -Destination $target}
 }
 $map=@{
  '@SHUTDOWN_PORT@'=$n.ShutdownPort; '@APP_PORT@'=$n.AppPort; '@P2P_PORT@'=$n.P2pPort
  '@APP_SERVER_KEYSTORE@'=$n.AppServerKeyStorePath; '@APP_SERVER_PASSWORD_FILE@'=$n.AppServerKeyStorePasswordFile
  '@P2P_SERVER_KEYSTORE@'=$n.P2pServerKeyStorePath; '@P2P_SERVER_PASSWORD_FILE@'=$n.P2pServerKeyStorePasswordFile
  '@P2P_CLIENT_TRUSTSTORE@'=$n.P2pClientTrustStorePath
  '@P2P_CLIENT_TRUSTSTORE_PASSWORD@'=$trustPassword
 }
 foreach($token in $map.Keys){ $escaped=[Security.SecurityElement]::Escape([string]$map[$token]); $template=$template.Replace($token,$escaped) }
 if($template -match '@[A-Z0-9_]+@'){ throw "Node $($n.Id) server.xml has unresolved values." }
  if(-not $serverExists){[IO.File]::WriteAllText($server,$template,[Text.UTF8Encoding]::new($false))}
  $commonLib=Join-Path $base 'lib'
  New-Item -ItemType Directory -Path $commonLib -Force | Out-Null
  $driverTarget=Join-Path $commonLib 'mysql-connector-j-8.4.0.jar'
  if(Test-Path $driverTarget){
   if((Get-FileHash -LiteralPath $mysqlDriver -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $driverTarget -Algorithm SHA256).Hash){throw "Node $($n.Id) has a different common-loader JDBC driver; refusing to overwrite."}
  }else{Copy-Item -LiteralPath $mysqlDriver -Destination $driverTarget}
 Copy-Item -LiteralPath $war -Destination (Join-Path $base 'webapps\AgriTrace.war')
}
Write-Host 'Prepared/updated external WAR artifacts and installed the matching MySQL Connector/J in each Tomcat common loader. Existing server.xml files were preserved; no Tomcat was started.'
