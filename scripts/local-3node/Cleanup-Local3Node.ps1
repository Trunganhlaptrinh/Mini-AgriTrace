param([ValidateSet('A','B','C','All')][string]$Node='All',[string]$ConfigPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\nodes.psd1'))
$ErrorActionPreference='Stop'
Write-Warning 'Cleanup stops selected demo services only. It intentionally keeps DB volumes, config, logs, WARs, keys, certificates and truststores; it does not delete data.'
& (Join-Path $PSScriptRoot 'Stop-Local3Node.ps1') -Node $Node -ConfigPath $ConfigPath
