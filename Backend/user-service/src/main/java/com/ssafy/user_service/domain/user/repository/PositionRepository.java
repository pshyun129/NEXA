package com.ssafy.user_service.domain.user.repository;

import com.ssafy.user_service.domain.user.entity.Position;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<Position, Long> {
}
