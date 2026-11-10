"""Local coursework closure: money deadlines, corrections, applications and task handover.

Run against the disposable instance with command scanning disabled so HTTP requests
must settle expired states themselves. Lock races use independent MySQL sessions.
"""
from integration import *
from test_db import sql, VERIFY, SqlSession
from concurrent.futures import ThreadPoolExecutor


def run():
    assert sql('SELECT DATABASE()') == VERIFY
    sql('DELETE FROM af_query_limit')
    center, reviewer, pay, public, grid = map(Client, ['center','reviewer','pay1','public1','grid1'])
    tag = 'LIFECYCLE-' + uuid.uuid4().hex[:8]

    def prepared(result='全额执行'):
        r=center.call('/reports',{'kind':'号码','value':'13800000009','description':tag,'requestKey':uuid.uuid4().hex})
        aid=center.call(f"/reports/{r['id']}")['alert_id']
        center.call(f'/alerts/{aid}/review',{'decision':'通过','victimId':1,'communityId':1,'note':tag})
        task=center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':100})
        cid=task['commandId'];pay.call(f'/commands/{cid}/accept',{})
        if result:pay.call(f'/commands/{cid}/receipt',{'result':result,'amount':0 if result=='拒绝执行' else 100,'reason':tag,'voucher':tag})
        return aid,cid,task['visitId']

    def apply(cid,kind='返还'):
        return public.call('/applications',{'kind':kind,'originalId':cid,'amount':30,'reason':tag,'payee':'6222000000000099','attachments':[public.file()]})['id']

    def correction(cid,result='部分执行',amount=80):
        pay.call(f'/commands/{cid}/correction',{'result':result,'amount':amount,'reason':tag})
        return int(sql(f'SELECT MAX(id) FROM af_correction WHERE command_id={cid}'))

    def wait_for(check,message):
        until=time.monotonic()+10
        while time.monotonic()<until:
            if check():return
            time.sleep(.05)
        raise AssertionError(message)

    def after_bank_wait(cid,client,path,body,pending=False):
        field='DATE_ADD(accepted_at,INTERVAL 30 MINUTE)' if pending else 'expires_at'
        sql(f'UPDATE af_command SET '+('accepted_at=DATE_SUB(DATE_ADD(NOW(),INTERVAL 3 SECOND),INTERVAL 30 MINUTE)' if pending else 'expires_at=DATE_ADD(NOW(),INTERVAL 3 SECOND)')+f' WHERE id={cid}')
        gate=SqlSession();pool=ThreadPoolExecutor(max_workers=1)
        try:
            gate.send("START TRANSACTION; SELECT id FROM af_bank WHERE id=1 FOR UPDATE; SELECT GET_LOCK('lifecycle_bank_gate',0);")
            wait_for(lambda:sql("SELECT IS_USED_LOCK('lifecycle_bank_gate') IS NOT NULL")=='1','资金锁未建立')
            require(sql(f'SELECT NOW()<{field} FROM af_command WHERE id={cid}')=='1','请求开始时尚未超期')
            work=pool.submit(client.call,path,body,expected=409)
            wait_for(lambda:int(sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE '%af_bank%' AND INFO LIKE '%FOR UPDATE%'"))>0,'请求未等待资金锁')
            wait_for(lambda:sql(f'SELECT NOW()>={field} FROM af_command WHERE id={cid}')=='1','未跨过时效边界')
            gate.finish();gate=None;work.result()
        finally:
            if gate:gate.finish()
            pool.shutdown()

    aid,cid,vid=prepared(None)
    before=sql('SELECT available FROM af_bank WHERE id=1')
    after_bank_wait(cid,pay,f'/commands/{cid}/receipt',{'result':'全额执行','amount':100,'voucher':tag},pending=True)
    require(sql(f'SELECT state FROM af_command WHERE id={cid}')=='未处理' and sql('SELECT available FROM af_bank WHERE id=1')==before,'资金锁等待后超时回执被拦截，超时状态已提交且资金不动')
    require(sql(f'SELECT COUNT(*) FROM af_receipt WHERE command_id={cid}')=='0','被拦截的回执未留下有效执行记录')

    aid,cid,vid=prepared();x=correction(cid)
    after_bank_wait(cid,center,f'/corrections/{x}/review',{'approve':True,'note':tag})
    require(sql(f'SELECT state FROM af_command WHERE id={cid}')=='已解除' and sql(f'SELECT state FROM af_correction WHERE id={x}')=='已失效','更正等待期间到期，提交解除并结束更正，不能修改冻结')
    require(sql(f'SELECT COUNT(*) FROM af_receipt WHERE command_id={cid}')=='1','到期更正不新增回执版本')

    # A correction filed before an additional clue must recheck approval at review time.
    aid,cid,vid=prepared('拒绝执行');x=correction(cid,'全额执行',100)
    merged=center.call('/reports',{'kind':'号码','value':'13800000009','description':tag,'victimId':1,'requestKey':uuid.uuid4().hex})
    require(center.call(f"/reports/{merged['id']}")['alert_id']==aid,'追加线索实际归并到受审查的预警')
    center.call(f'/corrections/{x}/review',{'approve':True,'note':tag},expected=409)
    require(sql(f'SELECT state FROM af_command WHERE id={cid}')=='拒绝执行','更正不能绕过待补充复核形成新冻结')
    center.call(f'/alerts/{aid}/review',{'decision':'通过','note':tag})
    center.call(f'/corrections/{x}/review',{'approve':True,'note':tag})
    require(sql(f'SELECT period_version FROM af_command WHERE id={cid}')=='2','重新形成冻结开启新时效周期')
    center.call(f'/commands/{cid}/simulate',{'node':'24小时'})
    require(sql(f"SELECT COUNT(*) FROM af_notice WHERE command_id={cid} AND period_version=2 AND node='24小时'")=='1','新周期提醒不会被旧周期去重吞掉')

    aid,cid,vid=prepared('拒绝执行');x=correction(cid,'全额执行',100)
    grid.call(f'/visits/{vid}/claim',{});grid.call(f'/visits/{vid}/arrive',{'lat':'30.3','lng':'120.1','simulated':True})
    grid.call(f'/visits/{vid}/feedback',{'process':tag,'result':'已完成','attachments':[grid.file()]})
    center.call(f'/alerts/{aid}/close',{'note':tag+'，已核对拒绝执行原因并异常办结。'})
    center.call(f'/corrections/{x}/review',{'approve':True,'note':tag},expected=409)
    center.call(f'/corrections/{x}/review',{'approve':False,'note':tag})
    require(sql(f'SELECT state FROM af_alert WHERE id={aid}')=='已闭环','已闭环预警不被更正恢复冻结，仍可驳回更正')

    aid,cid,vid=prepared()
    for result,amount in [('不存在的结果',10),('部分执行',-1),('全额执行',90),('拒绝执行',10)]:
        pay.call(f'/commands/{cid}/correction',{'result':result,'amount':amount,'reason':tag},expected=400)
    require(sql(f'SELECT COUNT(*) FROM af_correction WHERE command_id={cid}')=='0','无效更正在申请阶段被拒绝，不进入审核队列')
    x=correction(cid);app=apply(cid)
    center.call(f'/applications/{app}/review',{'decision':'通过','amount':30,'note':tag})
    center.call(f'/corrections/{x}/review',{'approve':True,'note':tag},expected=409)
    center.call(f'/corrections/{x}/review',{'approve':False,'note':tag})
    require(float(center.call(f'/commands/{cid}')['reserved'])==30,'申请更正后新增资金依赖也在审批时拦截')

    # Parent expiry must stop every entry, persist money release, and end unfinished applications.
    for entry in ['accept','receipt','supplement','review','extend','apply','retry']:
        aid,cid,vid=prepared();refund=apply(cid);appeal=apply(cid,'申诉')
        center.call(f'/applications/{appeal}/review',{'decision':'受理','note':tag})
        center.call(f'/applications/{appeal}/review',{'decision':'补正','note':tag})
        child=None
        if entry in ['accept','receipt','retry']:
            center.call(f'/applications/{refund}/review',{'decision':'通过','amount':30,'note':tag})
            child=public.call(f'/applications/{refund}')['commands'][0]['id']
            if entry=='receipt':pay.call(f'/commands/{child}/accept',{})
            if entry=='retry':center.call(f'/commands/{child}/simulate',{'node':'超时'})
        sql(f'UPDATE af_command SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id={cid}')
        detail=center.call(f'/commands/{cid}')
        require(detail['deadline_passed'] and not detail['actions']['extend'] and not detail['application_eligible'],'未扫描的过期状态不提供新的办理入口')
        if child:
            actor=pay if entry!='retry' else center
            data={'result':'全额执行','amount':30,'voucher':tag} if entry=='receipt' else {'note':tag}
            actor.call(f'/commands/{child}/{entry}',data,expected=409)
        elif entry=='supplement':public.call(f'/applications/{appeal}/supplement',{'reason':tag,'attachments':[public.file()]},expected=409)
        elif entry=='review':center.call(f'/applications/{refund}/review',{'decision':'通过','amount':30,'note':tag},expected=409)
        elif entry=='extend':center.call(f'/commands/{cid}/extend',{'note':tag},expected=409)
        else:public.call('/applications',{'kind':'申诉','originalId':cid,'reason':tag},expected=409)
        require(sql(f'SELECT state FROM af_command WHERE id={cid}')=='已解除' and sql(f"SELECT COUNT(*) FROM af_ledger WHERE command_id={cid} AND action='到期解除'")=='1',entry+'触发到期并提交唯一释放流水')
        require(public.call(f'/applications/{appeal}')['state']=='已失效','待补材料的申诉随冻结到期结束')
        expected='核定通过' if child else '已失效'
        require(public.call(f'/applications/{refund}')['state']==expected,'保留已核定决定，尚未核定的申请转失效')

    # Identity changes cannot be used to continue someone else's subject-linked appeal.
    aid,cid,vid=prepared();app=apply(cid,'申诉')
    center.call(f'/applications/{app}/review',{'decision':'受理','note':tag});center.call(f'/applications/{app}/review',{'decision':'补正','note':tag})
    uid=int(sql("SELECT id FROM af_user WHERE username='public1'"))
    sql(f'UPDATE af_user SET verified=FALSE,victim_id=NULL WHERE id={uid}')
    try:public.call(f'/applications/{app}/supplement',{'reason':tag,'attachments':[]},expected=404)
    finally:sql(f'UPDATE af_user SET verified=TRUE,victim_id=1 WHERE id={uid}')
    public.call(f'/applications/{app}/supplement',{'reason':tag,'attachments':[public.file()]})
    require(public.call(f'/applications/{app}')['state']=='待受理','实名与原受害人关联恢复后可以正常补正')

    grid_name='lifegrid'+uuid.uuid4().hex[:8]
    center.call('/users',{'username':grid_name,'password':'Demo2026!','displayName':'生命周期验收网格员','role':'GRID','communityId':1,'enabled':True,'verified':False})
    grid=Client(grid_name)
    aid,cid,vid=prepared();grid.call(f'/visits/{vid}/claim',{})
    account=next(r for r in center.call('/users') if r['username']==grid_name)
    body={'username':grid_name,'displayName':account['display_name'],'role':'GRID','communityId':1,'enabled':False,'verified':False}
    center.call(f"/users/{account['id']}",body,expected=409)
    sql(f'UPDATE af_visit SET due_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id={vid}')
    attention=center.call('/visit-attention');require(any(v['id']==vid and v['attention']=='超期未回传' for v in attention),'超期上门任务进入中心督办列表')
    grid.call('/visit-attention',expected=403)
    grid.call(f'/visits/{vid}/arrive',{'lat':'30.3','lng':'120.1','simulated':True})
    center.call(f"/users/{account['id']}",{**body,'enabled':True,'communityId':2},expected=409)
    grid.call(f'/visits/{vid}/feedback',{'process':tag,'result':'未完成','reason':'未联系到本人，已记录下一步处理安排。','attachments':[grid.file()]})
    require(any(v['id']==vid and v['attention']=='劝阻未完成' for v in center.call('/visit-attention')),'未完成反馈保留在督办队列')
    center.call(f'/alerts/{aid}/close',{'note':tag+'，已联系家属协助，后续安排已明确。'})
    require(not any(v['id']==vid for v in center.call('/visit-attention')),'异常处理意见确认办结后任务退出督办')
    # Several earlier fixtures intentionally still have unclaimed tasks, which do not bind this user.
    center.call(f"/users/{account['id']}",body)
    center.call(f"/users/{account['id']}",{**body,'enabled':True})

    def campaign():return center.call('/campaigns',{'title':tag,'description':tag,'communityId':1,'dueAt':(datetime.datetime.now()+datetime.timedelta(days=1)).isoformat()})['id']
    campaign_id=campaign()
    center.call(f'/campaigns/{campaign_id}/finish',{'state':'已完成','note':tag},expected=409)
    punch={'form':'入户宣传','time':datetime.datetime.now().isoformat(),'sessions':1,'people':0,'note':tag,'requestKey':uuid.uuid4().hex}
    grid=Client(grid_name);grid.call(f'/campaigns/{campaign_id}/checkin',punch)
    grid.call(f'/campaigns/{campaign_id}/finish',{'state':'已完成','note':tag},expected=403)
    center.call(f'/campaigns/{campaign_id}/finish',{'state':'已完成','note':tag})
    grid.call(f'/campaigns/{campaign_id}/checkin',{**punch,'requestKey':uuid.uuid4().hex},expected=409)
    info=center.call(f'/campaigns/{campaign_id}')
    require(info['state']=='已完成' and len(info['records'])==1 and info['timeline'][-1]['action']=='结束宣防','宣防完成保留活动和结束日志，并停止打卡')
    cancelled=campaign();center.call(f'/campaigns/{cancelled}/finish',{'state':'已取消','note':tag})
    require(center.call(f'/campaigns/{cancelled}')['state']=='已取消','无活动的错误宣防任务可以取消')

    require(center.call('/reconciliation')['ok'] and center.call('/audit/verify')['ok'],'所有生命周期与异常恢复操作后，资金和审计完整')


if __name__=='__main__':
    status='failed'
    try:run();status='passed'
    finally:
        output=pathlib.Path(__file__).parent/'results';output.mkdir(exist_ok=True)
        (output/'lifecycle-regressions.json').write_text(json.dumps({'status':status,'steps':len(results),'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Lifecycle regressions passed:',len(results),'steps')
