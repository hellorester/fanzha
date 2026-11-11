"""Account concurrency, session invalidation and controlled identity corrections."""
from integration import *
from test_db import sql, VERIFY
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier


def run():
    assert sql('SELECT DATABASE()') == VERIFY
    sql('DELETE FROM af_query_limit')
    center = Client('center')
    tag = 'CONTROL-' + uuid.uuid4().hex[:10]

    def person(label, identity=None):
        return center.call('/people', {'name': label, 'identity': identity or tag + label,
            'phone': '13900001234', 'communityId': 1, 'note': '账号与身份控制专项验收'})['id']

    def account(label, role='PUBLIC', **extra):
        body = {'username': (tag + label).lower(), 'password': 'Demo2026!',
            'displayName': label, 'role': role, 'enabled': True, **extra}
        return center.call('/users', body)['id'], body

    def update_body(row, reason, **changes):
        body = {'username': row['username'], 'displayName': row['display_name'],
            'role': row['role'], 'password': '', 'orgId': row.get('org_id') or 0,
            'communityId': row.get('community_id') or 0, 'victimId': row.get('victim_id') or 0,
            'verified': bool(row.get('verified')), 'enabled': bool(row.get('enabled')),
            'admin': bool(row.get('admin')), 'version': row['version'], 'reason': reason}
        body.update(changes)
        return body

    def find(actor, account_id):
        return next(item for item in actor.call('/users') if int(item['id']) == int(account_id))

    # New rows are immediately visible on page 1 and role filters/search use business labels.
    newest_id, newest = account('最新账号')
    first_page = center.call('/list/users?page=1&size=10')
    require(first_page['items'][0]['id'] == newest_id, '新建账号按最新顺序显示在第一页')
    searched = center.call('/list/users?page=1&size=50&role=PUBLIC&enabled=true&q=' + urllib.parse.quote('群众用户'))
    require(any(item['id'] == newest_id for item in searched['items']), '账号列表支持中文角色搜索和角色状态筛选')

    # Optimistic locking prevents an old dialog from overwriting a newer update.
    editor_a, editor_b = Client('center'), Client('center')
    stale = find(editor_a, newest_id)
    fresh_body = update_body(stale, '第一位管理员先保存', displayName='已由管理员甲更新')
    editor_a.call(f'/users/{newest_id}', fresh_body)
    stale_body = update_body(stale, '第二位管理员提交旧页面', displayName='旧页面错误覆盖')
    editor_b.call(f'/users/{newest_id}', stale_body, expected=409)
    require(find(editor_a, newest_id)['display_name'] == '已由管理员甲更新', '过期账号表单不能覆盖较新的修改')

    # Password and identity/permission changes revoke the old authenticated session.
    active_user = Client(newest['username'])
    row = find(center, newest_id)
    center.call(f'/users/{newest_id}', update_body(row, '重置密码验证旧会话失效', password='Changed2026!'))
    active_user.call('/session', expected=401)
    relogin = Client()
    session = relogin.call('/login', {'username': newest['username'].upper(), 'password': 'Changed2026!', 'role': 'PUBLIC'})
    require(session['user']['id'] == newest_id, '密码变更使旧会话失效且规范化账号可重新登录')

    # Equivalent identity spellings cannot create duplicate people.
    identity = 'id-' + tag + '-x'
    person('证件格式甲', identity)
    fullwidth = ''.join(chr(ord(ch) + 0xFEE0) if ch.isascii() and ch.isalnum() else ch for ch in identity.replace('-', '').upper())
    center.call('/people', {'name': '证件格式乙', 'identity': fullwidth,
        'phone': '13900001235', 'communityId': 1, 'note': '等价证件格式重复验证'}, expected=409)

    # Raw historical bindings and effective permissions are represented consistently.
    subject = person('冲突实名')
    first_id, first = account('冲突账号甲')
    second_id, _ = account('冲突账号乙')
    sql(f"UPDATE af_user SET victim_id={subject},verified=TRUE,session_version=session_version+1 WHERE id IN ({first_id},{second_id})")
    conflict_session = Client(first['username']).call('/session')['user']
    conflict_rows = [find(center, first_id), find(center, second_id)]
    require(not conflict_session['verified'] and all(not row['effective_verified'] and row['identity_state'] == '关联冲突' for row in conflict_rows),
        '账号列表与登录权限都将历史重复实名关联标为冲突并拒绝授权')
    sql(f"UPDATE af_user SET victim_id=NULL,verified=FALSE,version=version+1,session_version=session_version+1 WHERE id={second_id}")
    require(Client(first['username']).call('/session')['user']['verified'], '清理历史冲突后唯一实名账号恢复本人权限')

    # A stale victim id with verified=false is cleared explicitly instead of silently restored.
    stale_id, stale_account = account('历史残留')
    sql(f"UPDATE af_user SET victim_id={subject},verified=FALSE,version=version+1 WHERE id={stale_id}")
    stale_row = find(center, stale_id)
    center.call(f'/users/{stale_id}', update_body(stale_row, '清理未核验账号的历史实名残留', victimId=0, verified=False))
    require(sql(f'SELECT victim_id IS NULL AND verified=FALSE FROM af_user WHERE id={stale_id}') == '1',
        '未核验账号的历史实名残留可被明确清理且不会自动恢复')

    # Person details and unused bank ownership can be corrected, while used evidence is immutable.
    owner_a = person('原户主')
    owner_b = person('更正户主')
    number = '6219' + str(uuid.uuid4().int % 10**12).zfill(12)
    bank = center.call('/banks', {'personId': owner_a, 'number': number, 'orgId': 1,
        'opening': 3000, 'note': '专项验收未使用账户'})['id']
    center.call(f'/people/{owner_a}', {'name': '原户主更正名', 'identity': tag + '原户主',
        'phone': '13900009999', 'communityId': 1, 'note': '核对原始资料后更正姓名'})
    found = center.call('/list/banks?q=' + urllib.parse.quote(number))['items']
    require(found and found[0]['holder'] == '原户主更正名', '实名人员姓名更正同步到账户户名展示')
    center.call(f'/banks/{bank}/owner', {'personId': owner_b, 'note': '开户资料录入错误，尚未进入任何业务'})
    found = center.call('/list/banks?q=' + urllib.parse.quote(number))['items']
    require(found and found[0]['holder'] == '更正户主', '未进入业务的账户可以受控更正户主')
    report = center.call('/reports', {'kind': '账户', 'value': number, 'description': tag,
        'reporterRelation': '提供线索', 'requestKey': uuid.uuid4().hex})
    alert_id = center.call(f"/reports/{report['id']}")['alert_id']
    center.call(f'/alerts/{alert_id}/accounts', {'reportId': report['id'], 'bankId': bank,
        'note': '核对举报材料后确认该资金账户关联'})
    center.call(f'/banks/{bank}/owner', {'personId': owner_a, 'note': '尝试修改已作为核验证据的账户'}, expected=409)

    # With exactly two administrators, concurrent mutual revocation may remove only one.
    admin_a_id, admin_a_body = account('管理员甲', 'CENTER', admin=True)
    admin_b_id, admin_b_body = account('管理员乙', 'CENTER', admin=True)
    admin_a, admin_b = Client(admin_a_body['username']), Client(admin_b_body['username'])
    original_center = next(item for item in admin_a.call('/users') if item['username'] == 'center')
    admin_a.call(f"/users/{original_center['id']}", update_body(original_center, '并发管理员门槛专项验收', admin=False))
    row_a, row_b = find(admin_a, admin_a_id), find(admin_a, admin_b_id)
    gate = Barrier(2)

    def revoke(actor, target):
        gate.wait()
        try:
            actor.call(f"/users/{target['id']}", update_body(target, '并发互相撤销管理员权限', admin=False))
            return 200
        except AssertionError as exc:
            message = str(exc)
            for status in (401, 403, 409):
                if f'received {status}' in message:
                    return status
            raise

    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = [pool.submit(revoke, admin_a, row_b), pool.submit(revoke, admin_b, row_a)]
        statuses = [item.result() for item in outcomes]
    require(statuses.count(200) == 1 and sql("SELECT COUNT(*) FROM af_user WHERE role='CENTER' AND enabled=TRUE AND admin=TRUE") == '1',
        '两个管理员并发互相撤权时只允许一个成功并始终保留管理员')
    winner_name = sql("SELECT username FROM af_user WHERE role='CENTER' AND enabled=TRUE AND admin=TRUE LIMIT 1")
    winner = Client(winner_name)
    original_center = next(item for item in winner.call('/users') if item['username'] == 'center')
    winner.call(f"/users/{original_center['id']}", update_body(original_center, '专项验收结束后恢复原中心管理员', admin=True))


if __name__ == '__main__':
    out = pathlib.Path(__file__).resolve().parent / 'results'; out.mkdir(exist_ok=True)
    status = 'passed'; error = None
    try:
        run()
    except Exception as exc:
        status = 'failed'; error = str(exc)
    report = {'status': status, 'timestamp': datetime.datetime.now().isoformat(),
        'steps': len(results), 'error': error, 'results': results}
    (out / 'account-control-regressions.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key, value in report.items() if key != 'results'}, ensure_ascii=False))
    if error:
        sys.exit(1)
