"""Exercise upgrade from legacy fingerprints while preserving every business row."""
from test_db import *
import json,sys,time
from integration import Client,require,results
output=pathlib.Path(__file__).resolve().parent/'results'
output.mkdir(exist_ok=True)
snapshot=output/'migration-fixture.json'
if sys.argv[1]=='prepare':
    assert sql('SELECT DATABASE()')==VERIFY
    values=['https://legacy-migration.example/login','https://legacy-migration.example.:443/login']
    for value in values:
        sql("INSERT INTO af_blacklist(kind,value_hash,value_enc,source,enabled) VALUES('网址',"+quoted(hashlib.sha256(value.encode()).hexdigest())+','+quoted(encryption(value))+",'migration-regression',TRUE)")
    # A representative multi-batch fixture, not a claim about production-scale upgrades.
    bulk=[]
    for i in range(3000):
        host=f'migration-bulk-{i:05d}.example'
        value=f'https://{host}/a/%2e%2e/login'
        bulk.append("('网址',"+quoted(hashlib.sha256(value.encode()).hexdigest())+','+quoted(encryption(value))+','+quoted(f'migration-bulk-{i:05d}')+",TRUE)")
    sql("INSERT INTO af_blacklist(kind,value_hash,value_enc,source,enabled) VALUES"+','.join(bulk))
    sql("UPDATE af_blacklist SET value_hash=SHA2(CONCAT('legacy-blacklist-',id),256); ALTER TABLE af_blacklist ADD UNIQUE INDEX kind(kind,value_hash), ADD UNIQUE INDEX uq_review_source_id(source,id)")
    ended=int(sql("SELECT id FROM af_command WHERE kind='止付' AND state='已解除' ORDER BY id LIMIT 1"))
    owner=int(sql("SELECT id FROM af_user WHERE username='public1'"))
    for code,kind,state in [('MIG-REFUND','返还','待核定'),('MIG-APPEAL','申诉','驳回待补'),('MIG-DECIDED','返还','核定驳回')]:
        sql(f"INSERT INTO af_application(code,kind,owner_id,original_id,state,amount,reason,note,created_at,updated_at) VALUES({quoted(code)},{quoted(kind)},{owner},{ended},{quoted(state)},10,'legacy application','保留历史意见',NOW(),NOW())")
    sql(f"INSERT INTO af_correction(command_id,actor_id,reason,new_result,new_amount,created_at) VALUES({ended},{owner},'legacy ended freeze','部分执行',10,NOW())")
    legacy_correction=sql('SELECT MAX(id) FROM af_correction')
    tables=sql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'af_%' AND TABLE_NAME NOT IN ('af_migration','af_notice','af_upload_guard','af_query_limit') ORDER BY TABLE_NAME").splitlines()
    counts={table:int(sql('SELECT COUNT(*) FROM '+table)) for table in tables}
    sql("UPDATE af_report SET value_hash=SHA2(CONCAT('legacy-report-',id),256); UPDATE af_bank SET number_hash=SHA2(CONCAT('legacy-bank-',id),256); DELETE FROM af_migration WHERE version IN (1,2,3,4,5,6,7,8,12)")
    # Remove only new columns to emulate the previous installed schema; existing business rows remain.
    sql('ALTER TABLE af_report DROP COLUMN request_owner_key, DROP COLUMN query_token_enc; ALTER TABLE af_alert DROP COLUMN score_config_id, DROP COLUMN score_blacklist_version; ALTER TABLE af_command DROP INDEX ix_command_retry, DROP COLUMN retry_of')
    sql('ALTER TABLE af_log MODIFY COLUMN note VARCHAR(1000); ALTER TABLE af_correction DROP COLUMN base_receipt_id; ALTER TABLE af_article DROP FOREIGN KEY fk_article_cover, DROP COLUMN cover_id')
    openings=sql('SELECT bank_id,opening FROM af_bank_origin ORDER BY bank_id')
    sql('DELETE FROM af_bank_origin; DELETE FROM af_audit_head; ALTER TABLE af_log DROP COLUMN prev_hash, DROP COLUMN entry_hash')
    snapshot.write_text(json.dumps({'counts':counts,'openings':openings,'legacyCorrection':legacy_correction,'startedAt':time.time()}),encoding='utf-8')
    print('Prepared legacy fingerprint fixtures in disposable database')
else:
    fixture=json.loads(snapshot.read_text(encoding='utf-8'));before=fixture['counts']
    require(all(int(sql('SELECT COUNT(*) FROM '+table))>=count if table=='af_log' else int(sql('SELECT COUNT(*) FROM '+table))==count for table,count in before.items()),'升级保留全部业务记录，允许追加状态修复审计')
    require(sql("SELECT COUNT(*) FROM af_application WHERE code IN ('MIG-REFUND','MIG-APPEAL') AND state='已失效' AND note='保留历史意见'")=='2','已结束冻结的历史未处理申请迁移为失效，审核意见保留')
    require(sql("SELECT state FROM af_application WHERE code='MIG-DECIDED'")=='核定驳回','已作出的历史决定不被升级覆盖')
    require(sql(f"SELECT state FROM af_correction WHERE id={fixture['legacyCorrection']}")=='已失效','历史已解除止付的待审核更正也结束')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=8')=='1','生命周期升级已登记')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=6')=='1','旧库新增身份重试、评分版本及重发关系字段成功')
    require(sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='af_report' AND COLUMN_NAME IN ('request_owner_key','query_token_enc')) OR (TABLE_NAME='af_alert' AND COLUMN_NAME IN ('score_config_id','score_blacklist_version')) OR (TABLE_NAME='af_command' AND COLUMN_NAME='retry_of'))")=='5','升级补齐全部新增字段')
    require(sql("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_log' AND COLUMN_NAME='note'")=='text','旧库日志字段升级为TEXT且原记录保留')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=7')=='1','文字长度及回执快照升级已登记')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=12')=='1','文章封面升级已登记')
    require(sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_article' AND COLUMN_NAME='cover_id'")=='1','旧库文章表补齐封面字段')
    require(sql("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='af_article' AND CONSTRAINT_NAME='fk_article_cover'")=='1','文章封面附件外键升级成功')
    expected=fingerprint('网址','legacy-migration.example/login')
    require(sql("SELECT COUNT(*) FROM af_blacklist WHERE source='migration-regression' AND value_hash="+quoted(expected))=='2','旧等价URL的两个来源均保留并重新建立统一指纹')
    require(sql("SELECT number_hash FROM af_bank WHERE id=1")==fingerprint('账户','6222000000000001'),'既有账户指纹迁移成功')
    require(sql('SELECT COUNT(*) FROM af_migration WHERE version=2')=='1','升级版本成功记录')
    require(sql("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_blacklist' AND INDEX_NAME='kind'")=='0','只移除已知旧版的kind唯一索引')
    require(sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_blacklist' AND INDEX_NAME='uq_review_source_id' AND NON_UNIQUE=0")=='source,id','团队额外添加的唯一索引完整保留')
    require(sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='af_blacklist' AND INDEX_NAME='ix_blacklist_value' AND NON_UNIQUE=1")=='kind,value_hash','迁移后的匹配索引结构正确')
    hashes=sql("SELECT source,value_hash FROM af_blacklist WHERE source LIKE 'migration-bulk-%'").splitlines()
    require(len(hashes)==3000 and all(row.split('\t')[1]==fingerprint('网址',row.split('\t')[0]+'.example/login') for row in hashes),'3000条历史编码URL跨批迁移后逐条匹配正确')
    elapsed=round(time.time()-fixture['startedAt'],3)
    print(f'Legacy restart and upgrade elapsed: {elapsed}s; bulk URL rows: 3000')
    center=Client('center');r=center.call('/reports',{'kind':'网址','value':'https://legacy-migration.example/login','description':'迁移后旧黑名单命中验证','requestKey':__import__('uuid').uuid4().hex})
    alert=center.call('/alerts/'+str(center.call('/reports/'+str(r['id']))['alert_id']))
    require(any(row['details']['hit'] for row in alert['scores']),'迁移后的旧黑名单可供新举报正确匹配')
    require(sql('SELECT bank_id,opening FROM af_bank_origin ORDER BY bank_id')==fixture['openings'],'旧库首次对账基线反推与原初始余额一致')
    require(center.call('/reconciliation')['ok'],'旧数据升级后独立资金对账通过')
    require(center.call('/audit/verify')['ok'],'旧数据升级后审计链仍完整')
    (output/'migration-regressions.json').write_text(json.dumps({'status':'passed','bulkRows':3000,'restartAndMigrationSeconds':elapsed,'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Migration regression checks passed')
