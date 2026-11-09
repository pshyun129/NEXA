package com.ssafy.user_service.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "company")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_id")
    private Long companyId;     // 회사ID

    @Column(name = "name", length = 255)
    private String name;        // 회사명

    @Column(name = "biz_no")
    private Long bizNo;         // 사업자번호

    @Builder
    private Company(String name, Long bizNo) {
        this.name = name;
        this.bizNo = bizNo;
    }
}
