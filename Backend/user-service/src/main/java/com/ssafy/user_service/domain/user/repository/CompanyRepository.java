package com.ssafy.user_service.domain.user.repository;

import com.ssafy.user_service.domain.user.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
}
