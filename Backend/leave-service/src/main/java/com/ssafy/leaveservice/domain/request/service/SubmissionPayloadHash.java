package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest;
import org.springframework.stereotype.Component;
import java.io.*;
import java.security.*;
import java.util.*;

@Component
public class SubmissionPayloadHash {
    public String calculate(LeaveRequestPreviewRequest request) {
        // 버전·길이·null 구분을 포함해 구분자 충돌 없이 요청 의미를 정규화한다.
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(1);
            output.writeLong(request.leaveTypeId());
            String reason = request.reason();
            output.writeInt(reason == null ? -1 : reason.length());
            if (reason != null) {
                for (int index = 0; index < reason.length(); index++) {
                    output.writeChar(reason.charAt(index));
                }
            }
            List<LeaveRequestPreviewRequest.DateRequest> dates = request.dates().stream()
                    .sorted(Comparator.comparing(LeaveRequestPreviewRequest.DateRequest::leaveDate)
                            .thenComparing(date -> date.portion().name())).toList();
            output.writeInt(dates.size());
            for (LeaveRequestPreviewRequest.DateRequest date : dates) {
                output.writeLong(date.leaveDate().toEpochDay());
                output.writeUTF(date.portion().name());
            }
            output.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("신청 해시 생성에 실패했습니다.", exception);
        }
    }
}
