param([switch]$KeepStopped)
. (Join-Path $PSScriptRoot 'Env.ps1')
$wasRunning=$false
if(Test-Path -LiteralPath (Join-Path $LocalRoot 'server.json')){$record=Get-Content -LiteralPath (Join-Path $LocalRoot 'server.json') -Raw -Encoding UTF8 | ConvertFrom-Json;$p=Get-Process -Id $record.pid -ErrorAction SilentlyContinue;$wasRunning=!!($p -and [Math]::Abs(($p.StartTime.ToUniversalTime()-([datetime]$record.startedAt).ToUniversalTime()).TotalSeconds) -lt 1)}
if($wasRunning){& (Join-Path $PSScriptRoot 'Stop.ps1')}
$destination=Join-Path $LocalRoot ('backups\'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $destination -Force | Out-Null
try {
 $env:MYSQL_PWD=$env:DB_PASSWORD
 & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe' --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USER --protocol=TCP --single-transaction --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "--result-file=$destination\database.sql" $env:DB_NAME
 if($LASTEXITCODE -ne 0){throw '数据库备份失败'}
 foreach($name in @('database.json','db-password.dpapi','app-key.dpapi')){Copy-Item -LiteralPath (Join-Path $LocalRoot $name) -Destination (Join-Path $destination $name)}
 if(Test-Path -LiteralPath $env:APP_STORAGE){Copy-Item -LiteralPath $env:APP_STORAGE -Destination (Join-Path $destination 'uploads') -Recurse}
 $files=Get-ChildItem -LiteralPath $destination -File -Recurse | ForEach-Object {@{path=$_.FullName.Substring($destination.Length+1);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}}
 @{createdAt=(Get-Date).ToString('o');database=$env:DB_NAME;keyProtection='Windows DPAPI current user';files=@($files)} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding UTF8
 Write-Output ('备份完成：'+$destination)
} finally {Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue;if($wasRunning -and !$KeepStopped){& (Join-Path $PSScriptRoot 'Start.ps1')}}
