package com.ssafy.leaveservice.client.approval;

import com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResult;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public final class ApprovalHttpClient implements ApprovalClient {
    private static final String DOCUMENTS = "/api/v1/documents";
    private static final ParameterizedTypeReference<Envelope<CreatedDocument>> CREATED = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Envelope<DocumentInfo>> FOUND = new ParameterizedTypeReference<>() {};

    private final RestClient client;

    public ApprovalHttpClient(RestClient client) {
        this.client = client;
    }

    @Override
    public boolean isAvailable() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && token.isAuthenticated();
    }

    @Override
    public boolean supportsRecovery() {
        return true;
    }

    @Override
    public ApprovalCreationResult create(ApprovalCreateCommand command) {
        requireEmployee(command.employeeId());
        UUID key = command.approvalRequestId();
        CreateDocument body = new CreateDocument(
                "휴가 신청", "LEAVE", "LEAVE", command.requestId(), key, new Attributes(calculateDays(command))
        );
        try {
            var response = client.post().uri(DOCUMENTS).body(body).retrieve().toEntity(CREATED);
            Envelope<CreatedDocument> envelope = response.getBody();
            int status = response.getStatusCode().value();
            if ((status == 200 || status == 201) && successful(envelope) && validId(envelope.data().documentId())) {
                return new ApprovalCreationResult(key, ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,
                        envelope.data().documentId());
            }
            return unknown(key, "APPROVAL_CREATE_INVALID_RESPONSE");
        } catch (RestClientResponseException exception) {
            return fromHttpFailure(key, exception);
        } catch (RestClientException exception) {
            return unknown(key, "APPROVAL_CREATE_TRANSPORT_OR_RESPONSE_ERROR");
        }
    }

    @Override
    public ApprovalCreationResult findByRequestId(UUID requestKey) {
        // 조회 결과를 연결하려면 로컬 신청과 기안자 정보가 필요하다.
        throw new BusinessException(ErrorCode.INVALID_INPUT);
    }

    @Override
    public ApprovalCreationResult recover(LeaveSubmissionResult request) {
        requireEmployee(request.employeeId());
        UUID key = request.approvalRequestId();
        try {
            var response = client.get().uri(DOCUMENTS + "/by-request/{requestKey}", key).retrieve().toEntity(FOUND);
            Envelope<DocumentInfo> envelope = response.getBody();
            if (response.getStatusCode().value() != 200 || !successful(envelope)) {
                return unknown(key, "APPROVAL_LOOKUP_INVALID_RESPONSE");
            }
            DocumentInfo document = envelope.data();
            if (!validId(document.documentId()) || !key.equals(document.requestKey())
                    || !request.requestId().equals(document.sourceId()) || !request.employeeId().equals(document.drafterId())
                    || !"LEAVE".equals(document.sourceType()) || !"LEAVE".equals(document.formType())
                    || (request.approvalDocId() != null && !request.approvalDocId().equals(document.documentId()))) {
                return conflict(key);
            }
            if (!"INPROGRESS".equals(document.status()) && !"APPROVED".equals(document.status())
                    && !"REJECTED".equals(document.status()) && !"CANCEL".equals(document.status())) {
                return unknown(key, "APPROVAL_LOOKUP_UNKNOWN_STATUS");
            }
            return new ApprovalCreationResult(key, ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED, document.documentId());
        } catch (RestClientResponseException exception) {
            return fromHttpFailure(key, exception);
        } catch (RestClientException exception) {
            return unknown(key, "APPROVAL_LOOKUP_TRANSPORT_OR_RESPONSE_ERROR");
        }
    }

    private void requireEmployee(Long employeeId) {
        if (!isAvailable()
                || !(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)
                || !Objects.equals(String.valueOf(employeeId), token.getToken().getSubject())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private BigDecimal calculateDays(ApprovalCreateCommand command) {
        if (command.dates().isEmpty()) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        BigDecimal days = BigDecimal.ZERO;
        for (ApprovalCreateCommand.LeaveDate date : command.dates()) {
            BigDecimal portion = switch (date.portion()) {
                case FULL -> BigDecimal.ONE;
                case AM_HALF, PM_HALF -> new BigDecimal("0.5");
                case HOURLY -> throw new BusinessException(ErrorCode.INVALID_INPUT);
            };
            days = days.add(portion);
        }
        return days;
    }

    private ApprovalCreationResult fromHttpFailure(UUID key, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 409) {
            return conflict(key);
        }
        return unknown(key, "APPROVAL_HTTP_" + status);
    }

    private ApprovalCreationResult conflict(UUID key) {
        return new ApprovalCreationResult(key, ApprovalCreationResult.Outcome.CONFLICT, null, "APPROVAL_DOCUMENT_CONFLICT");
    }

    private ApprovalCreationResult unknown(UUID key, String reason) {
        return new ApprovalCreationResult(key, ApprovalCreationResult.Outcome.UNKNOWN, null, reason);
    }

    private boolean successful(Envelope<?> envelope) {
        return envelope != null && envelope.success() && envelope.data() != null;
    }

    private boolean validId(Long id) {
        return id != null && id > 0;
    }

    private record CreateDocument(String title, String formType, String sourceType, Long sourceId,
                                  UUID requestKey, Attributes attributes) {}
    private record Attributes(BigDecimal days) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope<T>(boolean success, T data) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record CreatedDocument(Long documentId) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record DocumentInfo(Long documentId, UUID requestKey, Long sourceId, String sourceType,
                                String formType, Long drafterId, String status) {}
}
