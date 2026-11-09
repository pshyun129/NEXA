package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.request.dto.LeaveApprovalDecision;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.service.LeaveApprovalSettlementService;
import com.ssafy.leaveservice.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LeaveApprovalSettlementServiceTest {
    private final LeaveRequestRepository requests = mock(LeaveRequestRepository.class);
    private final LeaveApprovalSettlementService service =
            new LeaveApprovalSettlementService(requests, null, null, null, null, null, null);

    @ParameterizedTest
    @ValueSource(strings = {"INPROGRESS", "UNKNOWN", "WITHDRAWN"})
    void rejectsNonFinalApprovalStatusesBeforeLookingUpRequest(String status) {
        assertThatThrownBy(() -> service.settle(decision(status))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(requests);
    }

    @Test
    void reportsMissingRequestSeparately() {
        when(requests.findEmployeeIdByRequestId(1L)).thenReturn(Optional.empty());

        assertThat(service.settle(decision("APPROVED")))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.REQUEST_NOT_FOUND);
    }

    private LeaveApprovalDecision decision(String status) {
        return new LeaveApprovalDecision(
                1, "DOCUMENT_COMPLETED", "LEAVE", "LEAVE", 1L, 2L, UUID.randomUUID(), status
        );
    }
}
