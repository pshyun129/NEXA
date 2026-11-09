package com.ssafy.leaveservice.migration;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.request.entity.*;
import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={
        "leave.test.database-owner=FlywayV4PostgresIntegrationTest",
        "spring.cloud.config.enabled=false","eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/","spring.jpa.open-in-view=false"})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class FlywayV4PostgresIntegrationTest {
    @Autowired
    private PostgreSQLContainer postgres;
    @Autowired EntityManagerFactory entityManagerFactory;
    private String schema;
    private JdbcTemplate jdbc;
    private List<Map<String,Object>> legacyRequests;
    private List<Map<String,Object>> legacyLedger;
    private static final String NEW_COLUMNS="'submission_key','submission_payload_hash','approval_creation_status','approval_request_id','approval_status_changed_at','approval_last_error','approval_attempt_count'";

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target(target).load();
    }

    @BeforeEach
    void setUp() {
        schema="v4_"+UUID.randomUUID().toString().replace("-","");
        flyway("3").migrate();
        String url=postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema;
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));
        jdbc.update("insert into leave_policy_group(policy_id,name) values(1,'정책')");
        jdbc.update("insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type) values(1,1,1,'HIRE_DATE')");
        jdbc.update("insert into leave_balance_type(balance_type_id,name) values(1,'연차')");
        jdbc.update("insert into leave_type(leave_type_id,balance_type_id,name) values(1,1,'연차')");
        jdbc.update("insert into leave_account(account_id,emp_id,balance_type_id) values(1,1,1),(2,2,1)");
        jdbc.update("""
                insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from)
                values(1,1,'MANUAL',480,'2020-01-01Z'),(2,1,'MANUAL',480,'2020-01-01Z'),(3,2,'MANUAL',480,'2020-01-01Z')
                """);
        jdbc.update("""
                insert into leave_request(leave_req_id,emp_id,policy_version_id,leave_type_id,status,reason)
                values(1,1,1,1,'DRAFT','보존'),(2,2,1,1,'SUBMITTED',null),(3,1,1,1,'REJECTED',null),
                      (4,1,1,1,'APPROVED',null),(5,1,1,1,'WITHDRAWN',null),(6,1,1,1,'CANCELLED',null)
                """);
        jdbc.update("""
                insert into leave_request_date(request_date_id,leave_req_id,leave_date,portion,requested_minutes,deduct_minutes)
                values(11,1,'2030-01-07T00:00:00+09:00','FULL',480,480),
                      (12,1,'2030-01-08T00:00:00+09:00','FULL',480,480),
                      (21,2,'2030-01-07T00:00:00+09:00','FULL',480,480)
                """);
        jdbc.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,leave_req_id,event_type,quantity_delta,idempotency_key)
                values(1,1,1,1,'GRANT',480,'legacy')
                """);
        legacyRequests=jdbc.queryForList("select to_jsonb(r)::text as row from leave_request r order by leave_req_id");
        legacyLedger=jdbc.queryForList("select to_jsonb(l)::text as row from leave_ledger l order by entry_id");
        flyway("4").migrate();
        jdbc.update("""
                insert into leave_request_allocation(allocation_id,leave_req_id,request_date_id,emp_id,account_id,grant_id,allocated_minutes)
                values(1,1,11,1,1,1,240)
                """);
        jdbc.update("""
                insert into leave_request(leave_req_id,emp_id,policy_version_id,leave_type_id,status,submission_key,
                    submission_payload_hash,approval_creation_status,approval_request_id,approval_status_changed_at,approval_attempt_count)
                values(10,1,1,1,'SUBMITTING','key',repeat('a',64),'UNKNOWN',
                    '00000000-0000-0000-0000-000000000010',now(),1)
                """);
    }

    @Test
    void migrationPreservesAllLegacyValuesAndChecksumsAndValidatesV4() {
        assertThat(jdbc.queryForList("select (to_jsonb(r)-ARRAY["+NEW_COLUMNS+"])::text as row from leave_request r where leave_req_id<10 order by leave_req_id"))
                .isEqualTo(legacyRequests);
        assertThat(jdbc.queryForList("select (to_jsonb(l)-'allocation_id')::text as row from leave_ledger l order by entry_id"))
                .isEqualTo(legacyLedger);
        assertThat(jdbc.queryForObject("select count(*) from leave_request where leave_req_id<10 and submission_key is null and approval_creation_status is null",Integer.class)).isEqualTo(6);
        assertThat(Arrays.stream(flyway("4").info().applied()).filter(info->info.getVersion()!=null)
                .map(info->info.getVersion().getVersion()).toList())
                .containsExactly("1","2","3","4");
        flyway("4").validate();
    }

    @Test
    void jpaMapsMetadataAllocationAndLedgerWithoutChangingResponseDtos() {
        // 현재 Entity의 V6 취소 추적 컬럼까지 적용한 후 V4 매핑을 검증한다.
        flyway("6").migrate();
        jdbc.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,leave_req_id,allocation_id,event_type,reserved_delta,idempotency_key)
                values(2,1,1,1,1,'RESERVE',240,'reserve')
                """);
        try(EntityManager em=entityManagerFactory.createEntityManager()) {
            em.getTransaction().begin();
            em.createNativeQuery("set local search_path to "+schema).executeUpdate();
            LeaveRequest request=em.find(LeaveRequest.class,10L);
            assertThat(request.getStatus()).isEqualTo(LeaveRequestStatus.SUBMITTING);
            assertThat(request.getApprovalCreationStatus()).isEqualTo(ApprovalCreationStatus.UNKNOWN);
            assertThat(request.getSubmissionKey()).isEqualTo("key");
            assertThat(request.getSubmissionPayloadHash()).isEqualTo("a".repeat(64));
            assertThat(request.getApprovalAttemptCount()).isEqualTo(1);
            assertThat(request.getApprovalRequestId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000010"));
            assertThat(em.find(LeaveRequestAllocation.class,1L).getAllocatedMinutes()).isEqualTo(240);
            assertThat(em.find(LeaveLedger.class,2L).getAllocationId()).isEqualTo(1L);
            em.getTransaction().rollback();
        }
    }

    @Test
    void splitDateAndSharedGrantAcrossDatesAreSupported() {
        jdbc.update("""
                insert into leave_request_allocation(allocation_id,leave_req_id,request_date_id,emp_id,account_id,grant_id,allocated_minutes)
                values(2,1,11,1,1,2,240),(3,1,12,1,1,1,240)
                """);
        assertThat(jdbc.queryForObject("select count(*) from leave_request_allocation",Integer.class)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings={
        "update leave_request_allocation set allocated_minutes=0",
        "update leave_request_allocation set allocated_minutes=-1",
        "update leave_request_allocation set request_date_id=999",
        "update leave_request_allocation set request_date_id=21",
        "update leave_request_allocation set grant_id=3",
        "update leave_request_allocation set account_id=2",
        "update leave_request_allocation set emp_id=2",
        "update leave_request_allocation set leave_req_id=2",
        "delete from leave_request_date where request_date_id=11",
        "delete from leave_grant where grant_id=1",
        "delete from leave_account where account_id=1",
        "insert into leave_request_allocation(allocation_id,leave_req_id,request_date_id,emp_id,account_id,grant_id,allocated_minutes) values(9,1,11,1,1,1,20)"
    })
    void allocationConstraintsRejectInvalidOwnershipQuantityDuplicatesAndDeletes(String sql) {
        assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={
        "update leave_ledger set allocation_id=999 where entry_id=1",
        "update leave_ledger set allocation_id=1,leave_req_id=null where entry_id=1",
        "update leave_ledger set allocation_id=1,leave_req_id=2 where entry_id=1",
        "update leave_ledger set allocation_id=1,grant_id=2 where entry_id=1",
        "update leave_ledger set allocation_id=1,account_id=2,grant_id=3 where entry_id=1"
    })
    void ledgerAllocationMembershipIsEnforced(String sql) {
        assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={
        "update leave_request set submission_payload_hash=null where leave_req_id=10",
        "update leave_request set submission_payload_hash=repeat('x',64) where leave_req_id=10",
        "update leave_request set submission_payload_hash=repeat('a',63) where leave_req_id=10",
        "update leave_request set submission_key=' ' where leave_req_id=10",
        "update leave_request set submission_key=null where leave_req_id=10",
        "update leave_request set approval_creation_status=null where leave_req_id=10",
        "update leave_request set approval_creation_status='BAD' where leave_req_id=10",
        "update leave_request set approval_creation_status='LINKED' where leave_req_id=10",
        "update leave_request set approval_creation_status='FAILED' where leave_req_id=10",
        "update leave_request set approval_request_id=null where leave_req_id=10",
        "update leave_request set approval_status_changed_at=null where leave_req_id=10",
        "update leave_request set approval_attempt_count=-1 where leave_req_id=10",
        "update leave_request set approval_attempt_count=null where leave_req_id=10",
        "update leave_request set status='CANCELLED' where leave_req_id=10",
        "update leave_request set status='DRAFT' where leave_req_id=10",
        "update leave_request set status='SUBMITTED' where leave_req_id=10",
        "update leave_request set approval_doc_id=123 where leave_req_id=10",
        "update leave_request set status='SUBMITTING' where leave_req_id=1",
        "update leave_request set submission_payload_hash=repeat('a',64) where leave_req_id=1"
    })
    void submissionChecksRejectIncompleteOrInconsistentNewMetadata(String sql) {
        assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"NOT_SENT","IN_PROGRESS","UNKNOWN"})
    void pendingStatesSurviveReload(String state) {
        jdbc.update("update leave_request set approval_creation_status=? where leave_req_id=10",state);
        assertThat(jdbc.queryForObject("select approval_creation_status from leave_request where leave_req_id=10",String.class)).isEqualTo(state);
    }

    @Test
    void linkedAndConfirmedFailureStatesAreSupportedAndDocumentUniqueIsPreserved() {
        jdbc.update("update leave_request set status='SUBMITTED',approval_creation_status='LINKED',approval_doc_id=123,submitted_at=now() where leave_req_id=10");
        jdbc.update("update leave_request set approval_doc_id=123 where leave_req_id=10");
        assertThatThrownBy(()->jdbc.update("update leave_request set approval_doc_id=123 where leave_req_id=2"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update leave_request set status='CANCELLED',approval_creation_status='FAILED',approval_doc_id=null,approval_last_error='미생성 확인' where leave_req_id=10");
        assertThat(jdbc.queryForObject("select approval_last_error from leave_request where leave_req_id=10",String.class)).isEqualTo("미생성 확인");
    }

    @Test
    void employeeScopedKeysAndGlobalExternalCorrelationAreUnique() {
        String copy="""
                insert into leave_request(leave_req_id,emp_id,policy_version_id,leave_type_id,status,submission_key,
                    submission_payload_hash,approval_creation_status,approval_request_id,approval_status_changed_at,approval_attempt_count)
                select 20,?,policy_version_id,leave_type_id,status,submission_key,submission_payload_hash,
                    approval_creation_status,?::uuid,approval_status_changed_at,approval_attempt_count
                from leave_request where leave_req_id=10
                """;
        assertThatThrownBy(()->jdbc.update(copy,1,UUID.randomUUID().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update(copy,2,"00000000-0000-0000-0000-000000000010")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(copy,2,UUID.randomUUID().toString());
        assertThat(jdbc.queryForObject("select count(*) from leave_request where submission_key='key'",Integer.class)).isEqualTo(2);
    }

    @Test
    void allocationHistoryCannotBeDeletedWhileLedgerReferencesIt() {
        jdbc.update("update leave_ledger set allocation_id=1 where entry_id=1");
        assertThatThrownBy(()->jdbc.update("delete from leave_request_allocation where allocation_id=1")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
