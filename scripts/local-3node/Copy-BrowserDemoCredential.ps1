[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('Farmer','Carrier','Retailer')][string]$RoleName)
$ErrorActionPreference='Stop'
if(-not ('AgriTrace.BrowserDemoCredentialReader' -as [type])){
Add-Type -TypeDefinition @"
using System;using System.ComponentModel;using System.Runtime.InteropServices;using System.Text;
namespace AgriTrace { public static class BrowserDemoCredentialReader {
[StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] private struct C {public UInt32 Flags;public UInt32 Type;public string TargetName;public string Comment;public long LastWritten;public UInt32 CredentialBlobSize;public IntPtr CredentialBlob;public UInt32 Persist;public UInt32 AttributeCount;public IntPtr Attributes;public string TargetAlias;public string UserName;}
[DllImport("advapi32.dll",EntryPoint="CredReadW",CharSet=CharSet.Unicode,SetLastError=true)]private static extern bool CredRead(string t,UInt32 type,UInt32 flags,out IntPtr p);
[DllImport("advapi32.dll")]private static extern void CredFree(IntPtr p);
public static string Read(string target,out string username){IntPtr p;if(!CredRead(target,1,0,out p))throw new Win32Exception(Marshal.GetLastWin32Error(),"Credential lookup failed.");try{C c=(C)Marshal.PtrToStructure(p,typeof(C));username=c.UserName;byte[] b=new byte[c.CredentialBlobSize];Marshal.Copy(c.CredentialBlob,b,0,b.Length);try{return b.Length==64?Encoding.UTF8.GetString(b):Encoding.Unicode.GetString(b).TrimEnd('\0');}finally{Array.Clear(b,0,b.Length);}}finally{CredFree(p);}}
}}
"@
}
$identities=@{Farmer='ui-farmer-demo';Carrier='ui-carrier-demo';Retailer='ui-retailer-demo'}
$user=$identities[$RoleName]
$target='AgriTrace/Local3Node/ui-demo-'+$RoleName.ToLowerInvariant()
$storedUser=$null
$secret=[AgriTrace.BrowserDemoCredentialReader]::Read($target,[ref]$storedUser)
if($storedUser -ne $user){throw 'Credential metadata does not match this demo identity.'}
try {
    Set-Clipboard -Value $secret
    $secret=$null
    Write-Host "Password for $user copied to clipboard. Paste it into the login page within 30 seconds; the clipboard will then be cleared."
    Start-Sleep -Seconds 30
} finally {
    Set-Clipboard -Value ''
    $secret=$null
}
