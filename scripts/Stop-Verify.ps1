. (Join-Path $PSScriptRoot 'Tools.ps1')
$recordPath=Join-Path $ProjectRoot '.local\verify-server.json'
if(Test-Path -LiteralPath $recordPath){
 $process=Get-OwnedServer $recordPath
 if($process){Stop-Process -Id $process.Id;Wait-Process -Id $process.Id -Timeout 10 -ErrorAction SilentlyContinue;Write-Output '独立验收实例已停止。'}
}
