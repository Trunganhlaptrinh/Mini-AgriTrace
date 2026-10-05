param(
    [ValidateSet('A','B','C','All')][string]$Node='All',
    [string]$ConfigPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\nodes.psd1'),
    [string]$ManifestPath=(Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node\manifest\consortium-manifest.json'),
    [string]$Maven='mvn',
    [switch]$PreflightOnly
)
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$stateRoot=Join-Path $env:LOCALAPPDATA 'AgriTrace\local-3node'
$config=if(Test-Path -LiteralPath $ConfigPath){Import-PowerShellDataFile $ConfigPath}else{Import-PowerShellDataFile (Join-Path $PSScriptRoot 'nodes.example.psd1')}
if(-not (Test-Path -LiteralPath $ConfigPath)){
  $config.StateRoot=$stateRoot
  foreach($item in $config.Nodes){
    $id=$item.Id.ToLowerInvariant()
    $item.P2pKeyStorePath=Join-Path $stateRoot "identity\node-$id-peer.p12"
    $item.P2pKeyStorePasswordFile=Join-Path $stateRoot "secrets\node-$id-peer-password.txt"
    $item.AppServerKeyStorePath=Join-Path $stateRoot "identity\node-$id-server.p12"
    $item.AppServerKeyStorePasswordFile=Join-Path $stateRoot "secrets\node-$id-server-password.txt"
    $item.P2pServerKeyStorePath=$item.AppServerKeyStorePath
    $item.P2pServerKeyStorePasswordFile=$item.AppServerKeyStorePasswordFile
    $item.P2pClientTrustStorePath=Join-Path $stateRoot "trust\node-$id-peer-trust.p12"
    $item.JavaPeerTrustStorePath=Join-Path $stateRoot "trust\node-$id-server-trust.p12"
    $item.CatalinaBase=Join-Path $stateRoot "tomcat\node-$id"
  }
}
if(-not (Test-Path -LiteralPath $ManifestPath)){throw 'The external signed local consortium manifest is missing.'}
$selected=if($Node -eq 'All'){@($config.Nodes)}else{@($config.Nodes|Where-Object Id -eq $Node)}
$selected=@($selected|Sort-Object Id)
if(-not $selected -or ($Node -eq 'All' -and $selected.Count -ne 3)){throw 'The configuration must resolve exactly nodes A, B, and C.'}
$expected=@{A=@{Port=3307;User='admin-a';Peer='peer-node-a-farmer'};B=@{Port=3308;User='admin-b';Peer='peer-node-b-carrier'};C=@{Port=3309;User='admin-c';Peer='peer-node-c-retailer'}}
foreach($n in $selected){
  if(-not $expected.ContainsKey($n.Id) -or $n.DatabasePort -ne $expected[$n.Id].Port -or $n.PeerId -ne $expected[$n.Id].Peer){throw "Node $($n.Id) config does not match the approved isolated local topology."}
  if($n.CatalinaBase -and ([IO.Path]::GetFullPath($n.CatalinaBase).StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase))){throw 'Runtime state must remain outside the repository.'}
}

# The generated secret is written directly into the current user's Windows Credential Manager.
# It is never materialized as a PowerShell string, command argument, environment variable, or file.
if(-not ('AgriTrace.LocalCredentialWriter' -as [type])){
  Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
namespace AgriTrace {
  public static class LocalCredentialWriter {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    private struct CREDENTIAL {
      public UInt32 Flags; public UInt32 Type; public string TargetName; public string Comment;
      public long LastWritten; public UInt32 CredentialBlobSize; public IntPtr CredentialBlob;
      public UInt32 Persist; public UInt32 AttributeCount; public IntPtr Attributes;
      public string TargetAlias; public string UserName;
    }
    [DllImport("advapi32.dll", EntryPoint="CredWriteW", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool CredWrite(ref CREDENTIAL credential, UInt32 flags);
    [DllImport("advapi32.dll", EntryPoint="CredReadW", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool CredRead(string target, UInt32 type, UInt32 flags, out IntPtr credential);
    [DllImport("advapi32.dll", EntryPoint="CredFree", SetLastError=false)]
    private static extern void CredFree(IntPtr credential);
    public static bool Exists(string target) {
      IntPtr value;
      if(!CredRead(target, 1, 0, out value)) {
        int code=Marshal.GetLastWin32Error();
        if(code==1168) return false;
        throw new Win32Exception(code, "Credential Manager lookup failed.");
      }
      if(value!=IntPtr.Zero) CredFree(value);
      return true;
    }
    public static void CreateRandom(string target, string username) {
      byte[] random=new byte[32]; char[] chars=new char[64]; byte[] blob=new byte[64];
      IntPtr native=IntPtr.Zero;
      try {
        RandomNumberGenerator.Fill(random);
        const string hex="0123456789abcdef";
        for(int i=0;i<random.Length;i++){chars[2*i]=hex[random[i]>>4];chars[2*i+1]=hex[random[i]&15];}
        for(int i=0;i<chars.Length;i++) blob[i]=(byte)chars[i];
        native=Marshal.AllocHGlobal(blob.Length); Marshal.Copy(blob,0,native,blob.Length);
        CREDENTIAL c=new CREDENTIAL(); c.Type=1; c.TargetName=target; c.UserName=username;
        c.CredentialBlobSize=(UInt32)blob.Length; c.CredentialBlob=native; c.Persist=2;
        if(!CredWrite(ref c,0)) throw new Win32Exception(Marshal.GetLastWin32Error(), "Credential Manager write failed.");
      } finally {
        Array.Clear(random,0,random.Length); Array.Clear(chars,0,chars.Length); Array.Clear(blob,0,blob.Length);
        if(native!=IntPtr.Zero){for(int i=0;i<64;i++) Marshal.WriteByte(native,i,0);Marshal.FreeHGlobal(native);}
      }
    }
  }
}
'@
}

$composeEnv=Join-Path $stateRoot 'compose.env'
if(-not (Test-Path -LiteralPath $composeEnv)){throw 'External isolated database configuration is missing.'}
$secretDirLine=Get-Content -LiteralPath $composeEnv | Where-Object {$_ -match '^AGRITRACE_LOCAL3NODE_SECRET_DIR='} | Select-Object -First 1
if(-not $secretDirLine){throw 'External database secret directory configuration is missing.'}
$secretDir=$secretDirLine.Substring('AGRITRACE_LOCAL3NODE_SECRET_DIR='.Length).Replace('/','\')
$rows=[Collections.Generic.List[object]]::new()

function Invoke-BootstrapCli([string[]]$CliArgs,[hashtable]$NodeConfig,[string]$NodeUser){
  $dbFile=Join-Path $secretDir ('node-{0}-app-password.txt' -f $NodeConfig.Id.ToLowerInvariant())
  $peerPasswordFile=$NodeConfig.P2pKeyStorePasswordFile
  $peerStore=$NodeConfig.P2pKeyStorePath
  if(-not (Test-Path -LiteralPath $dbFile)){throw "Node $($NodeConfig.Id) database credential file is missing."}
  if(-not (Test-Path -LiteralPath $peerPasswordFile) -or -not (Test-Path -LiteralPath $peerStore)){throw "Node $($NodeConfig.Id) peer identity prerequisite is missing."}
  $previous=@{}
  foreach($key in 'AGRITRACE_DB_URL','AGRITRACE_DB_USERNAME','AGRITRACE_DB_PASSWORD','AGRITRACE_P2P_PEER_ID','AGRITRACE_P2P_KEYSTORE_PATH','AGRITRACE_P2P_KEYSTORE_PASSWORD'){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
  try {
    $env:AGRITRACE_DB_URL="jdbc:mysql://127.0.0.1:$($NodeConfig.DatabasePort)/agritrace?serverTimezone=UTC"
    $env:AGRITRACE_DB_USERNAME='agritrace_app';$env:AGRITRACE_DB_PASSWORD=[IO.File]::ReadAllText($dbFile).Trim()
    $env:AGRITRACE_P2P_PEER_ID=$NodeConfig.PeerId;$env:AGRITRACE_P2P_KEYSTORE_PATH=$peerStore
    $env:AGRITRACE_P2P_KEYSTORE_PASSWORD=[IO.File]::ReadAllText($peerPasswordFile).Trim()
    $output=& $Maven -q "-Dexec.args=$($CliArgs -join ' ')" exec:java 2>&1
    $code=$LASTEXITCODE
    if($code -ne 0){
      $diagnostic=$output -join "`n"
      $category=if($diagnostic -match 'Access denied for user'){'database authentication'}
        elseif($diagnostic -match 'Communications link failure|ConnectException'){'database connectivity'}
        elseif($diagnostic -match 'Missing P2P configuration|Could not verify configured local peer certificate|PKCS#12'){'local peer identity/configuration'}
        elseif($diagnostic -match 'PluginResolutionException|Could not resolve artifact'){'Maven dependency resolution'}
        elseif($diagnostic -match 'Usage: validate'){'CLI argument parsing'}
        else{'bootstrap CLI/runtime'}
      throw "Bootstrap CLI failed safely for node $($NodeConfig.Id); category=$category; exit code $code."
    }
    return @($output | ForEach-Object { [string]$_ })
  } finally {
    foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
  }
}

# Preflight every requested node before generating any credentials or initializing any database.
foreach($n in $selected){
  $username=$expected[$n.Id].User
  $output=Invoke-BootstrapCli @('status',$ManifestPath,$username) $n $username
  $jsonLine=@($output|Where-Object {$_ -match '^\s*\{.*"state"\s*:'}|Select-Object -Last 1)
  if(-not $jsonLine){throw "Node $($n.Id) status could not be safely verified; no bootstrap was run."}
  $status=$jsonLine[0]|ConvertFrom-Json
  if(-not $status.localPeerVerified){throw "Node $($n.Id) signed peer identity does not match the local certificate."}
  if($status.state -notin @('UNINITIALIZED','RESUMABLE','INITIALIZED')){throw "Node $($n.Id) database state '$($status.state)' is not safe to initialize."}
    if($status.networkId -ne 'agritrace-local-3node-v1'){throw "Node $($n.Id) is not configured for the approved local network."}
  $rows.Add([pscustomobject]@{Node=$n;User=$username;State=[string]$status.state;
    Blocks=[int]$status.blocksPresent;ExpectedBlocks=[int]$status.blocksExpected;
    GovernanceTransactions=[int]$status.governanceTransactionsPresent;
    ExpectedGovernanceTransactions=[int]$status.governanceTransactionsExpected;
    AdminPresent=($status.localAdminUsername -eq $username);PeerVerified=[bool]$status.localPeerVerified})
}
if($PreflightOnly){
  foreach($row in $rows){Write-Host "Node $($row.Node.Id): $($row.State); blocks $($row.Blocks)/$($row.ExpectedBlocks); governance transactions $($row.GovernanceTransactions)/$($row.ExpectedGovernanceTransactions); ADMIN=$($row.AdminPresent); peer=$($row.PeerVerified). No credential or database changes made."}
  exit 0
}

foreach($row in $rows){
  if($row.State -eq 'INITIALIZED'){
    Write-Host "Node $($row.Node.Id): already initialized and verified."
    continue
  }
  $target='AgriTrace/Local3Node/'+$row.User
  if(-not [AgriTrace.LocalCredentialWriter]::Exists($target)){
    [AgriTrace.LocalCredentialWriter]::CreateRandom($target,$row.User)
  }
  $cliArgs=@('initialize',$ManifestPath,$row.User,'--credential-target',$target)
  $result=Invoke-BootstrapCli $cliArgs $row.Node $row.User
  if($LASTEXITCODE -ne 0){throw "Node $($row.Node.Id) initialization failed. Previously initialized nodes were retained; no rollback was attempted."}
  Write-Host "Node $($row.Node.Id): bootstrap initialized and verified."
}
Write-Host 'Local bootstrap processing finished. No development or test database was targeted.'
