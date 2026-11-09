"""Second review: real account isolation, reconciliation, tampering and overlapping SQL transactions."""
from integration import *
from test_db import sql, VERIFY, quoted, SqlSession
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlencode


def run():
    assert sql('SELECT DATABASE()') == VERIFY
    center, public, other, pay, pay2 = map(Client, ['center', 'public1', 'public2', 'pay1', 'pay2'])
    tag = 'INTEGRITY' + uuid.uuid4().hex[:8]
    def report(client, files=None):
        return client.call('/reports', {'kind': '号码', 'value': '13800000009', 'description': tag, 'requestKey': uuid.uuid4().hex, 'attachments': files or []})
    def download(client, attachment):
        with client.opener.open(BASE + '/attachments/' + attachment) as response:
            return response.read()
    def check(client=center):
        return client.call('/reconciliation')

    original_victim = sql('SELECT victim_id FROM af_user WHERE id=5')
    try:
        sql('UPDATE af_user SET victim_id=1 WHERE id=5')
        file = public.file(); own = report(public, [file]); foreign = report(other)
        for client, hidden in [(other, own), (public, foreign)]:
            client.call('/reports/' + str(hidden['id']), expected=404)
            require(all(r['id'] != hidden['id'] for r in client.call('/reports')), '同一受害人账号的普通举报列表仍按提交者隔离')
            require(not client.call('/list/reports?' + urlencode({'q': hidden['code']}))['items'], '分页搜索无法读取同一受害人的其他账号举报')
        other.call('/attachments/' + file, expected=404)
        require(bool(download(public, file)) and bool(download(center, file)), '举报人和中心可以读取举报附件')
        other.call(f"/reports/{own['id']}/query-token", {'mode': '重置', 'note': tag}, expected=404)
        anon = Client()
        anon.call('/anonymous/query', {'code': own['code'], 'token': own['queryToken']})
        reset = public.call(f"/reports/{own['id']}/query-token", {'mode': '重置', 'note': tag})
        anon.call('/anonymous/query', {'code': own['code'], 'token': own['queryToken']}, expected=404)
        anon.call('/anonymous/query', {'code': own['code'], 'token': reset['queryToken']})
        public.call(f"/reports/{own['id']}/query-token", {'mode': '停用', 'note': tag})
        anon.call('/anonymous/query', {'code': own['code'], 'token': reset['queryToken']}, expected=404)
        require(public.call(f"/reports/{own['id']}")['id'] == own['id'], '停用匿名查询凭证不影响本人登录查询')
        # A center report linked to the same victim must not become a public user's report.
        created = report(center, [center.file()]); aid = center.call(f"/reports/{created['id']}")['alert_id']
        center.call(f'/alerts/{aid}/review', {'decision': '通过', 'victimId': 1, 'communityId': 1, 'note': tag})
        public.call(f"/reports/{created['id']}", expected=404)
        command = center.call(f'/alerts/{aid}/dispatch', {'bankId': 1, 'amount': 100})['commandId']
        pay.call(f'/commands/{command}/accept', {})
        receipt_file = pay.file()
        pay.call(f'/commands/{command}/receipt', {'result': '全额执行', 'amount': 100, 'voucher': tag, 'attachments': [receipt_file]})
        pay2.call(f'/commands/{command}', expected=404); pay2.call('/attachments/' + receipt_file, expected=404)
        report_file = center.call(f"/reports/{created['id']}")['attachments'][0]['id']
        pay2.call('/attachments/' + report_file, expected=404)
        require(all(b['org_id'] == 2 for b in pay2.call('/banks')), '机构账户列表只包含本机构账户')
    finally:
        sql('UPDATE af_user SET victim_id=' + original_victim + ' WHERE id=5')

    # Check all prior workflow tests too, not just a synthetic zero-balance example.
    initial = check()
    require(initial['ok'], '正常止付、返还、解除、更正及并发业务全部通过独立对账：' + json.dumps(initial, ensure_ascii=False))
    require({a['id'] for a in check(pay2)['accounts']} == {2}, '对账结果严格按支付机构隔离')
    public.call('/reconciliation', expected=403)
    sql('UPDATE af_bank SET available=available+0.01 WHERE id=1')
    try:
        result = check()
        require(not result['ok'] and any(i['check'] == '账户余额与账本不符' for a in result['accounts'] for i in a['issues']), '独立对账识别一分钱余额漂移')
    finally:
        sql('UPDATE af_bank SET available=available-0.01 WHERE id=1')
    ledger = sql('SELECT MAX(id) FROM af_ledger')
    sql(f'UPDATE af_ledger SET amount=amount+1 WHERE id={ledger}')
    try:
        require(not check()['ok'], '独立对账识别流水金额篡改')
    finally:
        sql(f'UPDATE af_ledger SET amount=amount-1 WHERE id={ledger}')
    sql(f'CREATE TABLE af_review_ledger_copy LIKE af_ledger; INSERT INTO af_review_ledger_copy SELECT * FROM af_ledger WHERE id={ledger}; DELETE FROM af_ledger WHERE id={ledger}')
    try:
        require(not check()['ok'], '独立对账识别缺失流水')
    finally:
        sql('INSERT INTO af_ledger SELECT * FROM af_review_ledger_copy; DROP TABLE af_review_ledger_copy')
    require(check()['ok'], '恢复测试夹具后资金重新一致')

    # Tampering must fail verification without a verifier silently rebuilding hashes.
    audit = center.call('/audit/verify'); require(audit['ok'] and audit['checked'] > 0, '正常并发业务的审计链完整')
    Client('reviewer').call('/audit/verify', expected=403)
    log_id = sql('SELECT MAX(id) FROM af_log')
    sql(f'CREATE TABLE af_review_log_copy LIKE af_log; INSERT INTO af_review_log_copy SELECT * FROM af_log WHERE id={log_id}')
    try:
        sql(f"UPDATE af_log SET note='tampered-fixture' WHERE id={log_id}")
        require(not center.call('/audit/verify')['ok'], '修改审计日志内容会被发现')
        sql(f'DELETE FROM af_log WHERE id={log_id}')
        require(not center.call('/audit/verify')['ok'], '删除审计链尾部会被发现')
    finally:
        sql(f'DELETE FROM af_log WHERE id={log_id}; INSERT INTO af_log SELECT * FROM af_review_log_copy; DROP TABLE af_review_log_copy')
    require(center.call('/audit/verify')['ok'], '恢复日志夹具后校验通过')

    # Shared IP quota cannot be bypassed by replacing sessions or spoofing proxy headers.
    sql('DELETE FROM af_query_limit')
    for _ in range(60):
        anon.call('/anonymous/query', {'code': 'missing', 'token': 'wrong'}, expected=404)
    Client().call('/anonymous/query', {'code': 'missing', 'token': 'wrong'}, expected=429)
    sql('UPDATE af_query_limit SET window_end=DATE_SUB(NOW(),INTERVAL 1 SECOND)')
    Client().call('/anonymous/query', {'code': 'missing', 'token': 'wrong'}, expected=404)
    require(sql('SELECT MAX(attempts) FROM af_query_limit') == '1', '限流窗口到期后恢复查询')
    sql('DELETE FROM af_query_limit')

    attachment_races(center, tag)
    require(check()['ok'] and center.call('/audit/verify')['ok'], '并发和回滚测试后资金与审计仍一致')


def attachment_races(center, tag):
    root = pathlib.Path(os.environ['APP_STORAGE']).resolve()
    def wait_for(test, label, seconds=8):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            if test(): return
            time.sleep(.1)
        raise AssertionError(label)
    def report(file, key, expected=200):
        return center.call('/reports', {'kind': '号码', 'value': '13800000009', 'description': tag, 'requestKey': key, 'attachments': [file]}, expected=expected)
    def locked(name): return sql(f"SELECT IS_USED_LOCK('{name}') IS NOT NULL") == '1'
    def waiting(file):
        return int(sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE '%af_attachment%' AND INFO LIKE '%FOR UPDATE%' AND INFO LIKE '%"+file+"%'"))

    # Hold audit and draft rows separately. The binder queues first, then reaches
    # audit and pauses with the file locked. Cleanup must wait and recheck at commit.
    file = center.file(); key = uuid.uuid4().hex
    audit_gate, file_gate = SqlSession(), SqlSession()
    try:
        audit_gate.send("START TRANSACTION; SELECT id FROM af_audit_head WHERE id=1 FOR UPDATE; SELECT GET_LOCK('af_review_audit_gate',0);")
        wait_for(lambda: locked('af_review_audit_gate'), '日志行锁未就绪')
        file_gate.send(f"START TRANSACTION; UPDATE af_attachment SET created_at=DATE_SUB(NOW(),INTERVAL 25 HOUR) WHERE id={quoted(file)}; SELECT GET_LOCK('af_review_file_gate',0);")
        wait_for(lambda: locked('af_review_file_gate'), '附件行锁未就绪')
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(report,file,key)
            wait_for(lambda: waiting(file)>0, '业务事务未等待附件锁')
            file_gate.finish(); file_gate=None
            wait_for(lambda: int(sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE '%af_audit_head%' AND INFO LIKE '%FOR UPDATE%'"))>0,'业务绑定后未等待日志锁')
            wait_for(lambda: waiting(file)>0, '未观察到清理事务等待已绑定附件的行锁')
            audit_gate.finish(); audit_gate=None
            result=future.result()
        require(sql(f"SELECT entity_id FROM af_attachment WHERE id={quoted(file)}")==str(result['id']) and (root/file).is_file(),'业务先绑定时，真实并发清理等待后保留记录和文件')
    finally:
        if file_gate:file_gate.finish()
        if audit_gate:audit_gate.finish()

    # Both actual cleanup and submission wait on one row. Accept either legal winner;
    # a committed business record must never reference deleted bytes.
    file=center.file();key=uuid.uuid4().hex;gate=SqlSession()
    try:
        gate.send(f"UPDATE af_attachment SET created_at=DATE_SUB(NOW(),INTERVAL 25 HOUR) WHERE id={quoted(file)}; START TRANSACTION; SELECT * FROM af_attachment WHERE id={quoted(file)} FOR UPDATE; SELECT GET_LOCK('af_review_delete_gate',0);")
        wait_for(lambda: locked('af_review_delete_gate'),'删除竞争行锁未就绪')
        wait_for(lambda: waiting(file)>0,'后台清理未等待行锁')
        def race():
            try:return report(file,key)
            except AssertionError as error:
                if 'received 404' in str(error):return None
                raise
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(race)
            wait_for(lambda: waiting(file)>=2,'没有观察到同时等待的清理和业务事务')
            gate.finish();gate=None;result=future.result()
        if result:
            require((root/file).is_file() and sql(f"SELECT entity_id FROM af_attachment WHERE id={quoted(file)}")==str(result['id']),'并发竞争中业务胜出时记录与文件均保留')
        else:
            wait_for(lambda:not (root/file).exists(),'清理事务提交后附件未删除')
            require(sql('SELECT COUNT(*) FROM af_report WHERE request_key='+quoted(key))=='0','清理胜出时举报整体回滚，不产生缺失附件的业务记录')
    finally:
        if gate:gate.finish()

    # A narrow constraint fails the metadata insert only after the bytes are written.
    before={p.name for p in root.iterdir() if p.is_file()}
    sql("ALTER TABLE af_attachment ADD CONSTRAINT ck_review_upload_failure CHECK(filename<>'rollback.txt')")
    try:
        boundary=uuid.uuid4().hex
        data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="rollback.txt"\r\nContent-Type: text/plain\r\n\r\nrollback fixture\r\n--{boundary}--\r\n').encode()
        try:center.call('/attachments',raw=data,ctype='multipart/form-data; boundary='+boundary,expected=409)
        except AssertionError as error:require('received 500' in str(error),'上传数据库约束故障被拒绝')
    finally:
        sql('ALTER TABLE af_attachment DROP CHECK ck_review_upload_failure')
    require({p.name for p in root.iterdir() if p.is_file()}==before,'磁盘写入后数据库失败不留下孤立附件')


if __name__ == '__main__':
    try:
        run(); status = 'passed'
    except Exception:
        status = 'failed'; raise
    finally:
        target = pathlib.Path(__file__).parent/'results'; target.mkdir(exist_ok=True)
        (target/'integrity-regressions.json').write_text(json.dumps({'status': status, 'results': results}, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Integrity regressions passed:', len(results), 'steps')
