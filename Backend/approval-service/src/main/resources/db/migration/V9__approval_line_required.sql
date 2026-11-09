-- 위임전결 규정으로 정해진 결재자 표시. 이 결재자는 기안자가 빼거나 순서를 바꾸거나 전결로 건너뛸 수 없다.
-- 기안자가 나중에 추가한 결재자와 이 기능 전에 만든 결재선은 false (지금처럼 변경 가능)
ALTER TABLE approval_line
    ADD COLUMN required BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN approval_line.required IS '위임전결 규정상 결재자 여부. true 면 제외·순서 변경·전결 건너뛰기 불가';
