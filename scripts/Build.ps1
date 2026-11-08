param([switch]$SkipTests,[switch]$Offline)
. (Join-Path $PSScriptRoot 'Tools.ps1')
$null=Initialize-Java -RequireCompiler
$npm=Initialize-Node
$MavenCommand=Join-Path $ProjectRoot 'backend\mvnw.cmd'
if(!(Test-Path -LiteralPath $MavenCommand)){throw '项目缺少 backend/mvnw.cmd，请完整克隆仓库。'}
$mavenHome=Get-LocalToolSetting 'mavenUserHome'
if($mavenHome){$env:MAVEN_USER_HOME=$mavenHome}
$repository=Get-LocalToolSetting 'mavenRepository'
if(!$repository -and $env:MAVEN_USER_HOME){$repository=Join-Path $env:MAVEN_USER_HOME 'repository'}
$cache=Get-LocalToolSetting 'npmCache'
if($cache){$env:npm_config_cache=$cache}
# Building does not read database credentials or generate application keys.
& (Join-Path $PSScriptRoot 'Stop.ps1')
$distPath=[IO.Path]::GetFullPath((Join-Path $ProjectRoot 'frontend\dist'))
if(!$distPath.StartsWith($ProjectRoot+[IO.Path]::DirectorySeparatorChar)){throw '前端输出目录越界'}
if(Test-Path -LiteralPath $distPath){Remove-Item -LiteralPath $distPath -Recurse -Force}
Push-Location (Join-Path $ProjectRoot 'frontend')
try{
 $installArgs=@('ci','--no-audit','--no-fund')
 if($Offline){$installArgs+='--offline'}
 & $npm @installArgs
 if($LASTEXITCODE -ne 0){throw '前端依赖安装失败。首次构建请联网；缓存完整后才使用 -Offline。'}
 & $npm run build
 if($LASTEXITCODE -ne 0){throw '前端构建失败'}
}finally{Pop-Location}
$staticPath=[IO.Path]::GetFullPath((Join-Path $ProjectRoot 'backend\src\main\resources\static'))
if(!$staticPath.StartsWith($ProjectRoot+[IO.Path]::DirectorySeparatorChar)){throw '静态资源目录越界'}
if(Test-Path -LiteralPath $staticPath){Remove-Item -LiteralPath $staticPath -Recurse -Force}
New-Item -ItemType Directory -Path $staticPath -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'frontend\dist') | Copy-Item -Destination $staticPath -Recurse -Force
$mavenArgs=@('-B','-f',(Join-Path $ProjectRoot 'backend\pom.xml'))
if($repository){$mavenArgs+="-Dmaven.repo.local=$repository"}
if($Offline){$mavenArgs+='-o'}
if($SkipTests){$mavenArgs+='-DskipTests'}
$mavenArgs+=@('clean','package')
& $MavenCommand @mavenArgs
if($LASTEXITCODE -ne 0){throw '后端构建失败。首次构建需要联网下载 Maven 与依赖，请检查上述日志。'}
Write-Output '完整运行包已构建：backend\target\antifraud-1.0.0.jar'
