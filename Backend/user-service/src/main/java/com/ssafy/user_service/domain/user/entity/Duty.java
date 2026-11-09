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
@Table(name = "duty")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Duty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "duty_id")
    private Long dutyId;    // 직책ID

    @Column(name = "name", length = 255)
    private String name;    // 직책명

    @Builder
    private Duty(String name) {
        this.name = name;
    }
}
