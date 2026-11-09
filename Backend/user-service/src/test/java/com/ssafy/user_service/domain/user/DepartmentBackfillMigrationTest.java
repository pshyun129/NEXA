package com.ssafy.user_service.domain.user;

import com.ssafy.user_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4 보정 마이그레이션: 값이 비어 있는 부서의 유형과 부서장을 채운다.
 * 이관 등으로 들어온 부서(유형·부서장 없음)를 흉내 내 넣고 스크립트를 다시 실행한다. 테스트가 끝나면 롤백.
 * 부서 9xx: 회사(900) > 본부(910) > 팀(920, 921, 922) > 파트(930)
 */
@Transactional
class DepartmentBackfillMigrationTest extends AbstractIntegrationTest {

    private static final String SCRIPT = "db/migration/V4__backfill_department_type_and_head.sql";
    private static final long TEAM_LEADER = 2L, DIVISION_HEAD = 3L, CEO = 4L, MEMBER = 1L;   // dev seed 직책 ID

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void legacyOrganization() {
        department(900, null);
        department(910, 900L);
        department(920, 910L);
        department(921, 910L);
        department(922, 910L);
        department(930, 920L);

        employee(9001, 900, CEO, "EMPLOYED");
        employee(9002, 910, DIVISION_HEAD, "EMPLOYED");
        employee(9003, 920, TEAM_LEADER, "EMPLOYED");
        employee(9004, 920, MEMBER, "EMPLOYED");
        employee(9005, 921, TEAM_LEADER, "TERMINATED");     // 퇴직한 팀장 → 공석
        employee(9006, 922, TEAM_LEADER, "EMPLOYED");       // 팀장이 두 명 → 판단하지 않음
        employee(9007, 922, TEAM_LEADER, "EMPLOYED");
    }

    private void runBackfill() {
        ScriptUtils.executeSqlScript(DataSourceUtils.getConnection(dataSource), new ClassPathResource(SCRIPT));
    }

    @Test
    void 트리_깊이로_유형을_정하고_직책으로_부서장을_채운다() {
        runBackfill();

        assertThat(row(900)).isEqualTo("COMPANY/9001");
        assertThat(row(910)).isEqualTo("DIVISION/9002");
        assertThat(row(920)).isEqualTo("TEAM/9003");
        assertThat(row(930)).isEqualTo("TEAM/null");   // 팀 아래 단계도 팀, 팀장 없음
    }

    @Test
    void 재직_중인_후보가_정확히_한_명일_때만_부서장으로_정한다() {
        runBackfill();

        assertThat(row(921)).isEqualTo("TEAM/null");   // 퇴직
        assertThat(row(922)).isEqualTo("TEAM/null");   // 두 명
    }

    @Test
    void 이미_있는_값은_덮어쓰지_않는다() {
        jdbc.update("UPDATE department SET dept_type = 'DIVISION', head_emp_id = 9004 WHERE dept_id = 920");

        runBackfill();

        assertThat(row(920)).isEqualTo("DIVISION/9004");
        // dev seed 조직도 그대로 (개발팀장 105, 영업본부 공석)
        assertThat(row(2)).isEqualTo("TEAM/105");
        assertThat(row(12)).isEqualTo("DIVISION/null");
    }

    private void department(long id, Long parent) {
        jdbc.update("INSERT INTO department (dept_id, name, sort_order, company_id, parent_dept_id) VALUES (?, ?, 1, 1, ?)",
                id, "이관부서" + id, parent);
    }

    private void employee(long id, long dept, long duty, String status) {
        jdbc.update("""
                INSERT INTO employee (emp_id, emp_no, login_id, name, dept_id, position_id, grade_id, duty_id,
                                      job_family_id, job_kind_id, job_role_id, status, role)
                VALUES (?, ?, ?, ?, ?, 1, 1, ?, 1, 2, 3, ?, 'USER')""",
                id, "MIG" + id, "mig" + id + "@local.dev", "이관" + id, dept, duty, status);
    }

    private String row(long deptId) {
        return jdbc.queryForObject("SELECT dept_type || '/' || COALESCE(head_emp_id::text, 'null') FROM department WHERE dept_id = ?",
                String.class, deptId);
    }
}
