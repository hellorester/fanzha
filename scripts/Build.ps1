param([switch]$SkipTests)
. (Join-Path $PSScriptRoot 'Env.ps1')
if(!$MavenCommand){$MavenCommand=Join-Path $ProjectRoot 'backend\mvnw.cmd'}
& (Join-Path $PSScriptRoot 'Stop.ps1')
$distPath=[IO.Path]::GetFullPath((Join-Path $ProjectRoot 'frontend\dist'))
if(!$distPath.StartsWith($ProjectRoot+[IO.Path]::DirectorySeparatorChar)){throw '前端输出目录越界'}
if(Test-Path -LiteralPath $distPath){Remove-Item -LiteralPath $distPath -Recurse -Force}
Push-Location (Join-Path $ProjectRoot 'frontend')
try{
 if(!(Test-Path -LiteralPath 'node_modules')){& 'D:\nodejs\npm.cmd' ci --offline;if($LASTEXITCODE -ne 0){throw '本机依赖缓存缺失，请联网运行 npm ci 后重试。'}}
 & 'D:\nodejs\npm.cmd' run build
 if($LASTEXITCODE -ne 0){throw '前端构建失败'}
}finally{Pop-Location}
$staticPath=[IO.Path]::GetFullPath((Join-Path $ProjectRoot 'backend\src\main\resources\static'))
if(!$staticPath.StartsWith($ProjectRoot+[IO.Path]::DirectorySeparatorChar)){throw '静态资源目录越界'}
if(Test-Path -LiteralPath $staticPath){Remove-Item -LiteralPath $staticPath -Recurse -Force}
New-Item -ItemType Directory -Path $staticPath -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'frontend\dist') | Copy-Item -Destination $staticPath -Recurse -Force
$mavenArgs=@('-f',(Join-Path $ProjectRoot 'backend\pom.xml'),'-Dmaven.repo.local=D:\Devtool\maven\repository','-o','clean','package')
if($SkipTests){$mavenArgs+='-DskipTests'}
& $MavenCommand @mavenArgs
if($LASTEXITCODE -ne 0){throw '后端构建失败'}
Write-Output '完整运行包已构建：backend\target\antifraud-1.0.0.jar'
