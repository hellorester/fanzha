"""Run on a disposable instance started with --app.commands.scan-enabled=false.

Past deadlines retain their stored active state until the HTTP dispatch call itself
settles them; no Timer or simulate endpoint is used to perform that transition.
"""
from integration import *
from test_db import sql, VERIFY, SqlSession
from concurrent.futures import ThreadPoolExecutor


def run():
    holder=Client('public3')
    assert sql('SELECT DATABASE()')==VERIFY
    sql('DELETE FROM af_query_limit')
    center,reviewer,public,pay=map(Client,['center','reviewer','public1','pay1'])
    tag='DISPATCH-DEADLINE-'+uuid.uuid4().hex[:8]

    def prepared(receipt=True,extended=False,accept=True):
        report=center.call('/reports',{'reporterRelation':'本人受损','kind':'号码','value':'13800000009','description':tag,'requestKey':uuid.uuid4().hex})
        aid=center.call(f"/reports/{report['id']}")['alert_id']
        center.verify_accounts(aid,[1,3]);center.call(f'/alerts/{aid}/review',{'decision':'通过','victimId':1,'communityId':1,'note':tag})
        task=center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':1000});cid=task['commandId']
        if accept:pay.call(f'/commands/{cid}/accept',{})
        if receipt:pay.call(f'/commands/{cid}/receipt',{'result':'全额执行','amount':1000,'voucher':tag})
        if extended:center.call(f'/commands/{cid}/extend',{'note':tag})
        return aid,cid,task['visitId']

    def expired(aid,cid,vid):
        available=sql('SELECT available FROM af_bank WHERE id=1')
        before_count=sql(f'SELECT COUNT(*) FROM af_command WHERE alert_id={aid}')
        old_state=sql(f'SELECT state FROM af_command WHERE id={cid}')
        require(old_state in ['生效中','已延期'] and sql(f'SELECT active_key IS NOT NULL FROM af_command WHERE id={cid}')=='1', '定时扫描尚未处理，原指令仍保留生效状态及占用键')
        options=center.call(f'/alerts/{aid}')['commands'];row=next(c for c in options if c['id']==cid)
        require(row['dispatch_deadline_passed'] and not row['dispatch_reusable'], '账户选择按实际期限排除未扫描的过期指令')
        require(sql(f'SELECT state FROM af_command WHERE id={cid}')==old_state, '详情读取只显示时效，不提前代替派发完成释放')
        blocked=center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':2000},expected=409)
        require('已到期' in json.dumps(blocked,ensure_ascii=False), '过期派发返回明确的到期释放提示')
        state=center.call(f'/commands/{cid}')
        require(state['state']=='已解除' and float(state['remaining'])==0 and float(state['reserved'])==0 and sql(f'SELECT active_key IS NULL FROM af_command WHERE id={cid}')=='1', '409响应后到期解除、冻结清零及占用键清除已经提交')
        require(float(sql('SELECT available FROM af_bank WHERE id=1'))==float(available)+1000, '剩余冻结在拒绝普通派发的同时归还可用余额')
        require(sql(f"SELECT COUNT(*) FROM af_ledger WHERE command_id={cid} AND action='到期解除'")=='1' and sql(f"SELECT COUNT(*) FROM af_log WHERE entity_type='command' AND entity_id={cid} AND action='到期解除'")=='1', '同一事务保存一条释放流水和一条有效审计记录')
        require(sql(f'SELECT COUNT(*) FROM af_command WHERE alert_id={aid}')==before_count and center.call(f'/alerts/{aid}')['visits'][0]['id']==vid, '过期处理不重建指令、网格任务或重开预警')
        center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':1000},expected=409)
        center.call(f'/commands/{cid}/retry',{'note':tag},expected=409)
        center.call(f'/commands/{cid}/simulate',{'node':'到期'})
        require(sql(f"SELECT COUNT(*) FROM af_ledger WHERE command_id={cid} AND action='到期解除'")=='1' and float(sql('SELECT available FROM af_bank WHERE id=1'))==float(available)+1000, '重复派发及随后扫描不会重复解冻或重复入账')

    for extended in [False,True]:
        aid,cid,vid=prepared(extended=extended)
        reused=center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':500})
        require(reused['reused'] and reused['commandId']==cid, '有效期内的普通及延期止付仍复用原单号')
        # Leave both refund reservation and pending release to verify their expiry cleanup.
        refund=public.call('/applications',{'originalId':cid,'kind':'返还','amount':200,'payeeBankId':3,'reason':tag,'attachments':[public.file()]})['id']
        center.call(f'/applications/{refund}/review',{'decision':'通过','amount':200,'note':tag})
        appeal=holder.call('/applications',{'originalId':cid,'kind':'申诉','reason':tag})['id']
        center.call(f'/applications/{appeal}/review',{'decision':'受理','note':tag})
        center.call(f'/applications/{appeal}/review',{'decision':'成立','note':tag})
        sql(f'UPDATE af_command SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id={cid}')
        expired(aid,cid,vid)
        require(sql(f"SELECT COUNT(*) FROM af_command WHERE original_id={cid} AND ((kind='返还' AND state='已取消') OR (kind='解除' AND state='已失效'))")=='2', '同步到期同时清除返还占用并使待执行后续指令失效')
        fresh=center.call(f'/alerts/{aid}/dispatch',{'bankId':3,'amount':100})
        require(not fresh['reused'] and fresh['visitId']==vid, '过期账户不影响同一预警向其他账户首次派发')

    # Awaiting acceptance/receipt also must not reuse a timed-out active key.
    for accepted in [False,True]:
        aid,cid,vid=prepared(receipt=False,accept=accepted)
        field='accepted_at' if accepted else 'created_at'
        sql(f'UPDATE af_command SET {field}=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id={cid}')
        available=sql('SELECT available FROM af_bank WHERE id=1')
        center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':1000},expected=409)
        require(sql(f'SELECT state FROM af_command WHERE id={cid}')=='未处理' and sql(f'SELECT active_key IS NULL FROM af_command WHERE id={cid}')=='1' and sql('SELECT available FROM af_bank WHERE id=1')==available, '未受理或待回执超时同步提交督办状态，余额不变')
        retry=center.call(f'/commands/{cid}/retry',{'note':tag})['id']
        require(sql(f'SELECT retry_of FROM af_command WHERE id={retry}')==str(cid), '超时同步后仅通过原指令重发继续办理')

    # The check must use a clock read after waiting for the money row lock.
    aid,cid,vid=prepared();gate=SqlSession();pool=ThreadPoolExecutor(max_workers=1)
    def wait_for(check,message):
        until=time.monotonic()+25
        while time.monotonic()<until:
            if check():return
            time.sleep(.08)
        raise AssertionError(message)
    try:
        sql(f'UPDATE af_command SET expires_at=DATE_ADD(NOW(),INTERVAL 12 SECOND) WHERE id={cid}')
        gate.send("START TRANSACTION; SELECT id FROM af_bank WHERE id=1 FOR UPDATE; SELECT GET_LOCK('af_dispatch_expiry_gate',0);")
        wait_for(lambda:sql("SELECT IS_USED_LOCK('af_dispatch_expiry_gate') IS NOT NULL")=='1','资金行锁未就绪')
        require(sql(f'SELECT NOW()<expires_at FROM af_command WHERE id={cid}')=='1', '请求开始时尚未到期，随后在资金锁等待中跨过期限')
        dispatch=pool.submit(reviewer.call,f'/alerts/{aid}/dispatch',{'bankId':1,'amount':1000},expected=409)
        wait_for(lambda:int(sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE '%af_bank%' AND INFO LIKE '%FOR UPDATE%'"))>0,'派发未等待资金行锁')
        wait_for(lambda:sql(f'SELECT NOW()>=expires_at FROM af_command WHERE id={cid}')=='1','未跨过到期时间')
        gate.finish();gate=None;dispatch.result()
    finally:
        if gate:gate.finish()
        pool.shutdown()
    require(sql(f'SELECT state FROM af_command WHERE id={cid}')=='已解除' and sql(f"SELECT COUNT(*) FROM af_ledger WHERE command_id={cid} AND action='到期解除'")=='1', '等待资金锁期间到期，也不会按请求开始时的旧时间复用')

    # A real SQL failure still rolls back every fund/state change; only the deliberate 409 commits.
    aid,cid,vid=prepared();available=sql('SELECT available FROM af_bank WHERE id=1')
    sql(f'UPDATE af_command SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id={cid}')
    sql('RENAME TABLE af_ledger TO af_dispatch_ledger_guard')
    try:center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':1000},expected=500)
    finally:sql('RENAME TABLE af_dispatch_ledger_guard TO af_ledger')
    require(sql(f'SELECT state FROM af_command WHERE id={cid}')=='生效中' and sql('SELECT available FROM af_bank WHERE id=1')==available and sql(f'SELECT active_key IS NOT NULL FROM af_command WHERE id={cid}')=='1', '账本写入失败会回滚释放、余额和状态，而非提交部分结果')
    expired(aid,cid,vid)
    require(center.call('/reconciliation')['ok'] and center.call('/audit/verify')['ok'], '同步时效、锁等待及故障恢复后资金和审计校验通过')


if __name__=='__main__':
    status='failed'
    try:run();status='passed'
    finally:
        target=pathlib.Path(__file__).parent/'results';target.mkdir(exist_ok=True)
        (target/'dispatch-deadline-regressions.json').write_text(json.dumps({'status':status,'steps':len(results),'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Dispatch deadline regressions passed:',len(results),'steps')
