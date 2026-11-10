"""Approval, actual credit and freeze closure, through HTTP and a disposable MySQL database."""
from integration import *
from test_db import sql, VERIFY


def run():
    assert sql('SELECT DATABASE()') == VERIFY
    center, pay = Client('center'), Client('pay1')
    tag = 'REFUND-END-' + uuid.uuid4().hex[:10]

    def person(label):
        pid = center.call('/people', {'name': label, 'identity': tag + label,
            'phone': '13987651235', 'communityId': 1, 'note': tag})['id']
        username = tag + label
        center.call('/users', {'username': username, 'password': 'Demo2026!',
            'displayName': label, 'role': 'PUBLIC', 'verified': True, 'victimId': pid, 'enabled': True})
        return pid, Client(username)

    victim_id, victim = person('收款人')
    holder_id, holder = person('冻结户主')
    number = '6222' + str(uuid.uuid4().int % 10**12).zfill(12)
    source = center.call('/banks', {'personId': holder_id, 'number': number,
        'orgId': 1, 'opening': 20000, 'note': tag})['id']
    destination = center.call('/banks', {'personId': victim_id, 'number': str(int(number) + 1),
        'orgId': 1, 'opening': 0, 'note': tag})['id']

    def freeze():
        report = center.call('/reports', {'kind': '号码', 'value': '13987651235',
            'description': tag, 'reporterRelation': '提供线索', 'requestKey': uuid.uuid4().hex})
        aid = center.call(f"/reports/{report['id']}")['alert_id']
        center.call(f'/alerts/{aid}/accounts', {'reportId': report['id'], 'bankId': source, 'note': tag})
        center.call(f'/alerts/{aid}/review', {'decision': '通过', 'victimId': victim_id,
            'communityId': 1, 'note': tag})
        cid = center.call(f'/alerts/{aid}/dispatch', {'bankId': source, 'amount': 1000})['commandId']
        pay.call(f'/commands/{cid}/accept', {})
        receipt(cid, 1000)
        return cid

    def receipt(cid, amount, result='全额执行', **extra):
        return pay.call(f'/commands/{cid}/receipt', {'result': result, 'amount': amount,
            'reason': tag, 'voucher': tag, **extra})

    def refund(cid, amount):
        app = victim.call('/applications', {'kind': '返还', 'originalId': cid,
            'payeeBankId': destination, 'amount': amount, 'reason': tag, 'attachments': [victim.file()]})['id']
        center.call(f'/applications/{app}/review', {'decision': '通过', 'amount': amount, 'note': tag})
        child = center.call(f'/applications/{app}')['commands'][0]['id']
        pay.call(f'/commands/{child}/accept', {})
        return app, child

    def progress(app, state, credited, application_state='核定通过'):
        data = victim.call(f'/applications/{app}')
        require(data['state'] == application_state and data['fundsProgress']['state'] == state
            and float(data['fundsProgress']['credited']) == credited, '审批状态与实际入账进度一致：' + state)
        require(data['note'] == tag, '申请状态收尾不覆盖原审核意见')
        for actor in (victim, center):
            page = actor.call('/list/applications?size=50&q=' + data['code'])
            require(page['total'] == 1 and len(page['items']) == 1, '按编号搜索后保留正确的分页与权限范围')
            listed = page['items'][0]
            require(listed['id'] == app and listed['progress_state'] == state
                and float(listed['credited']) == credited and listed['approved'] == data['approved'],
                '中心与群众的列表核定金额、入账金额和进度均与详情一致')
            require(not any(k.startswith('progress_original') or k in ('progress_expires_at','progress_remaining') for k in listed),
                '列表不输出内部计算字段')
        legacy = next(x for x in victim.call('/applications') if x['id'] == app)
        require(legacy['progress_state'] == state and float(legacy['credited']) == credited,
            '非分页申请列表沿用同一入账计算规则')
        other = holder.call('/list/applications?q=' + data['code'])
        require(other['total'] == 0 and not other['items'], '新增到账字段不会跨账号泄露申请')
        return data

    # The review's 600+400 example must NOT invalidate the still-reserved 600.
    root = freeze()
    pending = victim.call('/applications', {'kind': '返还', 'originalId': root,
        'payeeBankId': destination, 'amount': 1000, 'reason': tag, 'attachments': [victim.file()]})
    listed = victim.call('/list/applications?q=' + pending['code'])['items'][0]
    require(listed['approved'] is None and float(listed['credited']) == 0
        and listed['progress_state'] == '等待核定', '待核定申请不把申请金额显示为核定或到账金额')
    a, ac = refund(root, 600); b, bc = refund(root, 400)
    receipt(bc, 400)
    require(sql(f'SELECT remaining,reserved FROM af_command WHERE id={root}') == '600.00\t600.00',
        '先执行400元只消耗400元，其余600元仍由先核定的申请占用')
    progress(a, '等待执行', 0); progress(b, '已成功入账', 400)
    receipt(ac, 600)
    progress(a, '已成功入账', 600); progress(b, '已成功入账', 400)

    ended = []
    # Failure releases the reservation; another approval may then exhaust the freeze.
    for failure in ('拒绝执行', '超时'):
        root = freeze(); a, ac = refund(root, 600)
        if failure == '超时':
            center.call(f'/commands/{ac}/simulate', {'node': '超时'})
        else:
            receipt(ac, 0, '拒绝执行')
        historical = pay.call(f'/commands/{ac}')['state']
        progress(a, '执行失败', 0)
        b, bc = refund(root, 1000)
        balance = sql(f'SELECT available FROM af_bank WHERE id={destination}')
        pay.call(f'/commands/{bc}/receipt', {'result': '全额执行', 'amount': 1000,
            'voucher': tag, 'attachments': ['missing-file']}, expected=404)
        require(sql(f'SELECT available FROM af_bank WHERE id={destination}') == balance,
            '收尾后附件绑定失败，资金入账回滚')
        progress(a, '执行失败', 0)
        receipt(bc, 1000)
        data = progress(a, '申请已失效', 0, '已失效')
        progress(b, '已成功入账', 1000)
        require(float(data['approved']) == 600 and any('全部返还' in str(x['note']) for x in data['timeline']),
            '失效保留原核定金额，并记录其他返还耗尽冻结的原因')
        require(pay.call(f'/commands/{ac}')['state'] == historical, '保留失败或超时指令的原历史状态')
        center.call(f'/commands/{ac}/retry', {'note': tag}, expected=409)
        listing = victim.call('/list/applications?size=50')['items']
        require(next(x for x in listing if x['id'] == a)['state'] == '已失效', '申请列表也显示失效，不能再误认为等待执行')
        ended.append(a)

    # A failed attempt followed by a successful retry credits only once.
    root = freeze(); a, ac = refund(root, 1000)
    receipt(ac, 0, '拒绝执行')
    retry = center.call(f'/commands/{ac}/retry', {'note': tag})['id']
    progress(a, '等待执行', 0)
    pay.call(f'/commands/{retry}/accept', {}); receipt(retry, 1000)
    progress(a, '已成功入账', 1000)

    # Partially credited decisions remain meaningful even after the rest is returned.
    root = freeze(); a, ac = refund(root, 600)
    receipt(ac, 400, '部分执行'); progress(a, '部分入账', 400)
    b, bc = refund(root, 600); receipt(bc, 600)
    progress(a, '部分入账（已结束）', 400); progress(b, '已成功入账', 600)

    # Expiration and a successful appeal both cancel approved but unpaid refunds.
    for ending in ('到期', '申诉'):
        root = freeze(); partial, pc = refund(root, 600)
        receipt(pc, 400, '部分执行'); progress(partial, '部分入账', 400)
        a, ac = refund(root, 600)
        if ending == '到期':
            center.call(f'/commands/{root}/simulate', {'node': '到期'})
        else:
            appeal = holder.call('/applications', {'kind': '申诉', 'originalId': root, 'reason': tag})['id']
            listed = next(x for x in holder.call('/list/applications?size=50')['items'] if x['id'] == appeal)
            require(listed['credited'] is None and listed['progress_state'] == '待受理', '申诉展示办理状态，不显示返还入账金额')
            center.call(f'/applications/{appeal}/review', {'decision': '受理', 'note': tag})
            center.call(f'/applications/{appeal}/review', {'decision': '成立', 'note': tag})
            release = center.call(f'/applications/{appeal}')['commands'][0]['id']
            pay.call(f'/commands/{release}/accept', {}); receipt(release, 0, '解除成功')
        progress(a, '申请已失效', 0, '已失效')
        ended_partial=progress(partial, '部分入账（已结束）', 400)
        require('不能基于该止付继续办理' in ended_partial['fundsProgress']['note'], '部分入账在到期或解除后明确提示不能继续办理')
        require(pay.call(f'/commands/{ac}')['state'] == '已取消', '原冻结结束时同步取消未执行返还')
        pay.call(f'/commands/{ac}/receipt', {'result': '全额执行', 'amount': 1000, 'voucher': tag}, expected=409)
        ended.append(a)

    require(center.call('/reconciliation')['ok'], '全部组合的资金对账一致')
    require(center.call('/audit/verify')['ok'], '全部组合的审计链完整')
    return {'expiredApplications': ended, 'source': source, 'destination': destination}


if __name__ == '__main__':
    error, ids = None, {}
    try:
        ids = run()
    except Exception as exc:
        error = str(exc)
    output = {'status': 'failed' if error else 'passed', 'steps': len(results), 'error': error, 'ids': ids, 'results': results}
    folder = pathlib.Path(__file__).parent / 'results'; folder.mkdir(exist_ok=True)
    (folder / 'refund-completion-regressions.json').write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in output.items() if k != 'results'}, ensure_ascii=False))
    if error:
        sys.exit(1)
