# 反诈协同处置平台

## 启动

本机已配置时，双击 **启动原型.cmd**，等到提示“原型已就绪”，在浏览器打开提示的地址，默认 <http://127.0.0.1:8088>。使用完双击 **停止原型.cmd**。重启后业务记录仍然保留。首次克隆请先完成下方的构建和本机配置。

| 角色 | 账号 |
|---|---|
| 中心管理员 | `center` |
| 中心研判员 | `reviewer` |
| 群众 | `public1`、`public2` |
| 支付机构 | `pay1`、`pay2` |
| 青禾社区网格员 | `grid1`、`grid2` |
| 云栖社区网格员 | `grid3` |

统一演示密码：**`Demo2026!`**。它与数据库密码无关。更换角色先退出登录；同一浏览器普通标签页共享会话。

## 操作顺序

1. `public2` 提交举报：账户 `6222000000000001`、6000元、23:30、异地，可上传 `tests/fixtures/演示材料.txt`。
2. `center` 核实举报并查看五因子评分，选择模拟账户1，派发止付与云栖上门任务。
3. `pay1` 受理并提交回执。`grid3` 领取任务，记录到场，填写过程和材料后回传。
4. `center` 核对两端结果并办结。返还、申诉、时效、宣传与管理可从对应菜单办理。

已有验收案例已走过后续返还或解除流程，重演请新建举报。全部居民、账户与材料均为虚构演示数据。银行资金、身份核验和坐标采用模拟；权限、规则、状态、金额约束和数据库保存真实执行。

## 源码与维护

前端位于 `frontend/src`，后端位于 `backend/src`。技术栈为 Vue 3、Spring Boot、Java 21 和 MySQL 8。本机已构建的 `backend/target/antifraud-1.0.0.jar` 包含前端资源，直接运行不需要另开Vite。Git仓库只保存源码，首次克隆需先构建。

本机重新构建：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Start.ps1
```

`Build.ps1` 自动查找 Java、Node/npm，使用仓库自带 Maven Wrapper，默认联网安装锁文件中的依赖。构建不需要数据库连接和密码。已有完整依赖缓存时可加 `-Offline`；仅需要构建且明确跳过单元测试时可加 `-SkipTests`。

备份使用 `scripts/Backup.ps1`，MySQL命令行工具从本机设置、PATH、MYSQL_HOME、安装注册表或常见安装目录查找。`Restore-Verify.ps1 -BackupFolder "完整备份目录"` 默认恢复到“当前数据库名_verify”，使用8089端口；可通过 `-TargetDatabase` 和 `-Port` 指定已创建的独立验证库及端口。该验证库需要事先创建并授权，不能指定为当前业务库，恢复会覆盖验证库内同名表。结束时执行 `Stop-Verify.ps1`。

## 已做的检查

9个规则单元测试方法、354项接口请求或断言、四角色29个页面入口、主线真实表单操作、10万条虚构数据及20个独立账号的容量检查、数据库和附件恢复、本机启停均已通过。本机结果保存在 `tests/results`，运行记录不上传Git仓库。综合测试会生成业务数据，部分脚本引用本轮案例ID，不作为每次启动步骤。

工具路径和本机配置另通过27项脚本检查，并在不含数据库配置的全新目录完成构建；备份、独立库恢复及自定义端口启停已实测。可运行 `powershell -NoProfile -ExecutionPolicy Bypass -File tests/portable_scripts.ps1` 重做脚本检查，该检查使用临时目录，不连接业务数据库。

启动失败时先看窗口提示和 `.local/logs`，确认自己的MySQL服务运行且应用端口未被其他程序占用。脚本面向 Windows PowerShell 5.1及以上，macOS/Linux需自行使用等价的环境变量和启动命令。

## 首次从仓库构建

准备 JDK 21、Node.js 22.12以上及 MySQL 8，设置 `JAVA_HOME` 或把Java加入PATH，并把Node/npm加入PATH。先在仓库根目录构建，不用复制他人的数据库配置：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build.ps1
```

首次构建需要联网下载Maven和依赖，无需单独安装Maven。然后在自己的MySQL中建立utf8mb4数据库和专用用户，执行本机配置。例如自己建立的数据库名为 `fanzha_dev`、用户为 `fanzha_user`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Configure.ps1 -Database fanzha_dev -Username fanzha_user -HostAddress 127.0.0.1 -Port 3306
.\启动原型.cmd
```

脚本在本机提示输入数据库密码；它只保存连接配置，不创建数据库或数据库用户。`-Port` 是MySQL端口，`-AppPort` 是网页端口，默认8088。应用首次启动会创建表和虚构基础资料。已有配置会拒绝覆盖；更改连接地址或端口可以编辑自己的 `.local/database.json` 后重启。不同电脑的库名、账号、密码和端口无需一致。

## 不同电脑的工具路径

一般只需配置环境变量，不用修改脚本。Java按 `.local/tools.json` 的 `javaHome`、`JAVA_HOME`、PATH查找；Node按 `nodeHome`、`NODE_HOME`、PATH查找。MySQL客户端仅在备份及恢复时需要。

特殊安装目录可把 `scripts/tools.example.json` 复制为 `.local/tools.json`，仅填写需要覆盖的字段。例如：

```json
{
  "mysqlBin": "E:/Software/MySQL/bin",
  "mavenUserHome": "E:/DevCache/maven",
  "npmCache": "E:/DevCache/npm"
}
```

`javaHome` 指向JDK根目录，`nodeHome` 指向含node.exe和npm.cmd的目录，`mysqlBin` 指向含mysql.exe和mysqldump.exe的目录。路径支持空格，JSON建议使用正斜杠。`mavenUserHome` 控制Wrapper缓存，并默认使用其repository子目录保存Maven依赖；`mavenRepository`可单独覆盖依赖目录。未设置时沿用当前电脑的环境变量与默认缓存。

`.local`已被Git忽略，自己的工具路径和数据库配置不会提交或覆盖队友配置。密码及应用密钥使用当前Windows用户的DPAPI保存，请各自初始化，不复制他人的 `.local`。修改数据库结构时仍须随代码提交升级SQL并在各自数据库执行。

仓库不含 `.local`、数据库口令、业务密钥、上传文件、备份、依赖目录、构建产物和本机测试输出。复制源代码不会带走现有业务数据。
