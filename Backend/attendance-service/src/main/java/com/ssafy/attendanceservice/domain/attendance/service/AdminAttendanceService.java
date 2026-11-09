package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogUpdateRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.DailyAttendanceResponse;

public interface AdminAttendanceService {

    /** 근무 상태 로그 직접 등록. 등록 후 그날 근태를 재동기화하고 수정된 일별 타임라인을 반환 */
    DailyAttendanceResponse registerLog(Long adminId, AdminWorkStatusLogRequest request);

    /** 근무 상태 로그 수정. 기존 로그는 삭제 처리하고 수정 내용으로 새 로그를 등록한다 (변경 이력 보존) */
    DailyAttendanceResponse updateLog(Long adminId, Long logId, AdminWorkStatusLogUpdateRequest request);

    /** 근무 상태 로그 삭제 (소프트 삭제). 삭제 후 그날 근태를 재동기화하고 일별 타임라인을 반환 */
    DailyAttendanceResponse deleteLog(Long logId);
}