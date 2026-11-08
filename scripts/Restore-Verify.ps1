param([Parameter(Mandatory=$true)][string]$BackupFolder)
. (Join-Path $PSScriptRoot 'Env.ps1')
$backup=[IO.Path]::GetFullPath($BackupFolder)
$manifest=Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach($file in $manifest.files){$target=[IO.Path]::GetFullPath((Join-Path $backup $file.path));if(!$target.StartsWith($backup+[IO.Path]::DirectorySeparatorChar)){throw '备份文件路径越界'};if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $file.sha256){throw ('备份校验失败：'+$file.path)}}
$targetDatabase='antifraud_course_verify'
if($env:DB_NAME -eq $targetDatabase){throw '恢复验证库不能是当前业务库'}
$restoreRoot=Join-Path $LocalRoot ('restore-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $restoreRoot -Force | Out-Null
if(Test-Path -LiteralPath (Join-Path $backup 'uploads')){Copy-Item -LiteralPath (Join-Path $backup 'uploads') -Destination (Join-Path $restoreRoot 'uploads') -Recurse}
$env:MYSQL_PWD=$env:DB_PASSWORD
try {
 $info=New-Object System.Diagnostics.ProcessStartInfo
 $info.FileName='C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
 $info.Arguments='--protocol=TCP --host='+$env:DB_HOST+' --port='+$env:DB_PORT+' --user='+$env:DB_USER+' --default-character-set=utf8mb4 --database='+$targetDatabase
 $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardInput=$true;$info.RedirectStandardError=$true
 $process=[Diagnostics.Process]::Start($info);$stream=[IO.File]::OpenRead((Join-Path $backup 'database.sql'))
 try{$stream.CopyTo($process.StandardInput.BaseStream)}finally{$stream.Dispose();$process.StandardInput.Close()}
 $stderr=$process.StandardError.ReadToEnd();$process.WaitForExit();if($process.ExitCode -ne 0){throw ('恢复验证失败：'+$stderr)}
 $env:DB_NAME=$targetDatabase;$env:APP_STORAGE=Join-Path $restoreRoot 'uploads';$env:APP_PORT='8089';$env:APP_LOG=Join-Path $restoreRoot 'application.log';$env:APP_SEED='false'
 $keySecure=(Get-Content -LiteralPath (Join-Path $backup 'app-key.dpapi') -Raw).Trim() | ConvertTo-SecureString
 $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($keySecure)
 try{$env:APP_KEY=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
 $server=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList @('-jar','backend\target\antifraud-1.0.0.jar') -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $restoreRoot 'stdout.log') -RedirectStandardError (Join-Path $restoreRoot 'stderr.log') -PassThru
 @{pid=$server.Id;startedAt=$server.StartTime.ToUniversalTime().ToString('o');database=$targetDatabase;root=$restoreRoot;backup=$backup;url='http://127.0.0.1:8089'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $LocalRoot 'verify-server.json') -Encoding UTF8
 Write-Output '已恢复到独立验收库并启动 http://127.0.0.1:8089；原业务库未覆盖。'
} finally {Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue;Remove-Item Env:\APP_PORT -ErrorAction SilentlyContinue;Remove-Item Env:\APP_SEED -ErrorAction SilentlyContinue}
