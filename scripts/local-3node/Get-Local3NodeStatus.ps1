param([ValidateSet('A','B','C','All')][string]$Node='All',[string]$ConfigPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\nodes.psd1'))
$ErrorActionPreference='Stop'; $config=Import-PowerShellDataFile $ConfigPath
$selected=if($Node -eq 'All'){$config.Nodes}else{@($config.Nodes|Where-Object Id -eq $Node)}
foreach($n in $selected){
  Write-Host "Node $($n.Id) $($n.Role): peer=$($n.PeerId) base=$($n.CatalinaBase)"
  foreach($port in $n.AppPort,$n.P2pPort,$n.DatabasePort,$n.ShutdownPort){$l=Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue|Select-Object -First 1; if($l){"  port $port LISTEN pid=$($l.OwningProcess)"}else{"  port $port not listening"}}
}
$envFile=Join-Path $config.StateRoot 'compose.env'
if(Test-Path $envFile){& docker compose --env-file $envFile -f (Join-Path $PSScriptRoot 'compose.yaml') ps}else{Write-Host 'Compose secret configuration not prepared.'}
