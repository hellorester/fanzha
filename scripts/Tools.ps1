$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$LocalRoot = Join-Path $ProjectRoot '.local'

function Get-LocalToolSetting([string]$Name) {
    $settingsPath = Join-Path $LocalRoot 'tools.json'
    if (!(Test-Path -LiteralPath $settingsPath)) { return $null }
    try { $settings = Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json }
    catch { throw '.local/tools.json 格式无效，请使用 JSON 对象和双引号。' }
    $property = $settings.PSObject.Properties[$Name]
    if (!$property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) { return $null }
    if ($property.Value -isnot [string]) { throw "tools.json 的 $Name 必须是路径字符串。" }
    $value = [Environment]::ExpandEnvironmentVariables($property.Value)
    if (![IO.Path]::IsPathRooted($value)) { $value = Join-Path $ProjectRoot $value }
    return [IO.Path]::GetFullPath($value)
}

function Find-PathCommand([string]$Name) {
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    return $null
}

function Add-ToolDirectory([string]$Directory) {
    if (($env:Path -split ';') -notcontains $Directory) { $env:Path = $Directory + ';' + $env:Path }
}

function Initialize-Java([switch]$RequireCompiler) {
    $javaDirectory = Get-LocalToolSetting 'javaHome'
    if (!$javaDirectory) { $javaDirectory = $env:JAVA_HOME }
    if ($javaDirectory) {
        $java = Join-Path $javaDirectory 'bin\java.exe'
        if (!(Test-Path -LiteralPath $java -PathType Leaf)) { throw 'javaHome 或 JAVA_HOME 无效，请指向含 bin/java.exe 的 Java 21 目录。' }
    } else { $java = Find-PathCommand 'java.exe' }
    if (!$java) { throw '找不到 Java。请安装 Java 21，并设置 JAVA_HOME 或加入 PATH。' }
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $java
    $info.Arguments = '-XshowSettings:properties -version'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($info)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(10000)) { $process.Kill(); throw 'Java 版本检查超时，请检查 JAVA_HOME 指向的程序。' }
        $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'Java 版本检查失败。' }
    } finally { $process.Dispose() }
    $major = [regex]::Match($output, '(?m)^\s*java\.specification\.version\s*=\s*(\d+)')
    $resolved = [regex]::Match($output, '(?m)^\s*java\.home\s*=\s*(.+)$')
    if (!$major.Success -or [int]$major.Groups[1].Value -lt 21 -or !$resolved.Success) { throw '本项目需要 Java 21 或更高版本，建议两位开发者统一使用 Java 21。' }
    $env:JAVA_HOME = $resolved.Groups[1].Value.Trim()
    if ($RequireCompiler -and !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { throw '构建需要完整 JDK，请将 JAVA_HOME 指向含 javac.exe 的 JDK 21。' }
    Add-ToolDirectory (Join-Path $env:JAVA_HOME 'bin')
    return (Join-Path $env:JAVA_HOME 'bin\java.exe')
}

function Initialize-Node {
    $nodeDirectory = Get-LocalToolSetting 'nodeHome'
    if (!$nodeDirectory) { $nodeDirectory = $env:NODE_HOME }
    if ($nodeDirectory) {
        $node = Join-Path $nodeDirectory 'node.exe'
        if (!(Test-Path -LiteralPath $node -PathType Leaf)) { throw 'nodeHome 或 NODE_HOME 无效，请指向含 node.exe 的目录。' }
    } else { $node = Find-PathCommand 'node.exe' }
    if (!$node) { throw '找不到 Node.js。请安装 Node.js 22.12+，并加入 PATH。' }
    $version = (& $node --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^v(\d+)\.(\d+)\.(\d+)') { throw 'Node.js 版本检查失败。' }
    $major = [int]$Matches[1]; $minor = [int]$Matches[2]
    if (!(($major -eq 20 -and $minor -ge 19) -or ($major -eq 22 -and $minor -ge 12) -or $major -ge 23)) { throw '当前 Vite 需要 Node.js 20.19 或 22.12 以上的受支持版本。' }
    Add-ToolDirectory (Split-Path -Parent $node)
    $npm = Join-Path (Split-Path -Parent $node) 'npm.cmd'
    if (!(Test-Path -LiteralPath $npm -PathType Leaf)) { $npm = Find-PathCommand 'npm.cmd' }
    if (!$npm) { throw '找不到 npm.cmd，请检查 Node.js 安装。' }
    return $npm
}

function Resolve-MySqlTool([ValidateSet('mysql.exe','mysqldump.exe')][string]$Name) {
    $explicit = Get-LocalToolSetting 'mysqlBin'
    if ($explicit) {
        $path = Join-Path $explicit $Name
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "mysqlBin 目录中找不到 $Name。" }
        return $path
    }
    $found = Find-PathCommand $Name
    if ($found) { return $found }
    $directories = @()
    if ($env:MYSQL_HOME) { $directories += (Join-Path $env:MYSQL_HOME 'bin'); $directories += $env:MYSQL_HOME }
    foreach ($registryRoot in @('HKLM:\SOFTWARE\MySQL AB','HKLM:\SOFTWARE\WOW6432Node\MySQL AB')) {
        foreach ($key in @(Get-ChildItem -LiteralPath $registryRoot -ErrorAction SilentlyContinue | Where-Object {$_.PSChildName -like 'MySQL Server *'} | Sort-Object PSChildName -Descending)) {
            $location = (Get-ItemProperty -LiteralPath $key.PSPath -ErrorAction SilentlyContinue).Location
            if ($location) { $directories += (Join-Path $location 'bin') }
        }
    }
    foreach ($programDirectory in @($env:ProgramFiles,${env:ProgramFiles(x86)})) {
        if (!$programDirectory) { continue }
        $mysqlRoot = Join-Path $programDirectory 'MySQL'
        foreach ($install in @(Get-ChildItem -LiteralPath $mysqlRoot -Directory -ErrorAction SilentlyContinue | Where-Object {$_.Name -like 'MySQL Server *'} | Sort-Object Name -Descending)) { $directories += (Join-Path $install.FullName 'bin') }
    }
    foreach ($directory in ($directories | Select-Object -Unique)) {
        $path = Join-Path $directory $Name
        if (Test-Path -LiteralPath $path -PathType Leaf) { return $path }
    }
    throw "找不到 $Name。请将 MySQL 的 bin 加入 PATH，或在 .local/tools.json 设置 mysqlBin。运行原型本身不需要 MySQL 命令行工具，备份或恢复才需要。"
}

function Get-OwnedServer([string]$RecordPath) {
    if (!(Test-Path -LiteralPath $RecordPath)) { return $null }
    $record = Get-Content -LiteralPath $RecordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -eq 'java' -and [Math]::Abs(($process.StartTime.ToUniversalTime()-([datetime]$record.startedAt).ToUniversalTime()).TotalSeconds) -lt 1) { return $process }
    return $null
}

function Assert-LocalPortAvailable([int]$Port) {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$Port)
    try { $listener.Start() }
    catch { throw "本机端口 $Port 已被占用或不可用，请先停止对应实例，或更换本机配置中的 appPort。" }
    finally { $listener.Stop() }
}

function Wait-PrototypeReady($Process,[string]$Url) {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $Process.Refresh()
        if ($Process.HasExited) { throw '启动失败，请查看实例目录内的 stdout.log 与 stderr.log。' }
        try {
            $response = Invoke-WebRequest -Uri ($Url+'/api/session') -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw '启动尚未就绪，请查看实例日志；不要连续重复启动。'
}
