"""Disposable verification DB only: restore integrity and measured 20-user capacity run."""
import os,subprocess,hashlib,base64,statistics,threading,platform
from concurrent.futures import ThreadPoolExecutor
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import integration as I
from integration import Client,require,results
import json,time,datetime,pathlib,uuid
ROOT=pathlib.Path(__file__).resolve().parents[1]
MYSQL=r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
VERIFY='antifraud_course_verify'
def sql(query,db=VERIFY):
 assert db in (VERIFY,'antifraud_course')
 env=os.environ.copy();env['MYSQL_PWD']=env['DB_PASSWORD']
 p=subprocess.run([MYSQL,'--protocol=TCP','--host=127.0.0.1','--port=3306','--user='+env['DB_USER'],'--database='+db,'--batch','--skip-column-names','--default-character-set=utf8mb4'],input=query.encode('utf-8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env)
 if p.returncode:raise RuntimeError(p.stderr.decode('utf-8','replace'))
 return p.stdout.decode('utf-8','replace').strip()
def counts(db):
 tables=sql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'af_%' ORDER BY TABLE_NAME",db).splitlines()
 return {t:int(sql('SELECT COUNT(*) FROM '+t,db)) for t in tables}
def encryption(text):
 iv=os.urandom(12);return base64.b64encode(iv).decode()+':'+base64.b64encode(AESGCM(base64.b64decode(os.environ['APP_KEY'])).encrypt(iv,text.encode(),None)).decode()
def quoted(s):return "'"+s.replace('\\','\\\\').replace("'","''")+"'"
def cte(n):return f'WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<{n}) '
def run():
 before=counts('antifraud_course');restored=counts(VERIFY);require(before==restored,'独立库恢复全部表行数与原库一致')
 state=json.loads((ROOT/'.local/verify-server.json').read_text(encoding='utf-8-sig'));backup=pathlib.Path(state['backup']);restore=pathlib.Path(state['root'])
 manifest=json.loads((backup/'manifest.json').read_text(encoding='utf-8-sig'));files=[x for x in manifest['files'] if x['path'].startswith('uploads')]
 for f in files:require(hashlib.sha256((restore/f['path']).read_bytes()).hexdigest().upper()==f['sha256'],'恢复附件哈希 '+f['path'])
 I.BASE='http://127.0.0.1:8088/api';a=Client('center');sample=a.call('/reports')[0];original=a.call('/reports/'+str(sample['id']))
 I.BASE='http://127.0.0.1:8089/api';b=Client('center');copy=b.call('/reports/'+str(sample['id']));require(original==copy,'恢复实例可正确解密业务详情且关联一致')
 if files:
  fid=files[0]['path'].replace('\\','/').split('/')[-1]
  with b.opener.open(I.BASE+'/attachments/'+fid) as response:blob=response.read()
  require(hashlib.sha256(blob).hexdigest().upper()==files[0]['sha256'],'恢复实例附件下载字节一致')
 recovery={'tableCounts':before,'attachmentCount':len(files),'decryptionAndHttpDownload':True,'sourceDb':'antifraud_course','restoredDb':VERIFY}
 (ROOT/'tests/results/recovery.json').write_text(json.dumps({'status':'passed','timestamp':datetime.datetime.now().isoformat(),**recovery},ensure_ascii=False,indent=2),encoding='utf-8')
 prefix='CAP'+datetime.datetime.now().strftime('%m%d%H%M%S');assert sql('SELECT DATABASE()')==VERIFY
 encrypted_value=encryption('13800000009');encrypted_payload=encryption(json.dumps({'description':'容量测试虚拟线索','amount':'6000','transactionTime':'23:30','remote':True},ensure_ascii=False))
 sql('SET SESSION cte_max_recursion_depth=110000;'+
  'INSERT INTO af_user(username,password_hash,display_name,role,enabled) '+cte(500)+f"SELECT CONCAT('{prefix}u',n),(SELECT password_hash FROM af_user WHERE username='center'),CONCAT('容量验收用户',n),'CENTER',TRUE FROM seq;"+
  'INSERT INTO af_alert(code,victim_id,community_id,state,level,score,root_time,updated_at) '+cte(1000)+f"SELECT CONCAT('{prefix}a',n),1,1,IF(n<=400,'已闭环',IF(n<=600,'处置中','待研判')),ELT(1+MOD(n,3),'高','中','低'),MOD(n,101),DATE_SUB(NOW(),INTERVAL (1+MOD(n,20)) DAY),NOW() FROM seq;"+
  'INSERT INTO af_report(code,request_key,owner_id,victim_id,anonymous,kind,value_hash,value_enc,payload_enc,source,alert_id,processing,created_at) '+cte(100000)+f"SELECT CONCAT('{prefix}r',n),CONCAT('{prefix}r',n),2,1,FALSE,'号码',SHA2('13800000009',256),{quoted(encrypted_value)},{quoted(encrypted_payload)},'模拟监测',a.id,'已研判',a.root_time FROM seq JOIN af_alert a ON a.code=CONCAT('{prefix}a',1+MOD(n,1000));"+
  'INSERT INTO af_command(code,kind,alert_id,bank_id,org_id,victim_id,state,requested,actual,remaining,created_at,accepted_at,expires_at) '+cte(600)+f"SELECT CONCAT('{prefix}c',n),'止付',a.id,1,1,1,IF(n<=400,'已解除','拒绝执行'),100,IF(n<=400,100,0),0,a.root_time,a.root_time,IF(n<=400,DATE_ADD(a.root_time,INTERVAL 48 HOUR),NULL) FROM seq JOIN af_alert a ON a.code=CONCAT('{prefix}a',n);"+
  f"INSERT INTO af_receipt(command_id,version,result,amount,reason,voucher,actor_id,created_at) SELECT id,1,IF(actual=0,'拒绝执行','全额执行'),actual,'容量验收虚拟回执',CONCAT('CAP-',id),3,created_at FROM af_command WHERE code LIKE '{prefix}c%';"+
  f"INSERT INTO af_ledger(command_id,action,amount,available_after,note,created_at) SELECT id,'容量模拟冻结',actual,60000-actual,'容量验收历史样例',created_at FROM af_command WHERE code LIKE '{prefix}c%';"+
  f"INSERT INTO af_ledger(command_id,action,amount,available_after,note,created_at) SELECT id,'容量模拟到期',actual,60000,'容量验收历史样例',expires_at FROM af_command WHERE code LIKE '{prefix}c%' AND actual>0;"+
  'INSERT INTO af_visit(code,alert_id,community_id,assignee_id,state,due_at,arrived_at,lat,lng,location_simulated,process_text,result,returned_at,created_at) '+cte(400)+f"SELECT CONCAT('{prefix}v',n),a.id,1,4,'已归档',DATE_ADD(a.root_time,INTERVAL 2 HOUR),DATE_ADD(a.root_time,INTERVAL 1 HOUR),30.3,120.1,TRUE,'容量验收虚拟回传','已完成',DATE_ADD(a.root_time,INTERVAL 2 HOUR),a.root_time FROM seq JOIN af_alert a ON a.code=CONCAT('{prefix}a',n);"+
  'INSERT INTO af_log(actor_id,actor_role,action,entity_type,entity_id,note,created_at) '+cte(5000)+f"SELECT 1,'CENTER','容量测试','capacity',n,'{prefix}',NOW() FROM seq;")
 entries=[]
 for n in range(1000):
  value='139'+str(10000000+n);entries.append(f"('号码',SHA2('{value}',256),{quoted(encryption(value))},'{prefix}',TRUE)")
 sql('INSERT INTO af_blacklist(kind,value_hash,value_enc,source,enabled) VALUES '+','.join(entries)+';')
 dataset=counts(VERIFY);clients=[Client(prefix+'u'+str(n)) for n in range(1,21)]
 for c in clients[:3]:c.call('/list/reports?page=1');c.call('/stats')
 # 20 different accounts, 20 independent cookie jars, same barrier; mixed read + scoring write workload.
 barrier=threading.Barrier(20);samples=[];lock=threading.Lock()
 def worker(pair):
  i,c=pair;barrier.wait();local=[]
  for j in range(15):
   kind=['query','dashboard','score'][j%3]
   started=time.perf_counter()
   if kind=='query':c.call('/list/reports?page='+str(1+(i*37+j*83)%9000)+'&size=10')
   elif kind=='dashboard':c.call('/stats')
   else:c.call('/reports',{'kind':'账户','value':'6222000000000001','amount':6000,'transactionTime':'23:30','remote':True,'description':'20人容量并发虚构举报','requestKey':str(uuid.uuid4())})
   local.append({'user':prefix+'u'+str(i+1),'kind':kind,'ms':round((time.perf_counter()-started)*1000,2)})
  with lock:samples.extend(local)
 with ThreadPoolExecutor(max_workers=20) as pool:list(pool.map(worker,enumerate(clients)))
 summary={}
 for kind,limit in [('query',2000),('dashboard',3000),('score',1000)]:
  numbers=sorted(s['ms'] for s in samples if s['kind']==kind);summary[kind]={'n':len(numbers),'p50Ms':statistics.median(numbers),'p95Ms':numbers[int(len(numbers)*.95)-1],'maxMs':max(numbers),'limitMs':limit,'passed':max(numbers)<=limit}
 require(sql("SELECT COUNT(*) FROM af_command WHERE actual<0 OR remaining<0 OR reserved<0 OR returned<0 OR reserved>remaining")=='0','容量并发后资金非负且占用不超过剩余')
 require(sql("SELECT COUNT(*) FROM (SELECT command_id FROM af_receipt WHERE valid=TRUE GROUP BY command_id HAVING COUNT(*)>1) t")=='0','容量并发后有效回执唯一')
 source_after=counts('antifraud_course');require(all(source_after[t]==before[t] for t in before if t!='af_log'),'容量写入未污染业务库')
 return {'status':'passed' if all(x['passed'] for x in summary.values()) else 'failed','timestamp':datetime.datetime.now().isoformat(),'database':VERIFY,'generatedPrefix':prefix,'businessReportsGenerated':100000,'distinctUsers':20,'independentSessions':20,'warmupRequests':6,'samples':samples,'summary':summary,'dataset':dataset,'host':platform.platform(),'measurement':'本机 HTTP 端到端耗时，包括网络、权限、SQL、评分与持久化；算法值单独详见评分测试。'}
if __name__=='__main__':
 out=ROOT/'tests/results';out.mkdir(exist_ok=True)
 try:
  report=run();(out/'capacity.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k not in ('samples','dataset')},ensure_ascii=False))
  if report['status']!='passed':raise SystemExit(1)
 except Exception as e:
  (out/'recovery-capacity-error.json').write_text(json.dumps({'error':str(e),'steps':results},ensure_ascii=False,indent=2),encoding='utf-8');raise
