package com.ssafy.user_service.domain.user.repository;

import com.ssafy.user_service.domain.user.entity.JobCode;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobCodeRepository extends JpaRepository<JobCode, Long> {
    /** 해당 ID가 존재하고 종류(직군/직종/직무)까지 일치하는지 */
    boolean existsByJobCodeIdAndType(Long jobCodeId, JobCodeType type);

    /** 종류별 코드 목록 */
    List<JobCode> findByType(JobCodeType type);
}
