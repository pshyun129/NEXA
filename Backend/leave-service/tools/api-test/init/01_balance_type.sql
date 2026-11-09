-- 개발 전용, 수동 검토 후 psql로 실행. Python Runner는 SQL을 실행하지 않는다.
-- 필수 -v: expected_database, fixture_key, allow_dev_write=YES
-- 재실행 -v: expected_balance_type_id=<manifest 실제 ID>
\set ON_ERROR_STOP on
\if :{?expected_balance_type_id}
\else
  \set expected_balance_type_id ''
\endif
BEGIN;
SELECT set_config('e2e.expected_database', :'expected_database', true),
       set_config('e2e.fixture_key', :'fixture_key', true),
       set_config('e2e.allow_dev_write', :'allow_dev_write', true),
       set_config('e2e.expected_balance_type_id', :'expected_balance_type_id', true);
DO $fixture$
DECLARE
    p_key text := current_setting('e2e.fixture_key');
    p_id bigint := nullif(current_setting('e2e.expected_balance_type_id'), '')::bigint;
    v_row leave_balance_type%ROWTYPE;
BEGIN
    IF current_setting('e2e.allow_dev_write') <> 'YES'
       OR current_database() <> current_setting('e2e.expected_database')
       OR current_database() !~ '(dev|test|leave)' THEN
        RAISE EXCEPTION '승인된 개발 DB와 쓰기 허용 확인 필요';
    END IF;
    IF p_key !~ '^[A-Za-z0-9_-]{1,48}$' THEN
        RAISE EXCEPTION '고정 ASCII fixtureKey 1~48자 필요';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('leave:e2e-fixture'), hashtext(p_key));
    SELECT * INTO v_row FROM leave_balance_type
     WHERE name = 'E2E_' || p_key || '_ANNUAL_BALANCE' FOR UPDATE;
    IF FOUND THEN
        IF p_id IS NULL OR p_id <> v_row.balance_type_id OR NOT v_row.is_active THEN
            RAISE EXCEPTION '기존 잔액 유형 소유 ID 또는 속성 불일치';
        END IF;
    ELSE
        IF p_id IS NOT NULL THEN
            RAISE EXCEPTION 'manifest 잔액 유형 없음';
        END IF;
        INSERT INTO leave_balance_type(name, is_active)
        VALUES ('E2E_' || p_key || '_ANNUAL_BALANCE', TRUE);
    END IF;
END
$fixture$;
SELECT balance_type_id AS "balanceTypeId", name, is_active AS "isActive"
FROM leave_balance_type
WHERE name = 'E2E_' || current_setting('e2e.fixture_key') || '_ANNUAL_BALANCE';
COMMIT;
-- 오류 시 ON_ERROR_STOP으로 종료되며 연결 종료 시 미커밋 트랜잭션은 롤백된다.
