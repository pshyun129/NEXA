package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDate;
import java.util.List;

@JsonDeserialize(using = LeaveRequestPreviewRequestDeserializer.class)
public record LeaveRequestPreviewRequest(
        @NotNull @Positive Long leaveTypeId,
        @Size(max = 2000) String reason,
        @NotNull @Size(min = 1, max = 366) List<@NotNull @Valid DateRequest> dates
) {
    public record DateRequest(@NotNull LocalDate leaveDate, @NotNull LeaveRequestPortion portion) {
        @AssertTrue(message = "종일과 오전·오후 반차만 신청할 수 있습니다.")
        public boolean isSupportedPortion() {
            return portion == null || portion != LeaveRequestPortion.HOURLY;
        }
    }
}
