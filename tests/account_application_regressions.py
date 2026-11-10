"""Identity changes and exhausted freezes, through real HTTP and a disposable MySQL database."""
from integration import *
from test_db import sql, VERIFY
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier


def run():
    assert sql('SELECT DATABASE()') == VERIFY
    sql('DELETE FROM af_query_limit')
    center, pay = Client('center'), Client('pay1')
    tag = 'ACCOUNT-' + uuid.uuid4().hex[:10]

    def person(label):
        return center.call('/people', {'name': label, 'identity': tag + label,
            'phone': '13987651234', 'communityId': 1, 'note': '核对实名资料用于业务边界验收'})['id']

    def account(label, identity=0):
        body = {'username': tag + label, 'password': 'Demo2026!', 'displayName': label,
            'role': 'PUBLIC', 'verified': bool(identity), 'victimId': identity, 'enabled': True}
        uid = center.call('/users', body)['id']
        return uid, body, Client(body['username'])

    def report(actor):
        return actor.call('/reports', {'kind': '号码', 'value': '13987651234',
            'description': tag, 'reporterRelation': '提供线索', 'requestKey': uuid.uuid4().hex})

    # Anonymous reporting does not prevent the first verified identity association.
    uid, body, unverified = account('首次实名')
    original_report = report(unverified)
    first_person, other_person = person('首次实名'), person('其他实名')
    verified_body = {**body, 'verified': True, 'victimId': first_person}
    center.call(f'/users/{uid}', verified_body)
    require(unverified.call('/session')['user']['verified'], '举报后的首次实名核验可成功')
    require(sql(f"SELECT victim_id IS NULL FROM af_report WHERE id={original_report['id']}") == '1',
        '首次实名不追溯把匿名提供的线索改成本人受损')
    for change in [{'victimId': other_person}, {'verified': False, 'victimId': 0},
                   {'role': 'CENTER', 'verified': False, 'victimId': 0}]:
        center.call(f'/users/{uid}', {**verified_body, **change}, expected=409)
    center.call(f'/users/{uid}', {**verified_body, 'displayName': '核验后正常修改显示名'})
    require(sql(f'SELECT owner_id FROM af_report WHERE id={original_report["id"]}') == str(uid),
        '正常资料维护保留举报提交者，不能通过改绑或去实名转移历史业务')
    center.call(f'/users/{uid}', {**verified_body, 'enabled': False})
    center.call(f'/users/{uid}', verified_body)
    empty_id, empty_body, _ = account('无业务首次实名')
    center.call(f'/users/{empty_id}', {**empty_body, 'verified': True, 'victimId': other_person})

    # Concurrent first verification and reporting may refresh once, but cannot save a stale identity.
    race_id, race_body, race_user = account('并发核验')
    race_person = person('并发核验')
    race_center, gate = Client('center'), Barrier(2)

    def racing_report():
        gate.wait()
        try:
            return report(race_user)
        except AssertionError as exc:
            if 'received 409' not in str(exc):
                raise
            return None

    def racing_verify():
        gate.wait()
        race_center.call(f'/users/{race_id}', {**race_body, 'verified': True, 'victimId': race_person})

    with ThreadPoolExecutor(max_workers=2) as pool:
        submitted, verified = pool.submit(racing_report), pool.submit(racing_verify)
        raced = submitted.result(); verified.result()
    if raced is None:
        raced = report(race_user)
    require(sql(f'SELECT victim_id FROM af_user WHERE id={race_id}') == str(race_person)
        and sql(f"SELECT owner_id FROM af_report WHERE id={raced['id']}") == str(race_id),
        '并发举报和首次实名都可继续，身份变更后重试不会串账号')

    victim_person, holder_person = person('收款受害人'), person('被冻结户主')
    victim_id, victim_body, victim = account('收款受害人', victim_person)
    holder_id, holder_body, holder = account('被冻结户主', holder_person)
    number = '6222' + str(uuid.uuid4().int % 10**12).zfill(12)
    source = center.call('/banks', {'personId': holder_person, 'number': number,
        'orgId': 1, 'opening': 5000, 'note': '验收已核验户主的原始资金账户'})['id']
    destination = center.call('/banks', {'personId': victim_person, 'number': str(int(number) + 1),
        'orgId': 1, 'opening': 0, 'note': '验收已核验受害人本人的收款账户'})['id']

    def freeze():
        r = report(center)
        aid = center.call(f"/reports/{r['id']}")['alert_id']
        link = {'reportId': r['id'], 'bankId': source, 'note': '核对交易凭据和原始线索的账户关系'}
        center.call(f'/alerts/{aid}/accounts', link)
        center.call(f'/alerts/{aid}/review', {'decision': '通过', 'victimId': victim_person,
            'communityId': 1, 'note': tag})
        cid = center.call(f'/alerts/{aid}/dispatch', {'bankId': source, 'amount': 100})['commandId']
        pay.call(f'/commands/{cid}/accept', {})
        pay.call(f'/commands/{cid}/receipt', {'result': '全额执行', 'amount': 100, 'voucher': tag})
        return aid, cid, link

    def appeal(cid, state):
        app = holder.call('/applications', {'kind': '申诉', 'originalId': cid, 'reason': tag})['id']
        if state != '待受理':
            center.call(f'/applications/{app}/review', {'decision': '受理', 'note': tag})
        if state in ('驳回待补', '已维持', '已生成解除指令'):
            center.call(f'/applications/{app}/review', {'decision': {
                '驳回待补': '补正', '已维持': '维持', '已生成解除指令': '成立'}[state], 'note': tag})
        return app

    def refund(cid, amount=100):
        app = victim.call('/applications', {'kind': '返还', 'originalId': cid,
            'payeeBankId': destination, 'amount': amount, 'reason': tag, 'attachments': [victim.file()]})['id']
        center.call(f'/applications/{app}/review', {'decision': '通过', 'amount': amount, 'note': tag})
        command = center.call(f'/applications/{app}')['commands'][0]['id']
        pay.call(f'/commands/{command}/accept', {})
        return app, command

    completed = []
    for initial in ('待受理', '审核中', '驳回待补', '已维持', '已生成解除指令'):
        aid, cid, link = freeze()
        center.call(f'/alerts/{aid}/accounts', link)
        require(not center.call(f'/alerts/{aid}')['needs_review'], '重复提交相同核验材料不撤销已完成的复核')
        center.call(f'/alerts/{aid}/accounts', {**link, 'note': '试图覆盖已经用于止付的原始核验证据'}, expected=409)
        require(center.call(f'/alerts/{aid}')['targets'][0]['basis'] == link['note'], '已派发账户的核验证据不能被覆盖')
        require(victim.call(f'/commands/{cid}')['bank']['holder'] == '***'
            and holder.call(f'/commands/{cid}')['bank']['holder'] == '被冻结户主', '受害人不能读取其他账户户主的完整姓名')
        app = appeal(cid, initial)
        if initial == '已维持':
            holder.call('/applications', {'kind': '申诉', 'originalId': cid, 'reason': tag}, expected=409)
            require(not holder.call(f'/commands/{cid}')['appeal_eligible'], '已裁定维持的申诉不能重复新建')
            row = holder.call('/list/commands?size=50')
            require(not next(c for c in row['items'] if c['id'] == cid)['appeal_eligible'], '列表与详情的申诉入口保持一致')
        if initial in ('待受理', '审核中', '驳回待补', '已生成解除指令'):
            center.call(f'/users/{holder_id}', {**holder_body, 'enabled': False}, expected=409)
        release = None
        if initial == '已生成解除指令':
            release = center.call(f'/applications/{app}')['commands'][0]['id']
            pay.call(f'/commands/{release}/accept', {})
        returned, command = refund(cid)
        extra = victim.call('/applications', {'kind': '返还', 'originalId': cid, 'amount': 1,
            'payeeBankId': destination, 'reason': tag, 'attachments': [victim.file()]})['id']
        if initial == '驳回待补':
            balance = sql(f'SELECT available FROM af_bank WHERE id={destination}')
            pay.call(f'/commands/{command}/receipt', {'result': '全额执行', 'amount': 100,
                'voucher': tag, 'attachments': ['missing-file']}, expected=404)
            require(sql(f'SELECT available FROM af_bank WHERE id={destination}') == balance
                and holder.call(f'/applications/{app}')['state'] == initial
                and sql(f'SELECT remaining FROM af_command WHERE id={cid}') == '100.00',
                '返还回执失败时，入账、申请收尾与原冻结状态一起回滚')
            barrier, pay_race, holder_race = Barrier(2), Client('pay1'), Client(holder_body['username'])

            def finish():
                barrier.wait()
                pay_race.call(f'/commands/{command}/receipt', {'result': '全额执行', 'amount': 100, 'voucher': tag})

            def supplement():
                evidence = holder_race.file()
                barrier.wait()
                try:
                    holder_race.call(f'/applications/{app}/supplement', {'reason': tag, 'attachments': [evidence]})
                except AssertionError as exc:
                    if 'received 409' not in str(exc):
                        raise

            with ThreadPoolExecutor(max_workers=2) as pool:
                jobs = [pool.submit(finish), pool.submit(supplement)]
                for job in jobs:
                    job.result()
        else:
            pay.call(f'/commands/{command}/receipt', {'result': '全额执行', 'amount': 100, 'voucher': tag})
        expected = initial if initial in ('已维持', '已生成解除指令') else '已失效'
        detail = holder.call(f'/applications/{app}')
        require(detail['state'] == expected and detail['note'] == (tag if initial != '待受理' else None),
            '全额返还结束未决定申诉，已作出的裁定和原审核意见保留')
        if expected == '已失效':
            require(any('全部返还' in (x['note'] or '') for x in detail['timeline']), '申请结束说明具体原因')
        require(victim.call(f'/applications/{extra}')['state'] == '已失效'
            and victim.call(f'/applications/{returned}')['state'] == '核定通过', '未核定返还结束，成功返还的审核决定保留')
        ended = holder.call(f'/commands/{cid}')
        require(ended['state'] == '已解除' and float(ended['remaining']) == 0
            and not ended['application_eligible'] and not ended['actions']['extend'], '资金耗尽后关闭原止付及新申请、延期入口')
        holder.call(f'/applications/{app}/supplement', {'reason': tag}, expected=409)
        holder.call('/applications', {'kind': '申诉', 'originalId': cid, 'reason': tag}, expected=409)
        if release:
            require(pay.call(f'/commands/{release}')['state'] == '已失效', '尚未执行的解除指令同步结束')
            pay.call(f'/commands/{release}/receipt', {'result': '解除成功', 'amount': 0, 'voucher': tag}, expected=409)
        require(sql(f'SELECT COUNT(*) FROM af_ledger WHERE command_id={command}') == '2', '返还仅保存转出和收款流水，不重复释放给户主')
        completed.append(cid)

    # A rejected instruction is still actionable; completed retry history must not block disabling.
    _, cid, _ = freeze()
    returned, command = refund(cid)
    pay.call(f'/commands/{command}/receipt', {'result': '拒绝执行', 'amount': 0, 'reason': tag, 'voucher': tag})
    center.call(f'/users/{victim_id}', {**victim_body, 'enabled': False}, expected=409)
    retry = center.call(f'/commands/{command}/retry', {'note': tag})['id']
    pay.call(f'/commands/{retry}/accept', {})
    pay.call(f'/commands/{retry}/receipt', {'result': '全额执行', 'amount': 100, 'voucher': tag})
    center.call(f'/users/{victim_id}', {**victim_body, 'enabled': False})
    center.call(f'/users/{victim_id}', victim_body)
    center.call(f'/users/{holder_id}', {**holder_body, 'enabled': False})
    center.call(f'/users/{holder_id}', holder_body)
    require(center.call('/reconciliation')['ok'], '全额返还、失败重试与并发补正后的全部资金对账通过')
    require(center.call('/audit/verify')['ok'], '账户维护与申请收尾的审计链通过')
    return {'completed': completed, 'source': source, 'destination': destination}


if __name__ == '__main__':
    error, ids = None, {}
    try:
        ids = run()
    except Exception as exc:
        error = str(exc)
    output = {'status': 'failed' if error else 'passed', 'steps': len(results), 'error': error, 'ids': ids, 'results': results}
    folder = pathlib.Path(__file__).parent / 'results'; folder.mkdir(exist_ok=True)
    (folder / 'account-application-regressions.json').write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in output.items() if k != 'results'}, ensure_ascii=False))
    if error:
        sys.exit(1)
