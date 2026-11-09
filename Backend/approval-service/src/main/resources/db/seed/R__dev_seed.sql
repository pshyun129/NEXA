-- 로컬/개발 전용 테스트 데이터 (소량). dev 프로필에서만 flyway.locations에 포함된다. 운영에는 절대 포함하지 말 것
-- Repeatable(R__) 마이그레이션이라 모든 V__ 뒤에 실행되고, 이 파일이 바뀌면 다시 실행된다.
--
-- 사원 ID 는 user-service dev seed 와 맞춘다 (관리자는 결재선에 넣지 않는다. ADMIN 은 모든 문서를 볼 수 있다)
--   101 이서연(인사팀 대리)  102 최민준(인사팀 부장·팀장)
--   103 정하은(개발팀 사원)  104 강도윤(개발팀 과장)  105 윤지호(개발팀 부장·팀장)
--   106 한예린(영업팀 과장·팀장)
--
-- 문서 ID 는 1001~1008 로 고정한다. 다시 실행되면 이 문서들의 결재선·이력을 지우고 다시 넣는다 (화면에서 처리한 내용도 초기화)
-- 문서번호는 처음 넣을 때만 채번 카운터에서 받고, 이후에는 그대로 둔다 (이미 있는 문서번호와 겹치지 않게)
-- 연동 데이터가 필요 없는 직접 기안 양식(GENERAL, BUSINESS_TRIP)만 쓴다. 아웃박스에는 넣지 않는다 (Kafka 로 발행되지 않음)
--
-- | 문서 | 양식          | 상태                  | 기안자 | 결재선 (지금 차례 ▶)
-- | 1001 | GENERAL       | 진행중 (1단계 대기)   | 103    | ▶104 → 105, 참조 101
-- | 1002 | GENERAL       | 진행중 (2단계 합의)   | 101    | 102 승인 → ▶105(합의), 열람 103
-- | 1003 | BUSINESS_TRIP | 승인                  | 106    | 102 승인 → 105 승인
-- | 1004 | GENERAL       | 반려                  | 104    | 105 반려 → 102 대기
-- | 1005 | GENERAL       | 취소 (기안자 회수)    | 103    | 105 대기
-- | 1006 | BUSINESS_TRIP | 승인 (전결)           | 104    | 105 전결 → 102 생략
-- | 1007 | GENERAL       | 진행중 (반려 후 재기안) | 106  | ▶102, 참조 101
-- | 1008 | BUSINESS_TRIP | 진행중 (1단계 대기)   | 102    | ▶105, 참조 106

-- 문서 하나를 넣거나 갱신한다. 문서번호는 없을 때만 채번
CREATE OR REPLACE FUNCTION pg_temp.seed_document(p_doc_id BIGINT, p_form VARCHAR, p_title VARCHAR, p_status VARCHAR,
                                                 p_drafted_at TIMESTAMPTZ, p_completed_at TIMESTAMPTZ,
                                                 p_drafter_id BIGINT)
RETURNS VOID AS $$
DECLARE
    v_doc_no VARCHAR;
    v_year   INT := EXTRACT(YEAR FROM p_drafted_at AT TIME ZONE 'Asia/Seoul')::INT;
    v_next   INT;
BEGIN
    SELECT doc_no INTO v_doc_no FROM approval_document WHERE doc_id = p_doc_id;
    IF v_doc_no IS NULL THEN
        INSERT INTO document_number_counter (form_type, year, last_no)
        VALUES (p_form, v_year, 1)
        ON CONFLICT (form_type, year) DO UPDATE SET last_no = document_number_counter.last_no + 1
        RETURNING last_no INTO v_next;
        v_doc_no := p_form || '-' || v_year || '-' || lpad(v_next::text, 4, '0');
    END IF;

    INSERT INTO approval_document (doc_id, doc_no, form_type, title, status, drafted_at, completed_at, drafter_id,
                                   source_type, source_id, request_key)
    VALUES (p_doc_id, v_doc_no, p_form, p_title, p_status, p_drafted_at, p_completed_at, p_drafter_id,
            'DIRECT', NULL, NULL)
    ON CONFLICT (doc_id) DO UPDATE SET
        form_type    = EXCLUDED.form_type,
        title        = EXCLUDED.title,
        status       = EXCLUDED.status,
        drafted_at   = EXCLUDED.drafted_at,
        completed_at = EXCLUDED.completed_at,
        drafter_id   = EXCLUDED.drafter_id,
        source_type  = EXCLUDED.source_type,
        source_id    = EXCLUDED.source_id,
        request_key  = EXCLUDED.request_key;
END;
$$ LANGUAGE plpgsql;

-- 다시 실행될 때를 위해 seed 문서의 결재선·이력을 비운다
DELETE FROM approval_history WHERE doc_id BETWEEN 1001 AND 1008;
DELETE FROM approval_line    WHERE doc_id BETWEEN 1001 AND 1008;

-- ===== 문서 =====
SELECT pg_temp.seed_document(1001, 'GENERAL',       '개발팀 모니터 추가 구매 요청',   'INPROGRESS',
                             '2026-10-30 10:00+09', NULL,                  103);
SELECT pg_temp.seed_document(1002, 'GENERAL',       '11월 사내 교육 일정 안내',       'INPROGRESS',
                             '2026-10-28 14:00+09', NULL,                  101);
SELECT pg_temp.seed_document(1003, 'BUSINESS_TRIP', '부산 고객사 방문 출장',          'APPROVED',
                             '2026-10-20 09:30+09', '2026-10-21 11:00+09', 106);
SELECT pg_temp.seed_document(1004, 'GENERAL',       '서버 증설 비용 품의',            'REJECTED',
                             '2026-10-22 10:00+09', '2026-10-23 09:00+09', 104);
SELECT pg_temp.seed_document(1005, 'GENERAL',       '팀 회식비 지원 요청',            'CANCEL',
                             '2026-10-24 17:00+09', '2026-10-24 17:30+09', 103);
SELECT pg_temp.seed_document(1006, 'BUSINESS_TRIP', '판교 협력사 기술 미팅',          'APPROVED',
                             '2026-10-27 09:00+09', '2026-10-27 13:00+09', 104);
-- 재기안하면 상신일시가 재기안 시각으로 바뀐다
SELECT pg_temp.seed_document(1007, 'GENERAL',       '영업팀 신입 채용 공고 게시 요청', 'INPROGRESS',
                             '2026-10-29 09:00+09', NULL,                  106);
SELECT pg_temp.seed_document(1008, 'BUSINESS_TRIP', '대전 지사 교육 출장',            'INPROGRESS',
                             '2026-11-02 10:00+09', NULL,                  102);

-- ===== 결재선 =====
-- 참조자·열람자는 step 이 없다. 결재선은 만들 때 모두 PENDING 으로 시작한다
INSERT INTO approval_line (doc_id, step, role, emp_id, action, acted_at) VALUES
    (1001, 1,    'APPROVER',  104, 'PENDING',  NULL),
    (1001, 2,    'APPROVER',  105, 'PENDING',  NULL),
    (1001, NULL, 'REFERENCE', 101, 'PENDING',  NULL),

    (1002, 1,    'APPROVER',  102, 'AGREE',    '2026-10-28 16:00+09'),
    (1002, 2,    'AGREEMENT', 105, 'PENDING',  NULL),
    (1002, NULL, 'VIEWER',    103, 'PENDING',  NULL),

    (1003, 1,    'APPROVER',  102, 'AGREE',    '2026-10-20 15:00+09'),
    (1003, 2,    'APPROVER',  105, 'AGREE',    '2026-10-21 11:00+09'),

    (1004, 1,    'APPROVER',  105, 'DISAGREE', '2026-10-23 09:00+09'),
    (1004, 2,    'APPROVER',  102, 'PENDING',  NULL),

    (1005, 1,    'APPROVER',  105, 'PENDING',  NULL),

    (1006, 1,    'APPROVER',  105, 'AGREE',    '2026-10-27 13:00+09'),
    (1006, 2,    'APPROVER',  102, 'SKIPPED',  '2026-10-27 13:00+09'),

    (1007, 1,    'APPROVER',  102, 'PENDING',  NULL),
    (1007, NULL, 'REFERENCE', 101, 'PENDING',  NULL),

    (1008, 1,    'APPROVER',  105, 'PENDING',  NULL),
    (1008, NULL, 'REFERENCE', 106, 'PENDING',  NULL);

-- ===== 이력 =====
-- round: 상신이 1차, 재기안할 때마다 +1
INSERT INTO approval_history (doc_id, round, actor_id, action, target_emp_id, comment, occurred_at) VALUES
    (1001, 1, 103, 'SUBMIT',        NULL, NULL, '2026-10-30 10:00+09'),

    (1002, 1, 101, 'SUBMIT',        NULL, NULL, '2026-10-28 14:00+09'),
    (1002, 1, 102, 'APPROVE',       NULL, NULL, '2026-10-28 16:00+09'),

    (1003, 1, 106, 'SUBMIT',        NULL, NULL, '2026-10-20 09:30+09'),
    (1003, 1, 102, 'APPROVE',       NULL, NULL, '2026-10-20 15:00+09'),
    (1003, 1, 105, 'APPROVE',       NULL, NULL, '2026-10-21 11:00+09'),

    (1004, 1, 104, 'SUBMIT',        NULL, NULL, '2026-10-22 10:00+09'),
    (1004, 1, 105, 'REJECT',        NULL, '견적서를 두 곳 이상 받아 비교한 뒤 다시 올려 주세요.', '2026-10-23 09:00+09'),

    (1005, 1, 103, 'SUBMIT',        NULL, NULL, '2026-10-24 17:00+09'),
    (1005, 1, 103, 'CANCEL',        NULL, NULL, '2026-10-24 17:30+09'),

    (1006, 1, 104, 'SUBMIT',        NULL, NULL, '2026-10-27 09:00+09'),
    (1006, 1, 105, 'FINAL_APPROVE', NULL, NULL, '2026-10-27 13:00+09'),

    (1007, 1, 106, 'SUBMIT',        NULL, NULL, '2026-10-26 10:00+09'),
    (1007, 1, 102, 'REJECT',        NULL, '모집 인원과 근무지를 공고에 적어 주세요.', '2026-10-26 15:00+09'),
    (1007, 2, 106, 'RESUBMIT',      NULL, NULL, '2026-10-29 09:00+09'),

    (1008, 1, 102, 'SUBMIT',        NULL, NULL, '2026-11-02 10:00+09');

-- 문서 ID 를 직접 넣었으므로 시퀀스를 최댓값 뒤로 맞춘다 (API 로 만드는 문서는 1009 부터)
SELECT setval('approval_document_doc_id_seq', (SELECT MAX(doc_id) FROM approval_document));
