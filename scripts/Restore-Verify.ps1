param([Parameter(Mandatory=$true)][string]$BackupFolder,[ValidatePattern('^[a-zA-Z0-9_]{1,64}$')][string]$TargetDatabase,[ValidateRange(1,65535)][int]$Port=8089)
. (Join-Path $PSScriptRoot 'Env.ps1')
$mysqlCommand=Resolve-MySqlTool 'mysql.exe'
if(!$TargetDatabase){$TargetDatabase=$env:DB_NAME+'_verify'}
if($TargetDatabase.Length -gt 64){throw '验证库名称过长，请通过 -TargetDatabase 指定已创建的独立验证库。'}
if($env:DB_NAME -eq $TargetDatabase){throw '恢复验证库不能是当前业务库'}
if(Get-OwnedServer (Join-Path $LocalRoot 'verify-server.json')){throw '已有本项目独立验证实例，请先运行 Stop-Verify.ps1。'}
Assert-LocalPortAvailable $Port
$backup=[IO.Path]::GetFullPath($BackupFolder)
$manifest=Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach($file in $manifest.files){$target=[IO.Path]::GetFullPath((Join-Path $backup $file.path));if(!$target.StartsWith($backup+[IO.Path]::DirectorySeparatorChar)){throw '备份文件路径越界'};if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $file.sha256){throw ('备份校验失败：'+$file.path)}}
$restoreRoot=Join-Path $LocalRoot ('restore-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $restoreRoot -Force | Out-Null
if(Test-Path -LiteralPath (Join-Path $backup 'uploads')){Copy-Item -LiteralPath (Join-Path $backup 'uploads') -Destination (Join-Path $restoreRoot 'uploads') -Recurse}
$env:MYSQL_PWD=$env:DB_PASSWORD
try {
 $info=New-Object System.Diagnostics.ProcessStartInfo
 $info.FileName=$mysqlCommand
 $info.Arguments='--protocol=TCP --host='+$env:DB_HOST+' --port='+$env:DB_PORT+' --user='+$env:DB_USER+' --default-character-set=utf8mb4 --database='+$targetDatabase
 $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardInput=$true;$info.RedirectStandardError=$true
 $process=[Diagnostics.Process]::Start($info);$stream=[IO.File]::OpenRead((Join-Path $backup 'database.sql'))
 try{$stream.CopyTo($process.StandardInput.BaseStream)}finally{$stream.Dispose();$process.StandardInput.Close()}
 $stderr=$process.StandardError.ReadToEnd();$process.WaitForExit();if($process.ExitCode -ne 0){throw ('恢复验证失败：'+$stderr)}
 $env:DB_NAME=$TargetDatabase;$env:APP_STORAGE=Join-Path $restoreRoot 'uploads';$env:APP_PORT=[string]$Port;$env:APP_LOG=Join-Path $restoreRoot 'application.log';$env:APP_SEED='false'
 $keySecure=(Get-Content -LiteralPath (Join-Path $backup 'app-key.dpapi') -Raw).Trim() | ConvertTo-SecureString
 $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($keySecure)
 try{$env:APP_KEY=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
 $url='http://127.0.0.1:'+ $Port
 $server=Start-Process -FilePath $JavaCommand -ArgumentList @('-jar','backend\target\antifraud-1.0.0.jar') -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $restoreRoot 'stdout.log') -RedirectStandardError (Join-Path $restoreRoot 'stderr.log') -PassThru
 @{pid=$server.Id;startedAt=$server.StartTime.ToUniversalTime().ToString('o');database=$TargetDatabase;root=$restoreRoot;backup=$backup;url=$url;port=$Port} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $LocalRoot 'verify-server.json') -Encoding UTF8
 Wait-PrototypeReady $server $url
 Write-Output ('已恢复到独立验收库 '+$TargetDatabase+' 并启动 '+$url+'；原业务库未覆盖。')
} finally {Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue;Remove-Item Env:\APP_PORT -ErrorAction SilentlyContinue;Remove-Item Env:\APP_SEED -ErrorAction SilentlyContinue}
