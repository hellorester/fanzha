"""Run the copied delivery JAR with a policy allowing loopback sockets only."""
import os,subprocess,pathlib,shutil,time,json,re,urllib.request,datetime,hashlib
import integration as I
ROOT=pathlib.Path(__file__).resolve().parents[1]
out=ROOT/'.local/offline-check';out.mkdir(parents=True,exist_ok=True)
jar=ROOT/'backend/target/antifraud-1.0.0.jar';shutil.copy2(jar,out/'app.jar')
env=os.environ.copy();env['APP_PORT']='8090';env['APP_SEED']='false';env['APP_LOG']=str(out/'application.log')
java=str(pathlib.Path(env['JAVA_HOME'])/'bin/java.exe')
policy=(ROOT/'tests/loopback-only.policy').as_uri()
report={'timestamp':datetime.datetime.now().isoformat(),'networkMode':'Java policy permits loopback SocketPermission only; OS network unchanged','jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
stdout=(out/'stdout.log').open('wb');stderr=(out/'stderr.log').open('wb')
proc=subprocess.Popen([java,'-Djava.security.manager','-Djava.security.policy=='+policy,'-jar',str(out/'app.jar')],cwd=out,env=env,stdout=stdout,stderr=stderr,creationflags=subprocess.CREATE_NO_WINDOW)
try:
 ready=False
 for _ in range(50):
  if proc.poll() is not None:raise RuntimeError('Restricted-network startup exited; inspect offline-check logs')
  try:
   with urllib.request.urlopen('http://127.0.0.1:8090/api/session',timeout=2) as r:ready=r.status==200
  except Exception:time.sleep(.3);continue
  if ready:break
 if not ready:raise RuntimeError('Startup timeout')
 I.BASE='http://127.0.0.1:8090/api';c=I.Client('center');report['reportsVisible']=c.call('/list/reports')['total'];report['statsMetrics']=len(c.call('/stats')['metrics'])
 with urllib.request.urlopen('http://127.0.0.1:8090/') as r:html=r.read().decode()
 assets=re.findall(r'(?:src|href)="([^\"]+)"',html);report['assets']=assets
 for path in assets:
  if not path.startswith('/'):raise AssertionError('Non-local asset '+path)
  with urllib.request.urlopen('http://127.0.0.1:8090'+path) as r:assert r.status==200
 report['status']='passed';report['copiedDeliveryJarStarts']=True
except Exception as e:report['status']='failed';report['error']=str(e)
finally:
 proc.terminate()
 try:proc.wait(timeout=15)
 except subprocess.TimeoutExpired:proc.kill();proc.wait()
 stdout.close();stderr.close()
 (ROOT/'tests/results/offline.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False))
if report['status']!='passed':raise SystemExit(1)
