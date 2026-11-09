package com.ssafy.approval_service.domain.document.service;

import com.ssafy.approval_service.domain.approval.event.ApprovalResultEventRecorder;
import com.ssafy.approval_service.domain.approval.service.DocumentCompleter;
import com.ssafy.approval_service.domain.approval.model.entity.ApprovalHistory;
import com.ssafy.approval_service.domain.approval.model.entity.enums.HistoryAction;
import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;
import com.ssafy.approval_service.domain.approval.policy.ApprovalAttributes;
import com.ssafy.approval_service.domain.approval.policy.ApprovalLevel;
import com.ssafy.approval_service.domain.approval.policy.ApprovalLineGenerator;
import com.ssafy.approval_service.domain.approval.policy.ApprovalPolicy;
import com.ssafy.approval_service.domain.approval.policy.ApprovalPolicyProperties;
import com.ssafy.approval_service.domain.approval.policy.GeneratedApprovalLine;
import com.ssafy.approval_service.domain.approval.repository.ApprovalHistoryRepository;
import com.ssafy.approval_service.domain.approval.repository.ApprovalLineRepository;
import com.ssafy.approval_service.domain.document.model.dto.ApprovalLineRequest;
import com.ssafy.approval_service.domain.document.model.dto.DocumentCreateRequest;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import com.ssafy.approval_service.domain.document.model.entity.enums.DocumentStatus;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import com.ssafy.approval_service.domain.document.model.entity.enums.SourceType;
import com.ssafy.approval_service.domain.document.repository.DocumentRepository;
import com.ssafy.approval_service.global.exception.BusinessException;
import com.ssafy.approval_service.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 문서 생성(DocumentServiceImplV1.create): 출처·양식 검증, 문서번호 채번, 요청 키 재요청 처리 */
class DocumentCreateServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
    private final RequestKeyLock requestKeyLock = mock(RequestKeyLock.class);
    private final ApprovalLineRepository lineRepository = mock(ApprovalLineRepository.class);
    private final ApprovalHistoryRepository historyRepository = mock(ApprovalHistoryRepository.class);
    private final ApprovalLineGenerator lineGenerator = mock(ApprovalLineGenerator.class);
    private final ApprovalResultEventRecorder resultEventRecorder = mock(ApprovalResultEventRecorder.class);
    private final ApprovalPolicy policy = new ApprovalPolicy(new ApprovalPolicyProperties(
            new BigDecimal("5"), 720, 1_000_000L, 10_000_000L));
    private final DocumentServiceImplV1 service = new DocumentServiceImplV1(
            documentRepository,
            lineRepository,
            historyRepository,
            numberGenerator,
            requestKeyLock,
            policy,
            lineGenerator,
            new DocumentCompleter(resultEventRecorder));

    /** 기본 조직도: 팀장(2) 한 명이 결재. 테스트마다 다시 stub 하면 그 값이 쓰인다 */
    @BeforeEach
    void defaultApprovalLine() {
        when(lineGenerator.generate(anyLong(), any())).thenReturn(new GeneratedApprovalLine(
                List.of(new ApprovalLineRequest(2L, (short) 1, LineRole.APPROVER)), false));
    }

    /** JPA 저장처럼 문서ID를 채워 돌려준다 */
    private static Document persist(InvocationOnMock inv) {
        Document document = inv.getArgument(0);
        ReflectionTestUtils.setField(document, "docId", 12L);
        return document;
    }

    @Test
    void 문서번호는_양식_연도_일련번호로_저장_전에_채운다() {
        when(numberGenerator.next(eq(FormType.LEAVE), anyInt())).thenReturn(7);
        when(documentRepository.save(any())).thenAnswer(DocumentCreateServiceTest::persist);

        var res = service.create(request(FormType.LEAVE, SourceType.LEAVE, 42L), 1L).response();

        Document saved = savedDocument();
        String expected = "LEAVE-%d-0007".formatted(saved.getDraftedAt().getYear());
        assertThat(saved.getDocNo()).isEqualTo(expected);
        assertThat(res.docNo()).isEqualTo(expected);
        assertThat(res.documentId()).isEqualTo(12L);
        verify(numberGenerator).next(FormType.LEAVE, saved.getDraftedAt().getYear());
    }

    @Test
    void 검증에_실패하면_번호를_발급하지_않는다() {
        expectError(request(FormType.LEAVE, SourceType.DIRECT, null), ErrorCode.FORM_SOURCE_MISMATCH);
        verifyNoInteractions(numberGenerator);
    }

    /** 출처가 있는 요청에는 요청 키를 채운다 (필수) */
    private static DocumentCreateRequest request(FormType formType, SourceType sourceType, Long sourceId) {
        UUID requestKey = sourceType == null || sourceType == SourceType.DIRECT ? null : UUID.randomUUID();
        return new DocumentCreateRequest("테스트 문서", formType, sourceType, sourceId, requestKey, null, null);
    }

    private static DocumentCreateRequest request(SourceType sourceType, Long sourceId) {
        FormType formType = sourceType == SourceType.LEAVE ? FormType.LEAVE : FormType.GENERAL;
        return request(formType, sourceType, sourceId);
    }

    private void expectError(DocumentCreateRequest request, ErrorCode code) {
        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getErrorCode() == code);
        verifyNoInteractions(documentRepository);
    }

    @Test
    void 휴가_양식을_직접_기안하면_400() {
        expectError(request(FormType.LEAVE, SourceType.DIRECT, null), ErrorCode.FORM_SOURCE_MISMATCH);
        expectError(request(FormType.LEAVE, null, null), ErrorCode.FORM_SOURCE_MISMATCH);
    }

    @Test
    void 휴가_출처인데_다른_양식이면_400() {
        expectError(request(FormType.GENERAL, SourceType.LEAVE, 42L), ErrorCode.FORM_SOURCE_MISMATCH);
    }

    @ParameterizedTest
    @EnumSource(value = FormType.class, names = {"OVERTIME", "TIME_FIX", "HOLIDAY_SUBST"})
    void 근태_양식은_근태_신청에서_만들_수_있다(FormType formType) {
        when(documentRepository.save(any())).thenAnswer(DocumentCreateServiceTest::persist);

        service.create(request(formType, SourceType.ATTENDANCE, 7L), 1L);

        Document saved = savedDocument();
        assertThat(saved.getFormType()).isEqualTo(formType);
        assertThat(saved.getSourceType()).isEqualTo(SourceType.ATTENDANCE);
        assertThat(saved.getSourceId()).isEqualTo(7L);
    }

    @ParameterizedTest
    @EnumSource(value = FormType.class, names = {"OVERTIME", "TIME_FIX", "HOLIDAY_SUBST"})
    void 근태_양식을_직접_기안하면_400(FormType formType) {
        expectError(request(formType, SourceType.DIRECT, null), ErrorCode.FORM_SOURCE_MISMATCH);
    }

    @Test
    void 출처와_양식_서비스가_다르면_400() {
        expectError(request(FormType.LEAVE, SourceType.ATTENDANCE, 7L), ErrorCode.FORM_SOURCE_MISMATCH);
        expectError(request(FormType.OVERTIME, SourceType.LEAVE, 7L), ErrorCode.FORM_SOURCE_MISMATCH);
        expectError(request(FormType.GENERAL, SourceType.ATTENDANCE, 7L), ErrorCode.FORM_SOURCE_MISMATCH);
    }

    @Test
    void 일반_기안은_업무_자동화에서_만들_수_있고_금액으로_전결_단계를_정한다() {
        when(documentRepository.save(any())).thenAnswer(DocumentCreateServiceTest::persist);
        var request = new DocumentCreateRequest("구매 품의", FormType.GENERAL, SourceType.AUTOMATION, 501L,
                UUID.randomUUID(), new ApprovalAttributes(null, null, null, null, 5_000_000L), null);

        service.create(request, 1L);

        Document saved = savedDocument();
        assertThat(saved.getSourceType()).isEqualTo(SourceType.AUTOMATION);
        assertThat(saved.getSourceId()).isEqualTo(501L);
        assertThat(saved.getApprovalLevel()).isEqualTo(ApprovalLevel.DIVISION);
        assertThat(saved.isFromSource()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = FormType.class, names = "GENERAL", mode = EnumSource.Mode.EXCLUDE)
    void 업무_자동화는_일반_기안_외_양식을_만들_수_없다(FormType formType) {
        expectError(request(formType, SourceType.AUTOMATION, 501L), ErrorCode.FORM_SOURCE_MISMATCH);
    }

    @Test
    void 업무_자동화인데_원본_ID나_요청_키가_없으면_400() {
        expectError(request(FormType.GENERAL, SourceType.AUTOMATION, null), ErrorCode.INVALID_DOCUMENT_SOURCE);
        expectError(new DocumentCreateRequest("t", FormType.GENERAL, SourceType.AUTOMATION, 501L, null, null, null),
                ErrorCode.REQUEST_KEY_REQUIRED);
    }

    @Test
    void 휴가_외_양식은_직접_기안할_수_있다() {
        when(documentRepository.save(any())).thenAnswer(DocumentCreateServiceTest::persist);

        service.create(request(FormType.BUSINESS_TRIP, SourceType.DIRECT, null), 1L);

        assertThat(savedDocument().getFormType()).isEqualTo(FormType.BUSINESS_TRIP);
    }

    private Document savedDocument() {
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void 출처를_생략하면_직접_기안으로_저장된다() {
        when(documentRepository.save(any())).thenAnswer(DocumentCreateServiceTest::persist);

        service.create(request(null, null), 1L);

        Document saved = savedDocument();
        assertThat(saved.getSourceType()).isEqualTo(SourceType.DIRECT);
        assertThat(saved.getSourceId()).isNull();
    }

    @Test
    void 다른_서비스에서_온_문서는_출처와_원본_ID가_저장된다() {
        when(documentRepository.save(any())).thenAnswer(DocumentCreateServiceTest::persist);

        service.create(request(SourceType.LEAVE, 42L), 1L);

        Document saved = savedDocument();
        assertThat(saved.getSourceType()).isEqualTo(SourceType.LEAVE);
        assertThat(saved.getSourceId()).isEqualTo(42L);
    }

    @Test
    void 다른_서비스에서_왔는데_원본_ID가_없으면_400() {
        assertThatThrownBy(() -> service.create(request(SourceType.LEAVE, null), 1L))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getErrorCode() == ErrorCode.INVALID_DOCUMENT_SOURCE);
        verifyNoInteractions(documentRepository);
    }

    @Test
    void 직접_기안인데_원본_ID가_있으면_400() {
        assertThatThrownBy(() -> service.create(request(SourceType.DIRECT, 42L), 1L))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getErrorCode() == ErrorCode.INVALID_DOCUMENT_SOURCE);
        verifyNoInteractions(documentRepository);
    }

    @Test
    void 출처가_있는데_요청_키가_없으면_400() {
        var request = new DocumentCreateRequest("t", FormType.LEAVE, SourceType.LEAVE, 42L, null, null, null);
        expectError(request, ErrorCode.REQUEST_KEY_REQUIRED);
    }

    @Test
    void 같은_요청_키로_다시_오면_새로_만들지_않고_기존_문서를_돌려준다() {
        UUID key = UUID.randomUUID();
        Document existing = existingDocument(key, 1L, 42L);
        when(documentRepository.findByRequestKey(key)).thenReturn(Optional.of(existing));

        var result = service.create(
                new DocumentCreateRequest("재시도", FormType.LEAVE, SourceType.LEAVE, 42L, key, null, null), 1L);

        assertThat(result.created()).isFalse();
        assertThat(result.response().documentId()).isEqualTo(12L);
        assertThat(result.response().docNo()).isEqualTo("LEAVE-2026-0003");
        verify(requestKeyLock).lock(key);
        verify(documentRepository, never()).save(any());
        verifyNoInteractions(numberGenerator);
    }

    @Test
    void 같은_요청_키인데_다른_신청이면_409() {
        UUID key = UUID.randomUUID();
        when(documentRepository.findByRequestKey(key)).thenReturn(Optional.of(existingDocument(key, 1L, 42L)));

        // 원본 신청 ID가 다르다
        assertThatThrownBy(() -> service.create(
                new DocumentCreateRequest("t", FormType.LEAVE, SourceType.LEAVE, 99L, key, null, null), 1L))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getErrorCode() == ErrorCode.REQUEST_KEY_CONFLICT);
        // 기안자가 다르다
        assertThatThrownBy(() -> service.create(
                new DocumentCreateRequest("t", FormType.LEAVE, SourceType.LEAVE, 42L, key, null, null), 2L))
                .matches(t -> ((BusinessException) t).getErrorCode() == ErrorCode.REQUEST_KEY_CONFLICT);
        verify(documentRepository, never()).save(any());
    }

    // ===== 결재선 자동 결정 (위임전결 규정) =====

    private static DocumentCreateRequest leave(ApprovalAttributes attributes) {
        return new DocumentCreateRequest("휴가", FormType.LEAVE, SourceType.LEAVE, 42L, UUID.randomUUID(), attributes, null);
    }

    @Test
    void 전결_단계로_조직도에서_결재선을_만들고_규정상_결재자로_표시한다() {
        when(documentRepository.save(any())).thenAnswer(DocumentCreateServiceTest::persist);
        when(lineGenerator.generate(1L, ApprovalLevel.DIVISION)).thenReturn(new GeneratedApprovalLine(List.of(
                new ApprovalLineRequest(10L, (short) 1, LineRole.APPROVER),
                new ApprovalLineRequest(20L, (short) 2, LineRole.APPROVER)), false));
        var attributes = new ApprovalAttributes(new BigDecimal("5"), null, null, null, null);

        service.create(leave(attributes), 1L);

        Document saved = savedDocument();
        assertThat(saved.getApprovalLevel()).isEqualTo(ApprovalLevel.DIVISION);
        assertThat(saved.getAttributes()).isEqualTo(attributes);
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.INPROGRESS);
        verify(resultEventRecorder, never()).recordCompleted(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.ssafy.approval_service.domain.approval.model.entity.ApprovalLine>> lines =
                ArgumentCaptor.forClass(List.class);
        verify(lineRepository).saveAll(lines.capture());
        assertThat(lines.getValue()).extracting("empId").containsExactly(10L, 20L);
        assertThat(lines.getValue()).extracting("required").containsExactly(true, true);
    }

    @Test
    void 자가결재면_상신과_함께_승인하고_결과_이벤트를_남긴다() {
        when(documentRepository.save(any())).thenAnswer(DocumentCreateServiceTest::persist);
        when(lineGenerator.generate(1L, ApprovalLevel.TEAM)).thenReturn(new GeneratedApprovalLine(List.of(), true));

        service.create(leave(null), 1L);

        Document saved = savedDocument();
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(saved.getCompletedAt()).isNotNull();
        ArgumentCaptor<ApprovalHistory> histories = ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(historyRepository, times(2)).save(histories.capture());
        assertThat(histories.getAllValues()).extracting(ApprovalHistory::getAction)
                .containsExactly(HistoryAction.SUBMIT, HistoryAction.FINAL_APPROVE);
        verify(resultEventRecorder).recordCompleted(saved);
    }

    private static Document existingDocument(UUID key, Long drafterId, Long sourceId) {
        Document document = Document.builder().formType(FormType.LEAVE).title("휴가").drafterId(drafterId)
                .sourceType(SourceType.LEAVE).sourceId(sourceId).requestKey(key).build();
        ReflectionTestUtils.setField(document, "docId", 12L);
        ReflectionTestUtils.setField(document, "docNo", "LEAVE-2026-0003");
        return document;
    }
}
