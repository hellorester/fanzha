"""Course completion rules, long review text, recovery and historical receipt comparisons."""
from integration import *
from test_db import sql, VERIFY, quoted


def run():
    assert sql('SELECT DATABASE()') == VERIFY
    sql('DELETE FROM af_query_limit')
    center, public, pay, pay2, grid, reviewer = map(Client, ['center', 'public1', 'pay1', 'pay2', 'grid1', 'reviewer'])
    tag = 'CLOSURE-' + uuid.uuid4().hex[:8]
    def report(**extra):
        return center.call('/reports', {'kind': '号码', 'value': '13800000009', 'description': tag, 'requestKey': uuid.uuid4().hex, **extra})
    def prepared(result='全额执行', amount=1000):
        r = report(); aid = center.call(f"/reports/{r['id']}")['alert_id']
        center.call(f'/alerts/{aid}/review', {'decision': '通过', 'victimId': 1, 'communityId': 1, 'note': tag})
        tasks = center.call(f'/alerts/{aid}/dispatch', {'bankId': 1, 'amount': 1000}); cid = tasks['commandId']
        pay.call(f'/commands/{cid}/accept', {})
        if result=='未处理': center.call(f'/commands/{cid}/simulate', {'node':'超时'})
        else: pay.call(f'/commands/{cid}/receipt', {'result': result, 'amount': amount, 'reason': tag, 'voucher': tag})
        return aid, cid, tasks['visitId']
    def close(aid, vid):
        grid.call(f'/visits/{vid}/claim', {})
        grid.call(f'/visits/{vid}/arrive', {'lat': 30, 'lng': 120, 'simulated': True})
        grid.call(f'/visits/{vid}/feedback', {'process': tag, 'result': '已完成', 'attachments': [grid.file()]})
        center.call(f'/alerts/{aid}/close', {'note': tag + '，两端反馈及异常意见已核对。'})

    risk = reviewer.call('/list/blacklist')['items'][0]
    require(all(k in risk for k in ['kind','value','source','enabled']), '普通研判员列表包含只读详情所需信息')
    reviewer.call('/blacklist', {'kind':'号码','value':'13800000007','source':tag,'enabled':True}, expected=403)

    # Subject correction before dispatch synchronizes the complete group without transferring ownership.
    victim = int(sql('INSERT INTO af_victim(name_enc,community_id) SELECT name_enc,community_id FROM af_victim WHERE id=1; SELECT LAST_INSERT_ID()'))
    first, second = report(victimId=victim), report(victimId=victim)
    aid = center.call(f"/reports/{first['id']}")['alert_id']
    owner = sql(f"SELECT owner_id FROM af_report WHERE id={first['id']}")
    center.call(f'/alerts/{aid}/review', {'decision': '通过', 'victimId': 2, 'communityId': 2, 'note': tag})
    require(sql(f'SELECT COUNT(*) FROM af_report WHERE alert_id={aid} AND victim_id=2') == '2', '派发前更正同步全部关联举报的当前核验身份')
    require(sql(f"SELECT owner_id FROM af_report WHERE id={first['id']}") == owner, '身份更正不更换原举报提交人')
    require(sql(f"SELECT COUNT(*) FROM af_log WHERE entity_type='alert' AND entity_id={aid} AND action='更正受害人关联'") == '1', '更正保留旧身份和新身份的审计记录')
    following = report(victimId=victim)
    require(center.call(f"/reports/{following['id']}")['alert_id'] != aid, '旧身份后续线索不会误归并到更正后的预警')

    # Reprocess in place: retain id/code/attachments, and never create a replacement report.
    require(bool(os.environ.get('APP_LOG')), 'APP_LOG须指向本次测试实例的日志文件')
    log_path=pathlib.Path(os.environ['APP_LOG']);log_start=log_path.stat().st_size
    file = center.file(); sql('RENAME TABLE af_score TO af_closure_score_guard')
    try: failed = report(attachments=[file])
    finally: sql('RENAME TABLE af_closure_score_guard TO af_score')
    before = center.call(f"/reports/{failed['id']}")
    require(before['processing']=='人工待研判' and before['alert_id'] is None, '规则异常留下可恢复的原举报')
    with log_path.open('rb') as log_file:
        log_file.seek(log_start);failure_log=log_file.read().decode('utf-8','replace')
    marker=f"report-processing-failed reportId={failed['id']}"
    require(marker in failure_log, '自动研判异常日志可按举报ID定位')
    trace=failure_log.split(marker,1)[1]
    require('Caused by:' in trace and 'at cn.course.antifraud.Clues.process(' in trace, '自动研判保留数据库原始异常及完整调用栈')
    public.call(f"/reports/{failed['id']}/retry", {}, expected=403)
    reviewer.call(f"/reports/{failed['id']}/retry", {})
    recovered = center.call(f"/reports/{failed['id']}")
    require(recovered['code']==failed['code'] and recovered['processing']=='已研判' and recovered['alert_id'] and recovered['attachments'][0]['id']==file, '中心恢复同一举报并保留受理编号及附件')
    reviewer.call(f"/reports/{failed['id']}/retry", {})
    require(sql(f"SELECT COUNT(*) FROM af_score WHERE report_id={failed['id']}")=='1', '重复恢复不会重复评分或新建举报')

    # New freezes need the same current approval whether dispatched or retried.
    for failed_state in ['拒绝执行','未处理']:
        aid,cid,vid=prepared(failed_state,0)
        original_state=center.call(f'/alerts/{aid}')['state']
        review_id=int(sql(f'SELECT MAX(id) FROM af_assessment WHERE alert_id={aid}'))
        for change,restore in [
            (f"UPDATE af_assessment SET decision='重发复核' WHERE id={review_id}",f"UPDATE af_assessment SET decision='通过' WHERE id={review_id}"),
            (f'UPDATE af_alert SET reviewed_by=NULL WHERE id={aid}',f'UPDATE af_alert SET reviewed_by=1 WHERE id={aid}'),
            (f"UPDATE af_alert SET state='待研判' WHERE id={aid}",f'UPDATE af_alert SET state={quoted(original_state)} WHERE id={aid}')
        ]:
            sql(change)
            try: center.call(f'/commands/{cid}/retry', {'note':tag},expected=409)
            finally: sql(restore)
        added=report(victimId=1)
        require(center.call(f"/reports/{added['id']}")['alert_id']==aid, f'{failed_state}后新增举报归并到原预警')
        pending=center.call(f'/commands/{cid}')
        require(pending['alert_needs_review'] and not pending['freeze_review_ready'], '指令详情明确阻止尚待补充复核的止付重发')
        before_count=sql(f'SELECT COUNT(*) FROM af_command WHERE alert_id={aid}')
        center.call(f'/commands/{cid}/retry', {'note':tag},expected=409)
        center.call(f'/alerts/{aid}/dispatch', {'bankId':1,'amount':1000},expected=409)
        unchanged=center.call(f'/alerts/{aid}')
        require(sql(f'SELECT COUNT(*) FROM af_command WHERE alert_id={aid}')==before_count and unchanged['state']==original_state and unchanged['needs_review'] and unchanged['visits'][0]['id']==vid, '拒绝重发不新增指令、不改预警状态、不替换任务或清除复核标志')
        reviewer.call(f'/alerts/{aid}/review', {'decision':'驳回','note':tag})
        center.call(f'/commands/{cid}/retry', {'note':tag},expected=409)
        center.call(f'/alerts/{aid}/dispatch', {'bankId':1,'amount':1000},expected=409)
        reviewer.call(f'/alerts/{aid}/review', {'decision':'通过','note':tag})
        require(center.call(f'/commands/{cid}')['freeze_review_ready'], '补充复核通过后恢复新止付入口')
        retried=center.call(f'/commands/{cid}/retry', {'note':tag})['id']
        require(sql(f'SELECT retry_of FROM af_command WHERE id={retried}')==str(cid), '通过复核后可重发且保留原指令关系')
        pay.call(f'/commands/{retried}/accept', {})
        pay.call(f'/commands/{retried}/receipt', {'result':'拒绝执行','amount':0,'reason':tag,'voucher':tag})
        center.call(f'/commands/{retried}/retry', {'note':tag})
        require(center.call(f'/alerts/{aid}')['freeze_review_ready'], '重发操作记录不会替代或抹除有效人工复核')

    # A pending supplemental clue must not block independent refunds/releases.
    aid,cid,vid=prepared()
    report(victimId=1)
    require(center.call(f'/alerts/{aid}')['needs_review'], '后续资金业务在预警仍需补充复核时验收')
    app=public.call('/applications', {'originalId':cid,'kind':'返还','amount':200,'payee':'6222000000000099','reason':tag,'attachments':[public.file()]})['id']
    center.call(f'/applications/{app}/review', {'decision':'通过','amount':200,'note':tag})
    refund=public.call(f'/applications/{app}')['commands'][0]['id']
    pay.call(f'/commands/{refund}/accept', {})
    pay.call(f'/commands/{refund}/receipt', {'result':'拒绝执行','amount':0,'reason':tag,'voucher':tag})
    retried=center.call(f'/commands/{refund}/retry', {'note':tag})['id']
    pay.call(f'/commands/{retried}/accept', {})
    pay.call(f'/commands/{retried}/receipt', {'result':'全额执行','amount':200,'voucher':tag})
    appeal=public.call('/applications', {'originalId':cid,'kind':'申诉','reason':tag})['id']
    center.call(f'/applications/{appeal}/review', {'decision':'受理','note':tag})
    center.call(f'/applications/{appeal}/review', {'decision':'成立','note':tag})
    release=public.call(f'/applications/{appeal}')['commands'][0]['id']
    pay.call(f'/commands/{release}/accept', {})
    pay.call(f'/commands/{release}/receipt', {'result':'拒绝执行','amount':0,'reason':tag,'voucher':tag})
    retried=center.call(f'/commands/{release}/retry', {'note':tag})['id']
    pay.call(f'/commands/{retried}/accept', {})
    pay.call(f'/commands/{retried}/receipt', {'result':'解除成功','amount':0,'voucher':tag})
    require(center.call(f'/commands/{cid}')['state']=='已解除' and center.call(f'/alerts/{aid}')['needs_review'], '待补充复核不阻止返还、申诉、解除及其失败重发，也不被这些操作误清除')

    # A closed rejected freeze cannot reopen its alert; archived visits remain archived.
    aid, cid, vid = prepared('拒绝执行', 0); close(aid, vid)
    count = sql(f'SELECT COUNT(*) FROM af_command WHERE alert_id={aid}')
    center.call(f'/commands/{cid}/retry', {'note': tag}, expected=409)
    require(sql(f'SELECT COUNT(*) FROM af_command WHERE alert_id={aid}') == count, '闭环后拒绝新增止付重发')
    require(center.call(f'/alerts/{aid}')['state']=='已闭环' and grid.call(f'/visits/{vid}')['state']=='已归档', '重发被拒绝后闭环及归档状态保持不变')
    require(pay.call(f'/commands/{cid}')['alert_state']=='已闭环', '指令详情提供预警状态供页面控制入口')

    # Review text up to 2000 characters must survive both business and chained audit storage.
    aid, cid, vid = prepared(); close(aid, vid)
    long_note = '审核意见' * 500
    app = public.call('/applications', {'originalId': cid, 'kind': '返还', 'amount': 200, 'payee': '6222000000000099', 'reason': tag, 'attachments': [public.file()]})['id']
    center.call(f'/applications/{app}/review', {'decision': '通过', 'amount': 200, 'note': long_note+'超'}, expected=400)
    require(public.call(f'/applications/{app}')['state']=='待核定', '超长审核意见在参数层拒绝且不更改状态')
    center.call(f'/applications/{app}/review', {'decision': '通过', 'amount': 200, 'note': long_note})
    detail = public.call(f'/applications/{app}'); refund = detail['commands'][0]['id']
    require(detail['note']==long_note and sql(f"SELECT CHAR_LENGTH(note) FROM af_log WHERE entity_type='application' AND entity_id={app} AND action='中心审核' ORDER BY id DESC LIMIT 1")=='2000', '2000字审核意见在申请和日志中完整保存')
    funds=detail['originalFunds'];require(float(funds['remaining'])==1000 and float(funds['reserved'])==200 and float(funds['available'])==800, '申请详情展示冻结、待执行占用及可核定额')
    pay.call(f'/commands/{refund}/accept', {})
    pay.call(f'/commands/{refund}/receipt', {'result':'拒绝执行','amount':0,'reason':tag,'voucher':tag})
    retry = center.call(f'/commands/{refund}/retry', {'note': tag})['id']
    pay.call(f'/commands/{retry}/accept', {})
    pay.call(f'/commands/{retry}/receipt', {'result':'全额执行','amount':200,'voucher':tag})
    require(center.call(f'/alerts/{aid}')['state']=='已闭环', '闭环后正常返还及失败返还重发仍可办理且不重开预警')
    appeal = public.call('/applications', {'originalId':cid,'kind':'申诉','reason':tag})['id']
    center.call(f'/applications/{appeal}/review', {'decision':'受理','note':long_note})
    center.call(f'/applications/{appeal}/review', {'decision':'成立','note':long_note})
    release=public.call(f'/applications/{appeal}')['commands'][0]['id'];pay.call(f'/commands/{release}/accept', {})
    pay.call(f'/commands/{release}/receipt', {'result':'解除成功','amount':0,'voucher':tag})
    require(pay.call(f'/commands/{cid}')['state']=='已解除' and center.call(f'/alerts/{aid}')['state']=='已闭环', '闭环后的申诉及解除独立完成')

    # Correction compares the receipt at application time, even after its approval creates a new version.
    aid, cid, vid = prepared()
    pay.call(f'/commands/{cid}/correction', {'result':'部分执行','amount':800,'reason':tag})
    correction=next(x['id'] for x in center.call('/corrections') if x['command_id']==cid)
    before=center.call(f'/corrections/{correction}');require(float(before['originalReceipt']['amount'])==1000 and float(before['new_amount'])==800, '更正详情同时展示原回执和申请金额')
    pay2.call(f'/corrections/{correction}', expected=404);public.call(f'/corrections/{correction}', expected=403)
    center.call(f'/corrections/{correction}/review', {'approve':True,'note':tag})
    after=center.call(f'/corrections/{correction}');require(after['originalReceipt']==before['originalReceipt'], '审批后原回执快照仍指向申请时的版本')
    require(center.call('/reconciliation')['ok'] and center.call('/audit/verify')['ok'], '功能收尾与长文本操作后资金及日志校验通过')


if __name__=='__main__':
    status='failed'
    try: run();status='passed'
    finally:
        target=pathlib.Path(__file__).parent/'results';target.mkdir(exist_ok=True)
        (target/'functional-regressions.json').write_text(json.dumps({'status':status,'steps':len(results),'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Functional regressions passed:',len(results),'steps')
