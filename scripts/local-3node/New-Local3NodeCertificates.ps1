param([string]$StateRoot=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'));$state=[IO.Path]::GetFullPath($StateRoot)
if($state.StartsWith($repo,[StringComparison]::OrdinalIgnoreCase)){throw 'StateRoot must be outside the repository.'}
foreach($d in 'identity','trust','secrets'){New-Item -ItemType Directory -Force -Path (Join-Path $state $d)|Out-Null}
$identity=Join-Path $state 'identity';$trust=Join-Path $state 'trust';$secrets=Join-Path $state 'secrets'
$kt=(Get-Command keytool.exe -ErrorAction Stop).Source
$nodes=@(@{Id='A';Role='Farmer';Peer='peer-node-a-farmer'},@{Id='B';Role='Carrier';Peer='peer-node-b-carrier'},@{Id='C';Role='Retailer';Peer='peer-node-c-retailer'})
$all=@('local-dev-ca.p12','local-dev-ca.cer','certificate-inventory.json')
foreach($n in $nodes){$id=$n.Id.ToLowerInvariant();foreach($p in 'server','peer'){$all+=@("node-$id-$p.p12","node-$id-$p.cer","node-$id-$p.csr","node-$id-$p-signed.cer","node-$id-$p-trust.p12")}}
foreach($f in $all){$d=if($f -like '*trust.p12'){$trust}else{$identity};if(Test-Path (Join-Path $d $f)){throw "Refusing to overwrite existing artifact: $f"}}
function NewPass {$b=[byte[]]::new(32);[Security.Cryptography.RandomNumberGenerator]::Fill($b);[Convert]::ToHexString($b).ToLowerInvariant()}
function SavePass($file,$value){$path=Join-Path $secrets $file;[IO.File]::WriteAllText($path,$value,[Text.UTF8Encoding]::new($false));$path}
function Invoke-Keytool([string[]]$Arguments){& $kt @Arguments *> $null;if($LASTEXITCODE -ne 0){throw "keytool failed with exit code $LASTEXITCODE"}}
function Finger($cert){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($cert.RawData)).ToLowerInvariant()}
$caPass=NewPass;[void](SavePass 'local-dev-ca-password.txt' $caPass);[Environment]::SetEnvironmentVariable('AGRITRACE_CA_PASS',$caPass,'Process')
Invoke-Keytool @('-genkeypair','-alias','local-dev-ca','-dname','CN=AgriTrace Local Development CA','-keyalg','RSA','-keysize','3072','-validity','1825','-ext','BC=ca:true','-ext','KU=keyCertSign,cRLSign','-keystore',(Join-Path $identity 'local-dev-ca.p12'),'-storetype','PKCS12','-storepass:env','AGRITRACE_CA_PASS','-keypass:env','AGRITRACE_CA_PASS')
Invoke-Keytool @('-exportcert','-alias','local-dev-ca','-keystore',(Join-Path $identity 'local-dev-ca.p12'),'-storetype','PKCS12','-storepass:env','AGRITRACE_CA_PASS','-file',(Join-Path $identity 'local-dev-ca.cer'))
$ca=[Security.Cryptography.X509Certificates.X509Certificate2]::new((Join-Path $identity 'local-dev-ca.cer'))
$caChain=[Security.Cryptography.X509Certificates.X509Chain]::new();$caChain.ChainPolicy.TrustMode=[Security.Cryptography.X509Certificates.X509ChainTrustMode]::CustomRootTrust;[void]$caChain.ChainPolicy.CustomTrustStore.Add($ca);$caChain.ChainPolicy.RevocationMode=[Security.Cryptography.X509Certificates.X509RevocationMode]::NoCheck
if(-not $caChain.Build($ca)){throw 'Local CA self-signature validation failed.'}
$inventory=[ordered]@{schemaVersion=1;generatedAtUtc=[DateTimeOffset]::UtcNow.ToString('o');ca=[ordered]@{subject=$ca.Subject;issuer=$ca.Issuer;notBeforeUtc=$ca.NotBefore.ToUniversalTime().ToString('o');notAfterUtc=$ca.NotAfter.ToUniversalTime().ToString('o');sha256Fingerprint=(Finger $ca);publicCertificate='local-dev-ca.cer'};nodes=@()}
$fingerprints=[Collections.Generic.HashSet[string]]::new();[void]$fingerprints.Add((Finger $ca))
foreach($n in $nodes){
 $id=$n.Id.ToLowerInvariant()
 foreach($purpose in 'server','peer'){
  $keyPass=NewPass;$keyEnv="AGRITRACE_$($id.ToUpperInvariant())_$($purpose.ToUpperInvariant())_PASS";$pfx="node-$id-$purpose.p12";$pwfile="node-$id-$purpose-password.txt"
  [void](SavePass $pwfile $keyPass);[Environment]::SetEnvironmentVariable($keyEnv,$keyPass,'Process')
  $alias="node-$id-$purpose";$subject=if($purpose -eq 'server'){"node-$id-server.local"}else{$n.Peer}
  $eku=if($purpose -eq 'server'){'serverAuth'}else{'clientAuth'}
  Invoke-Keytool @('-genkeypair','-alias',$alias,'-dname',"CN=$subject",' -keyalg'.Trim(),'RSA','-keysize','2048','-validity','730','-ext','KU=digitalSignature','-ext',"EKU=$eku",'-keystore',(Join-Path $identity $pfx),'-storetype','PKCS12','-storepass:env',$keyEnv,'-keypass:env',$keyEnv)
  if($purpose -eq 'server'){Invoke-Keytool @('-certreq','-alias',$alias,'-file',(Join-Path $identity "node-$id-$purpose.csr"),'-keystore',(Join-Path $identity $pfx),'-storetype','PKCS12','-storepass:env',$keyEnv,'-keypass:env',$keyEnv)}
  else{Invoke-Keytool @('-certreq','-alias',$alias,'-file',(Join-Path $identity "node-$id-$purpose.csr"),'-keystore',(Join-Path $identity $pfx),'-storetype','PKCS12','-storepass:env',$keyEnv,'-keypass:env',$keyEnv)}
  $issued=Join-Path $identity "node-$id-$purpose-signed.cer"
  $signArgs=@('-gencert','-alias','local-dev-ca','-infile',(Join-Path $identity "node-$id-$purpose.csr"),'-outfile',$issued,'-validity','730','-ext','KU=digitalSignature','-ext',"EKU=$eku",'-keystore',(Join-Path $identity 'local-dev-ca.p12'),'-storetype','PKCS12','-storepass:env','AGRITRACE_CA_PASS','-keypass:env','AGRITRACE_CA_PASS')
  if($purpose -eq 'server'){$signArgs+=@('-ext','SAN=DNS:localhost,IP:127.0.0.1')}
  Invoke-Keytool $signArgs
  Invoke-Keytool @('-importcert','-noprompt','-alias','local-dev-ca','-file',(Join-Path $identity 'local-dev-ca.cer'),'-keystore',(Join-Path $identity $pfx),'-storetype','PKCS12','-storepass:env',$keyEnv)
  Invoke-Keytool @('-importcert','-noprompt','-alias',$alias,'-file',$issued,'-keystore',(Join-Path $identity $pfx),'-storetype','PKCS12','-storepass:env',$keyEnv)
  $publicCert=[Security.Cryptography.X509Certificates.X509Certificate2]::new($issued);$fp=Finger $publicCert
  if(-not $fingerprints.Add($fp)){throw 'Duplicate leaf certificate fingerprint.'}
  $chain=[Security.Cryptography.X509Certificates.X509Chain]::new();$chain.ChainPolicy.TrustMode=[Security.Cryptography.X509Certificates.X509ChainTrustMode]::CustomRootTrust;[void]$chain.ChainPolicy.CustomTrustStore.Add($ca);$chain.ChainPolicy.RevocationMode=[Security.Cryptography.X509Certificates.X509RevocationMode]::NoCheck
  if(-not $chain.Build($publicCert)){throw "CA chain validation failed for $id $purpose"}
  $oid=if($purpose -eq 'server'){'1.3.6.1.5.5.7.3.1'}else{'1.3.6.1.5.5.7.3.2'}
  $ekuExt=$publicCert.Extensions|Where-Object {$_.Oid.Value -eq '2.5.29.37'}
  if(-not $ekuExt -or -not ($ekuExt.EnhancedKeyUsages | Where-Object {$_.Value -eq $oid})){throw "EKU check failed for $id $purpose"}
  $sans=if($purpose -eq 'server'){@('DNS:localhost','IP:127.0.0.1')}else{@()}
  $publicName="node-$id-$purpose-signed.cer"
  $inventory.nodes+=,[ordered]@{node=$n.Id;role=$n.Role;peerId=$n.Peer;purpose=$purpose;subject=$publicCert.Subject;issuer=$publicCert.Issuer;sans=$sans;notBeforeUtc=$publicCert.NotBefore.ToUniversalTime().ToString('o');notAfterUtc=$publicCert.NotAfter.ToUniversalTime().ToString('o');sha256Fingerprint=$fp;publicCertificate=$publicName}
  $publicCert.Dispose();$chain.Dispose()
 }
 foreach($purpose in 'server','peer'){
  $trustPass=NewPass;$trustEnv="AGRITRACE_$($id.ToUpperInvariant())_$($purpose.ToUpperInvariant())_TRUST_PASS";$trustFile="node-$id-$purpose-trust-password.txt";[void](SavePass $trustFile $trustPass);[Environment]::SetEnvironmentVariable($trustEnv,$trustPass,'Process')
  Invoke-Keytool @('-importcert','-noprompt','-alias','local-dev-ca','-file',(Join-Path $identity 'local-dev-ca.cer'),'-keystore',(Join-Path $trust "node-$id-$purpose-trust.p12"),'-storetype','PKCS12','-storepass:env',$trustEnv)
  $n[$purpose+'TrustStorePath']=Join-Path $trust "node-$id-$purpose-trust.p12";$n[$purpose+'TrustStorePasswordFile']=Join-Path $secrets $trustFile
 }
}
$inventory|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $identity 'certificate-inventory.json') -Encoding utf8
$owner=(Get-Acl $state).Owner
$targets=@($identity,$trust)+@(Get-ChildItem $secrets -File|Where-Object Name -match '^(local-dev-ca-password|node-[abc]-(server|peer|server-trust|peer-trust)-password)\.txt$'|ForEach-Object FullName)
foreach($target in $targets){& icacls.exe $target /inheritance:r /grant:r "$owner`:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' /T /C|Out-Null;if($LASTEXITCODE -ne 0){throw 'Could not restrict generated private material ACL.'}}
[Environment]::SetEnvironmentVariable('AGRITRACE_CA_PASS',$null,'Process')
Write-Host "Created the local development CA, six unique private-key identities, and six truststores under $state."
Write-Host "Public inventory: $(Join-Path $identity 'certificate-inventory.json')"
Write-Host 'Private key and password contents are not displayed.'
