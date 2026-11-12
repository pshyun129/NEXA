package com.ssafy.approval_service.domain.document.repository;

import com.ssafy.approval_service.domain.approval.model.entity.enums.LineAction;
import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import com.ssafy.approval_service.domain.document.model.entity.enums.DocumentStatus;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 목록 조회(find*)는 Pageable 을 받는다. 정렬은 각 쿼리의 ORDER BY 로 고정이고(문서함마다 의미가 다름),
 * 페이지가 흔들리지 않게 마지막 정렬 키는 docId 다. Pageable.unpaged() 면 전체
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 결재상태/문서양식/기안자 필터 조회. null인 조건은 무시.
     * 본인이 기안했거나 결재선(결재자/합의자/참조자/열람자)에 있는 문서만 조회한다.
     * 관리자도 같다: 목록(결재함 "전체")에 회사 전체 문서를 보이지 않는다 (QA C17). 단건 조회 권한은 getReadableDocument 가 따로 정한다
     */
    @Query("""
            SELECT d FROM Document d
            WHERE (:status IS NULL OR d.status = :status)
              AND (:formType IS NULL OR d.formType = :formType)
              AND (:drafterId IS NULL OR d.drafterId = :drafterId)
              AND (d.drafterId = :employeeId
                   OR EXISTS (SELECT 1 FROM ApprovalLine l WHERE l.document = d AND l.empId = :employeeId))
            ORDER BY d.draftedAt DESC, d.docId DESC
            """)
    List<Document> findInvolvedByStatusAndFormType(@Param("status") DocumentStatus status,
                                                   @Param("formType") FormType formType,
                                                   @Param("drafterId") Long drafterId,
                                                   @Param("employeeId") Long employeeId,
                                                   Pageable pageable);

    /** findInvolvedByStatusAndFormType 과 같은 조건의 건수 */
    @Query("""
            SELECT COUNT(d) FROM Document d
            WHERE (:status IS NULL OR d.status = :status)
              AND (:formType IS NULL OR d.formType = :formType)
              AND (:drafterId IS NULL OR d.drafterId = :drafterId)
              AND (d.drafterId = :employeeId
                   OR EXISTS (SELECT 1 FROM ApprovalLine l WHERE l.document = d AND l.empId = :employeeId))
            """)
    long countInvolvedByStatusAndFormType(@Param("status") DocumentStatus status,
                                          @Param("formType") FormType formType,
                                          @Param("drafterId") Long drafterId,
                                          @Param("employeeId") Long employeeId);

    /**
     * 결재 대기함: 진행 중이고 지금 요청자 차례인 문서. 오래된 문서부터 (처리 순서).
     * 차례 규칙은 ApprovalProgress 와 같다: 결재자(APPROVER/AGREEMENT) PENDING 줄 중 (step, lineId) 가 가장 앞인 줄.
     * 그래서 MIN(step) 비교가 아니라 "나보다 앞선 PENDING 결재자 줄이 없다"(NOT EXISTS)로 쓴다.
     * status 를 함께 주면 교집합이다 (INPROGRESS 외에는 빈 결과)
     */
    @Query("""
            SELECT d FROM Document d
            WHERE d.status = com.ssafy.approval_service.domain.document.model.entity.enums.DocumentStatus.INPROGRESS
              AND (:status IS NULL OR d.status = :status)
              AND (:formType IS NULL OR d.formType = :formType)
              AND EXISTS (""" + MY_TURN_LINE + """
            )
            ORDER BY d.draftedAt ASC, d.docId ASC
            """)
    List<Document> findPendingFor(@Param("status") DocumentStatus status,
                                  @Param("formType") FormType formType,
                                  @Param("employeeId") Long employeeId,
                                  Pageable pageable);

    /** findPendingFor 와 같은 조건의 건수 */
    @Query("""
            SELECT COUNT(d) FROM Document d
            WHERE d.status = com.ssafy.approval_service.domain.document.model.entity.enums.DocumentStatus.INPROGRESS
              AND (:status IS NULL OR d.status = :status)
              AND (:formType IS NULL OR d.formType = :formType)
              AND EXISTS (""" + MY_TURN_LINE + """
            )
            """)
    long countPendingFor(@Param("status") DocumentStatus status,
                         @Param("formType") FormType formType,
                         @Param("employeeId") Long employeeId);

    /**
     * 참조함: 요청자의 결재선 줄 중 역할(roles)과 처리 상태(actions)가 맞는 줄이 있는 문서. 최신 문서부터.
     * 문서 상태는 따지지 않는다 (status 를 주면 교집합)
     */
    @Query("""
            SELECT d FROM Document d
            WHERE (:status IS NULL OR d.status = :status)
              AND (:formType IS NULL OR d.formType = :formType)
              AND EXISTS (""" + MY_LINE + """
            )
            ORDER BY d.draftedAt DESC, d.docId DESC
            """)
    List<Document> findByMyLine(@Param("status") DocumentStatus status,
                                @Param("formType") FormType formType,
                                @Param("employeeId") Long employeeId,
                                @Param("roles") Collection<LineRole> roles,
                                @Param("actions") Collection<LineAction> actions,
                                Pageable pageable);

    /**
     * 처리함: 요청자의 줄 중 역할(roles)·처리 상태(actions)가 맞는 줄이 있는 문서. 요청자가 처리한 시각이 최근인 문서부터.
     * 정렬 기준(actedAt)이 결재선에 있어서 결재선에서 출발한다. 한 문서에 내 줄이 여럿이면 GROUP BY 로 한 번만, 가장 최근 처리 시각으로 정렬한다.
     * 건수는 countByMyLine 과 조건이 같다
     */
    @Query("""
            SELECT d FROM ApprovalLine l JOIN l.document d
            WHERE l.empId = :employeeId
              AND l.role IN :roles
              AND l.action IN :actions
              AND (:status IS NULL OR d.status = :status)
              AND (:formType IS NULL OR d.formType = :formType)
            GROUP BY d
            ORDER BY MAX(l.actedAt) DESC, d.docId DESC
            """)
    List<Document> findByMyLineOrderByActedAt(@Param("status") DocumentStatus status,
                                              @Param("formType") FormType formType,
                                              @Param("employeeId") Long employeeId,
                                              @Param("roles") Collection<LineRole> roles,
                                              @Param("actions") Collection<LineAction> actions,
                                Pageable pageable);

    /** findByMyLine 과 같은 조건의 건수 */
    @Query("""
            SELECT COUNT(d) FROM Document d
            WHERE (:status IS NULL OR d.status = :status)
              AND (:formType IS NULL OR d.formType = :formType)
              AND EXISTS (""" + MY_LINE + """
            )
            """)
    long countByMyLine(@Param("status") DocumentStatus status,
                       @Param("formType") FormType formType,
                       @Param("employeeId") Long employeeId,
                       @Param("roles") Collection<LineRole> roles,
                       @Param("actions") Collection<LineAction> actions);

    /** 문서 d 에 요청자(:employeeId)의 줄 중 역할·처리 상태가 맞는 줄이 있는지. findByMyLine / countByMyLine 이 같이 쓴다 */
    String MY_LINE = """
            SELECT 1 FROM ApprovalLine l
            WHERE l.document = d
              AND l.empId = :employeeId
              AND l.role IN :roles
              AND l.action IN :actions
            """;

    /** 문서 d 에서 요청자(:employeeId)의 줄이 현재 차례인지. findPendingFor / countPendingFor 가 같이 쓴다 */
    String MY_TURN_LINE = """
            SELECT 1 FROM ApprovalLine l
            WHERE l.document = d
              AND l.empId = :employeeId
              AND l.role IN (com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole.APPROVER,
                             com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole.AGREEMENT)
              AND l.action = com.ssafy.approval_service.domain.approval.model.entity.enums.LineAction.PENDING
              AND NOT EXISTS (
                  SELECT 1 FROM ApprovalLine p
                  WHERE p.document = d
                    AND p.role IN (com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole.APPROVER,
                                   com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole.AGREEMENT)
                    AND p.action = com.ssafy.approval_service.domain.approval.model.entity.enums.LineAction.PENDING
                    AND (p.step < l.step OR (p.step = l.step AND p.lineId < l.lineId)))
            """;

    /** 생성 요청 키로 조회 */
    Optional<Document> findByRequestKey(UUID requestKey);

    /** 변경용 조회. 행 잠금(SELECT ... FOR UPDATE)으로 같은 문서의 변경 요청을 한 번에 하나씩 처리한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.docId = :docId")
    Optional<Document> findByIdForUpdate(@Param("docId") Long docId);
}
