. (Join-Path $PSScriptRoot 'Env.ps1')
$jar=Join-Path $ProjectRoot 'backend\target\antifraud-1.0.0.jar'
if(!(Test-Path -LiteralPath $jar)){throw '请先运行 scripts\Build.ps1 生成运行包。'}
$logRoot=Join-Path $LocalRoot 'logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$pidPath=Join-Path $LocalRoot 'server.json'
if(Test-Path -LiteralPath $pidPath){
 $prior=Get-Content -LiteralPath $pidPath -Raw -Encoding UTF8 | ConvertFrom-Json
 $existing=Get-Process -Id $prior.pid -ErrorAction SilentlyContinue
 if($existing -and [Math]::Abs(($existing.StartTime.ToUniversalTime()-([datetime]$prior.startedAt).ToUniversalTime()).TotalSeconds) -lt 1){Write-Output '原型已在运行：http://127.0.0.1:8088';exit 0}
}
$process=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList @('-jar','backend\target\antifraud-1.0.0.jar') -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'stdout.log') -RedirectStandardError (Join-Path $logRoot 'stderr.log') -PassThru
@{pid=$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('o');jar=$jar} | ConvertTo-Json | Set-Content -LiteralPath $pidPath -Encoding UTF8
Write-Output ('启动进程 '+$process.Id+'；地址 http://127.0.0.1:8088；日志位于 .local\logs')
$ready=$false
for($attempt=0;$attempt -lt 30;$attempt++){
 if($process.HasExited){throw '启动失败，请查看 .local\logs\stdout.log 与 stderr.log。'}
 try{$response=Invoke-WebRequest -Uri 'http://127.0.0.1:8088/api/session' -UseBasicParsing -TimeoutSec 2;if($response.StatusCode -eq 200){$ready=$true;break}}catch{}
 Start-Sleep -Milliseconds 500
}
if(!$ready){throw '启动尚未就绪，请查看日志；不要连续重复启动。'}
Write-Output '原型已就绪，可在浏览器打开上述地址。'
