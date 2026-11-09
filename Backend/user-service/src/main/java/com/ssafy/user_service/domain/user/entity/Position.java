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
@Table(name = "position")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "position_id")
    private Long positionId;    // 직위ID

    @Column(name = "name", length = 255)
    private String name;        // 직위명

    @Column(name = "rank_order")
    private Integer rankOrder;  // 서열

    @Builder
    private Position(String name, Integer rankOrder) {
        this.name = name;
        this.rankOrder = rankOrder;
    }
}