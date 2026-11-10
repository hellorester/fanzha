param([ValidateRange(1,65535)][int]$Port=8090)
. (Join-Path $PSScriptRoot 'Env.ps1')
$demoDatabase=$env:DB_NAME+'_verify'
if($demoDatabase.Length -gt 64 -or $demoDatabase -eq $env:DB_NAME){throw '独立演示库名称无效'}
Write-Host ('将重建独立演示库 '+$demoDatabase+'，日常业务库不受影响。旧验证数据会先备份。')
if((Read-Host '请输入上述独立演示库全名确认') -cne $demoDatabase){throw '已取消，未修改数据库'}
if(Get-OwnedServer (Join-Path $LocalRoot 'verify-server.json')){& (Join-Path $PSScriptRoot 'Stop-Verify.ps1')}
Assert-LocalPortAvailable $Port
$mysql=Resolve-MySqlTool 'mysql.exe';$dump=Resolve-MySqlTool 'mysqldump.exe'
$root=Join-Path $LocalRoot ('demo-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$baseline=Join-Path $root 'baseline'
New-Item -ItemType Directory -Path $baseline -Force | Out-Null
$server=$null
try {
 $env:MYSQL_PWD=$env:DB_PASSWORD
 & $dump --protocol=TCP --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USER --single-transaction --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "--result-file=$root\previous-verification.sql" $demoDatabase
 if($LASTEXITCODE -ne 0){throw '请先建立并授权独立验证库；备份失败，未清理任何数据'}
 $tables=@(& $mysql --protocol=TCP --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USER --database=$demoDatabase --batch --skip-column-names --execute='SHOW TABLES')
 if($LASTEXITCODE -ne 0){throw '无法读取独立验证库'}
 $tables=@($tables | Where-Object {$_ -match '^af_[a-z0-9_]+$'})
 if($tables.Count){
  $statement='SET FOREIGN_KEY_CHECKS=0; DROP TABLE '+(($tables | ForEach-Object {'`'+$_+'`'}) -join ',')+'; SET FOREIGN_KEY_CHECKS=1;'
  $statement | & $mysql --protocol=TCP --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USER --database=$demoDatabase --default-character-set=utf8mb4
  if($LASTEXITCODE -ne 0){throw '独立验证表重建未完成，原数据已备份'}
 }
 $env:DB_NAME=$demoDatabase;$env:APP_PORT=[string]$Port;$env:APP_STORAGE=Join-Path $root 'uploads';$env:APP_LOG=Join-Path $root 'application.log';$env:APP_SEED='true'
 $url='http://127.0.0.1:'+ $Port
 $server=Start-Process -FilePath $JavaCommand -ArgumentList @('-jar','backend\target\antifraud-1.0.0.jar') -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root 'stdout.log') -RedirectStandardError (Join-Path $root 'stderr.log') -PassThru
 @{pid=$server.Id;startedAt=$server.StartTime.ToUniversalTime().ToString('o');database=$demoDatabase;root=$root;url=$url;port=$Port} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $LocalRoot 'verify-server.json') -Encoding UTF8
 Wait-PrototypeReady $server $url
 $web=New-Object Microsoft.PowerShell.Commands.WebRequestSession
 $headers=@{}
 function Invoke-DemoApi([string]$path,$body=$null){
  $options=@{Uri=$url+'/api'+$path;WebSession=$web;UseBasicParsing=$true;Headers=$headers}
  if($null -ne $body){$options.Method='Post';$options.ContentType='application/json; charset=utf-8';$options.Body=[Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6))}
  $response=Invoke-WebRequest @options
  # Windows PowerShell 5.1 otherwise guesses the encoding of JSON without a charset.
  $response.RawContentStream.Position=0
  $reader=New-Object IO.StreamReader($response.RawContentStream,[Text.Encoding]::UTF8)
  try{return ($reader.ReadToEnd() | ConvertFrom-Json)}finally{$reader.Dispose()}
 }
 $session=Invoke-DemoApi '/session'
 $headers=@{'X-CSRF-Token'=$session.csrf}
 $login=Invoke-DemoApi '/login' @{username='center';password='Demo2026!';role='CENTER'}
 $headers=@{'X-CSRF-Token'=$login.csrf}
 $cases=@(
  @{kind='号码';value='13800000008';description='演示起点：低风险待核实线索'},
  @{kind='账户';value='6222000000000099';description='演示起点：中风险退款核验';amount=6000;transactionTime='23:30';remote=$true},
  @{kind='账户';value='6222000000000001';description='演示起点：高风险转账线索';amount=6000;transactionTime='23:30';remote=$true}
 )
 $levels=@()
 foreach($case in $cases){
  $case.requestKey=[Guid]::NewGuid().ToString()
  $accepted=Invoke-DemoApi '/reports' $case
  $report=Invoke-DemoApi ('/reports/'+$accepted.id)
  if($report.processing -ne '已研判'){throw '演示案例未完成研判'}
  $alert=Invoke-DemoApi ('/alerts/'+$report.alert_id)
  $levels+=@{report=$accepted.code;alert=$alert.code;level=$alert.level;score=$alert.score}
 }
 if(($levels.level -join ',') -ne '低,中,高'){throw '演示案例风险等级不符合预期'}
 $server.Kill();$server.WaitForExit();$server=$null
 $tables=@(& $mysql --protocol=TCP --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USER --database=$demoDatabase --batch --skip-column-names --execute='SHOW TABLES') | Where-Object {$_ -match '^af_[a-z0-9_]+$'}
 & $dump --protocol=TCP --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USER --single-transaction --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "--result-file=$baseline\database.sql" $demoDatabase @tables
 if($LASTEXITCODE -ne 0){throw '初始快照保存失败'}
 Copy-Item -LiteralPath (Join-Path $LocalRoot 'app-key.dpapi') -Destination $baseline
 $files=Get-ChildItem -LiteralPath $baseline -File | ForEach-Object {@{path=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}}
 @{kind='course-demo-baseline';createdAt=(Get-Date).ToString('o');database=$demoDatabase;cases=$levels;files=@($files)} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $baseline 'manifest.json') -Encoding UTF8
 @{backup=$baseline;database=$demoDatabase;port=$Port} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $LocalRoot 'demo-baseline.json') -Encoding UTF8
 Write-Output ('初始快照已保存：'+$baseline+'。固定9个账号、4个初始账户、低中高3条待核实线索；尚无资金占用。运行 scripts/Start-Demo.ps1 可恢复并启动。')
} finally {if($server -and !$server.HasExited){$server.Kill();$server.WaitForExit()};Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue}
