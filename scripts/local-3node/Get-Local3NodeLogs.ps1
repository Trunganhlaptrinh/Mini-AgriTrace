param([ValidateSet('A','B','C')][string]$Node='A',[ValidateSet('tomcat','database')][string]$Source='tomcat',[int]$Tail=100,[string]$ConfigPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\nodes.psd1'))
$ErrorActionPreference='Stop'; $config=Import-PowerShellDataFile $ConfigPath
$n=$config.Nodes|Where-Object Id -eq $Node|Select-Object -First 1
if(-not $n){throw "No configured node matches $Node"}
if($Source -eq 'tomcat'){$dir=Join-Path $n.CatalinaBase 'logs';if(-not (Test-Path $dir)){throw "Tomcat logs do not exist: $dir"};Get-ChildItem $dir -File|Sort-Object LastWriteTime -Descending|Select-Object -First 5|ForEach-Object {Write-Host "--- $($_.Name) ---";Get-Content -LiteralPath $_.FullName -Tail $Tail}}
else{$configEnv=Join-Path $config.StateRoot 'compose.env';& docker compose --env-file $configEnv -f (Join-Path $PSScriptRoot 'compose.yaml') logs --tail $Tail ('node-'+$Node.ToLowerInvariant()+'-db')}
