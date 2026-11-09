package com.ssafy.attendanceservice.domain.attendance.entity;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.HolidayType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "holiday")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holiday {

    @Id
    @Column(name = "holiday_date")
    private LocalDate holidayDate;      // 휴일 일자

    @Column(name = "name", length = 50, nullable = false)
    private String name;                // 휴일명

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private HolidayType type;           // 휴일 구분

    @Builder
    private Holiday(LocalDate holidayDate, String name, HolidayType type) {
        this.holidayDate = holidayDate;
        this.name = name;
        this.type = type;
    }
}