package com.ssafy.leaveservice.domain.leavetype.controller;

import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeResponse;
import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeCreateRequest;
import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeUpdateRequest;
import com.ssafy.leaveservice.domain.leavetype.service.LeaveTypeService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/admin/types")
@RequiredArgsConstructor
public class AdminLeaveTypeController {
    private final LeaveTypeService leaveTypeService;

    // 관리자용 휴가 유형 목록 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<LeaveTypeResponse>>> getAllTypes() {
        List<LeaveTypeResponse> leaveTypes = leaveTypeService.getAllTypes();
        return ResponseEntity.ok(ApiResult.success(leaveTypes));
    }

    // 휴가 유형 등록
    @PostMapping
    public ResponseEntity<ApiResult<LeaveTypeResponse>> createType(
            @Valid @RequestBody LeaveTypeCreateRequest request
    ) {
        LeaveTypeResponse response = leaveTypeService.createType(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(response));
    }

    // 휴가 유형 정보 및 활성 상태 수정
    @PatchMapping("/{leaveTypeId}")
    public ResponseEntity<ApiResult<LeaveTypeResponse>> updateType(
            @PathVariable Long leaveTypeId,
            @Valid @RequestBody LeaveTypeUpdateRequest request
    ) {
        LeaveTypeResponse response = leaveTypeService.updateType(leaveTypeId, request);
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
