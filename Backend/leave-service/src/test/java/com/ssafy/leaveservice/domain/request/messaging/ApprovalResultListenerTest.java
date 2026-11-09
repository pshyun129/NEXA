package com.ssafy.leaveservice.domain.request.messaging;

import com.ssafy.leaveservice.domain.request.service.LeaveApprovalSettlementService;
import com.ssafy.leaveservice.domain.request.service.LeaveApprovalSettlementService.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalResultListenerTest {
    private final LeaveApprovalSettlementService settlement = mock(LeaveApprovalSettlementService.class);
    private final ApprovalResultListener listener = new ApprovalResultListener(JsonMapper.builder().build(), settlement);

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED", "CANCEL"})
    void passesSupportedStatusAndIdentityToSettlement(String status) {
        when(settlement.settle(any())).thenReturn(Outcome.APPLIED);

        listener.receive(payload(status));

        verify(settlement).settle(argThat(decision ->
                decision.status().equals(status) && decision.sourceId() == 41L && decision.documentId() == 42L));
    }

    @Test
    void skipsOtherDomainsBeforeParsingTheirPayload() {
        listener.receive("""
                {"sourceType":"ATTENDANCE","eventId":"future-format"}
                """);
        verifyNoInteractions(settlement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "{\"sourceType\":\"LEAVE\"}"})
    void rejectsMalformedPayload(String payload) {
        assertThatThrownBy(() -> listener.receive(payload)).isInstanceOf(ApprovalResultListener.InvalidResult.class);
        verifyNoInteractions(settlement);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"AWAITING_DOCUMENT_LINK", "REQUEST_NOT_FOUND"})
    void propagatesRetryableOutcome(Outcome outcome) {
        when(settlement.settle(any())).thenReturn(outcome);

        assertThatThrownBy(() -> listener.receive(payload("CANCEL"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propagatesConflictForDeadLetterRecovery() {
        when(settlement.settle(any())).thenReturn(Outcome.CONFLICT);

        assertThatThrownBy(() -> listener.receive(payload("APPROVED")))
                .isInstanceOf(ApprovalResultListener.InvalidResult.class);
    }

    @Test
    void returnsNormallyForAnAlreadyCommittedOutcome() {
        when(settlement.settle(any())).thenReturn(Outcome.ALREADY_APPLIED);

        listener.receive(payload("REJECTED"));

        verify(settlement).settle(any());
    }

    static String payload(String status) {
        return """
                {"eventId":"e3526838-5714-4f8b-9310-b0f398559825","eventVersion":1,"eventType":"DOCUMENT_COMPLETED",
                 "occurredAt":"2026-11-03T01:02:03Z","documentId":42,"docNo":"LEAVE-42","formType":"LEAVE",
                 "sourceType":"LEAVE","sourceId":41,"requestKey":"36296109-99df-422f-ae0b-11febfcd5ced",
                 "status":"%s","futureField":true}
                """.formatted(status);
    }
}
