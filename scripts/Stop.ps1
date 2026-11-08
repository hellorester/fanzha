$ErrorActionPreference='Stop'
$ProjectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$pidPath=Join-Path $ProjectRoot '.local\server.json'
if(!(Test-Path -LiteralPath $pidPath)){Write-Output '无本项目启动记录。';exit 0}
$record=Get-Content -LiteralPath $pidPath -Raw -Encoding UTF8 | ConvertFrom-Json
$process=Get-Process -Id $record.pid -ErrorAction SilentlyContinue
if($process -and $process.ProcessName -eq 'java' -and [Math]::Abs(($process.StartTime.ToUniversalTime()-([datetime]$record.startedAt).ToUniversalTime()).TotalSeconds) -lt 1){Stop-Process -Id $process.Id;Wait-Process -Id $process.Id -Timeout 10 -ErrorAction SilentlyContinue;Write-Output '原型已停止。'}else{Write-Output '记录中的本项目进程已退出或已被其他进程替代。'}
