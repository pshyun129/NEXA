package com.ssafy.leaveservice.client.approval;

import com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResult;
import com.ssafy.leaveservice.domain.request.entity.ApprovalCreationStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ApprovalHttpClientTest {
    private static final UUID KEY = UUID.fromString("36296109-99df-422f-ae0b-11febfcd5ced");
    private static final String DOCUMENTS = "http://approval.test/api/v1/documents";
    private MockRestServiceServer server;
    private ApprovalHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://approval.test")
                .requestInterceptor(new BearerTokenRelayInterceptor());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ApprovalHttpClient(builder.build());
        Jwt token = Jwt.withTokenValue("access-token").header("alg", "RS256").subject("7").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201})
    void createsDocumentWithStoredIdentityAndPortionsEvenWithoutDeduction(int status) {
        server.expect(requestTo(DOCUMENTS)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andExpect(content().json("""
                        {"title":"휴가 신청","formType":"LEAVE","sourceType":"LEAVE","sourceId":42,
                         "requestKey":"%s","attributes":{"days":1.5}}
                        """.formatted(KEY), org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatusCode.valueOf(status))
                        .body("{\"success\":true,\"error\":null,\"data\":{\"documentId\":123,\"docNo\":\"LEAVE-1\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(client.create(command()).documentId()).isEqualTo(123L);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 503})
    void distinguishesConflictAndNeverConfirmsMissingDocumentFromHttpStatus(int status) {
        server.expect(requestTo(DOCUMENTS + "/by-request/" + KEY)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)));

        ApprovalCreationResult result = client.recover(pending());
        ApprovalCreationResult.Outcome expected = status == 409 ? ApprovalCreationResult.Outcome.CONFLICT
                : status == 404 ? ApprovalCreationResult.Outcome.LOOKUP_NOT_FOUND : ApprovalCreationResult.Outcome.UNKNOWN;
        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.documentId()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(longs = {7, 8})
    void checksRemoteDrafterBeforeLinkingAnAlreadyApprovedDocument(long drafter) {
        server.expect(requestTo(DOCUMENTS + "/by-request/" + KEY))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"documentId":123,"requestKey":"%s","sourceId":42,
                        "sourceType":"LEAVE","formType":"LEAVE","drafterId":%d,"status":"APPROVED"}}
                        """.formatted(KEY, drafter), MediaType.APPLICATION_JSON));

        assertThat(client.recover(pending()).outcome()).isEqualTo(drafter == 7
                ? ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED : ApprovalCreationResult.Outcome.CONFLICT);
        server.verify();
    }

    @Test
    void rejectsMissingAuthenticationBeforeCallingApproval() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> client.create(command())).isInstanceOf(BusinessException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(longs = {7, 8})
    void resumeUsesSameUuidAndVerifiesIdentityAfterIdempotentPost(long drafter) {
        server.expect(requestTo(DOCUMENTS)).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"sourceId":42,"requestKey":"%s","attributes":{"days":1.5}}
                        """.formatted(KEY)))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"documentId\":123}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DOCUMENTS + "/by-request/" + KEY)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"documentId":123,"requestKey":"%s","sourceId":42,
                         "sourceType":"LEAVE","formType":"LEAVE","drafterId":%d,"status":"INPROGRESS"}}
                        """.formatted(KEY, drafter), MediaType.APPLICATION_JSON));

        assertThat(client.resume(command()).outcome()).isEqualTo(drafter == 7
                ? ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED : ApprovalCreationResult.Outcome.CONFLICT);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 409, 503})
    void failedResumeNeverTreatsHttpStatusAsConfirmedAbsence(int status) {
        server.expect(requestTo(DOCUMENTS)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)));
        assertThat(client.resume(command()).outcome()).isEqualTo(status == 409
                ? ApprovalCreationResult.Outcome.CONFLICT : ApprovalCreationResult.Outcome.UNKNOWN);
        server.verify();
    }

    private LeaveSubmissionResult pending() {
        return new LeaveSubmissionResult(42L, 7L, KEY, LeaveRequestStatus.SUBMITTING, ApprovalCreationStatus.UNKNOWN, null);
    }

    private ApprovalCreateCommand command() {
        OffsetDateTime firstDay = OffsetDateTime.parse("2026-11-03T00:00:00Z");
        return new ApprovalCreateCommand(42L, 7L, KEY, 1L, 2L, null, 720, 0, List.of(
                new ApprovalCreateCommand.LeaveDate(1L, firstDay, LeaveRequestPortion.FULL, 480, 0),
                new ApprovalCreateCommand.LeaveDate(2L, firstDay.plusDays(1), LeaveRequestPortion.AM_HALF, 240, 0)
        ));
    }
}
