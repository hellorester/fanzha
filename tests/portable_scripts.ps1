# Runs without Pester or database access. Uses an isolated copy of the scripts.
$ErrorActionPreference='Stop'
$sourceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot=Join-Path $sourceRoot ('.local\portability-tests\新同学 空格路径-'+[guid]::NewGuid().ToString('N'))
$testRoot=[IO.Path]::GetFullPath($testRoot)
if(!$testRoot.StartsWith($sourceRoot+'\.local\portability-tests\')){throw '测试目录越界'}
$saved=@{}
foreach($name in @('JAVA_HOME','NODE_HOME','MYSQL_HOME','Path','DB_HOST','DB_PORT','DB_NAME','DB_USER','DB_PASSWORD','APP_KEY','APP_PORT','APP_LOG','APP_STORAGE')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$checks=New-Object 'System.Collections.Generic.List[string]'
function Check([bool]$Condition,[string]$Message){if(!$Condition){throw $Message};$checks.Add($Message)}
function Expect-Failure([scriptblock]$Action,[string]$Pattern,[string]$Message){
 $caught=$false
 try{& $Action}catch{if($_.Exception.Message -notmatch $Pattern){throw};$caught=$true}
 Check $caught $Message
}
try{
 New-Item -ItemType Directory -Path (Join-Path $testRoot 'scripts') -Force | Out-Null
 Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'scripts') -Filter '*.ps1' | Copy-Item -Destination (Join-Path $testRoot 'scripts')
 foreach($file in (Get-ChildItem -LiteralPath (Join-Path $testRoot 'scripts') -Filter '*.ps1')){
  $tokens=$null;$errors=$null
  $null=[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors)
  Check ($errors.Count -eq 0) ('PowerShell parses '+$file.Name)
 }
 . (Join-Path $testRoot 'scripts\Tools.ps1')
 Check (!(Test-Path -LiteralPath $LocalRoot)) 'Tool loading does not create local config'
 $java=Initialize-Java -RequireCompiler
 $npm=Initialize-Node
 Check ((Test-Path -LiteralPath $java) -and (Test-Path -LiteralPath $npm)) 'Java and npm discovered without database config'
 $env:JAVA_HOME=$null
 Check ((Initialize-Java -RequireCompiler) -eq $java) 'Java PATH fallback resolves actual JDK home'
 & (Join-Path $testRoot 'scripts\Stop.ps1') | Out-Null
 Check (!(Test-Path -LiteralPath $LocalRoot)) 'Stopping unconfigured clone is harmless'
 Expect-Failure {. (Join-Path $testRoot 'scripts\Env.ps1')} 'Configure.ps1' 'Missing runtime config produces actionable error'
 $password=('Test-only-'+[guid]::NewGuid().ToString('N')) | ConvertTo-SecureString -AsPlainText -Force
 & (Join-Path $testRoot 'scripts\Configure.ps1') -Database teammate_db -Username teammate_user -HostAddress localhost -Port 3307 -AppPort 8097 -Password $password | Out-Null
 $config=Get-Content -LiteralPath (Join-Path $LocalRoot 'database.json') -Raw -Encoding UTF8 | ConvertFrom-Json
 Check ($config.database -eq 'teammate_db' -and $config.username -eq 'teammate_user' -and $config.port -eq 3307 -and $config.appPort -eq 8097) 'Teammate database and port settings remain independent'
 $before=(Get-FileHash -LiteralPath (Join-Path $LocalRoot 'database.json')).Hash
 Expect-Failure {& (Join-Path $testRoot 'scripts\Configure.ps1') -Database changed -Password $password} '已有本机配置' 'Existing configuration cannot be overwritten accidentally'
 Check ((Get-FileHash -LiteralPath (Join-Path $LocalRoot 'database.json')).Hash -eq $before) 'Existing connection file remains intact'
 $env:APP_PORT=$null
 . (Join-Path $testRoot 'scripts\Env.ps1')
 Check ($env:DB_NAME -eq 'teammate_db' -and $env:DB_PORT -eq '3307' -and $env:APP_PORT -eq '8097') 'Runtime consumes per-machine configuration'
 Check ($env:DB_PASSWORD.Length -gt 20 -and $env:APP_KEY.Length -gt 20) 'DPAPI credentials and new application key decrypt locally'
 $keyHash=(Get-FileHash -LiteralPath (Join-Path $LocalRoot 'app-key.dpapi')).Hash
 . (Join-Path $testRoot 'scripts\Env.ps1')
 Check ((Get-FileHash -LiteralPath (Join-Path $LocalRoot 'app-key.dpapi')).Hash -eq $keyHash) 'Repeated startup keeps existing encryption key'
 $bin=Join-Path $testRoot '自定义 MySQL\bin'
 New-Item -ItemType Directory -Path $bin -Force | Out-Null
 foreach($tool in @('mysql.exe','mysqldump.exe')){[IO.File]::WriteAllBytes((Join-Path $bin $tool),[byte[]]@())}
 @{mysqlBin=$bin} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $LocalRoot 'tools.json') -Encoding UTF8
 Check ((Resolve-MySqlTool 'mysql.exe') -eq (Join-Path $bin 'mysql.exe')) 'Explicit MySQL path supports Chinese and spaces'
 Check ((Resolve-MySqlTool 'mysqldump.exe') -eq (Join-Path $bin 'mysqldump.exe')) 'Backup tool uses the same configured installation'
 @{mysqlBin=(Join-Path $bin 'missing')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $LocalRoot 'tools.json') -Encoding UTF8
 Expect-Failure {Resolve-MySqlTool 'mysql.exe'} 'mysqlBin' 'Invalid explicit MySQL path is reported instead of silently falling back'
 '{}' | Set-Content -LiteralPath (Join-Path $LocalRoot 'tools.json') -Encoding UTF8
 $env:Path=$bin+';'+$env:Path
 Check ((Resolve-MySqlTool 'mysql.exe') -eq (Join-Path $bin 'mysql.exe')) 'MySQL PATH fallback works'
 Expect-Failure {& (Join-Path $testRoot 'scripts\Restore-Verify.ps1') -BackupFolder $testRoot -TargetDatabase teammate_db} '不能是当前业务库' 'Restore rejects the current business database'
 $record=Join-Path $LocalRoot 'server.json'
 @{pid=$PID;startedAt=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $record -Encoding UTF8
 Check ($null -eq (Get-OwnedServer $record)) 'Process ownership rejects non-Java PID records'
 $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0)
 try{$listener.Start();$busyPort=$listener.LocalEndpoint.Port;Expect-Failure {Assert-LocalPortAvailable $busyPort} '端口' 'Occupied application port is rejected before launch'}finally{$listener.Stop()}
 $results=Join-Path $sourceRoot 'tests\results'
 New-Item -ItemType Directory -Path $results -Force | Out-Null
 @{status='passed';powershell=$PSVersionTable.PSVersion.ToString();checks=@($checks);count=$checks.Count} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $results 'portability.json') -Encoding UTF8
 Write-Output ('Portable scripts: '+$checks.Count+' checks passed.')
}finally{
 foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
 if(Test-Path -LiteralPath $testRoot){Remove-Item -LiteralPath $testRoot -Recurse -Force}
}
