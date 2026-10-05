param([ValidateSet('A','B','C','All')][string]$Node='All',[switch]$DatabaseOnly,[switch]$TomcatOnly,[string]$ConfigPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\nodes.psd1'))
$ErrorActionPreference='Stop'; $config=Import-PowerShellDataFile $ConfigPath
if($DatabaseOnly -and $TomcatOnly){throw 'Choose either -DatabaseOnly or -TomcatOnly.'}
$tomcatJavaHome=$env:JAVA_HOME
if([string]::IsNullOrWhiteSpace($tomcatJavaHome)){$runtimeLine=& mvn -version|Where-Object {$_ -match '^Java version:.*runtime:\s*(.+)$'}|Select-Object -First 1;if(-not $runtimeLine -or $runtimeLine -notmatch '^Java version:.*runtime:\s*(.+)$'){throw 'Could not determine JAVA_HOME from Maven.'};$tomcatJavaHome=$Matches[1].Trim()}
if(-not (Test-Path (Join-Path $tomcatJavaHome 'bin\java.exe'))){throw 'JAVA_HOME does not contain bin\java.exe.'}
$selected=if($Node -eq 'All'){$config.Nodes}else{@($config.Nodes|Where-Object Id -eq $Node)}
if(-not $selected){throw "No configured node matches $Node"}
if(-not $DatabaseOnly){foreach($n in $selected){$env:JAVA_HOME=$tomcatJavaHome;$env:CATALINA_HOME=$config.CatalinaHome;$env:CATALINA_BASE=$n.CatalinaBase
  & (Join-Path $config.CatalinaHome 'bin\catalina.bat') stop
  $env:JAVA_HOME=$null;$env:CATALINA_HOME=$null;$env:CATALINA_BASE=$null
  if($LASTEXITCODE -ne 0){Write-Warning "Tomcat stop command for node $($n.Id) returned $LASTEXITCODE"}
}}
if(-not $TomcatOnly){$composeEnv=Join-Path $config.StateRoot 'compose.env'; $services=@($selected|ForEach-Object {'node-'+$_.Id.ToLowerInvariant()+'-db'});& docker compose --env-file $composeEnv -f (Join-Path $PSScriptRoot 'compose.yaml') stop @services;if($LASTEXITCODE -ne 0){throw 'Compose stop failed.'}}
if($TomcatOnly){Write-Host 'Stopped only selected Tomcat process(es); database containers, volumes, and CATALINA_BASE data were retained.'}else{Write-Host 'Stopped only selected Tomcat/database services. Database volumes and CATALINA_BASE data were retained.'}
