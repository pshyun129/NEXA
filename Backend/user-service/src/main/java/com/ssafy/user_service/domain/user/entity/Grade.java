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
@Table(name = "grade")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Grade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grade_id")
    private Long gradeId;       // 직급ID

    @Column(name = "name", length = 255)
    private String name;        // 직급명

    @Column(name = "level")
    private Short level;        // 호봉

    @Builder
    private Grade(String name, Short level) {
        this.name = name;
        this.level = level;
    }
}
