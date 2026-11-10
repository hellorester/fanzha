"""Real HTTP/SQL regression cases for the November code review; disposable database only."""
from integration import *
from test_db import sql,VERIFY,quoted,fingerprint
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlencode

def run():
 holder=Client('public3')
 assert sql('SELECT DATABASE()')==VERIFY
 center,pay,public,grid,other=map(Client,['center','pay1','public1','grid1','grid2'])
 tag='REVIEW'+uuid.uuid4().hex[:8]
 def dispatch():
  evidence=center.file()
  report=center.call('/reports',{'reporterRelation':'本人受损','kind':'号码','value':'13800000009','description':tag,'requestKey':str(uuid.uuid4()),'attachments':[evidence]})
  aid=center.call('/reports/'+str(report['id']))['alert_id']
  center.verify_accounts(aid,[1]);center.call(f'/alerts/{aid}/review',{'decision':'通过','victimId':1,'communityId':1,'note':tag})
  d=center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':100})
  return aid,d['commandId'],d['visitId'],evidence
 def download(client,file):
  with client.opener.open(BASE+'/attachments/'+file) as response:return response.read()
 def receipt(command):pay.call(f'/commands/{command}/receipt',{'result':'全额执行','amount':100,'voucher':tag})

 # Unclaimed summaries, both list endpoints, reassignment, and direct material URLs.
 aid,cid,vid,evidence=dispatch()
 preview=grid.call(f'/visits/{vid}')
 require(preview['restricted'] and not any(k in preview for k in ['victim_id','person','phone','process_text','lat','attachments','timeline']),'未领取任务仅返回可领取摘要')
 grid.call('/attachments/'+evidence,expected=404)
 grid.call(f'/visits/{vid}/claim',{})
 require(bool(download(grid,evidence)),'任务负责人可以下载关联举报材料')
 other.call(f'/visits/{vid}',expected=404);other.call('/attachments/'+evidence,expected=404)
 require(all(x['id']!=vid for x in other.call('/visits')),'普通任务列表隐藏其他负责人的任务')
 require(all(x['id']!=vid for x in other.call('/list/visits')['items']),'分页任务列表执行相同权限')
 center.call(f'/visits/{vid}/assign',{'assigneeId':7,'note':tag})
 grid.call(f'/visits/{vid}',expected=404);grid.call('/attachments/'+evidence,expected=404)
 require(bool(download(other,evidence)),'改派后旧负责人失去材料权限，新负责人获得权限')
 other.call(f'/visits/{vid}/arrive',{'lat':30,'lng':120,'simulated':True})
 onsite=other.file();other.call(f'/visits/{vid}/feedback',{'process':tag,'result':'已完成','attachments':[onsite]})
 grid.call('/attachments/'+onsite,expected=404)
 require(bool(download(other,onsite)) and bool(download(center,onsite)),'现场材料仅负责人和中心可读')
 require(all('process_text' not in x for x in other.call('/visits')),'任务列表不包含现场敏感内容')
 pay.call(f'/commands/{cid}/accept',{});receipt(cid)
 repeated=center.call(f'/alerts/{aid}/dispatch',{'bankId':1,'amount':100})
 require(repeated['commandId']==cid and repeated['visitId']==vid and center.call(f'/alerts/{aid}')['state']=='处置中','重复派发保持单号及处置中状态')

 # Deadline enforcement must commit the timeout state despite returning HTTP 409.
 _,late,_,_=dispatch()
 sql(f"UPDATE af_command SET created_at=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id={late}")
 pay.call(f'/commands/{late}/accept',{},expected=409)
 require(pay.call(f'/commands/{late}')['state']=='未处理','未扫描前的超时受理被同步拦截并持久化')
 _,late_receipt,_,_=dispatch();pay.call(f'/commands/{late_receipt}/accept',{})
 balance=sql('SELECT available FROM af_bank WHERE id=1')
 sql(f"UPDATE af_command SET accepted_at=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id={late_receipt}")
 pay.call(f'/commands/{late_receipt}/receipt',{'result':'全额执行','amount':100,'voucher':tag},expected=409)
 require(sql(f'SELECT COUNT(*) FROM af_receipt WHERE command_id={late_receipt}')=='0' and sql('SELECT available FROM af_bank WHERE id=1')==balance,'过期回执不创建回执或变动账本')
 refund=public.call('/applications',{'kind':'返还','originalId':cid,'amount':40,'payeeBankId':3,'reason':tag,'attachments':[public.file()]})['id']
 center.call(f'/applications/{refund}/review',{'decision':'通过','amount':40,'note':tag})
 child=public.call(f'/applications/{refund}')['commands'][0]['id'];pay.call(f'/commands/{child}/accept',{})
 sql(f"UPDATE af_command SET accepted_at=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id={child}")
 pay.call(f'/commands/{child}/receipt',{'result':'全额执行','amount':40,'voucher':tag},expected=409)
 pay.call(f'/commands/{child}/receipt',{'result':'全额执行','amount':40,'voucher':tag},expected=409)
 require(float(pay.call(f'/commands/{cid}')['reserved'])==0 and sql(f"SELECT COUNT(*) FROM af_notice WHERE command_id={child} AND node='超时'")=='1','返还同步超时只释放一次占用且提醒去重')

 # Non-freeze corrections must be rejected at creation, not left forever pending.
 retried=center.call(f'/commands/{child}/retry',{'note':tag})['id'];pay.call(f'/commands/{retried}/accept',{})
 pay.call(f'/commands/{retried}/receipt',{'result':'全额执行','amount':40,'voucher':tag})
 pay.call(f'/commands/{retried}/correction',{'result':'全额执行','amount':40,'reason':tag},expected=409)
 appeal=holder.call('/applications',{'kind':'申诉','originalId':cid,'reason':tag})['id']
 center.call(f'/applications/{appeal}/review',{'decision':'受理','note':tag});center.call(f'/applications/{appeal}/review',{'decision':'成立','note':tag})
 release=holder.call(f'/applications/{appeal}')['commands'][0]['id'];pay.call(f'/commands/{release}/accept',{})
 pay.call(f'/commands/{release}/receipt',{'result':'解除成功','amount':0,'voucher':tag})
 pay.call(f'/commands/{release}/correction',{'result':'解除成功','amount':0,'reason':tag},expected=409)
 require(sql(f'SELECT COUNT(*) FROM af_correction WHERE command_id IN ({retried},{release})')=='0','返还与解除不会留下无法审批的更正申请')

 # An activity must happen by its deadline; delayed entry of an on-time activity is allowed.
 campaign=center.call('/campaigns',{'title':tag,'description':tag,'communityId':1,'dueAt':(datetime.datetime.now()+datetime.timedelta(days=1)).isoformat()})['id']
 sql(f"UPDATE af_campaign SET created_at=DATE_SUB(NOW(),INTERVAL 2 DAY),due_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE id={campaign}")
 due=sql(f'SELECT due_at FROM af_campaign WHERE id={campaign}')
 punch={'people':1,'sessions':1,'form':'入户宣传','time':datetime.datetime.now().isoformat(),'requestKey':str(uuid.uuid4())}
 grid.call(f'/campaigns/{campaign}/checkin',punch,expected=400)
 punch['time']=due;grid.call(f'/campaigns/{campaign}/checkin',punch)
 sql(f"UPDATE af_campaign SET state='已取消' WHERE id={campaign}");punch['requestKey']=str(uuid.uuid4())
 grid.call(f'/campaigns/{campaign}/checkin',punch,expected=409)
 require(sql(f'SELECT COUNT(*) FROM af_campaign_record WHERE campaign_id={campaign}')=='1','活动截止边界及任务有效状态均生效')

 # Canonical URL + keyed fingerprint + exact value search and configuration validation.
 host=tag.lower()+'.example'
 black=center.call('/blacklist',{'kind':'网址','value':f'https://{host}.:443/login','source':tag,'enabled':True})['id']
 report=center.call('/reports',{'reporterRelation':'本人受损','kind':'网址','value':f'https://{host}/login','description':tag,'requestKey':str(uuid.uuid4())})['id']
 alert=center.call('/alerts/'+str(center.call(f'/reports/{report}')['alert_id']))
 require(any(score['details']['hit'] for score in alert['scores']),'显式默认端口与末尾点不会漏掉黑名单')
 found=center.call('/list/blacklist?'+urlencode({'q':f'https://{host}/login'}))
 require(any(item['id']==black for item in found['items']),'黑名单支持按完整规范化线索值查询')
 require(sql(f'SELECT value_hash FROM af_report WHERE id={report}')==fingerprint('网址',host+'/login'),'新线索存储有密钥且区分类型的指纹')
 config=center.call('/configs')[0];bad=dict(config['data']);bad['weights']=[4294967321,15,15,30,15]
 center.call('/configs',bad,expected=400)
 require(center.call('/configs')[0]['id']==config['id'],'溢出权重被拒绝且不发布配置')

 # Owner-checked cleanup, bound evidence preservation, per-session concurrent quota.
 owner,stranger=Client(),Client();draft=owner.file()
 stranger.call('/attachments/'+draft,expected=404,method='DELETE')
 original_csrf=owner.csrf;owner.csrf='wrong';owner.call('/attachments/'+draft,expected=403,method='DELETE');owner.csrf=original_csrf
 owner.call('/attachments/'+draft,method='DELETE');owner.call('/attachments/'+draft,method='DELETE')
 require(sql('SELECT COUNT(*) FROM af_attachment WHERE id='+quoted(draft))=='0','未关联材料删除有归属和CSRF校验，重复删除幂等')
 center.call('/attachments/'+evidence,expected=409,method='DELETE')
 require(bool(download(center,evidence)),'已提交的证据不会被清理接口删除')
 quota=Client();ids=[quota.file() for _ in range(18)]
 def upload_limited(_):
  try:return quota.file()
  except AssertionError as error:
   if 'received 429' in str(error):return None
   raise
 with ThreadPoolExecutor(max_workers=4) as pool:extra=list(pool.map(upload_limited,range(4)))
 ids.extend(x for x in extra if x)
 require(len(ids)==20 and extra.count(None)==2,'并发上传也不能突破会话的20份未提交材料上限')
 for file in ids:quota.call('/attachments/'+file,method='DELETE')
 one=quota.file();sql('UPDATE af_attachment SET bytes=52428800 WHERE id='+quoted(one))
 try:quota.file();raise AssertionError('byte quota should reject an extra file')
 except AssertionError as error:
  if 'received 429' not in str(error):raise
 quota.call('/attachments/'+one,method='DELETE')
 require(True,'未提交材料合计50MB上限生效，移除后可继续上传')

 old=owner.file();recent=owner.file()
 sql('UPDATE af_attachment SET created_at=DATE_SUB(NOW(),INTERVAL 2 DAY) WHERE id IN ('+quoted(old)+','+quoted(evidence)+')')
 storage=pathlib.Path(os.environ['APP_STORAGE']);orphan=storage/str(uuid.uuid4());orphan.write_text('crash orphan');os.utime(orphan,(time.time()-172800,time.time()-172800))
 for _ in range(40):
  if sql('SELECT COUNT(*) FROM af_attachment WHERE id='+quoted(old))=='0' and not orphan.exists():break
  time.sleep(.5)
 require(sql('SELECT COUNT(*) FROM af_attachment WHERE id='+quoted(old))=='0' and not (storage/old).exists() and not orphan.exists(),'定期清除过期草稿和中断上传遗留文件')
 require(sql('SELECT COUNT(*) FROM af_attachment WHERE id IN ('+quoted(recent)+','+quoted(evidence)+')')=='2' and bool(download(center,evidence)),'后台清理保留新草稿和已关联材料')
 owner.call('/attachments/'+recent,method='DELETE')
 return {'reviewAlert':aid,'lateCommand':late,'lateReceipt':late_receipt,'refundTimeout':child}

if __name__=='__main__':
 output=pathlib.Path(__file__).resolve().parent/'results';output.mkdir(exist_ok=True)
 status='passed';error=None;ids={}
 try:ids=run()
 except Exception as exception:status='failed';error=str(exception)
 report={'status':status,'timestamp':datetime.datetime.now().isoformat(),'steps':len(results),'ids':ids,'error':error,'results':results}
 (output/'review-regressions.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False))
 if error:raise SystemExit(1)
