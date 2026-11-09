-- API 기준 정책·유형·활성화·배정 이후에만 수동 실행한다.
-- 필수 -v: expected_database, fixture_key, allow_dev_write=YES,
-- dedicated_employee_confirmed=YES, employee_id, balance_type_id, policy_id,
-- version_id, minutes, valid_from, expires_on, credit_at
-- 최초 지급 후에도 동일 fixtureKey·수량·시각·ID를 재사용한다. 사용 이력은 보존한다.
\set ON_ERROR_STOP on
\if :{?expected_policy_name}
\else
  \set expected_policy_name ''
\endif
BEGIN;
SELECT set_config('e2e.expected_database', :'expected_database', true),
       set_config('e2e.fixture_key', :'fixture_key', true),
       set_config('e2e.allow_dev_write', :'allow_dev_write', true),
       set_config('e2e.dedicated_employee_confirmed', :'dedicated_employee_confirmed', true),
       set_config('e2e.employee_id', :'employee_id', true),
       set_config('e2e.balance_type_id', :'balance_type_id', true),
       set_config('e2e.policy_id', :'policy_id', true),
       set_config('e2e.expected_policy_name', :'expected_policy_name', true),
       set_config('e2e.version_id', :'version_id', true),
       set_config('e2e.minutes', :'minutes', true),
       set_config('e2e.valid_from', :'valid_from', true),
       set_config('e2e.expires_on', :'expires_on', true),
       set_config('e2e.credit_at', :'credit_at', true);
DO $fixture$
DECLARE
    p_key text := current_setting('e2e.fixture_key');
    p_employee bigint := current_setting('e2e.employee_id')::bigint;
    p_balance bigint := current_setting('e2e.balance_type_id')::bigint;
    p_policy bigint := current_setting('e2e.policy_id')::bigint;
    p_policy_name text := coalesce(nullif(current_setting('e2e.expected_policy_name'), ''),
                                  'E2E_' || current_setting('e2e.fixture_key') || '_POLICY');
    p_version bigint := current_setting('e2e.version_id')::bigint;
    p_minutes integer := current_setting('e2e.minutes')::integer;
    p_from timestamptz := current_setting('e2e.valid_from')::timestamptz;
    p_to timestamptz := current_setting('e2e.expires_on')::timestamptz;
    p_credit timestamptz := current_setting('e2e.credit_at')::timestamptz;
    v_now timestamptz;
    v_key text;
    v_reason text;
    v_account bigint;
    v_grant leave_grant%ROWTYPE;
    v_ledger leave_ledger%ROWTYPE;
    v_quantity numeric;
    v_reserved numeric;
BEGIN
    IF current_setting('e2e.allow_dev_write') <> 'YES'
       OR current_setting('e2e.dedicated_employee_confirmed') <> 'YES'
       OR current_database() <> current_setting('e2e.expected_database')
       OR current_database() !~ '(dev|test|leave)' THEN
        RAISE EXCEPTION '승인된 개발 DB·전용 직원·쓰기 허용 확인 필요';
    END IF;
    IF p_key !~ '^[A-Za-z0-9_-]{1,48}$'
       OR least(p_employee, p_balance, p_policy, p_version, p_minutes) <= 0 THEN
        RAISE EXCEPTION '유효한 Fixture 식별자와 실제 양수 ID 필요';
    END IF;
    IF left(p_policy_name, length('E2E_' || p_key || '_')) <> 'E2E_' || p_key || '_' THEN
        RAISE EXCEPTION '테스트 전용 정책 이름이 아님';
    END IF;
    -- T1/T2/ADJUST와 같은 직원 잠금을 가장 먼저 획득한다.
    PERFORM pg_advisory_xact_lock(-p_employee);
    PERFORM pg_advisory_xact_lock(hashtext('leave:e2e-fixture'), hashtext(p_key));
    v_now := clock_timestamp();
    PERFORM set_config('e2e.as_of', v_now::text, true);
    IF p_from > p_credit OR p_credit > v_now OR p_to <= v_now THEN
        RAISE EXCEPTION '지급 시각·현재 유효기간 불일치';
    END IF;
    v_key := 'e2e:grant:' || p_key || ':' || p_employee || ':initial';
    v_reason := 'E2E_' || p_key || '_INITIAL_GRANT';

    PERFORM 1 FROM leave_policy_group
     WHERE policy_id = p_policy AND name = p_policy_name
       AND active_policy_version_id = p_version FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION '정책 소유권·활성 버전 불일치'; END IF;
    PERFORM 1 FROM leave_policy_version
     WHERE policy_id = p_policy AND policy_version_id = p_version FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION '정책 버전 소속 불일치'; END IF;
    PERFORM 1 FROM leave_balance_type
     WHERE balance_type_id = p_balance AND is_active
       AND name = 'E2E_' || p_key || '_ANNUAL_BALANCE' FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION '잔액 유형 소유권 불일치'; END IF;
    IF (SELECT count(*) FROM employee_leave_policy_assignment
         WHERE emp_id = p_employee AND effective_from <= v_now
           AND (effective_to IS NULL OR v_now < effective_to)) <> 1
       OR NOT EXISTS (SELECT 1 FROM employee_leave_policy_assignment
         WHERE emp_id = p_employee AND policy_id = p_policy AND effective_from <= v_now
           AND (effective_to IS NULL OR v_now < effective_to)) THEN
        RAISE EXCEPTION '현재 직원 배정이 하나가 아니거나 정책 불일치';
    END IF;
    SELECT account_id INTO v_account FROM leave_account
     WHERE emp_id = p_employee AND balance_type_id = p_balance FOR UPDATE;
    IF NOT FOUND THEN
        INSERT INTO leave_account(emp_id, balance_type_id) VALUES (p_employee, p_balance)
        RETURNING account_id INTO v_account;
    END IF;
    SELECT * INTO v_ledger FROM leave_ledger WHERE idempotency_key = v_key FOR UPDATE;
    IF FOUND THEN
        SELECT * INTO STRICT v_grant FROM leave_grant WHERE grant_id = v_ledger.grant_id FOR UPDATE;
        IF v_ledger.account_id <> v_account OR v_ledger.event_type <> 'GRANT'
           OR v_ledger.quantity_delta <> p_minutes OR v_ledger.reserved_delta <> 0
           OR v_ledger.created_at <> p_credit OR v_ledger.reason IS DISTINCT FROM v_reason
           OR v_ledger.reverses_entry_id IS NOT NULL OR v_ledger.leave_req_id IS NOT NULL
           OR v_ledger.allocation_id IS NOT NULL OR v_ledger.actor_emp_id IS NOT NULL
           OR v_grant.account_id <> v_account OR v_grant.policy_version_id IS DISTINCT FROM p_version
           OR v_grant.rule_id IS NOT NULL OR v_grant.parent_grant_id IS NOT NULL
           OR v_grant.grant_kind <> 'MANUAL' OR v_grant.original_minutes <> p_minutes
           OR v_grant.granted_on <> p_credit OR v_grant.created_at <> p_credit
           OR v_grant.valid_from <> p_from OR v_grant.expires_on IS DISTINCT FROM p_to THEN
            RAISE EXCEPTION '기존 지급과 입력 계약 불일치: 덮어쓰기 금지';
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM leave_grant WHERE account_id = v_account)
           OR EXISTS (SELECT 1 FROM leave_ledger WHERE account_id = v_account) THEN
            RAISE EXCEPTION '최초 지급 키 없는 기존 계정 데이터: 중복 지급 금지';
        END IF;
        INSERT INTO leave_grant(account_id, policy_version_id, rule_id, parent_grant_id,
            grant_kind, granted_on, original_minutes, valid_from, expires_on, created_at)
        VALUES(v_account, p_version, NULL, NULL, 'MANUAL', p_credit, p_minutes, p_from, p_to, p_credit)
        RETURNING * INTO v_grant;
        INSERT INTO leave_ledger(account_id, grant_id, reverses_entry_id, leave_req_id,
            allocation_id, event_type, quantity_delta, reserved_delta, reason, created_at,
            idempotency_key, actor_emp_id)
        VALUES(v_account, v_grant.grant_id, NULL, NULL, NULL, 'GRANT', p_minutes, 0,
            v_reason, p_credit, v_key, NULL)
        RETURNING * INTO v_ledger;
    END IF;
    IF (SELECT count(*) FROM leave_grant WHERE account_id = v_account) <> 1
       OR EXISTS (SELECT 1 FROM leave_ledger WHERE reverses_entry_id = v_ledger.entry_id) THEN
        RAISE EXCEPTION '알 수 없는 발생분 또는 최초 지급 역분개 존재';
    END IF;
    -- 사용 후 재실행도 잔액을 복구하지 않는다. 현재 원장 합계만 검증한다.
    SELECT coalesce(sum(quantity_delta::numeric), 0), coalesce(sum(reserved_delta::numeric), 0)
      INTO v_quantity, v_reserved FROM leave_ledger
     WHERE account_id = v_account AND grant_id = v_grant.grant_id AND created_at <= v_now;
    IF v_quantity < 0 OR v_reserved < 0 OR v_reserved > v_quantity
       OR v_quantity > 9223372036854775807 OR v_reserved > 9223372036854775807 THEN
        RAISE EXCEPTION '현재 발생분 잔액 정합성·Long 범위 오류';
    END IF;
END
$fixture$;
SELECT a.account_id AS "accountId", g.grant_id AS "grantId", l.entry_id AS "initialGrantEntryId",
       current_setting('e2e.as_of')::timestamptz AS "asOf",
       g.valid_from AS "validFrom", g.expires_on AS "expiresOn",
       coalesce((SELECT sum(quantity_delta::numeric) FROM leave_ledger x
          WHERE x.grant_id = g.grant_id AND x.created_at <= current_setting('e2e.as_of')::timestamptz), 0) AS "quantityMinutes",
       coalesce((SELECT sum(reserved_delta::numeric) FROM leave_ledger x
          WHERE x.grant_id = g.grant_id AND x.created_at <= current_setting('e2e.as_of')::timestamptz), 0) AS "reservedMinutes"
FROM leave_account a JOIN leave_grant g ON g.account_id = a.account_id
JOIN leave_ledger l ON l.grant_id = g.grant_id AND l.account_id = a.account_id
WHERE l.idempotency_key = 'e2e:grant:' || current_setting('e2e.fixture_key') || ':' ||
      current_setting('e2e.employee_id') || ':initial';
COMMIT;
-- 오류는 ON_ERROR_STOP으로 연결을 종료하여 전부 롤백한다. 재실행 시 원장을 수정하지 않는다.
