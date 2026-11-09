"""Run only while the disposable HTTP server is stopped: fail-closed indexes and upgrade lock."""
from test_db import *
from integration import BASE, require, results
import json,time,urllib.request,urllib.error

root=pathlib.Path(__file__).resolve().parent.parent
output=root/'tests/results';output.mkdir(exist_ok=True)
process=None;handle=None;gate=None

def launch(label):
    global process,handle
    command=os.environ.get('TEST_JAVA_COMMAND') or shutil.which('java')
    if not command:raise RuntimeError('Java is required for migration safety tests')
    handle=(output/f'migration-{label}.log').open('wb')
    process=subprocess.Popen([command,'-jar',str(root/'backend/target/antifraud-1.0.0.jar')],cwd=root,stdout=handle,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)

def stop():
    global process,handle
    if process and process.poll() is None:
        process.terminate()
        try:process.wait(timeout=15)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
    if handle:handle.close()
    process=None;handle=None

def wait_for(test,label):
    until=time.monotonic()+25
    while time.monotonic()<until:
        if test():return
        if process and process.poll() is not None:raise AssertionError('Migration server exited before '+label)
        time.sleep(.1)
    raise AssertionError(label)

def http_status():
    try:
        with urllib.request.urlopen(BASE+'/session',timeout=2) as response:return response.status
    except urllib.error.HTTPError as error:return error.code
    except (OSError,urllib.error.URLError):return None

try:
    assert sql('SELECT DATABASE()')==VERIFY
    before=sql('SELECT (SELECT COUNT(*) FROM af_report),(SELECT COUNT(*) FROM af_ledger),(SELECT COUNT(*) FROM af_log)')
    sql("ALTER TABLE af_blacklist ADD UNIQUE INDEX kind(source,id); DELETE FROM af_migration WHERE version=1")
    launch('index-protection')
    require(process.wait(timeout=30)!=0,'同名但结构不同的旧索引会让升级明确失败')
    stop()
    require(sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_blacklist' AND INDEX_NAME='kind'")=='source,id','失败升级没有删除或改写未知索引')
    require(sql('SELECT (SELECT COUNT(*) FROM af_report),(SELECT COUNT(*) FROM af_ledger),(SELECT COUNT(*) FROM af_log)')==before,'失败升级保留举报、资金及日志记录')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=1')=='0','未完成的升级不会错误标记成功')
    sql('ALTER TABLE af_blacklist DROP INDEX kind')

    name='antifraud-upgrade-'+hashlib.sha256(VERIFY.encode()).hexdigest()[:40]
    gate=SqlSession();gate.send('SELECT GET_LOCK('+quoted(name)+',0);')
    wait_for(lambda:sql('SELECT IS_USED_LOCK('+quoted(name)+') IS NOT NULL')=='1','升级锁就绪')
    launch('named-lock')
    wait_for(lambda:http_status()==503,'升级锁等待期间接口应返回503')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=1')=='0','另一个实例持有锁时不会越过锁修改迁移版本')
    gate.finish();gate=None
    wait_for(lambda:http_status()==200,'释放升级锁后服务应完成初始化')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=1')=='1','释放锁后升级成功且接口恢复')
    status='passed'
except Exception:
    status='failed';raise
finally:
    if gate:gate.finish()
    stop()
    # Only the fixture's exact two-column index may be removed in cleanup.
    columns=sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_blacklist' AND INDEX_NAME='kind'")
    if columns=='source,id':sql('ALTER TABLE af_blacklist DROP INDEX kind')
    (output/'migration-safety.json').write_text(json.dumps({'status':status,'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
print('Migration safety passed:',len(results),'checks')
