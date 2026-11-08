$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$recordPath=Join-Path $root '.local\verify-server.json'
if(Test-Path -LiteralPath $recordPath){
 $record=Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
 $process=Get-Process -Id $record.pid -ErrorAction SilentlyContinue
 if($process -and $process.ProcessName -eq 'java' -and [Math]::Abs(($process.StartTime.ToUniversalTime()-([datetime]$record.startedAt).ToUniversalTime()).TotalSeconds) -lt 1){Stop-Process -Id $process.Id;Wait-Process -Id $process.Id -Timeout 10 -ErrorAction SilentlyContinue;Write-Output '独立验收实例已停止。'}
}
