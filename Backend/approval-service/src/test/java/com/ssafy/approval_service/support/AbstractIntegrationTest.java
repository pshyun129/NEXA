package com.ssafy.approval_service.support;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.domain.approval.model.entity.ApprovalLine;
import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;
import com.ssafy.approval_service.domain.approval.repository.ApprovalLineRepository;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import com.ssafy.approval_service.domain.document.repository.DocumentRepository;
import com.ssafy.approval_service.global.client.UserServiceClient.OrgUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.BASE;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Postgres·Kafka 컨테이너를 띄우는 통합 테스트 베이스. Docker 가 필요하다.
 * 컨테이너는 JVM 당 한 번만 띄우고 모든 테스트 클래스가 공유한다(싱글턴, user-service 와 같은 방식).
 * Eureka 등록은 끈다. 인증은 각 테스트에서 jwt() 로 흉내 내므로 JWKS 서버도 필요 없다.
 * user-service 는 FakeUserServiceClient 로 대신한다 (지정하지 않은 사원은 모두 재직 중).
 * config-server 는 쓰지 않는다 (Kafka 주소는 @ServiceConnection 이 넣어 준다). 아웃박스 스케줄러는 끈다. 발행은 테스트가 OutboxPublisher 를 직접 호출해 확인한다.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.config.enabled=false",
        "app.outbox.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(AbstractIntegrationTest.FakeUserServiceConfig.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    /** compose 와 같은 공식 이미지·버전 (KRaft 단일 노드) */
    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected FakeUserServiceClient fakeUserService;

    @Autowired
    private ApprovalLineRepository approvalLineRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 조직도: 기안자는 팀 하나에 소속되고 그 팀장은 head (팀장 전결 문서면 결재선 = [head]) */
    protected void assignTeamHead(long drafter, long head) {
        fakeUserService.setOrgChain(drafter, new OrgUnit(drafter, "테스트팀 " + drafter, "TEAM", head));
    }

    /** 일반 기안 생성 + 상신 요청. 결재선은 서버가 규정과 조직도로 만든다 (기안자 조직도는 미리 지정) */
    protected ResultActions submit(long drafter) throws Exception {
        return mvc.perform(post(BASE).with(user(drafter)).contentType(MediaType.APPLICATION_JSON).content("""
                {"title":"테스트 문서 %d","formType":"GENERAL"}""".formatted(drafter)));
    }

    /**
     * 결재 흐름 테스트용 문서. 규정대로 상신한 뒤 결재선을 지정한 결재선으로 바꿔 둔다.
     * 바꾼 결재선은 required=false (기안자가 추가한 결재자와 같음) 라서 규정 강제와 무관하게 흐름만 확인할 수 있다.
     * 규정으로 만든 결재선 자체는 ApprovalLinePolicyIntegrationTest 에서 확인한다.
     */
    protected long createDocument(long drafter, String... lines) throws Exception {
        long firstEmpId = ((Number) JsonPath.read(lines[0], "$.empId")).longValue();
        assignTeamHead(drafter, firstEmpId);
        MvcResult created = submit(drafter).andExpect(status().isCreated()).andReturn();
        long documentId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.data.documentId")).longValue();

        transactionTemplate.executeWithoutResult(tx -> {
            Document document = documentRepository.getReferenceById(documentId);
            approvalLineRepository.deleteByDocumentDocId(documentId);
            approvalLineRepository.saveAll(Arrays.stream(lines)
                    .map(line -> {
                        Number step = JsonPath.read(line, "$.step");
                        return ApprovalLine.builder()
                                .document(document)
                                .empId(((Number) JsonPath.read(line, "$.empId")).longValue())
                                .step(step == null ? null : step.shortValue())
                                .role(LineRole.valueOf(JsonPath.read(line, "$.role")))
                                .build();
                    })
                    .toList());
        });
        return documentId;
    }

    @TestConfiguration
    static class FakeUserServiceConfig {

        @Bean
        @Primary
        FakeUserServiceClient fakeUserServiceClient() {
            return new FakeUserServiceClient();
        }
    }
}
