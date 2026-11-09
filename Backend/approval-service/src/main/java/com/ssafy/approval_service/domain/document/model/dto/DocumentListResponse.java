package com.ssafy.approval_service.domain.document.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.approval_service.domain.approval.model.ApprovalProgress;
import com.ssafy.approval_service.domain.approval.model.entity.ApprovalLine;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/**
 * 문서 목록.
 *
 * @param totalCount 조건에 맞는 전체 건수 (페이지로 나눠도 전체 기준)
 * @param page       요청한 페이지(0부터). 페이지를 요청하지 않았으면 응답에 없다
 * @param size       페이지 크기. 페이지를 요청하지 않았으면 응답에 없다
 */
public record DocumentListResponse(
        List<DocumentSummaryResponse> documents,
        int totalCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer page,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer size
) {
    /**
     * @param linesByDocId 문서ID별 결재선 (목록 문서 전체를 한 번에 조회해 묶은 것). 없는 문서는 결재선이 없는 것으로 본다
     * @param requesterId  myTurn 계산 기준
     * @param totalCount   조건에 맞는 전체 건수
     */
    public static DocumentListResponse of(List<Document> documents, Map<Long, List<ApprovalLine>> linesByDocId,
                                          Long requesterId, long totalCount, Pageable pageable) {
        List<DocumentSummaryResponse> items = documents.stream()
                .map(document -> DocumentSummaryResponse.of(
                        document,
                        ApprovalProgress.of(document.isInProgress(),
                                linesByDocId.getOrDefault(document.getDocId(), List.of())),
                        requesterId))
                .toList();
        int total = Math.toIntExact(totalCount);
        return pageable.isPaged()
                ? new DocumentListResponse(items, total, pageable.getPageNumber(), pageable.getPageSize())
                : new DocumentListResponse(items, total, null, null);
    }
}
