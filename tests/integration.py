"""Runs real HTTP workflows against the local course prototype; uses virtual data only."""
import urllib.request,urllib.error,http.cookiejar,json,uuid,datetime,time,pathlib,sys,os,base64
sys.stdout.reconfigure(encoding='utf-8')
BASE=os.environ.get('TEST_BASE_URL','http://127.0.0.1:8088/api')
results=[]
class Client:
 def __init__(self,name=None):
  self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
  self.csrf=''; self.name=name or 'anonymous'; self.csrf=self.call('/session')['csrf']
  if name:self.csrf=self.call('/login',{'username':name,'password':'Demo2026!'})['csrf']
 def call(self,path,body=None,expected=200,raw=None,ctype=None,method=None):
  headers={'X-CSRF-Token':self.csrf}
  data=raw if raw is not None else None if body is None else json.dumps(body,ensure_ascii=False).encode()
  if data is not None:headers['Content-Type']=ctype or 'application/json'
  req=urllib.request.Request(BASE+path,data=data,headers=headers,method=method)
  started=time.perf_counter()
  try:
   with self.opener.open(req,timeout=20) as response:status=response.status;text=response.read()
  except urllib.error.HTTPError as e:status=e.code;text=e.read()
  elapsed=(time.perf_counter()-started)*1000
  value=json.loads(text) if text else None
  if status!=expected:raise AssertionError(f'{self.name} {path}: expected {expected}, received {status}: {value}')
  if isinstance(value,dict) and value.get('csrf'):self.csrf=value['csrf']
  results.append({'actor':self.name,'path':path,'status':status,'ms':round(elapsed,2)})
  return value
 def verify_accounts(self,alert_id,bank_ids=(1,)):
  """Explicit fixture setup: verified evidence must precede human review and dispatch."""
  alert=self.call(f'/alerts/{alert_id}');linked={t['bank_id'] for t in alert['targets']}
  for bank_id in bank_ids:
   if bank_id not in linked:self.call(f'/alerts/{alert_id}/accounts',{'bankId':bank_id,'reportId':alert['reports'][0]['id'],'note':'验收材料已核对线索与指定资金账户关系'})
 def file(self):
  boundary=uuid.uuid4().hex
  data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="proof.txt"\r\nContent-Type: text/plain\r\n\r\nCourse demonstration evidence\r\n--{boundary}--\r\n').encode()
  return self.call('/attachments',raw=data,ctype='multipart/form-data; boundary='+boundary)['id']
 def image(self):
  boundary=uuid.uuid4().hex;png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/6af4WQAAAABJRU5ErkJggg==')
  data=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="cover.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+png+f'\r\n--{boundary}--\r\n'.encode()
  return self.call('/attachments',raw=data,ctype='multipart/form-data; boundary='+boundary)['id']
 def binary(self,path,expected=200):
  req=urllib.request.Request(BASE+path,headers={'X-CSRF-Token':self.csrf});started=time.perf_counter()
  try:
   with self.opener.open(req,timeout=20) as response:status=response.status;content=response.read();ctype=response.headers.get_content_type()
  except urllib.error.HTTPError as e:status=e.code;content=e.read();ctype=e.headers.get_content_type()
  elapsed=(time.perf_counter()-started)*1000
  if status!=expected:raise AssertionError(f'{self.name} {path}: expected {expected}, received {status}')
  results.append({'actor':self.name,'path':path,'status':status,'ms':round(elapsed,2)})
  return ctype,content
def require(condition,label):
 if not condition:raise AssertionError(label)
 results.append({'check':label,'pass':True})
def flow():
 holder=Client('public3')
 public,center,pay,grid,pay2,other=map(Client,['public1','center','pay1','grid1','pay2','public2'])
 anon=Client();anon.call('/alerts',expected=401)
 public.call('/alerts',expected=403)
 report=public.call('/reports',{'reporterRelation':'本人受损','kind':'账户','value':'6222000000000001','description':'课程演示：接到冒充客服退款电话后被要求向陌生账户转账。','amount':'6000','transactionTime':'23:30','remote':True,'requestKey':str(uuid.uuid4()),'attachments':[public.file()]})
 detail=public.call('/reports/'+str(report['id']));aid=detail['alert_id'];alert=center.call('/alerts/'+str(aid));require(alert['score']==79,'五因子评分79分');other.call('/reports/'+str(report['id']),expected=404)
 center.verify_accounts(aid,[1]);center.call(f'/alerts/{aid}/review',{'decision':'通过','note':'核对演示材料，确认风险和受害人归属。','victimId':1,'communityId':1,'level':'高'})
 dispatch=center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':'6000'})
 repeated=center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':'6000'});require(dispatch['commandId']==repeated['commandId'] and dispatch['visitId']==repeated['visitId'],'重复派发复用双端单号')
 cid,vid=dispatch['commandId'],dispatch['visitId']
 pay2.call(f'/commands/{cid}',expected=404)
 pay.call(f'/commands/{cid}/accept',{})
 pay.call(f'/commands/{cid}/receipt',{'result':'全额执行','amount':'7000','voucher':'DEMO-OVER'},expected=400)
 pay.call(f'/commands/{cid}/receipt',{'result':'全额执行','amount':'6000','voucher':'DEMO-FREEZE','attachments':[pay.file()]})
 pay.call(f'/commands/{cid}/receipt',{'result':'全额执行','amount':'6000','voucher':'DEMO-DUP'},expected=409)
 frozen=pay.call(f'/commands/{cid}');require(frozen['state']=='生效中' and frozen['expires_at'] is not None,'成功回执才开始48小时冻结')
 grid.call(f'/visits/{vid}/claim',{})
 grid.call(f'/visits/{vid}/arrive',{'lat':'30.300001','lng':'120.100001','simulated':True})
 grid.call(f'/visits/{vid}/arrive',{'lat':'30.3','lng':'120.1','simulated':True},expected=409)
 grid.call(f'/visits/{vid}/feedback',{'process':'已完成电话核实及现场讲解，居民停止后续转账。','result':'已完成','attachments':[]},expected=400)
 grid.call(f'/visits/{vid}/feedback',{'process':'已核对聊天和交易材料，并讲解验证码及共享屏幕风险。','result':'已完成','attachments':[grid.file()]})
 center.call(f'/alerts/{aid}/close',{'note':'两端有效反馈已核对，完成本轮协同处置。'})
 require(center.call(f'/alerts/{aid}')['state']=='已闭环','主线两端反馈后闭环')
 refund=public.call('/applications',{'kind':'返还','originalId':cid,'amount':'2000','payeeBankId':3,'reason':'提供课程演示证明，申请返还已确认被骗资金。','attachments':[public.file()]})
 rid=refund['id'];center.call(f'/applications/{rid}/review',{'decision':'通过','amount':'7000','note':'超额测试'},expected=400)
 center.call(f'/applications/{rid}/review',{'decision':'通过','amount':'2000','note':'核定材料齐全，金额未超过剩余冻结。'})
 refundDetail=public.call(f'/applications/{rid}');rcid=refundDetail['commands'][0]['id'];pay.call(f'/commands/{rcid}/accept',{})
 pay.call(f'/commands/{rcid}/receipt',{'result':'部分执行','amount':'1500','reason':'课程演示：部分材料金额仍待确认。','voucher':'DEMO-RETURN'})
 origin=pay.call(f'/commands/{cid}');require(float(origin['remaining'])==4500 and float(origin['reserved'])==0 and float(origin['returned'])==1500,'部分返还释放剩余占用且账目守恒')
 appeal=holder.call('/applications',{'kind':'申诉','originalId':cid,'reason':'课程演示：申请核验剩余资金冻结是否有误。','attachments':[]});apid=appeal['id']
 center.call(f'/applications/{apid}/review',{'decision':'受理','note':'已接收申诉材料。'})
 center.call(f'/applications/{apid}/review',{'decision':'补正','note':'请补充账户权属材料。'})
 holder.call(f'/applications/{apid}/supplement',{'reason':'补充模拟账户权属证明。','attachments':[holder.file()]})
 center.call(f'/applications/{apid}/review',{'decision':'受理','note':'补正材料已收到。'})
 center.call(f'/applications/{apid}/review',{'decision':'成立','note':'课程模拟核验成立，生成解除指令。'})
 require(pay.call(f'/commands/{cid}')['state']=='生效中','申诉成立不提前解冻')
 release=holder.call(f'/applications/{apid}')['commands'][0]['id'];pay.call(f'/commands/{release}/accept',{});pay.call(f'/commands/{release}/receipt',{'result':'解除成功','amount':0,'voucher':'DEMO-RELEASE'})
 require(pay.call(f'/commands/{cid}')['state']=='已解除','解除有效回执同步原止付')
 public.call('/applications',{'kind':'返还','originalId':cid,'amount':100,'payeeBankId':3,'reason':'解除后申请','attachments':[]},expected=409)
 anonymous=anon.call('/reports',{'reporterRelation':'本人受损','kind':'号码','value':'13800000009','description':'匿名模拟风险来电，请协助核查。','requestKey':str(uuid.uuid4())})
 anon.call('/anonymous/query',{'code':anonymous['code'],'token':'wrong'},expected=404)
 summary=anon.call('/anonymous/query',{'code':anonymous['code'],'token':anonymous['queryToken']});require('score' not in summary and 'victim_id' not in summary,'匿名凭证查询仅返回脱敏摘要')
 stats=center.call('/stats');require(len(stats['metrics'])>=4,'统计来源于业务数据库')
 return {'alert':aid,'command':cid,'visit':vid,'refund':rid,'appeal':apid}
if __name__=='__main__':
 out=pathlib.Path(__file__).resolve().parent/'results';out.mkdir(exist_ok=True)
 status='passed';error=None;ids={}
 try:ids=flow()
 except Exception as e:status='failed';error=str(e)
 report={'status':status,'timestamp':datetime.datetime.now().isoformat(),'steps':len(results),'ids':ids,'error':error,'results':results}
 (out/'integration.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False))
 if error:sys.exit(1)
