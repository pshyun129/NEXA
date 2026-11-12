package com.ssafy.approval_service.domain.document.service;

import com.ssafy.approval_service.domain.approval.model.entity.ApprovalHistory;
import com.ssafy.approval_service.domain.approval.model.entity.ApprovalLine;
import com.ssafy.approval_service.domain.approval.model.entity.enums.HistoryAction;
import com.ssafy.approval_service.domain.approval.model.entity.enums.LineAction;
import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;
import com.ssafy.approval_service.domain.approval.repository.ApprovalHistoryRepository;
import com.ssafy.approval_service.domain.approval.repository.ApprovalLineRepository;
import com.ssafy.approval_service.domain.approval.policy.ApprovalAttributes;
import com.ssafy.approval_service.domain.approval.policy.ApprovalLevel;
import com.ssafy.approval_service.domain.approval.policy.ApprovalLineGenerator;
import com.ssafy.approval_service.domain.approval.policy.ApprovalPolicy;
import com.ssafy.approval_service.domain.approval.policy.GeneratedApprovalLine;
import com.ssafy.approval_service.domain.approval.service.DocumentCompleter;
import com.ssafy.approval_service.domain.document.model.dto.ApprovalLinePreviewResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentBox;
import com.ssafy.approval_service.domain.document.model.dto.DocumentCountResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentCreateRequest;
import com.ssafy.approval_service.domain.document.model.dto.DocumentCreateResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentInfoResponse;
import com.ssafy.approval_service.domain.document.model.dto.DocumentListResponse;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import com.ssafy.approval_service.domain.document.model.entity.enums.DocumentStatus;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import com.ssafy.approval_service.domain.document.model.entity.enums.SourceType;
import com.ssafy.approval_service.domain.document.repository.DocumentRepository;
import com.ssafy.approval_service.global.exception.BusinessException;
import com.ssafy.approval_service.global.exception.ErrorCode;
import com.ssafy.nexa.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentServiceImplV1 implements DocumentService {

    private static final int FIRST_ROUND = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    // 참조함: 참조·열람 줄 (처리 상태 무관. 이 줄은 결재 처리 대상이 아니라 늘 PENDING 이다)
    private static final Set<LineRole> REFERENCE_ROLES = EnumSet.of(LineRole.REFERENCE, LineRole.VIEWER);
    private static final Set<LineAction> ANY_ACTION = EnumSet.allOf(LineAction.class);
    // 처리함: 결재·합의 줄 중 처리가 끝난 줄. 전결로 생략된 줄도 포함한다 (내 결재 단계를 지나간 문서)
    private static final Set<LineRole> APPROVER_ROLES = EnumSet.of(LineRole.APPROVER, LineRole.AGREEMENT);
    private static final Set<LineAction> DONE_ACTIONS = EnumSet.of(LineAction.AGREE, LineAction.DISAGREE, LineAction.SKIPPED);

    private final DocumentRepository documentRepository;
    private final ApprovalLineRepository approvalLineRepository;
    private final ApprovalHistoryRepository approvalHistoryRepository;
    private final DocumentNumberGenerator documentNumberGenerator;
    private final RequestKeyLock requestKeyLock;
    private final ApprovalPolicy approvalPolicy;
    private final ApprovalLineGenerator approvalLineGenerator;
    private final DocumentCompleter documentCompleter;

    @Override
    public DocumentListResponse findByStatusAndType(String approvalStatus, String formType, String box,
                                                    Integer page, Integer size, AuthUser requester) {
        ListFilter filter = listFilter(approvalStatus, formType, box, requester);
        Pageable pageable = pageRequest(page, size);

        List<Document> documents = find(filter, pageable);
        // 페이지가 아니면 받은 목록이 전체라 건수 쿼리를 다시 하지 않는다
        long totalCount = pageable.isPaged() ? count(filter) : documents.size();
        return DocumentListResponse.of(documents, linesByDocId(documents), filter.employeeId(), totalCount, pageable);
    }

    @Override
    public DocumentCountResponse countByStatusAndType(String approvalStatus, String formType, String box,
                                                      AuthUser requester) {
        return new DocumentCountResponse(count(listFilter(approvalStatus, formType, box, requester)));
    }

    /** 목록 조건. box 가 없으면 요청자가 기안했거나 결재선에 있는 문서 전체 (관리자도 같음) */
    private record ListFilter(DocumentStatus status, FormType type, DocumentBox box, Long employeeId) {
    }

    private ListFilter listFilter(String approvalStatus, String formType, String box, AuthUser requester) {
        return new ListFilter(
                parseEnum(DocumentStatus.class, approvalStatus, "approvalStatus"),
                parseEnum(FormType.class, formType, "formType"),
                parseEnum(DocumentBox.class, box, "box"),
                requester.employeeId());
    }

    /** 문서함별 목록. 정렬은 쿼리마다 고정 (DocumentBox 주석 참고) */
    private List<Document> find(ListFilter f, Pageable pageable) {
        if (f.box() == null) {
            return documentRepository.findInvolvedByStatusAndFormType(
                    f.status(), f.type(), null, f.employeeId(), pageable);
        }
        return switch (f.box()) {
            case DRAFTED -> documentRepository.findInvolvedByStatusAndFormType(
                    f.status(), f.type(), f.employeeId(), f.employeeId(), pageable);
            case PENDING -> documentRepository.findPendingFor(f.status(), f.type(), f.employeeId(), pageable);
            case REFERENCE -> documentRepository.findByMyLine(
                    f.status(), f.type(), f.employeeId(), REFERENCE_ROLES, ANY_ACTION, pageable);
            case COMPLETED -> documentRepository.findByMyLineOrderByActedAt(
                    f.status(), f.type(), f.employeeId(), APPROVER_ROLES, DONE_ACTIONS, pageable);
        };
    }

    /** find 와 같은 조건의 건수 */
    private long count(ListFilter f) {
        if (f.box() == null) {
            return documentRepository.countInvolvedByStatusAndFormType(
                    f.status(), f.type(), null, f.employeeId());
        }
        return switch (f.box()) {
            case DRAFTED -> documentRepository.countInvolvedByStatusAndFormType(
                    f.status(), f.type(), f.employeeId(), f.employeeId());
            case PENDING -> documentRepository.countPendingFor(f.status(), f.type(), f.employeeId());
            case REFERENCE -> documentRepository.countByMyLine(
                    f.status(), f.type(), f.employeeId(), REFERENCE_ROLES, ANY_ACTION);
            case COMPLETED -> documentRepository.countByMyLine(
                    f.status(), f.type(), f.employeeId(), APPROVER_ROLES, DONE_ACTIONS);
        };
    }

    /**
     * page·size 가 둘 다 없으면 전체(기존 동작 유지). 하나라도 있으면 페이지로 나눈다.
     * page 는 0부터(기본 0), size 는 1~MAX_PAGE_SIZE(기본 DEFAULT_PAGE_SIZE)
     */
    private static Pageable pageRequest(Integer page, Integer size) {
        if (page == null && size == null) {
            return Pageable.unpaged();
        }
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_PAGE_SIZE : size;
        if (p < 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page 는 0 이상이어야 합니다: " + p);
        }
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "size 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다: " + s);
        }
        return PageRequest.of(p, s);
    }

    /** 목록 문서들의 결재선을 IN 조회 한 번으로 가져와 문서별로 묶는다 (문서마다 조회하지 않도록) */
    private Map<Long, List<ApprovalLine>> linesByDocId(List<Document> documents) {
        if (documents.isEmpty()) {
            return Map.of();
        }
        List<Long> docIds = documents.stream().map(Document::getDocId).toList();
        return approvalLineRepository.findByDocumentDocIdIn(docIds).stream()
                .collect(Collectors.groupingBy(line -> line.getDocument().getDocId()));
    }

    /*
     * 문서 생성 + 상신: 출처·요청 키 검증 → 전결 단계 결정 → 조직도로 결재선 생성
     * → 문서번호 채번 → 문서·결재선·SUBMIT 이력 저장 (자가결재면 즉시 승인)
     */
    @Override
    @Transactional
    public DocumentCreateResult create(DocumentCreateRequest request, Long drafterId) {
        // 출처 검증: 원본 신청 ID·요청 키 유무, 양식과 출처의 조합
        validateSource(request.formType(), request.sourceType(), request.sourceId(), request.requestKey());
        if (request.sourceType() == SourceType.DIRECT && request.sourceLabel() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "직접 기안 문서는 sourceLabel을 보낼 수 없습니다.");
        }

        // 같은 요청 키로 이미 만든 문서가 있으면 새로 만들지 않고 돌려준다 (재시도 대비)
        if (request.requestKey() != null) {
            requestKeyLock.lock(request.requestKey());
            Optional<Document> existing = documentRepository.findByRequestKey(request.requestKey());
            if (existing.isPresent()) {
                return new DocumentCreateResult(toCreateResponse(existing.get(), request, drafterId), false);
            }
        }

        // 위임전결 규정상 전결 단계와 조직도로 결재선을 만든다
        ApprovalLevel level = approvalPolicy.requiredLevel(request.formType(), request.attributes());
        GeneratedApprovalLine generated = approvalLineGenerator.generate(drafterId, level);

        // 문서 생성
        Document draft = Document.builder()
                .formType(request.formType())
                .title(request.title())
                .drafterId(drafterId)
                .sourceType(request.sourceType())
                .sourceId(request.sourceId())
                .sourceLabel(request.sourceLabel())
                .requestKey(request.requestKey())
                .approvalLevel(level)
                .attributes(request.attributes())
                .build();
        // 양식·연도별 일련번호를 받아 문서번호를 채운 뒤 저장한다
        draft.assignDocNo(documentNumberGenerator.next(draft.getFormType(), draft.numberingYear()));
        Document document = documentRepository.save(draft);

        // 결재선 우선 저장 (규정상 결재자로 표시)
        approvalLineRepository.saveAll(generated.toEntities(document));

        // 결재 이력 저장
        recordFirstRoundHistory(document, drafterId, HistoryAction.SUBMIT);

        // 자가결재(대표이사 기안): 결재자가 없으므로 상신과 함께 최종 승인. 출처가 있으면 결과 이벤트도 남긴다
        if (generated.selfApproval()) {
            documentCompleter.approve(document);
            recordFirstRoundHistory(document, drafterId, HistoryAction.FINAL_APPROVE);
        }

        return new DocumentCreateResult(new DocumentCreateResponse(document.getDocId(), document.getDocNo()), true);
    }

    private void recordFirstRoundHistory(Document document, Long actorId, HistoryAction action) {
        approvalHistoryRepository.save(ApprovalHistory.builder()
                .document(document)
                .round(FIRST_ROUND)
                .actorId(actorId)
                .action(action)
                .build());
    }

    /** 기본 결재선 미리보기. 요청자를 기안자로 보고 위임전결 규정과 조직도로 만든다 (저장하지 않음) */
    @Override
    public ApprovalLinePreviewResponse previewApprovalLine(FormType formType, ApprovalAttributes attributes,
                                                           Long drafterId) {
        ApprovalLevel level = approvalPolicy.requiredLevel(formType, attributes);
        return ApprovalLinePreviewResponse.of(formType, level, approvalLineGenerator.generate(drafterId, level));
    }

    /** 이미 있는 문서를 재요청에 돌려준다. 같은 키인데 다른 신청이면 409 */
    private DocumentCreateResponse toCreateResponse(Document existing, DocumentCreateRequest request, Long drafterId) {
        if (!existing.isSameRequest(drafterId, request.formType(), request.sourceType(), request.sourceId())) {
            throw new BusinessException(ErrorCode.REQUEST_KEY_CONFLICT);
        }
        return new DocumentCreateResponse(existing.getDocId(), existing.getDocNo());
    }

    /**
     * 요청 키로 문서 조회. 원본 서비스가 생성 결과를 모를 때(타임아웃 등)와 결과 이벤트를 놓쳤을 때 쓴다.
     * 없거나 조회 권한이 없으면 404 (404 = 이 키로 만든 문서가 없음)
     */
    @Override
    public DocumentInfoResponse findByRequestKey(UUID requestKey, AuthUser requester) {
        Document document = documentRepository.findByRequestKey(requestKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        return findById(document.getDocId(), requester);
    }

    @Override
    public DocumentInfoResponse findById(Long documentId, AuthUser requester) {
        Document document = getReadableDocument(documentId, requester);

        List<ApprovalLine> lines = approvalLineRepository.findByDocumentDocIdOrderByStepAsc(documentId);
        List<ApprovalHistory> histories = approvalHistoryRepository.findByDocumentDocIdOrderByOccurredAtAsc(documentId);

        return DocumentInfoResponse.of(document, lines, histories);
    }

    /** 잠금 없는 내부 조회 (조회 권한 검사용). 다른 도메인은 getReadableDocument / getDocumentForUpdate 를 쓴다. 없으면 404 */
    private Document getDocument(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    /**
     * 결재 처리와 결재선 변경용. 잠금은 호출한 쪽 트랜잭션이 끝날 때 풀리므로,
     * 같은 문서의 다음 요청은 기다렸다가 바뀐 상태로 다시 검증된다. 없으면 404
     */
    @Override
    @Transactional
    public Document getDocumentForUpdate(Long documentId) {
        return documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    /**
     * 조회 가능: 관리자, 기안자, 현재 결재선 참여자(결재자/합의자/참조자/열람자).
     * 권한이 없으면 문서 존재 여부도 드러나지 않게 없는 문서와 같은 404로 응답한다
     */
    @Override
    public Document getReadableDocument(Long documentId, AuthUser requester) {
        Document document = getDocument(documentId);
        if (requester.isAdmin() || document.isDraftedBy(requester.employeeId())) {
            return document;
        }
        if (!approvalLineRepository.existsByDocumentDocIdAndEmpId(documentId, requester.employeeId())) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    /**
     * 다른 서비스에서 온 문서는 원본 신청 ID가 있어야 하고, 직접 기안은 없어야 한다.
     * 양식마다 만들 수 있는 출처가 정해져 있다 (FormType.allows)
     */
    private void validateSource(FormType formType, SourceType sourceType, Long sourceId, UUID requestKey) {
        boolean direct = sourceType == SourceType.DIRECT;
        if (direct != (sourceId == null)) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT_SOURCE);
        }
        if (!formType.allows(sourceType)) {
            throw new BusinessException(ErrorCode.FORM_SOURCE_MISMATCH);
        }
        // 다른 서비스는 생성 요청을 재시도하므로 중복 생성을 막을 요청 키가 있어야 한다 (직접 기안은 선택)
        if (!direct && requestKey == null) {
            throw new BusinessException(ErrorCode.REQUEST_KEY_REQUIRED);
        }
    }

    /** 쿼리 파라미터 문자열을 enum으로 변환. null/빈 문자열은 null, 잘못된 값은 400 */
    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, paramName + " 값이 올바르지 않습니다: " + value);
        }
    }
}
