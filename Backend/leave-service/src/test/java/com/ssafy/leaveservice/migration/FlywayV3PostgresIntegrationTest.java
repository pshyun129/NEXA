package com.ssafy.leaveservice.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FlywayV3PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private String schema;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareVersionTwoSchema() {
        // 매 테스트를 새 스키마에서 시작해 기존 DB와 다른 테스트 데이터를 보호한다.
        schema = "v3_test_" + UUID.randomUUID().toString().replace("-", "");
        flyway("2").migrate();
        String jdbcUrl = postgres.getJdbcUrl();
        String schemaJdbcUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                schemaJdbcUrl, postgres.getUsername(), postgres.getPassword()
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        insertExistingBusinessData();
    }

    @Test
    void migratesInOrderPreservesBusinessRowsAndChecksumsAndValidates() {
        // V1/V2 적용 결과와 실제 기존 업무 데이터를 보존 기준으로 기록한다.
        List<Integer> oldChecksums = Arrays.stream(flyway("2").info().applied())
                .filter(migration -> migration.getVersion() != null)
                .map(MigrationInfo::getChecksum)
                .toList();
        Map<String, List<Map<String, Object>>> before = snapshotBusinessRows();

        Flyway latest = flyway(null);
        latest.migrate();
        latest.validate();

        List<String> appliedVersions = Arrays.stream(latest.info().applied())
                .filter(migration -> migration.getVersion() != null)
                .map(migration -> migration.getVersion().getVersion())
                .toList();
        assertThat(appliedVersions).containsExactly("1", "2", "3");
        List<Integer> currentChecksums = Arrays.stream(latest.info().applied())
                .filter(migration -> migration.getVersion() != null)
                .limit(2)
                .map(MigrationInfo::getChecksum)
                .toList();
        assertThat(currentChecksums).isEqualTo(oldChecksums);
        assertThat(snapshotBusinessRows()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from leave_policy_group where active_policy_version_id is not null", Long.class
        )).isZero();
    }

    @Test
    void allowsNullAndSameGroupActivationAndSwitchingWithoutRewritingHistory() {
        flyway(null).migrate();
        Map<String, List<Map<String, Object>>> before = snapshotBusinessRows();

        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 12 where policy_id = 1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");
        assertThat(jdbcTemplate.queryForObject(
                "select active_policy_version_id from leave_policy_group where policy_id = 1", Long.class
        )).isEqualTo(11L);
        assertThat(snapshotBusinessRows()).isEqualTo(before);

        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = null where policy_id = 1");
        assertThat(jdbcTemplate.queryForObject(
                "select active_policy_version_id from leave_policy_group where policy_id = 1", Long.class
        )).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {21L, 99999L})
    void rejectsOtherGroupOrMissingActiveVersion(long versionId) {
        flyway(null).migrate();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update leave_policy_group set active_policy_version_id = ? where policy_id = 1", versionId
        )).isInstanceOfSatisfying(DataAccessException.class, exception -> {
            SQLException cause = (SQLException) exception.getMostSpecificCause();
            assertThat(cause.getSQLState()).isEqualTo("23503");
            assertThat(cause.getMessage()).contains("fk_policy_group_active_version");
        });
    }

    @Test
    void blocksDeletingActiveVersionOrMovingItToAnotherGroup() {
        flyway(null).migrate();
        // 사용 이력이 없는 버전을 선택해 활성 포인터 FK 자체가 삭제를 막는지 확인한다.
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 12 where policy_id = 1");

        assertThatThrownBy(() -> jdbcTemplate.update("delete from leave_policy_version where policy_version_id = 12"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_policy_group_active_version");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update leave_policy_version set policy_id = 2, version_no = 2 where policy_version_id = 12"
        )).isInstanceOf(DataAccessException.class).hasMessageContaining("fk_policy_group_active_version");
    }

    @Test
    void supportsGroupThenVersionThenPointerWithCircularForeignKeys() {
        flyway(null).migrate();

        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (3, '신규 그룹')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id, policy_id, version_no, base_type)
                values (31, 3, 1, 'HIRE_DATE')
                """);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 31 where policy_id = 3");

        assertThat(jdbcTemplate.queryForObject(
                "select active_policy_version_id from leave_policy_group where policy_id = 3", Long.class
        )).isEqualTo(31L);
    }

    @Test
    void allowsOptionalDatesAndRetainsExistingPeriodCheck() {
        flyway(null).migrate();

        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id, policy_id, version_no, base_type)
                values (13, 1, 3, 'HIRE_DATE')
                """);
        jdbcTemplate.update("""
                update leave_policy_version set effective_to = '2030-01-01T00:00:00Z'
                where policy_version_id = 13
                """);
        assertThat(jdbcTemplate.queryForObject(
                "select effective_from from leave_policy_version where policy_version_id = 13", Object.class
        )).isNull();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                update leave_policy_version
                set effective_from = '2031-01-01T00:00:00Z', effective_to = '2030-01-01T00:00:00Z'
                where policy_version_id = 13
                """)).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_policy_period");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MATCHED", "VERSION_ONLY", "NO_POLICY"})
    void permitsValidGrantReferences(String referenceKind) {
        flyway(null).migrate();
        Long policyVersionId = "NO_POLICY".equals(referenceKind) ? null : 11L;
        Long ruleId = "MATCHED".equals(referenceKind) ? 111L : null;

        Long grantId = insertGrant(policyVersionId, ruleId);

        assertThat(grantId).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISMATCH", "MISSING_VERSION", "UNKNOWN_VERSION", "UNKNOWN_RULE"})
    void rejectsInvalidGrantReferencesAfterMigration(String referenceKind) {
        flyway(null).migrate();
        Long policyVersionId = "MISMATCH".equals(referenceKind) ? 21L : 11L;
        Long ruleId = 111L;
        if ("MISSING_VERSION".equals(referenceKind)) {
            policyVersionId = null;
        } else if ("UNKNOWN_VERSION".equals(referenceKind)) {
            policyVersionId = 99999L;
            ruleId = null;
        } else if ("UNKNOWN_RULE".equals(referenceKind)) {
            ruleId = 99999L;
        }
        Long targetVersionId = policyVersionId;
        Long targetRuleId = ruleId;

        assertThatThrownBy(() -> insertGrant(targetVersionId, targetRuleId))
                .isInstanceOfSatisfying(DataAccessException.class, exception -> {
                    SQLException cause = (SQLException) exception.getMostSpecificCause();
                    String expectedState = "MISSING_VERSION".equals(referenceKind) ? "23514" : "23503";
                    assertThat(cause.getSQLState()).isEqualTo(expectedState);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_VERSION", "MISMATCH"})
    void failsOnExistingInconsistentGrantsWithoutChangingRowsOrSchema(String inconsistency) {
        Long policyVersionId = "MISSING_VERSION".equals(inconsistency) ? null : 21L;
        Long invalidGrantId = insertGrant(policyVersionId, 111L);
        Map<String, List<Map<String, Object>>> before = snapshotBusinessRows();

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("grant_id(최대 20개)")
                .hasStackTraceContaining(invalidGrantId.toString());

        assertThat(snapshotBusinessRows()).isEqualTo(before);
        assertVersionThreeWasNotApplied();
        flyway("2").validate();
    }

    @Test
    void rollsBackEarlierDdlWhenLastIndexCreationFails() {
        // 실제 V1/V2에는 없는 이름 충돌을 만들어 V3 후반 실패의 원자성을 검증한다.
        jdbcTemplate.execute("create index idx_leave_grant_rule on leave_grant (account_id)");
        Map<String, List<Map<String, Object>>> before = snapshotBusinessRows();

        assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(FlywayException.class);

        assertThat(snapshotBusinessRows()).isEqualTo(before);
        assertVersionThreeWasNotApplied();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_constraint
                where connamespace = current_schema()::regnamespace
                  and conname in ('uq_policy_version_group_pair', 'uq_accrual_rule_version_pair', 'fk_grant_rule_version')
                """, Long.class)).isZero();
    }

    @Test
    void usesCompositeUniqueIndexInsteadOfDuplicateAccrualVersionIndex() {
        flyway(null).migrate();

        assertThat(jdbcTemplate.queryForList("""
                select indexname from pg_indexes
                where schemaname = current_schema() and tablename = 'leave_accrual_rule'
                order by indexname
                """, String.class)).containsExactly("leave_accrual_rule_pkey", "uq_accrual_rule_version_pair");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes where schemaname = current_schema()
                  and indexname in ('idx_leave_request_policy_version', 'idx_leave_grant_policy_version', 'idx_leave_grant_rule')
                """, Long.class)).isEqualTo(3L);
    }

    @Test
    void blocksDeletingRuleReferencedByGrant() {
        flyway(null).migrate();

        assertThatThrownBy(() -> jdbcTemplate.update("delete from leave_accrual_rule where rule_id = 111"))
                .isInstanceOf(DataAccessException.class);
    }

    private Flyway flyway(String target) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema);
        // V3 고유 회귀는 후속 마이그레이션과 분리해 검증한다.
        configuration.target(target == null ? "3" : target);
        return configuration.load();
    }

    private Long insertGrant(Long policyVersionId, Long ruleId) {
        return jdbcTemplate.queryForObject("""
                insert into leave_grant (account_id, policy_version_id, rule_id, grant_kind, original_minutes, valid_from)
                values (1, ?, ?, 'MANUAL', 480, '2025-01-01T00:00:00Z') returning grant_id
                """, Long.class, policyVersionId, ruleId);
    }

    private Map<String, List<Map<String, Object>>> snapshotBusinessRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("leave_policy_group", jdbcTemplate.queryForList("select policy_id, name from leave_policy_group order by policy_id"));
        for (String table : List.of("leave_policy_version", "leave_accrual_rule", "leave_policy_type_rule",
                "employee_leave_policy_assignment", "leave_request", "leave_grant")) {
            rows.put(table, jdbcTemplate.queryForList("select * from " + table + " order by 1"));
        }
        return rows;
    }

    private void assertVersionThreeWasNotApplied() {
        assertThat(flyway("2").info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = current_schema() and table_name = 'leave_policy_group'
                  and column_name = 'active_policy_version_id'
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema = current_schema() and table_name = 'leave_policy_version' and column_name = 'effective_from'
                """, String.class)).isEqualTo("NO");
    }

    private void insertExistingBusinessData() {
        // 시각과 과거 참조를 가진 V2 데이터를 준비해 V3의 무보정 원칙을 검증한다.
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '기존 정책'), (2, '다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version
                    (policy_version_id, policy_id, version_no, effective_from, effective_to, base_type)
                values (11, 1, 1, '2025-01-01T00:00:00Z', '2026-01-01T00:00:00Z', 'HIRE_DATE'),
                       (12, 1, 2, '2026-01-01T00:00:00Z', null, 'HIRE_DATE'),
                       (21, 2, 1, '2025-01-01T00:00:00Z', null, 'HIRE_DATE')
                """);
        jdbcTemplate.update("""
                insert into leave_accrual_rule (rule_id, policy_version_id, frequency, grant_minutes)
                values (111, 11, 'YEARLY', 480), (211, 21, 'YEARLY', 480)
                """);
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id, name) values (1, '연차')");
        jdbcTemplate.update("insert into leave_type (leave_type_id, balance_type_id, name) values (1, 1, '연차')");
        jdbcTemplate.update("insert into leave_account (account_id, emp_id, balance_type_id) values (1, 42, 1)");
        jdbcTemplate.update("insert into leave_policy_type_rule (policy_version_id, leave_type_id) values (11, 1)");
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (emp_id, policy_id, effective_from)
                values (42, 1, '2025-01-01T00:00:00Z')
                """);
        jdbcTemplate.update("""
                insert into leave_request (policy_version_id, leave_type_id, emp_id, status)
                values (11, 1, 42, 'APPROVED')
                """);
        insertGrant(11L, 111L);
    }
}
