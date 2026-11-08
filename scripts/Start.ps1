. (Join-Path $PSScriptRoot 'Env.ps1')
$port=[int]$env:APP_PORT
$url='http://127.0.0.1:'+ $port
$jar=Join-Path $ProjectRoot 'backend\target\antifraud-1.0.0.jar'
if(!(Test-Path -LiteralPath $jar)){throw '请先运行 scripts\Build.ps1 生成运行包。'}
$logRoot=Join-Path $LocalRoot 'logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$pidPath=Join-Path $LocalRoot 'server.json'
if(Test-Path -LiteralPath $pidPath){
 $prior=Get-Content -LiteralPath $pidPath -Raw -Encoding UTF8 | ConvertFrom-Json
 $existing=Get-OwnedServer $pidPath
 if($existing){$existingUrl=if($prior.url){$prior.url}else{'http://127.0.0.1:8088'};Write-Output ('原型已在运行：'+$existingUrl+'；若修改了配置，请先停止再启动。');return}
}
Assert-LocalPortAvailable $port
$process=Start-Process -FilePath $JavaCommand -ArgumentList @('-jar','backend\target\antifraud-1.0.0.jar') -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'stdout.log') -RedirectStandardError (Join-Path $logRoot 'stderr.log') -PassThru
@{pid=$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('o');jar=$jar;url=$url;port=$port} | ConvertTo-Json | Set-Content -LiteralPath $pidPath -Encoding UTF8
Write-Output ('启动进程 '+$process.Id+'；地址 '+$url+'；日志位于 .local\logs')
Wait-PrototypeReady $process $url
Write-Output '原型已就绪，可在浏览器打开上述地址。'
