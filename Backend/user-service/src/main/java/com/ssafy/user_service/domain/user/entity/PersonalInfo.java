package com.ssafy.user_service.domain.user.entity;

import com.ssafy.user_service.domain.user.entity.enums.Gender;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 사원 개인 정보 (employee 테이블의 컬럼 그룹) */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonalInfo {

    @Column(name = "eng_name", columnDefinition = "TEXT")
    private String engName;                     // 영문이름

    @Column(name = "office_phone", length = 100)
    private String officePhone;                 // 직통번호

    @Column(name = "mobile", length = 100)
    private String mobile;                      // 휴대전화

    @Column(name = "rrn_enc", length = 255)
    private String rrnEnc;                      // 주민번호 암호화

    @Column(name = "nationality", length = 255)
    private String nationality;                 // 국적

    @Column(name = "stay_status")
    private Short stayStatus;                   // 체류자격

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;                      // 성별

    @Column(name = "birth_date")
    private LocalDate birthDate;                // 생년월일

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;                     // 주소

    @Builder
    private PersonalInfo(String engName, String officePhone, String mobile, String rrnEnc, String nationality,
                         Short stayStatus, Gender gender, LocalDate birthDate, String address) {
        this.engName = engName;
        this.officePhone = officePhone;
        this.mobile = mobile;
        this.rrnEnc = rrnEnc;
        this.nationality = nationality;
        this.stayStatus = stayStatus;
        this.gender = gender;
        this.birthDate = birthDate;
        this.address = address;
    }
}
