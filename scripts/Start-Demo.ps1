. (Join-Path $PSScriptRoot 'Env.ps1')
$recordPath=Join-Path $LocalRoot 'demo-baseline.json'
if(!(Test-Path -LiteralPath $recordPath)){throw '请先运行 scripts/Prepare-Demo.ps1 准备独立演示快照'}
$demo=Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
if($demo.database -ne ($env:DB_NAME+'_verify')){throw '快照必须对应当前项目的独立验证库，不能覆盖日常业务库'}
$manifest=Get-Content -LiteralPath (Join-Path $demo.backup 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if($manifest.kind -ne 'course-demo-baseline' -or $manifest.database -ne $demo.database){throw '快照来源不符'}
Write-Host ('将把独立演示库 '+$demo.database+' 恢复到固定起点；此库后续演练记录会被覆盖。')
if((Read-Host '请输入独立演示库全名确认') -cne $demo.database){throw '已取消，未修改数据库'}
if(Get-OwnedServer (Join-Path $LocalRoot 'verify-server.json')){& (Join-Path $PSScriptRoot 'Stop-Verify.ps1')}
& (Join-Path $PSScriptRoot 'Restore-Verify.ps1') -BackupFolder $demo.backup -TargetDatabase $demo.database -Port $demo.port
