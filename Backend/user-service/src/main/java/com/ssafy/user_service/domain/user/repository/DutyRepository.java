package com.ssafy.user_service.domain.user.repository;

import com.ssafy.user_service.domain.user.entity.Duty;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DutyRepository extends JpaRepository<Duty, Long> {
}
