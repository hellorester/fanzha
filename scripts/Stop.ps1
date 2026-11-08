. (Join-Path $PSScriptRoot 'Tools.ps1')
$pidPath=Join-Path $ProjectRoot '.local\server.json'
if(!(Test-Path -LiteralPath $pidPath)){Write-Output '无本项目启动记录。';return}
$process=Get-OwnedServer $pidPath
if($process){Stop-Process -Id $process.Id;Wait-Process -Id $process.Id -Timeout 10 -ErrorAction SilentlyContinue;Write-Output '原型已停止。'}else{Write-Output '记录中的本项目进程已退出或已被其他进程替代。'}
