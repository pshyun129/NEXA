package com.ssafy.approval_service.global.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 발행 전 이벤트를 오래된 순으로 잠가서 가져온다.
     * SKIP LOCKED: 다른 인스턴스(발행기)가 이미 잠근 행은 기다리지 않고 건너뛴다 → 같은 이벤트를 두 인스턴스가 동시에 보내지 않음
     */
    @Query(value = """
            SELECT * FROM approval_outbox
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("limit") int limit);

    /** 발행 전 이벤트 수 (모니터링) */
    long countByPublishedAtIsNull();

    /** 가장 오래 기다린 발행 전 이벤트 (모니터링). 미발행 부분 인덱스(id) 를 탄다 */
    Optional<OutboxEvent> findFirstByPublishedAtIsNullOrderByIdAsc();

    /** 발행이 끝난 지 오래된 이벤트 정리 */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") OffsetDateTime before);
}
