"""Local-course correctness: identity, recoverable submissions, bounded inputs and real SQL races."""
from integration import *
from test_db import sql, VERIFY, quoted, fingerprint, SqlSession
from concurrent.futures import ThreadPoolExecutor
import io, zipfile


def race(client, path, body):
    try:
        return 200, client.call(path, body)
    except AssertionError as error:
        if 'received 409' in str(error): return 409, None
        raise


def wait_for(check, label):
    until = time.monotonic() + 8
    while time.monotonic() < until:
        if check(): return
        time.sleep(.08)
    raise AssertionError(label)


def run():
    assert sql('SELECT DATABASE()') == VERIFY
    sql('DELETE FROM af_query_limit')
    center, center2, pay, public, other = map(Client, ['center', 'center', 'pay1', 'public1', 'public2'])
    tag = 'LOCAL-' + uuid.uuid4().hex[:8]

    def body(**extra):
        return {'kind': '号码', 'value': '13800000009', 'description': tag, 'requestKey': uuid.uuid4().hex, **extra}

    def dispatch():
        report = center.call('/reports', body())
        aid = center.call(f"/reports/{report['id']}")['alert_id']
        center.call(f'/alerts/{aid}/review', {'decision': '通过', 'victimId': 1, 'communityId': 1, 'note': tag})
        command = center.call(f'/alerts/{aid}/dispatch', {'bankId': 1, 'amount': 100})['commandId']
        pay.call(f'/commands/{command}/accept', {})
        return aid, command

    # Existing malicious/legacy unverified mappings must not grant any subject rights.
    aid, command = dispatch()
    pay.call(f'/commands/{command}/receipt', {'result': '全额执行', 'amount': 100, 'voucher': tag})
    try:
        # Resolve seeded IDs by username; never rely on an accidental row number.
        public_id = sql("SELECT id FROM af_user WHERE username='public1'")
        sql(f'UPDATE af_user SET verified=FALSE WHERE id={public_id}')
        current = public.call('/session')['user']
        require(not current['verified'] and not current['victimId'], '未核验账号不会获得有效受害人身份')
        report = public.call('/reports', body(anonymous=False, victimId=1))
        require(sql(f"SELECT anonymous=1 AND victim_id IS NULL FROM af_report WHERE id={report['id']}") == '1', '未核验账号伪造非匿名字段仍无法关联身份')
        public.call(f'/commands/{command}', expected=404)
        require(not public.call('/commands') and not public.call('/list/commands')['items'], '未核验账号的两种指令列表均为空')
        public.call('/applications', {'originalId': command, 'kind': '申诉', 'reason': tag}, expected=400)
    finally:
        sql("UPDATE af_user SET verified=TRUE WHERE username='public1'")
    require(public.call(f'/commands/{command}')['id'] == command, '恢复唯一核验关联后可正常查看本人资金业务')

    own = public.call('/reports', body())
    original = sql("SELECT victim_id FROM af_user WHERE username='public2'")
    try:
        sql("UPDATE af_user SET victim_id=1 WHERE username='public2'")
        for client in [public, other]:
            require(not client.call('/session')['user']['verified'], '历史重复实名关联暂停敏感权限')
            client.call(f'/commands/{command}', expected=404)
            require(not client.call('/commands'), '重复关联不暴露回执和指令时间线')
            client.call('/applications', {'originalId': command, 'kind': '申诉', 'reason': tag}, expected=400)
        require(public.call(f"/reports/{own['id']}")['id'] == own['id'], '暂停实名权限不影响本人举报')
        other.call(f"/reports/{own['id']}", expected=404)
    finally:
        sql('UPDATE af_user SET victim_id=' + original + " WHERE username='public2'")

    def account(name, victim=1, verified=True):
        return {'username': name, 'displayName': tag, 'password': 'Demo2026!', 'role': 'PUBLIC', 'enabled': True, 'verified': verified, 'victimId': victim}
    center.call('/users', account(tag + '-invalid', verified=False), expected=400)
    center.call('/users', account(tag + '-duplicate'), expected=409)
    victim = sql('INSERT INTO af_victim(name_enc,community_id) SELECT name_enc,community_id FROM af_victim WHERE id=1; SELECT LAST_INSERT_ID()')
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = [pool.submit(race, client, '/users', account(tag + '-' + str(i), int(victim))) for i, client in enumerate([center, center2])]
        statuses = [f.result()[0] for f in pending]
    require(sorted(statuses) == [200, 409], '并发关联同一受害人只能成功一次')

    # A lost HTTP response can be recovered by the same session, never by another session.
    anon = Client(); payload = body(); first = anon.call('/reports', payload)
    replay = anon.call('/reports', payload)
    require(first['id'] == replay['id'] and first['queryToken'] == replay['queryToken'], '匿名重试取回同一受理记录和凭证')
    Client().call('/reports', payload, expected=409)
    with ThreadPoolExecutor(max_workers=2) as pool:
        same = list(pool.map(lambda _: anon.call('/reports', payload), range(2)))
    require(all(r['id'] == first['id'] and r['queryToken'] == first['queryToken'] for r in same), '同会话并发重试不重复建单或改变凭证')
    concurrent_payload = body()
    with ThreadPoolExecutor(max_workers=2) as pool:
        created = list(pool.map(lambda _: anon.call('/reports', concurrent_payload), range(2)))
    require(created[0]['id'] == created[1]['id'] and created[0]['queryToken'] == created[1]['queryToken'], '首次并发提交也只创建一条记录并返回同一凭证')
    pending_payload = body(); sql('RENAME TABLE af_score TO af_local_score_guard')
    try:
        pending_report = anon.call('/reports', pending_payload)
        require(pending_report.get('processing') == '人工待研判', '模拟评分失败仍保存匿名受理凭证')
    finally:
        sql('RENAME TABLE af_local_score_guard TO af_score')
    recovered = anon.call('/reports', pending_payload)
    require(recovered['id'] == pending_report['id'] and recovered['queryToken'] == pending_report['queryToken'], '失败后的同一请求可继续处理且保留凭证')
    require(sql(f"SELECT processing FROM af_report WHERE id={recovered['id']}") == '已研判', '重试补齐已保存但未完成的研判')
    center.call(f"/reports/{first['id']}/query-token", {'mode': '停用', 'note': tag})
    require(anon.call('/reports', payload)['queryToken'] is None, '重放旧请求不会恢复已停用凭证')
    require(sql(f"SELECT COUNT(*) FROM af_report WHERE request_key={quoted(payload['requestKey'])}") == '1', '请求重试始终只有一条举报')

    # Incremental scoring must keep the same rule version, and invalidate on rule/list changes.
    score_victim = sql('INSERT INTO af_victim(name_enc,community_id) SELECT name_enc,community_id FROM af_victim WHERE id=1; SELECT LAST_INSERT_ID()')
    scores = []
    for i in range(6):
        r = center.call('/reports', body(victimId=int(score_victim), value='13987654321', amount=1000*i))
        scores.append(r['id'])
    score_alert = sql(f'SELECT alert_id FROM af_report WHERE id={scores[0]}')
    require(sql(f'SELECT COUNT(*) FROM af_score WHERE alert_id={score_alert}') == '6', '同版本六条合并举报只生成六次评分，避免平方级重复')
    saved_config = center.call('/configs')[0]['data']; changed = dict(saved_config); changed['high'] = 99
    try:
        center.call('/configs', changed)
        center.call('/reports', body(victimId=int(score_victim), value='13987654321'))
        require(sql(f'SELECT COUNT(*) FROM af_score WHERE alert_id={score_alert}') == '13', '评分配置变化时全部七条线索重新评分')
        center.call('/blacklist', {'kind': '号码', 'value': '13987654321', 'source': tag, 'enabled': True})
        center.call('/reports', body(victimId=int(score_victim), value='13987654321'))
        require(sql(f'SELECT COUNT(*) FROM af_score WHERE alert_id={score_alert}') == '21', '名单变化时全部八条线索重新评分')
        latest = center.call('/alerts/' + score_alert)
        require(all(row['details']['hit'] for row in latest['scores'][:8]), '重算结果全部使用更新后的风险名单')
        require(float(latest['score']) == max(row['score'] for row in latest['scores'][:8]), '聚合风险仍取最新版本中的最高分')
    finally:
        center.call('/configs', saved_config)

    # Retry and correction are competing ways of resolving one refused command.
    def refused():
        aid, cid = dispatch()
        pay.call(f'/commands/{cid}/receipt', {'result': '拒绝执行', 'amount': 0, 'reason': tag, 'voucher': tag})
        pay.call(f'/commands/{cid}/correction', {'result': '全额执行', 'amount': 100, 'reason': tag})
        correction = next(x['id'] for x in center.call('/corrections') if x['command_id'] == cid)
        return aid, cid, correction
    aid, cid, correction = refused()
    retry = center.call(f'/commands/{cid}/retry', {'note': tag})['id']
    require(sql(f'SELECT retry_of FROM af_command WHERE id={retry}') == str(cid), '重发记录保留直接原指令关系')
    center.call(f'/corrections/{correction}/review', {'approve': True, 'note': tag}, expected=409)
    pay.call(f'/commands/{retry}/accept', {})
    pay.call(f'/commands/{retry}/receipt', {'result': '拒绝执行', 'amount': 0, 'reason': tag, 'voucher': tag})
    center.call(f'/commands/{cid}/retry', {'note': tag}, expected=409)
    center.call(f'/corrections/{correction}/review', {'approve': True, 'note': tag}, expected=409)
    require(sql(f'SELECT COUNT(*) FROM af_receipt WHERE command_id={cid} AND valid=TRUE') == '1', '后续重发失败也不能复活旧更正')
    aid, cid, correction = refused()
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(race, center, f'/commands/{cid}/retry', {'note': tag}), pool.submit(race, center2, f'/corrections/{correction}/review', {'approve': True, 'note': tag})]
        require(sorted(f.result()[0] for f in futures) == [200, 409], '重发和更正并发时只有一条路径成功，另一条明确提示冲突')

    # Force receipt to wait on bank while dispatch overlaps. Old alert/bank inversion deadlocked.
    aid, cid = dispatch(); gate = SqlSession(); pool = ThreadPoolExecutor(max_workers=2)
    try:
        gate.send("START TRANSACTION; SELECT id FROM af_bank WHERE id=1 FOR UPDATE; SELECT GET_LOCK('af_local_bank_gate',0);")
        wait_for(lambda: sql("SELECT IS_USED_LOCK('af_local_bank_gate') IS NOT NULL") == '1', '资金行锁未就绪')
        receipt = pool.submit(pay.call, f'/commands/{cid}/receipt', {'result': '全额执行', 'amount': 100, 'voucher': tag})
        wait_for(lambda: int(sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE '%af_bank%' AND INFO LIKE '%FOR UPDATE%'")) > 0, '回执未进入资金锁等待')
        repeated = pool.submit(center.call, f'/alerts/{aid}/dispatch', {'bankId': 1, 'amount': 100})
        wait_for(lambda: int(sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND (INFO LIKE '%af_victim%' OR INFO LIKE '%af_alert%') AND INFO LIKE '%FOR UPDATE%'")) > 0, '重复派发未按身份/预警顺序等待')
        gate.finish(); gate = None
        receipt.result(); require(repeated.result()['commandId'] == cid, '真实重叠回执与重复派发均完成且复用原指令')
    finally:
        if gate: gate.finish()
        pool.shutdown()
    require(sql(f'SELECT COUNT(*) FROM af_ledger WHERE command_id={cid}') == '1', '重叠操作只有一次资金流水')

    # Verify the HTTP path rejects expanded resource abuse and keeps upload storage unchanged.
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('[Content_Types].xml', '<Types/>'); archive.writestr('word/document.xml', '<document/>')
        archive.writestr('after-document.bin', b'0' * (21 * 1024 * 1024))
    before = sql('SELECT COUNT(*) FROM af_attachment')
    boundary = uuid.uuid4().hex
    data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="bounded.docx"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode() + buffer.getvalue() + f'\r\n--{boundary}--\r\n'.encode())
    center.call('/attachments', raw=data, ctype='multipart/form-data; boundary='+boundary, expected=400)
    require(sql('SELECT COUNT(*) FROM af_attachment') == before, '压缩炸弹被拒绝且未保存附件记录')

    # Quotas are shared across sessions and expire without growing a Java username map.
    def fill(action, identity, attempts):
        key = fingerprint(action, identity)
        sql(f'INSERT INTO af_query_limit(client_key,attempts,window_end) VALUES({quoted(key)},{attempts},DATE_ADD(NOW(),INTERVAL 5 MINUTE)) ON DUPLICATE KEY UPDATE attempts=VALUES(attempts),window_end=VALUES(window_end)')
    try:
        sql('DELETE FROM af_query_limit'); fill('report-ip', '127.0.0.1', 120)
        Client().call('/reports', body(), expected=429)
        sql('DELETE FROM af_query_limit'); fill('report-user', sql("SELECT id FROM af_user WHERE username='center'"), 60)
        center2.call('/reports', body(), expected=429)
        sql('DELETE FROM af_query_limit'); fill('upload-ip', '127.0.0.1', 120)
        center.call('/attachments', raw=data, ctype='multipart/form-data; boundary='+boundary, expected=429)
        sql('DELETE FROM af_query_limit'); fill('login-ip', '127.0.0.1', 240)
        anon.call('/login', {'username': tag, 'password': 'incorrect'}, expected=429)
        sql('DELETE FROM af_query_limit'); fill('login-failure', tag, 8)
        anon.call('/login', {'username': tag, 'password': 'incorrect'}, expected=429)
        sql('UPDATE af_query_limit SET window_end=DATE_SUB(NOW(),INTERVAL 1 SECOND)')
        anon.call('/login', {'username': tag, 'password': 'incorrect'}, expected=401)
        require(sql('SELECT MAX(attempts) FROM af_query_limit') == '1', '过期失败记录自动重置，不永久锁住账号')
    finally:
        sql('DELETE FROM af_query_limit')
    audit = center.call('/audit/verify')
    require(audit['ok'] and audit['checkedAt'] >= audit['sealedAt'], '审计返回本次校验时间并单独保留建立基线时间')
    require(center.call('/reconciliation')['ok'], '本轮身份与并发测试后账本仍守恒')


if __name__ == '__main__':
    status = 'failed'
    try:
        run(); status = 'passed'
    finally:
        target = pathlib.Path(__file__).parent/'results'; target.mkdir(exist_ok=True)
        (target/'local-review-regressions.json').write_text(json.dumps({'status': status, 'steps': len(results), 'results': results}, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Local review regressions passed:', len(results), 'steps')
