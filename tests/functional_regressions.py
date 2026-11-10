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
        pay.call(f'/commands/{cid}/receipt', {'result': result, 'amount': amount, 'reason': tag, 'voucher': tag})
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
    file = center.file(); sql('RENAME TABLE af_score TO af_closure_score_guard')
    try: failed = report(attachments=[file])
    finally: sql('RENAME TABLE af_closure_score_guard TO af_score')
    before = center.call(f"/reports/{failed['id']}")
    require(before['processing']=='人工待研判' and before['alert_id'] is None, '规则异常留下可恢复的原举报')
    public.call(f"/reports/{failed['id']}/retry", {}, expected=403)
    reviewer.call(f"/reports/{failed['id']}/retry", {})
    recovered = center.call(f"/reports/{failed['id']}")
    require(recovered['code']==failed['code'] and recovered['processing']=='已研判' and recovered['alert_id'] and recovered['attachments'][0]['id']==file, '中心恢复同一举报并保留受理编号及附件')
    reviewer.call(f"/reports/{failed['id']}/retry", {})
    require(sql(f"SELECT COUNT(*) FROM af_score WHERE report_id={failed['id']}")=='1', '重复恢复不会重复评分或新建举报')

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
