. (Join-Path $PSScriptRoot 'Tools.ps1')
if(!(Test-Path -LiteralPath (Join-Path $LocalRoot 'database.json')) -or !(Test-Path -LiteralPath (Join-Path $LocalRoot 'db-password.dpapi'))){throw '尚未配置本机数据库，请先运行 scripts\Configure.ps1。构建源码不需要此配置。'}
$dbConfig=Get-Content -LiteralPath (Join-Path $LocalRoot 'database.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if($dbConfig.database -notmatch '^[a-zA-Z0-9_]{1,64}$' -or $dbConfig.username -notmatch '^[a-zA-Z0-9_]+$' -or $dbConfig.host -notmatch '^[a-zA-Z0-9._:\-]+$' -or [int]$dbConfig.port -lt 1 -or [int]$dbConfig.port -gt 65535){throw '.local/database.json 的数据库、用户、地址或端口格式无效。'}
$securePassword=(Get-Content -LiteralPath (Join-Path $LocalRoot 'db-password.dpapi') -Raw).Trim() | ConvertTo-SecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try{$env:DB_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
$env:DB_HOST=$dbConfig.host
$env:DB_PORT=[string]$dbConfig.port
$env:DB_NAME=$dbConfig.database
$env:DB_USER=$dbConfig.username
if(!$env:APP_PORT){$env:APP_PORT=if($dbConfig.appPort){[string]$dbConfig.appPort}else{'8088'}}
if($env:APP_PORT -notmatch '^\d+$' -or [int]$env:APP_PORT -lt 1 -or [int]$env:APP_PORT -gt 65535){throw '应用端口必须在1至65535之间。'}
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
$JavaCommand=Initialize-Java
