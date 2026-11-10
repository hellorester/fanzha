"""Different reporter, victim and bank holder; verified clue targets and actual return credits."""
from integration import *
from test_db import sql, VERIFY
from concurrent.futures import ThreadPoolExecutor


def run():
    assert sql('SELECT DATABASE()')==VERIFY
    sql('DELETE FROM af_query_limit')
    center,reporter,victim,holder,pay,grid=map(Client,['center','public1','public2','public3','pay1','grid3'])
    tag='IDENTITY-'+uuid.uuid4().hex[:8]
    def report(actor,kind='号码',value='13987654321',relation='提供线索'):
        return actor.call('/reports',{'kind':kind,'value':value,'reporterRelation':relation,**({'victimId':2} if actor!=center else {}),'description':tag,'requestKey':uuid.uuid4().hex,'attachments':[actor.file()]})
    def approve(aid,person=2):
        center.call(f'/alerts/{aid}/review',{'decision':'通过','victimId':person,'communityId':2 if person==2 else 1,'note':tag})
    def execute(cid,amount=100):
        pay.call(f'/commands/{cid}/accept',{})
        pay.call(f'/commands/{cid}/receipt',{'result':'全额执行','amount':amount,'voucher':tag})
    def refund(actor,cid,bank,amount=30):
        app=actor.call('/applications',{'kind':'返还','originalId':cid,'payeeBankId':bank,'amount':amount,'reason':tag,'attachments':[actor.file()]})['id']
        center.call(f'/applications/{app}/review',{'decision':'通过','amount':amount,'note':tag})
        return app,center.call(f'/applications/{app}')['commands'][0]['id']

    # A verified person may report without a card. Identity alone must not assert account ownership.
    person=center.call('/people',{'name':tag,'phone':'13987650000','identity':tag,'communityId':2,'note':'现场核对实名资料'})['id']
    new_person=person
    username='no_bank_'+uuid.uuid4().hex[:8]
    center.call('/users',{'username':username,'password':'Demo2026!','displayName':tag,'role':'PUBLIC','victimId':person,'verified':True,'enabled':True})
    unbanked=Client(username)
    require(unbanked.call('/banks')==[] and unbanked.call('/list/banks')['total']==0,'没有绑卡的实名群众仍可登录，账户列表为空')
    r=report(unbanked,relation='本人受损');aid=center.call(f"/reports/{r['id']}")['alert_id']
    require(sql(f"SELECT victim_id FROM af_report WHERE id={r['id']}")==str(person),'明确本人受损时只使用已核验身份，不接受伪造victimId')
    approve(aid,person)
    require(not center.call(f'/alerts/{aid}')['targets'],'手机号不自动转换成资金账户')
    center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':100},expected=409)
    vid=center.call(f'/alerts/{aid}/visit',{'note':'资金账户尚不明确，先上门核查'})['visitId']
    require(sql(f'SELECT COUNT(*) FROM af_command WHERE alert_id={aid}')=='0','仅上门核查不会创建虚假的止付')
    grid1=Client('grid1');grid1.call(f'/visits/{vid}/claim',{});grid1.call(f'/visits/{vid}/arrive',{'lat':30,'lng':120,'simulated':True})
    center.call(f'/alerts/{aid}/review',{'decision':'通过','victimId':1,'communityId':1,'note':tag},expected=400)
    grid1.call(f'/visits/{vid}/feedback',{'process':tag,'result':'已完成','attachments':[grid1.file()]})
    center.call(f'/alerts/{aid}/close',{'note':'已核实未发生资金交易，完成上门提醒'})
    require(center.call(f'/alerts/{aid}')['state']=='已闭环','无资金账户的线索可以完成核查闭环')
    # An administrator can establish a bank relationship without permitting self-asserted ownership.
    unbanked.call('/banks',{'personId':person,'number':'6222000000000191'},expected=403)
    bank=center.call('/banks',{'personId':person,'number':'6222000000000191','orgId':1,'opening':0,'note':'核对本人账户开户凭证'})['id']
    require([b['id'] for b in unbanked.call('/banks')]==[bank],'经中心核验后只出现本人的账户')
    center.call(f'/banks/{bank}/owner',{'personId':1,'note':'尝试转移账户身份'},expected=409)

    # Reporter A, victim B, frozen-account holder C are three independent people.
    r=report(reporter,'网址','https://identity-risk.example/redirect?case='+tag,'代他人举报')
    aid=center.call(f"/reports/{r['id']}")['alert_id']
    require(sql(f"SELECT victim_id IS NULL FROM af_report WHERE id={r['id']}")=='1','代他人举报不把提交账号或伪造身份当成受害人')
    approve(aid)
    center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':100},expected=409)
    center.call(f'/alerts/{aid}/accounts',{'reportId':r['id'],'bankId':2,'note':'先核验另一账户供撤销验证'})
    center.call(f'/alerts/{aid}/accounts/2/remove',{'note':'进一步核对该账户与事件无关'})
    require(not center.call(f'/alerts/{aid}')['targets'],'尚未用于指令的错误账户关联可撤销并保留审计')
    center.call(f'/alerts/{aid}/accounts',{'reportId':r['id'],'bankId':1,'note':'核对网站引导转账的交易截图，收款账号尾号0001'})
    center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':100},expected=409)
    require(center.call(f'/alerts/{aid}')['needs_review'],'新核验的资金目标须重新人工复核')
    approve(aid);task=center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':100});cid=task['commandId']
    center.call(f'/alerts/{aid}/accounts/1/remove',{'note':'已派发后不能抹掉证据链'},expected=409)
    execute(cid)
    reporter.call(f'/commands/{cid}',expected=404)
    victim.call(f"/reports/{r['id']}",expected=404)
    holder.call(f"/reports/{r['id']}",expected=404)
    evidence=center.call(f"/reports/{r['id']}")['attachments'][0]['id']
    victim.call('/attachments/'+evidence,expected=404);holder.call('/attachments/'+evidence,expected=404)
    v=victim.call(f'/commands/{cid}');h=holder.call(f'/commands/{cid}')
    require(v['refund_eligible'] and not v['appeal_eligible'] and h['appeal_eligible'] and not h['refund_eligible'],'返还权来自受害人，申诉权来自被冻结账户户主')
    for c,field in [(victim,'refund_eligible'),(holder,'appeal_eligible')]:
        require(any(x['id']==cid and x[field] for x in c.call('/list/commands')['items']),'分页列表和详情保持一致的身份权限')
    require(not any('note' in t for t in h['timeline']) and 'victim_id' not in h,'户主不读取受害人身份字段及内部审核说明')
    victim.call('/applications',{'kind':'申诉','originalId':cid,'reason':tag},expected=409)
    holder.call('/applications',{'kind':'返还','originalId':cid,'payeeBankId':2,'amount':10,'reason':tag},expected=409)
    for wrong in [0,1,3]:victim.call('/applications',{'kind':'返还','originalId':cid,'payeeBankId':wrong,'amount':10,'reason':tag,'attachments':[]},expected=400)
    # No implicit bank lookup is granted to public users by a phone number or URL.
    require({b['id'] for b in victim.call('/banks')}=={4},'受害人不能枚举他人的资金账户')
    holder.call('/alerts',expected=403)
    other=report(reporter,'网址','https://identity-risk.example/redirect?case='+tag)
    otherAid=center.call(f"/reports/{other['id']}")['alert_id'];otherAlert=center.call(f'/alerts/{otherAid}')
    require(any(b['id']==1 for b in otherAlert['targetCandidates']) and not otherAlert['targets'],'历史核验仅提供候选，不自动授权新案件派发')
    center.call(f'/alerts/{otherAid}/accounts',{'reportId':r['id'],'bankId':1,'note':tag},expected=404)

    # A return has an actual receiving leg, and a failed transaction rolls both legs back.
    app,child=refund(victim,cid,4)
    application=victim.call(f'/applications/{app}')
    require(application['applicant_name']=='演示居民2' and application['payee'].endswith('0004'),'返还详情明确显示申请人和已核验本人收款账户')
    holder.call(f'/applications/{app}',expected=404);holder.call(f'/commands/{child}',expected=404)
    appFile=victim.call(f'/applications/{app}')['attachments'][0]['id'];holder.call('/attachments/'+appFile,expected=404)
    before=sql('SELECT available FROM af_bank WHERE id=4')
    pay.call(f'/commands/{child}/accept',{})
    pay.call(f'/commands/{child}/receipt',{'result':'全额执行','amount':30,'voucher':tag,'attachments':['not-a-file']},expected=404)
    require(sql('SELECT available FROM af_bank WHERE id=4')==before and sql(f'SELECT COUNT(*) FROM af_ledger WHERE command_id={child}')=='0','回执附件失败回滚收款、冻结变动及两端流水')
    payOther=Client('pay1')
    def competing(client):
        try:client.call(f'/commands/{child}/receipt',{'result':'全额执行','amount':30,'voucher':tag});return 200
        except AssertionError as e:
            if 'received 409' in str(e):return 409
            raise
    with ThreadPoolExecutor(max_workers=2) as pool:statuses=list(pool.map(competing,[pay,payOther]))
    require(sorted(statuses)==[200,409] and float(sql('SELECT available FROM af_bank WHERE id=4'))==float(before)+30,'并发返还回执只入账一次')
    require(sql(f"SELECT COUNT(*) FROM af_ledger WHERE command_id={child} AND bank_id=4 AND action='返还入账'")=='1','收款流水保存实际收款账户及原指令')
    appeal=holder.call('/applications',{'kind':'申诉','originalId':cid,'reason':tag,'attachments':[holder.file()]})['id']
    application=holder.call(f'/applications/{appeal}')
    require(application['applicant_name']=='演示居民3' and application['payee'] is None,'申诉详情显示被冻结账户户主，不冒用返还收款字段')
    victim.call(f'/applications/{appeal}',expected=404)
    center.call(f'/applications/{appeal}/review',{'decision':'受理','note':tag});center.call(f'/applications/{appeal}/review',{'decision':'成立','note':tag})
    release=holder.call(f'/applications/{appeal}')['commands'][0]['id'];victim.call(f'/commands/{release}',expected=404)
    pay.call(f'/commands/{release}/accept',{});pay.call(f'/commands/{release}/receipt',{'result':'解除成功','amount':0,'voucher':tag})
    require(float(sql('SELECT available FROM af_bank WHERE id=4'))==float(before)+30 and holder.call(f'/commands/{cid}')['state']=='已解除','户主申诉解除剩余冻结，不撤销已完成的受害人返还')

    # Opposite transfers must use the same bank locking order, not source-first ordering.
    pairs=[]
    for person,source,destination,actor in [(1,1,3,reporter),(3,3,1,holder)]:
        rr=report(center);aa=center.call(f"/reports/{rr['id']}")['alert_id']
        center.verify_accounts(aa,[source]);approve(aa,person)
        cc=center.call(f'/alerts/{aa}/dispatch',{'bankId':source,'amount':100})['commandId'];execute(cc)
        _,cc2=refund(actor,cc,destination,20);pay.call(f'/commands/{cc2}/accept',{});pairs.append(cc2)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(c.call,f'/commands/{cc}/receipt',{'result':'全额执行','amount':20,'voucher':tag}) for c,cc in zip([pay,payOther],pairs)]
        for f in futures:f.result()
    require(all(sql(f"SELECT COUNT(*) FROM af_ledger WHERE command_id={cc} AND action='返还入账'")=='1' for cc in pairs),'相反方向的并发返还均完成且不重复入账')
    require(center.call('/reconciliation')['ok'],'包含新增本人账户、双向返还及解除的全部资金对账通过')
    require(center.call('/audit/verify')['ok'],'身份、关系与资金变动都保留完整审计链')
    return {'alert':aid,'freeze':cid,'refund':app,'appeal':appeal,'newPerson':new_person}

if __name__=='__main__':
    error=None;ids={}
    try:ids=run()
    except Exception as e:error=str(e)
    output={'status':'failed' if error else 'passed','steps':len(results),'error':error,'ids':ids,'results':results}
    (pathlib.Path(__file__).parent/'results'/'identity-regressions.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in output.items() if k!='results'},ensure_ascii=False))
    if error:sys.exit(1)
