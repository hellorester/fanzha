param(
 [ValidatePattern('^[a-zA-Z0-9_]{1,64}$')][string]$Database='antifraud_course',
 [ValidatePattern('^[a-zA-Z0-9_]+$')][string]$Username='antifraud_dev',
 [ValidatePattern('^[a-zA-Z0-9._:\-]+$')][string]$HostAddress='127.0.0.1',
 [ValidateRange(1,65535)][int]$Port=3306,
 [ValidateRange(1,65535)][int]$AppPort=8088,
 [Security.SecureString]$Password
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$local=Join-Path $root '.local'
if(Test-Path -LiteralPath (Join-Path $local 'database.json')){throw '已有本机配置，为防止覆盖，请使用现有配置或在新的交付目录首次配置。'}
if($Database -notmatch '^[a-zA-Z0-9_]+$' -or $Username -notmatch '^[a-zA-Z0-9_]+$'){throw '数据库及用户名仅允许字母、数字和下划线。'}
New-Item -ItemType Directory -Path $local -Force | Out-Null
$secure=$Password
if(!$secure){$secure=Read-Host '请输入已经创建的项目 MySQL 用户密码（不是演示登录密码）' -AsSecureString}
if($secure.Length -eq 0){throw '项目数据库密码不能为空。'}
$secure | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $local 'db-password.dpapi') -Encoding ASCII
@{host=$HostAddress;port=$Port;database=$Database;username=$Username;appPort=$AppPort;credentialStorage='Windows DPAPI, current user'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $local 'database.json') -Encoding UTF8
Write-Output '本机连接配置已保存。首次启动会在空数据库内创建表和虚拟演示资料。'
