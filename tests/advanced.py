"""State boundaries, access control and actual concurrent HTTP transactions."""
from integration import *
from concurrent.futures import ThreadPoolExecutor

def parallel(actions):
 with ThreadPoolExecutor(max_workers=len(actions)) as pool:return list(pool.map(lambda f:f(),actions))

def advanced():
 center,pay,payb,public,grid,grid2,grid3,pay2,reviewer=map(Client,['center','pay1','pay1','public1','grid1','grid2','grid3','pay2','reviewer'])
 def dispatch(bank=1,amount=1000):
  r=center.call('/reports',{'reporterRelation':'本人受损','kind':'号码','value':'13800000009','description':'自动验收虚构线索：用于异常和并发验证。','requestKey':str(uuid.uuid4())})
  aid=center.call('/reports/'+str(r['id']))['alert_id']
  center.verify_accounts(aid,[bank]);center.call(f'/alerts/{aid}/review',{'decision':'通过','note':'确认虚构对象和社区，测试用。','victimId':1,'communityId':1})
  return aid,center.call(f'/alerts/{aid}/dispatch',{'bankId':bank,'amount':amount})
 def freeze(bank=1,amount=1000,actual=None):
  a,d=dispatch(bank,amount);c=d['commandId'];pay.call(f'/commands/{c}/accept',{})
  pay.call(f'/commands/{c}/receipt',{'result':'全额执行' if actual is None else '部分执行','amount':amount if actual is None else actual,'reason':'模拟部分执行','voucher':'AUTO-'+str(c)})
  return a,d
 def race(client,path,body):
  try:client.call(path,body);return 200
  except AssertionError as e:
   if 'received 409' in str(e):return 409
   if 'received 400' in str(e):return 400
   raise
 a,d=dispatch();cid,vid=d['commandId'],d['visitId']
 claims=parallel([lambda:race(grid,f'/visits/{vid}/claim',{}),lambda:race(grid2,f'/visits/{vid}/claim',{})]);require(sorted(claims)==[200,409],'两名网格员并发接收仅一人成功')
 winner=grid if claims[0]==200 else grid2;loser=grid2 if claims[0]==200 else grid
 grid3.call(f'/visits/{vid}',expected=404);loser.call(f'/visits/{vid}/arrive',{'lat':30,'lng':120,'simulated':True},expected=403)
 winner.call(f'/visits/{vid}/arrive',{'lat':30.1234567,'lng':120,'simulated':True},expected=400)
 winner.call(f'/visits/{vid}/arrive',{'lat':30.1,'lng':120.1,'simulated':True})
 winner.call(f'/visits/{vid}/feedback',{'process':'模拟无法联系住户','result':'未完成','attachments':[winner.file()]},expected=400)
 winner.call(f'/visits/{vid}/feedback',{'process':'模拟无法联系住户','result':'未完成','reason':'模拟住户不在家，已转中心处理。','attachments':[winner.file()]})
 pay.call(f'/commands/{cid}/accept',{})
 body={'result':'全额执行','amount':1000,'voucher':'CONCURRENT-DEMO'}
 received=parallel([lambda:race(pay,f'/commands/{cid}/receipt',body),lambda:race(payb,f'/commands/{cid}/receipt',body)])
 require(sorted(received)==[200,409],'并发回执只有一个生效版本')
 command=pay.call(f'/commands/{cid}');require(len(command['receipts'])==1 and float(command['remaining'])==1000,'并发回执未重复扣款')
 center.call(f'/alerts/{a}/close',{'note':'略'},expected=400)
 center.call(f'/alerts/{a}/close',{'note':'模拟住户未能联系，中心确认记录异常处理决定。'})
 center.call(f'/alerts/{a}/review',{'decision':'通过','note':'已闭环不可再审核'},expected=409)
 # Reserve two refunds concurrently against the same remaining frozen amount.
 def refund(n):return public.call('/applications',{'kind':'返还','originalId':cid,'amount':800,'payeeBankId':3,'reason':'并发核定测试 '+str(n),'attachments':[public.file()]})['id']
 r1,r2=refund(1),refund(2);centerb=Client('center')
 rs=parallel([lambda:race(center,f'/applications/{r1}/review',{'decision':'通过','amount':800,'note':'并发核定 A'}),lambda:race(centerb,f'/applications/{r2}/review',{'decision':'通过','amount':800,'note':'并发核定 B'})])
 require(sorted(rs)==[200,400],'并发核定不能超占冻结余额')
 approved=r1 if rs[0]==200 else r2;rejected=r2 if rs[0]==200 else r1
 center.call(f'/applications/{rejected}/review',{'decision':'驳回','note':'余额不足，保留驳回记录。'})
 child=public.call(f'/applications/{approved}')['commands'][0]['id']
 center.call(f'/commands/{child}/simulate',{'node':'超时'})
 require(float(pay.call(f'/commands/{cid}')['reserved'])==0,'返还超时释放核定占用')
 retried=center.call(f'/commands/{child}/retry',{'note':'重新核验材料和剩余冻结金额。'})['id']
 center.call(f'/commands/{child}/retry',{'note':'重复重发测试'},expected=409)
 pay.call(f'/commands/{retried}/accept',{});pay.call(f'/commands/{retried}/receipt',{'result':'拒绝执行','amount':0,'reason':'模拟材料核验不通过','voucher':'REFUND-REJECT'})
 require(float(pay.call(f'/commands/{cid}')['reserved'])==0,'返还拒绝释放核定占用')
 nextchild=center.call(f'/commands/{retried}/retry',{'note':'补充材料后再次核验。'})['id']
 pay.call(f'/commands/{nextchild}/accept',{});pay.call(f'/commands/{nextchild}/receipt',{'result':'全额执行','amount':800,'voucher':'REFUND-RETRY'})
 require(float(pay.call(f'/commands/{cid}')['remaining'])==200,'返还重发保持账目一致')
 # Correction appends a new receipt; dependent transactions block correction.
 pay.call(f'/commands/{cid}/correction',{'result':'部分执行','amount':900,'reason':'测试有返还依赖的更正保护'},expected=409)
 require(not any(x['command_id']==cid for x in center.call('/corrections')),'存在资金依赖时即阻止无效更正入队')
 _,d2=freeze();c2=d2['commandId']
 pay.call(f'/commands/{c2}/correction',{'result':'部分执行','amount':800,'reason':'模拟录入金额偏差，申请追加更正版本'})
 x=next(x for x in center.call('/corrections') if x['command_id']==c2)
 center.call(f"/corrections/{x['id']}/review",{'approve':True,'note':'核验凭据一致，批准更正。'})
 corrected=pay.call(f'/commands/{c2}');require(len(corrected['receipts'])==2 and sum(bool(x['valid']) for x in corrected['receipts'])==1 and float(corrected['remaining'])==800,'回执更正保留旧版本且仅一份有效')
 # Repeated reminders deduplicate; extensions cap at two; expiration blocks late work.
 for _ in range(2):center.call(f'/commands/{c2}/simulate',{'node':'24小时'})
 require(sum(n['command_id']==c2 and n['node']=='24小时' for n in center.call('/notices'))==1,'提醒按指令、周期与节点去重')
 for _ in range(2):center.call(f'/commands/{c2}/extend',{'note':'模拟延期审批，理由已记录。'})
 center.call(f'/commands/{c2}/extend',{'note':'第三次延期'},expected=409)
 center.call(f'/commands/{c2}/simulate',{'node':'2小时'});center.call(f'/commands/{c2}/simulate',{'node':'到期'})
 expired=pay.call(f'/commands/{c2}');require(expired['state']=='已解除' and float(expired['remaining'])==0,'到期解除归零剩余冻结')
 center.call(f'/commands/{c2}/extend',{'note':'解除后延期'},expected=409)
 _,small=freeze(bank=4,amount=1000,actual=600);sc=small['commandId']
 require(float(pay.call(f'/commands/{sc}')['actual'])==600,'模拟余额不足支持部分止付')
 center.call(f'/commands/{sc}/simulate',{'node':'到期'})
 _,late=dispatch();lc=late['commandId'];pay.call(f'/commands/{lc}/accept',{});center.call(f'/commands/{lc}/simulate',{'node':'超时'})
 pay.call(f'/commands/{lc}/receipt',body,expected=409)
 rc=center.call(f'/commands/{lc}/retry',{'note':'机构超时后重新核实并下发。'})['id']
 require(rc!=lc and pay.call(f'/commands/{rc}')['expires_at'] is None,'超时重发新指令未提前开始冻结')
 # Strict file access is based on bound record scope, even after uploader changes role.
 fileid=public.file();r=public.call('/reports',{'reporterRelation':'本人受损','kind':'号码','value':'13800000008','description':'附件访问权限验证','requestKey':str(uuid.uuid4()),'attachments':[fileid]})
 Client('public2').call('/attachments/'+fileid,expected=404);pay2.call('/attachments/'+fileid,expected=404)
 # Pagination / role scopes / config and knowledge publication.
 for who,resource in [(center,'reports'),(center,'alerts'),(pay,'commands'),(grid,'visits'),(public,'applications')]:
  v=who.call('/list/'+resource+'?page=1&size=2');require(len(v['items'])<=2 and v['total']>=len(v['items']),'后端分页 '+who.name+' '+resource)
 reviewer.call('/users',expected=403);public.call('/list/logs',expected=403);grid3.call('/list/visits?q=not-exist')
 account_meta=center.call('/meta');person=account_meta['victims'][0]
 require('community_name' in person and 'identity' in person and 'identity_enc' not in person,'账号关联候选展示社区与脱敏证件信息')
 account_tag=uuid.uuid4().hex[:8]
 def invalid_account(name,role,**extra):
  body={'username':'invalid-'+name+'-'+account_tag,'displayName':'无效账号边界','password':'Demo2026!','role':role,'enabled':True};body.update(extra);return body
 center.call('/users',invalid_account('payment','PAYMENT'),expected=400)
 center.call('/users',invalid_account('grid','GRID'),expected=400)
 center.call('/users',invalid_account('public','PUBLIC',verified=True),expected=400)
 center.call('/users',invalid_account('center','CENTER',orgId=1),expected=400)
 before=center.call('/configs')[0];bad=dict(before['data']);bad['weights']=[1,2,3,4,5];center.call('/configs',bad,expected=400)
 require(center.call('/configs')[0]['id']==before['id'],'无效配置不污染有效版本')
 config=dict(before['data']);config['weights']=[20,20,20,20,20];center.call('/configs',config)
 require(center.call('/configs')[0]['id']!=before['id'],'有效配置发布为新版本');center.call('/configs',before['data'])
 invalid_cover=center.file();invalid_article={'title':'无效封面测试','category':'课程示例','summary':'非图片不能作为文章封面。','body':'仅用于验证封面类型。','state':'草稿','cover':[invalid_cover]}
 center.call('/articles',invalid_article,expected=400);center.call('/attachments/'+invalid_cover,method='DELETE')
 cover=center.image();article={'title':'验收示例：辨别冒充客服','category':'课程示例','summary':'演示发布、封面替换与下线。','body':'请通过官方渠道核验，切勿向陌生账户转账。','state':'草稿','cover':[cover]}
 ar=center.call('/articles',article)['id'];public_reader=Client();public_reader.call(f'/articles/{ar}',expected=404);public_reader.binary('/attachments/'+cover,expected=404)
 article['state']='已发布';center.call(f'/articles/{ar}',article);published=public_reader.call(f'/articles/{ar}');require(published['cover_id']==cover,'已发布文章返回自定义封面');require(public_reader.binary('/attachments/'+cover)[0]=='image/png','已发布封面可公开读取')
 replacement=center.image();article['cover']=[replacement];center.call(f'/articles/{ar}',article);require(public_reader.call(f'/articles/{ar}')['cover_id']==replacement,'文章封面可以替换');public_reader.binary('/attachments/'+cover,expected=404)
 article['state']='已下线';center.call(f'/articles/{ar}',article);public_reader.call(f'/articles/{ar}',expected=404);public_reader.binary('/attachments/'+replacement,expected=404)
 camp=center.call('/campaigns',{'title':'防范冒充客服宣传（验收）','description':'虚构社区活动，用于宣防流程验收。','communityId':1,'dueAt':(datetime.datetime.now()+datetime.timedelta(days=2)).isoformat()})['id']
 punch={'people':0,'sessions':1,'form':'线下讲解','time':datetime.datetime.now().isoformat(),'note':'零触达人次边界测试','requestKey':str(uuid.uuid4())}
 grid.call(f'/campaigns/{camp}/checkin',punch);grid.call(f'/campaigns/{camp}/checkin',punch,expected=409)
 punch['requestKey']=str(uuid.uuid4());punch['people']=-1;grid.call(f'/campaigns/{camp}/checkin',punch,expected=400)
 punch['people']=12;punch['time']=(datetime.datetime.now()+datetime.timedelta(days=1)).isoformat();grid.call(f'/campaigns/{camp}/checkin',punch,expected=400)
 grid3.call(f'/campaigns/{camp}',expected=404)
 # Invalid file types / exact configured size boundary.
 def upload(payload,name,expected):
  boundary=uuid.uuid4().hex;data=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+payload+f'\r\n--{boundary}--\r\n'.encode()
  return public.call('/attachments',raw=data,ctype='multipart/form-data; boundary='+boundary,expected=expected)
 upload(b'<script>bad</script>','bad.html',400);upload(b'not-a-png','bad.png',400)
 upload(b'a'*(10*1024*1024),'boundary.txt',200);upload(b'a'*(10*1024*1024+1),'too-large.txt',413)
 old=public.csrf;public.csrf='invalid';public.call('/reports',{},expected=403);public.csrf=old
 return {'concurrentReceipt':cid,'correction':c2,'timeoutRetry':rc,'campaign':camp}

if __name__=='__main__':
 status='passed';error=None;ids={};out=pathlib.Path(__file__).resolve().parent/'results';out.mkdir(exist_ok=True)
 try:ids=advanced()
 except Exception as e:status='failed';error=str(e)
 report={'status':status,'timestamp':datetime.datetime.now().isoformat(),'steps':len(results),'ids':ids,'error':error,'results':results}
 (out/'advanced.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False))
 if error:sys.exit(1)
