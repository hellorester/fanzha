"""SQL fixtures are restricted to an explicitly disposable test database."""
import base64, hashlib, hmac, os, pathlib, re, shutil, subprocess
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

VERIFY=os.environ.get('TEST_DB_NAME','antifraud_course_verify')
def sql(query, db=None):
    database=db or VERIFY
    if database!=VERIFY or not re.fullmatch(r'antifraud_[a-z0-9_]*(?:verify|ci)',database):
        raise RuntimeError('SQL fixtures require a dedicated antifraud verification database')
    command=os.environ.get('MYSQL_COMMAND') or shutil.which('mysql')
    if not command and os.name=='nt':
        candidate=pathlib.Path(os.environ.get('ProgramFiles','C:/Program Files'))/'MySQL/MySQL Server 8.0/bin/mysql.exe'
        if candidate.is_file():command=str(candidate)
    if not command:raise RuntimeError('Set MYSQL_COMMAND or add mysql to PATH')
    env=os.environ.copy();env['MYSQL_PWD']=env['DB_PASSWORD']
    process=subprocess.run([command,'--protocol=TCP','--host='+env.get('DB_HOST','127.0.0.1'),'--port='+env.get('DB_PORT','3306'),'--user='+env['DB_USER'],'--database='+database,'--batch','--skip-column-names','--default-character-set=utf8mb4'],input=query.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env)
    if process.returncode:raise RuntimeError(process.stderr.decode('utf-8','replace'))
    return process.stdout.decode('utf-8','replace').strip()

def encryption(value):
    iv=os.urandom(12)
    return base64.b64encode(iv).decode()+':'+base64.b64encode(AESGCM(base64.b64decode(os.environ['APP_KEY'])).encrypt(iv,value.encode(),None)).decode()
def quoted(value):return "'"+value.replace('\\','\\\\').replace("'","''")+"'"
def fingerprint(kind, normalized):
    key=hmac.new(base64.b64decode(os.environ['APP_KEY']),b'antifraud/fingerprint-key/v1',hashlib.sha256).digest()
    return hmac.new(key,(kind+'\0'+normalized).encode(),hashlib.sha256).hexdigest()
