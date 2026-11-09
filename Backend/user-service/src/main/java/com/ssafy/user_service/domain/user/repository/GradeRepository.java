package com.ssafy.user_service.domain.user.repository;

import com.ssafy.user_service.domain.user.entity.Grade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradeRepository extends JpaRepository<Grade, Long> {
}
