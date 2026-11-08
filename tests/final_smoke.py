from integration import *
def run():
 center,public,pay=map(Client,['center','public2','pay1'])
 center.call('/users/1',{'username':'center','displayName':'中心管理员','role':'CENTER','enabled':True,'admin':True,'verified':False,'password':''})
 command=next(c for c in public.call('/commands') if c['code']=='ZF26110881F0E0862B');cid=command['id']
 maintained=public.call('/applications',{'kind':'申诉','originalId':cid,'reason':'虚构验收：申请复核，但证据不支持解除。'})['id']
 center.call(f'/applications/{maintained}/review',{'decision':'受理','note':'已受理模拟申诉。'})
 center.call(f'/applications/{maintained}/review',{'decision':'维持','note':'虚构核验后维持，原冻结继续。'})
 require(public.call(f'/commands/{cid}')['state']=='生效中','申诉维持保留原冻结')
 appeal=public.call('/applications',{'kind':'申诉','originalId':cid,'reason':'补充虚构证据后重新申请核验。'})['id']
 public.call('/applications',{'kind':'申诉','originalId':cid,'reason':'重复申诉'},expected=409)
 center.call(f'/applications/{appeal}/review',{'decision':'受理','note':'已受理后续模拟材料。'})
 center.call(f'/applications/{appeal}/review',{'decision':'成立','note':'模拟核验后认为解除条件满足，交机构执行。'})
 child=public.call(f'/applications/{appeal}')['commands'][0]['id'];pay.call(f'/commands/{child}/accept',{})
 pay.call(f'/commands/{child}/receipt',{'result':'拒绝执行','amount':0,'reason':'虚构验收：机构需再次核验材料','voucher':'UI-FOLLOW-REJECT'})
 require(public.call(f'/commands/{cid}')['state']=='生效中','机构拒绝解除不会错误解冻')
 retry=center.call(f'/commands/{child}/retry',{'note':'补齐模拟材料，再次核对解除条件。'})['id']
 pay.call(f'/commands/{retry}/accept',{});pay.call(f'/commands/{retry}/receipt',{'result':'解除成功','amount':0,'voucher':'UI-FOLLOW-RELEASE'})
 pay.call(f'/commands/{retry}/receipt',{'result':'解除成功','amount':0,'voucher':'DUP'},expected=409)
 require(public.call(f'/commands/{cid}')['state']=='已解除','解除重发执行回写原止付')
 commands=center.call('/commands');banks=center.call('/banks')
 for bank in banks:
  seed=600 if bank['id']==4 else 60000
  outstanding=sum(float(x['remaining'])+float(x['returned']) for x in commands if x['kind']=='止付' and x['bank_id']==bank['id'])
  require(round(float(bank['available'])+outstanding,2)==seed,'模拟账户资金守恒 '+str(bank['id']))
 stats=center.call('/stats');actual=sum(float(x['actual']) for x in commands if x['kind']=='止付');returned=sum(float(x['returned']) for x in commands if x['kind']=='止付')
 require(float(next(x['value'] for x in stats['metrics'] if x['label']=='实际冻结累计'))==actual,'统计实际冻结与逐条有效指令求和一致')
 require(float(next(x['value'] for x in stats['metrics'] if x['label']=='成功返还累计'))==returned,'统计返还与逐条账目求和一致')
 return {'closedBrowserAlert':'YJ26110806BA788729','original':cid,'maintainedAppeal':maintained,'releaseAppeal':appeal,'releaseRetry':retry}
if __name__=='__main__':
 status='passed';error=None;ids={}
 try:ids=run()
 except Exception as e:status='failed';error=str(e)
 report={'status':status,'timestamp':datetime.datetime.now().isoformat(),'steps':len(results),'ids':ids,'error':error,'results':results}
 (pathlib.Path(__file__).parent/'results/final-smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False))
 if error:sys.exit(1)
