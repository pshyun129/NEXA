package com.ssafy.leaveservice.domain.leavetype.controller;

import com.ssafy.leaveservice.domain.leavetype.dto.RequestableLeaveTypeResponse;
import com.ssafy.leaveservice.domain.leavetype.service.RequestableLeaveTypeService;
import com.ssafy.leaveservice.global.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves/types")
@RequiredArgsConstructor
public class RequestableLeaveTypeController {

    private final RequestableLeaveTypeService requestableLeaveTypeService;

    // 내가 신청할 수 있는 휴가 유형 조회
    @GetMapping
    public ResponseEntity<ApiResult<List<RequestableLeaveTypeResponse>>> getRequestableTypes() {
        List<RequestableLeaveTypeResponse> response = requestableLeaveTypeService.getRequestableTypes();
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
