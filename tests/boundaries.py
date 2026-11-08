"""Controlled SQL fixtures and failure injection are restricted to the verification database."""
import integration as I
from integration import *
from recovery_capacity import sql,VERIFY,quoted,encryption
from concurrent.futures import ThreadPoolExecutor
I.BASE='http://127.0.0.1:8089/api'
def run():
 assert sql('SELECT DATABASE()')==VERIFY
 center,center2,pay=map(Client,['center','center','pay1']);tag='BND'+uuid.uuid4().hex[:10]
 victim=int(sql(f"INSERT INTO af_victim(name_enc,phone_enc,identity_enc,community_id) SELECT name_enc,phone_enc,identity_enc,1 FROM af_victim WHERE id=1; SELECT LAST_INSERT_ID();"))
 payload=encryption(json.dumps({'description':'时间窗边界虚构材料','amount':'6000','transactionTime':'23:30','remote':True},ensure_ascii=False));val=encryption('6222000000000001')
 base=datetime.datetime.now().replace(microsecond=0)-datetime.timedelta(days=10)
 def fixture(at,person=victim):
  code=tag+uuid.uuid4().hex[:9]
  return int(sql(f"INSERT INTO af_report(code,request_key,owner_id,victim_id,anonymous,kind,value_hash,value_enc,payload_enc,source,created_at) VALUES('{code}','{code}',1,{person if person else 'NULL'},FALSE,'账户',SHA2('6222000000000001',256),{quoted(val)},{quoted(payload)},'模拟监测','{at.isoformat(' ')}'); SELECT LAST_INSERT_ID();"))
 def process(r):center.call(f'/reports/{r}/retry',{});return center.call('/reports/'+str(r))['alert_id']
 r1=fixture(base);a=process(r1);r2=fixture(base+datetime.timedelta(hours=24));require(process(r2)==a,'归并恰好24小时属于固定时间窗')
 r3=fixture(base+datetime.timedelta(hours=24,seconds=1));a3=process(r3);require(a3!=a,'归并超过24小时一秒创建新预警')
 anon1=process(fixture(base,None));anon2=process(fixture(base,None));require(anon1!=anon2,'未知主体线索不会因值相同自动归并')
 different=process(fixture(base,2));require(different!=a,'不同主体不归并')
 center.call(f'/alerts/{a3}/review',{'decision':'通过','note':'时间窗验证后核实派发。','victimId':victim,'communityId':1})
 # Both HTTP requests must observe the same two persisted business records.
 with ThreadPoolExecutor(max_workers=2) as pool:
  futures=[pool.submit(c.call,f'/alerts/{a3}/dispatch',{'bankId':1,'amount':1000}) for c in (center,center2)];dispatches=[x.result() for x in futures]
 require(dispatches[0]['commandId']==dispatches[1]['commandId'] and dispatches[0]['visitId']==dispatches[1]['visitId'],'并发派发复用止付及上门单号')
 r4=fixture(base+datetime.timedelta(hours=25));require(process(r4)==a3,'已派发窗内线索继续归并')
 detail=center.call(f'/alerts/{a3}');require(detail['needs_review'] and len(detail['commands'])==1 and len(detail['visits'])==1,'追加线索保留已派发任务且标记补充复核')
 center.call(f'/alerts/{a3}/dispatch',{'bankId':1,'amount':1000},expected=409)
 center.call(f'/alerts/{a3}/review',{'decision':'通过','note':'补充复核，保留原关联。','victimId':1,'communityId':1},expected=400)
 center.call(f'/alerts/{a3}/review',{'decision':'通过','note':'补充复核，保留原关联。','victimId':victim,'communityId':1})
 # Void is an audited transaction, never physical deletion.
 center.call(f'/alerts/{a3}/void',{'note':'验收用虚构任务，尚未执行，联动作废。'})
 require(pay.call('/commands/'+str(dispatches[0]['commandId']))['state']=='已作废','作废同步尚未执行的机构指令')
 center.call(f'/alerts/{a3}/dispatch',{'bankId':1,'amount':1000},expected=409)
 require(process(fixture(base+datetime.timedelta(hours=26)))!=a3,'作废预警不再吸收新线索')
 # Known independent victim with a complete void-free alert for failure injection.
 report=center.call('/reports',{'kind':'号码','value':'13800000009','description':'事务回滚验收虚构举报','requestKey':str(uuid.uuid4())})
 failed_a=center.call('/reports/'+str(report['id']))['alert_id'];center.call(f'/alerts/{failed_a}/review',{'decision':'通过','note':'故障注入前复核','victimId':1,'communityId':1})
 sql('RENAME TABLE af_visit TO af_visit_verification_guard;')
 try:center.call(f'/alerts/{failed_a}/dispatch',{'bankId':1,'amount':1000},expected=500)
 finally:sql('RENAME TABLE af_visit_verification_guard TO af_visit;')
 require(sql(f'SELECT COUNT(*) FROM af_command WHERE alert_id={failed_a}')=='0','派发第二步失败回滚第一步止付，不留半套记录')
 successful=center.call(f'/alerts/{failed_a}/dispatch',{'bankId':1,'amount':1000});pay.call(f"/commands/{successful['commandId']}/accept",{})
 center.call(f'/alerts/{failed_a}/void',{'note':'已受理不允许直接作废'},expected=409)
 # Score insertion failure retains submission; retry recovers the original report.
 sql('RENAME TABLE af_score TO af_score_verification_guard;')
 try:
  failed=center.call('/reports',{'kind':'号码','value':'13800000009','description':'算法故障注入虚构材料','requestKey':str(uuid.uuid4())});require(failed['processing']=='人工待研判','算法异常时举报受理成功并转人工队列')
 finally:sql('RENAME TABLE af_score_verification_guard TO af_score;')
 fid=failed['id'];require(center.call('/reports/'+str(fid))['alert_id'] is None,'评分失败事务未留下孤立预警')
 center.call(f'/reports/{fid}/retry',{});require(center.call('/reports/'+str(fid))['processing']=='已研判','故障恢复重试处理原举报')
 # Matching failure must not silently be interpreted as a blacklist miss.
 sql('RENAME TABLE af_blacklist TO af_blacklist_verification_guard;')
 try:failed_match=center.call('/reports',{'kind':'号码','value':'13800000009','description':'匹配服务异常验收','requestKey':str(uuid.uuid4())});require(failed_match['processing']=='人工待研判','名单匹配异常与未命中分别处理')
 finally:sql('RENAME TABLE af_blacklist_verification_guard TO af_blacklist;')
 center.call(f"/reports/{failed_match['id']}/retry",{})
 # Login errors and disabling existing sessions.
 username=tag.lower();data={'username':username,'displayName':'停用验收虚构账号','role':'PUBLIC','enabled':True,'verified':False,'password':'Demo2026!'}
 uid=center.call('/users',data)['id'];u=Client(username);u.call('/login',{'username':username,'password':'wrong'},expected=401)
 u.call('/applications',{'kind':'申诉','originalId':successful['commandId'],'reason':'未实名申请'},expected=400)
 data['enabled']=False;data['password']='';center.call(f'/users/{uid}',data);u.call('/reports',expected=401)
 u.call('/session');u.call('/login',{'username':username,'password':'Demo2026!'},expected=401)
 # Stable scope despite forged organisation query parameters, and date boundaries.
 pay2=Client('pay2');statistics=pay2.call('/stats');forged=pay2.call('/stats?orgId=1');require(statistics['metrics']==forged['metrics'],'统计范围使用会话机构，不采纳伪造机构参数')
 with pay2.opener.open(I.BASE+'/stats.csv?orgId=1') as response:csv=response.read().decode('utf-8-sig')
 require('机构指令' in csv and str(statistics['metrics'][0]['value']) in csv,'导出使用本机构统计结果')
 future=center.call('/list/reports?from=2035-01-01&to=2035-01-02');require(future['total']==0,'列表日期过滤在后端生效')
 empty=Client('grid3').call('/stats?from=2035-01-01&to=2035-01-02');require(any(x['label']=='回传率' and x['value']=='—' for x in empty['metrics']),'零分母显示空值标识')
 # Different user repeats same idempotency key cannot obtain another user's acceptance record.
 c=Client('public2');key=str(uuid.uuid4());b={'kind':'号码','value':'13800000008','description':'请求幂等验证','requestKey':key}
 first=c.call('/reports',b);again=c.call('/reports',b);require(first['id']==again['id'] and again['duplicate'],'同一用户重复提交不重复受理')
 Client('public1').call('/reports',b,expected=409)
 return {'windowAlert':a,'voidedAlert':a3,'rollbackAlert':failed_a,'recoveredReport':fid}
if __name__=='__main__':
 out=pathlib.Path(__file__).resolve().parent/'results';status='passed';error=None;ids={}
 try:ids=run()
 except Exception as e:status='failed';error=str(e)
 report={'status':status,'timestamp':datetime.datetime.now().isoformat(),'steps':len(results),'ids':ids,'error':error,'results':results}
 (out/'boundaries.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False))
 if error:sys.exit(1)
