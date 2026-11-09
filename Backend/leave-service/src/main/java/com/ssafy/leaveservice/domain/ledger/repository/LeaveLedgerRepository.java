package com.ssafy.leaveservice.domain.ledger.repository;

import com.ssafy.leaveservice.domain.ledger.entity.LeaveLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LeaveLedgerRepository extends JpaRepository<LeaveLedger, Long> {

    boolean existsByReversesEntryIdIn(List<Long> entryIds);

    @Query("""
            select ledgerEntry from LeaveLedger ledgerEntry
            join fetch ledgerEntry.account account
            join fetch account.balanceType
            where account.employeeId = :employeeId
            order by ledgerEntry.createdAt desc, ledgerEntry.entryId desc
            """)
    List<LeaveLedger> findAllWithAccountAndBalanceTypeByEmployeeId(@Param("employeeId") Long employeeId);

    List<LeaveLedger> findAllByLeaveReqIdOrderByEntryIdAsc(Long requestId);

    @Query("select ledger from LeaveLedger ledger join fetch ledger.account where ledger.idempotencyKey = :key")
    java.util.Optional<LeaveLedger> findByAdjustmentKey(@Param("key") String key);
}
