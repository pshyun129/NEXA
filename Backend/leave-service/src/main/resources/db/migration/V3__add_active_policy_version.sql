-- 기존 불일치 발생분은 자동 보정하지 않고, 확인할 ID를 표시하며 중단한다.
DO $$
DECLARE
    invalid_grant_ids BIGINT[];
BEGIN
    SELECT array_agg(grant_id ORDER BY grant_id)
      INTO invalid_grant_ids
      FROM (
          SELECT g.grant_id
            FROM leave_grant g
            LEFT JOIN leave_accrual_rule r ON r.rule_id = g.rule_id
           WHERE g.rule_id IS NOT NULL
             AND (g.policy_version_id IS NULL
                  OR r.rule_id IS NULL
                  OR g.policy_version_id IS DISTINCT FROM r.policy_version_id)
           ORDER BY g.grant_id
           LIMIT 20
      ) invalid_grants;

    IF invalid_grant_ids IS NOT NULL THEN
        RAISE EXCEPTION 'V3: leave_grant 규칙/버전 참조 불일치. grant_id(최대 20개)=%', invalid_grant_ids
            USING ERRCODE = '23514',
                  HINT = 'README의 V3 사전 검증 쿼리로 확인 후 승인된 데이터 정리 절차를 진행하세요.';
    END IF;
END $$;

-- 활성 포인터는 비워 두고, 같은 그룹 소속 버전만 선택할 수 있도록 보장한다.
ALTER TABLE leave_policy_group
    ADD COLUMN active_policy_version_id BIGINT NULL;

ALTER TABLE leave_policy_version
    ADD CONSTRAINT uq_policy_version_group_pair
        UNIQUE (policy_id, policy_version_id);

ALTER TABLE leave_policy_group
    ADD CONSTRAINT fk_policy_group_active_version
        FOREIGN KEY (policy_id, active_policy_version_id)
        REFERENCES leave_policy_version (policy_id, policy_version_id)
        MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;

-- 기간은 선택 입력으로 전환하며 기존 값과 ck_policy_period는 보존한다.
ALTER TABLE leave_policy_version
    ALTER COLUMN effective_from DROP NOT NULL;

COMMENT ON COLUMN leave_policy_group.active_policy_version_id
    IS '관리자가 선택한 활성 정책 버전. NULL이면 활성 버전 없음';
COMMENT ON COLUMN leave_policy_version.effective_from
    IS '선택적 참고 기간 시작 시각. 활성 버전 선택 기준으로 사용하지 않음';
COMMENT ON COLUMN leave_policy_version.effective_to
    IS '선택적 참고 기간 종료 시각. 활성 버전 선택 기준으로 사용하지 않음';

-- 규칙을 참조하는 발생분은 반드시 해당 규칙의 버전을 함께 참조한다.
ALTER TABLE leave_accrual_rule
    ADD CONSTRAINT uq_accrual_rule_version_pair
        UNIQUE (policy_version_id, rule_id);

ALTER TABLE leave_grant
    ADD CONSTRAINT ck_grant_rule_requires_version
        CHECK (rule_id IS NULL OR policy_version_id IS NOT NULL),
    ADD CONSTRAINT fk_grant_rule_version
        FOREIGN KEY (policy_version_id, rule_id)
        REFERENCES leave_accrual_rule (policy_version_id, rule_id)
        MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;

-- 발생 규칙의 버전별 조회는 위 UNIQUE 인덱스가 지원하므로 중복 생성하지 않는다.
CREATE INDEX idx_leave_request_policy_version ON leave_request (policy_version_id);
CREATE INDEX idx_leave_grant_policy_version ON leave_grant (policy_version_id);
CREATE INDEX idx_leave_grant_rule ON leave_grant (rule_id);
