$ErrorActionPreference='Stop'
$ProjectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$LocalRoot=Join-Path $ProjectRoot '.local'
$dbConfig=Get-Content -LiteralPath (Join-Path $LocalRoot 'database.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$securePassword=(Get-Content -LiteralPath (Join-Path $LocalRoot 'db-password.dpapi') -Raw).Trim() | ConvertTo-SecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try{$env:DB_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
$env:DB_HOST=$dbConfig.host
$env:DB_PORT=[string]$dbConfig.port
$env:DB_NAME=$dbConfig.database
$env:DB_USER=$dbConfig.username
$keyPath=Join-Path $LocalRoot 'app-key.dpapi'
if(!(Test-Path -LiteralPath $keyPath)){
 $keyBytes=New-Object byte[] 32
 $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
 $rng.GetBytes($keyBytes);$rng.Dispose()
 [Convert]::ToBase64String($keyBytes) | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $keyPath -Encoding ASCII
}
$keySecure=(Get-Content -LiteralPath $keyPath -Raw).Trim() | ConvertTo-SecureString
$keyPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($keySecure)
try{$env:APP_KEY=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)}
$env:APP_STORAGE=Join-Path $LocalRoot 'uploads'
$env:APP_LOG=Join-Path $LocalRoot 'logs\application.log'
if(!$env:JAVA_HOME -or !(Test-Path -LiteralPath ($env:JAVA_HOME+'\bin\java.exe'))){$env:JAVA_HOME='D:\Devtool\jdk-21.0.13.0.2+1'}
if(!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))){throw '找不到 Java 21，请先配置 JAVA_HOME。'}
$env:MAVEN_USER_HOME='D:\Devtool\maven'
$env:Path=$env:JAVA_HOME+'\bin;D:\nodejs;'+$env:Path
$MavenCommand=$null
if(Test-Path -LiteralPath 'D:\Devtool\maven\wrapper\dists\apache-maven-3.10.0'){$MavenCommand=(Get-ChildItem -LiteralPath 'D:\Devtool\maven\wrapper\dists\apache-maven-3.10.0' -Filter mvn.cmd -Recurse | Select-Object -First 1).FullName}
