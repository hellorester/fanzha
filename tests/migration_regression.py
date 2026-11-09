"""Exercise upgrade from legacy fingerprints while preserving every business row."""
from test_db import *
import json,sys
from integration import Client,require,results
output=pathlib.Path(__file__).resolve().parent/'results'
output.mkdir(exist_ok=True)
snapshot=output/'migration-fixture.json'
if sys.argv[1]=='prepare':
    assert sql('SELECT DATABASE()')==VERIFY
    values=['https://legacy-migration.example/login','https://legacy-migration.example.:443/login']
    for value in values:
        sql("INSERT INTO af_blacklist(kind,value_hash,value_enc,source,enabled) VALUES('网址',"+quoted(hashlib.sha256(value.encode()).hexdigest())+','+quoted(encryption(value))+",'migration-regression',TRUE)")
    tables=sql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'af_%' AND TABLE_NAME NOT IN ('af_migration','af_log','af_notice','af_upload_guard') ORDER BY TABLE_NAME").splitlines()
    counts={table:int(sql('SELECT COUNT(*) FROM '+table)) for table in tables}
    sql("UPDATE af_report SET value_hash=SHA2(CONCAT('legacy-report-',id),256); UPDATE af_bank SET number_hash=SHA2(CONCAT('legacy-bank-',id),256); DELETE FROM af_migration WHERE version=2")
    snapshot.write_text(json.dumps(counts),encoding='utf-8')
    print('Prepared legacy fingerprint fixtures in disposable database')
else:
    before=json.loads(snapshot.read_text(encoding='utf-8'))
    require(all(int(sql('SELECT COUNT(*) FROM '+table))==count for table,count in before.items()),'升级不丢失任何业务记录')
    expected=fingerprint('网址','legacy-migration.example/login')
    require(sql("SELECT COUNT(*) FROM af_blacklist WHERE source='migration-regression' AND value_hash="+quoted(expected))=='2','旧等价URL的两个来源均保留并重新建立统一指纹')
    require(sql("SELECT number_hash FROM af_bank WHERE id=1")==fingerprint('账户','6222000000000001'),'既有账户指纹迁移成功')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=2')=='1','升级版本成功记录')
    center=Client('center');r=center.call('/reports',{'kind':'网址','value':'https://legacy-migration.example/login','description':'迁移后旧黑名单命中验证','requestKey':__import__('uuid').uuid4().hex})
    alert=center.call('/alerts/'+str(center.call('/reports/'+str(r['id']))['alert_id']))
    require(any(row['details']['hit'] for row in alert['scores']),'迁移后的旧黑名单可供新举报正确匹配')
    (output/'migration-regressions.json').write_text(json.dumps({'status':'passed','results':results},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Migration regression checks passed')
