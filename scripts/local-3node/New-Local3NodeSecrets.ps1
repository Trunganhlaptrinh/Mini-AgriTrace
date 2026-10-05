param()
$ErrorActionPreference='Stop'
$stateRoot=Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node'
$secretDir=Join-Path $stateRoot 'secrets'
New-Item -ItemType Directory -Force -Path $secretDir | Out-Null
$envFile=Join-Path $stateRoot 'compose.env'
if(Test-Path $envFile){ throw "External secret configuration already exists; refusing to overwrite: $envFile" }
function New-RandomPassword { $b=New-Object byte[] 36; [Security.Cryptography.RandomNumberGenerator]::Fill($b); ([Convert]::ToHexString($b)).ToLowerInvariant() }
$entries=[Collections.Generic.List[string]]::new()
$entries.Add('AGRITRACE_LOCAL3NODE_SECRET_DIR='+$secretDir.Replace('\','/'))
$ports=@{A=3307;B=3308;C=3309}
foreach($node in 'A','B','C'){
 $letter=$node.ToLowerInvariant(); $prefix='NODE_'+$node+'_DB_'
 $entries.Add($prefix+'PORT='+$ports[$node])
 foreach($kind in 'app','root'){
  $value=New-RandomPassword
  $name="node-$letter-$kind-password.txt"
  [IO.File]::WriteAllText((Join-Path $secretDir $name),$value,[Text.UTF8Encoding]::new($false))
  $value=$null
 }
}
[IO.File]::WriteAllLines($envFile,$entries,[Text.UTF8Encoding]::new($false))
$current=[Security.Principal.WindowsIdentity]::GetCurrent().Name
foreach($file in (Get-ChildItem -LiteralPath $secretDir -File)+ (Get-Item $envFile)){
 $acl=Get-Acl -LiteralPath $file.FullName; $acl.SetAccessRuleProtection($true,$false)
 $rule=[Security.AccessControl.FileSystemAccessRule]::new($current,'FullControl','Allow'); $acl.SetAccessRule($rule); Set-Acl -LiteralPath $file.FullName -AclObject $acl
}
Write-Host "Created six random local DB passwords outside the repository: $secretDir"
Write-Host "Compose environment file: $envFile. This does not create or start database instances."
