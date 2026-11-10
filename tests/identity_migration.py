"""Restart-only test for legacy applications without bank ownership/payee evidence.

Run after stopping the disposable HTTP instance. Existing valid v9 applications are
kept alongside legacy rows to test a retry of the upgrade without erasing new work.
"""
from test_db import *
from integration import BASE, Client, require, results
import urllib.request,urllib.error,time,json

root=pathlib.Path(__file__).resolve().parents[1]
output=root/'tests/results'
server=None;handle=None
def start():
    global server,handle
    handle=(output/'identity-migration-server.log').open('wb')
    server=subprocess.Popen([os.environ.get('TEST_JAVA_COMMAND') or shutil.which('java'),'-jar',str(root/'backend/target/antifraud-1.0.0.jar'),'--app.commands.scan-enabled=false'],cwd=root,stdout=handle,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    end=time.monotonic()+30
    while time.monotonic()<end:
        if server.poll() is not None:raise AssertionError('Upgrade process exited')
        try:
            with urllib.request.urlopen(BASE+'/session',timeout=2) as response:
                if response.status==200:return
        except (OSError,urllib.error.URLError):pass
        time.sleep(.1)
    raise AssertionError('Upgrade readiness timeout')
def stop():
    global server,handle
    if server and server.poll() is None:
        server.terminate()
        try:server.wait(timeout=10)
        except subprocess.TimeoutExpired:server.kill();server.wait(timeout=5)
    if handle:handle.close()
    server=None;handle=None

error=None
try:
    assert sql('SELECT DATABASE()')==VERIFY
    # Select an existing valid freeze without changing ownership or money balances.
    cid=int(sql("SELECT id FROM af_command WHERE kind='止付' AND state IN ('生效中','已延期') AND remaining-reserved>=20 AND expires_at>DATE_ADD(NOW(),INTERVAL 1 HOUR) ORDER BY id DESC LIMIT 1"))
    original_reserved=sql(f'SELECT reserved FROM af_command WHERE id={cid}')
    valid_states=sql('SELECT id,state FROM af_application WHERE applicant_person_id IS NOT NULL ORDER BY id')
    balances=sql('SELECT id,available FROM af_bank ORDER BY id')
    ledger_count=sql('SELECT COUNT(*) FROM af_ledger')
    tag='LEGACY-ID-'+str(int(time.time()))
    apps=[];children=[]
    for kind,state,childKind in [('返还','核定通过','返还'),('申诉','已生成解除指令','解除'),('申诉','待受理',None)]:
        code=tag+str(len(apps));aid=int(sql(f"INSERT INTO af_application(code,kind,owner_id,original_id,state,amount,approved,reason,note,created_at,updated_at) VALUES({quoted(code)},{quoted(kind)},2,{cid},{quoted(state)},10,10,'legacy owner unknown','保留旧审核意见',NOW(),NOW());SELECT LAST_INSERT_ID()"));apps.append(aid)
        if childKind:
            child=int(sql(f"INSERT INTO af_command(code,kind,alert_id,original_id,application_id,bank_id,org_id,victim_id,requested,state,created_at) SELECT {quoted(code+'C')},{quoted(childKind)},alert_id,id,{aid},bank_id,org_id,victim_id,10,'已送达',NOW() FROM af_command WHERE id={cid};SELECT LAST_INSERT_ID()"));children.append(child)
    sql(f'UPDATE af_command SET reserved=reserved+10 WHERE id={cid}')
    # A failed legacy appeal also must not block the rightful holder from starting a new application.
    failed=int(sql(f"INSERT INTO af_application(code,kind,owner_id,original_id,state,reason,note,created_at,updated_at) VALUES({quoted(tag+'F')},'申诉',2,{cid},'已生成解除指令','old failed release','保留旧审核意见',NOW(),NOW());SELECT LAST_INSERT_ID()"));apps.append(failed)
    sql(f"INSERT INTO af_command(code,kind,alert_id,original_id,application_id,bank_id,org_id,victim_id,state,created_at) SELECT {quoted(tag+'FC')},'解除',alert_id,id,{failed},bank_id,org_id,victim_id,'拒绝执行',NOW() FROM af_command WHERE id={cid}")
    sql('DELETE FROM af_migration WHERE version=9')
    start();center=Client('center')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=9')=='1','账户关系升级成功登记版本9')
    require(sql(f"SELECT COUNT(*) FROM af_application WHERE id IN ({','.join(map(str,apps))}) AND state='已失效' AND note='保留旧审核意见'")=='4','缺少身份依据的旧待办与失败申诉结束，不冒认户主且保留原意见')
    require(sql(f"SELECT COUNT(*) FROM af_command WHERE id IN ({','.join(map(str,children))}) AND state='已取消'")=='2','旧未执行返还与解除指令停止')
    require(sql(f'SELECT reserved FROM af_command WHERE id={cid}')==original_reserved,'旧返还占用只释放一次')
    require(sql('SELECT id,state FROM af_application WHERE applicant_person_id IS NOT NULL ORDER BY id')==valid_states,'重新升级不改动已核验的新申请')
    require(sql('SELECT id,available FROM af_bank ORDER BY id')==balances and sql('SELECT COUNT(*) FROM af_ledger')==ledger_count,'升级不改实际余额或已执行资金流水')
    require(center.call('/reconciliation')['ok'] and center.call('/audit/verify')['ok'],'历史关系修复后资金与审计一致')
    counts=sql('SELECT (SELECT COUNT(*) FROM af_log),(SELECT COUNT(*) FROM af_application),(SELECT COUNT(*) FROM af_command)')
    stop();start()
    require(sql('SELECT (SELECT COUNT(*) FROM af_log),(SELECT COUNT(*) FROM af_application),(SELECT COUNT(*) FROM af_command)')==counts,'再次启动不重复失效、取消或追加迁移日志')
except Exception as e:error=str(e)
finally:stop()
report={'status':'failed' if error else 'passed','steps':len(results),'error':error,'results':results}
(output/'identity-migration.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False))
if error:sys.exit(1)
