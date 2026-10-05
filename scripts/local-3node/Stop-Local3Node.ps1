param([ValidateSet('A','B','C','All')][string]$Node='All',[switch]$DatabaseOnly,[string]$ConfigPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\nodes.psd1'))
$ErrorActionPreference='Stop'; $config=Import-PowerShellDataFile $ConfigPath
$selected=if($Node -eq 'All'){$config.Nodes}else{@($config.Nodes|Where-Object Id -eq $Node)}
if(-not $selected){throw "No configured node matches $Node"}
if(-not $DatabaseOnly){foreach($n in $selected){$env:CATALINA_HOME=$config.CatalinaHome;$env:CATALINA_BASE=$n.CatalinaBase
  & (Join-Path $config.CatalinaHome 'bin\catalina.bat') stop
  $env:CATALINA_HOME=$null;$env:CATALINA_BASE=$null
  if($LASTEXITCODE -ne 0){Write-Warning "Tomcat stop command for node $($n.Id) returned $LASTEXITCODE"}
}}
$composeEnv=Join-Path $config.StateRoot 'compose.env'; $services=@($selected|ForEach-Object {'node-'+$_.Id.ToLowerInvariant()+'-db'})
& docker compose --env-file $composeEnv -f (Join-Path $PSScriptRoot 'compose.yaml') stop @services
if($LASTEXITCODE -ne 0){throw 'Compose stop failed.'}
Write-Host "Stopped only selected Tomcat/database services. Database volumes and CATALINA_BASE data were retained."
