# 反诈协同处置平台

## 启动

双击 **启动原型.cmd**，等到提示“原型已就绪”，在浏览器打开 <http://127.0.0.1:8088>。使用完双击 **停止原型.cmd**。数据库和本机环境已配置，重启后业务记录仍然保留。

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

`Build.ps1` 使用本机已配置的 D 盘 Node/Maven 缓存。其他电脑先准备 Java21和MySQL8，并建立专用数据库用户，再在新的目录运行 `scripts/Configure.ps1` 保存连接配置。不要覆盖现有 `.local` 目录，该目录保存本机加密凭据、业务密钥、附件和日志。

备份使用 `scripts/Backup.ps1`。`Restore-Verify.ps1 -BackupFolder "完整备份目录"` 仅恢复到独立验证库并在8089启动，结束时执行 `Stop-Verify.ps1`。主业务库为 `antifraud_course`，容量和故障注入使用独立的 `antifraud_course_verify`。

## 已做的检查

9个规则单元测试方法、354项接口请求或断言、四角色29个页面入口、主线真实表单操作、10万条虚构数据及20个独立账号的容量检查、数据库和附件恢复、本机启停均已通过。本机结果保存在 `tests/results`，运行记录不上传Git仓库。综合测试会生成业务数据，部分脚本引用本轮案例ID，不作为每次启动步骤。

启动失败时先看窗口提示和 `.local/logs`，确认MySQL服务运行且8088端口未被其他程序占用。

## 首次从仓库构建

准备 Java 21、Node.js 22.12以上及 MySQL 8，设置 `JAVA_HOME`，建立 utf8mb4 数据库和专用数据库用户。在仓库根目录使用 PowerShell 执行：

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd run build
Set-Location ..
New-Item -ItemType Directory -Path backend/src/main/resources/static -Force
Copy-Item -Path frontend/dist/* -Destination backend/src/main/resources/static -Recurse -Force
Set-Location backend
.\mvnw.cmd clean package
Set-Location ..
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Configure.ps1
.\启动原型.cmd
```

首次构建需要联网下载依赖。`Configure.ps1` 默认连接本机数据库 `antifraud_course`，用户 `antifraud_dev`，也可传入 `-Database`、`-Username`、`-HostAddress` 和 `-Port`；它只保存连接配置，不创建数据库及数据库用户。应用首次启动会创建表和虚构基础资料。

仓库不含 `.local`、数据库口令、业务密钥、上传文件、备份、依赖目录、构建产物和本机测试输出。复制源代码不会带走现有业务数据。