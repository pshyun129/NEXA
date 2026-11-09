package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.service.SubmissionPayloadHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SubmissionPayloadHashTest {
    private final SubmissionPayloadHash hash = new SubmissionPayloadHash();
    private final LeaveRequestPreviewRequest.DateRequest full = new LeaveRequestPreviewRequest.DateRequest(LocalDate.of(2030,1,7),LeaveRequestPortion.FULL);
    private final LeaveRequestPreviewRequest.DateRequest half = new LeaveRequestPreviewRequest.DateRequest(LocalDate.of(2030,1,8),LeaveRequestPortion.AM_HALF);

    @Test
    void canonicalOrderAndRepeatAreStableSha256() {
        String first=hash.calculate(new LeaveRequestPreviewRequest(1L,"사유",List.of(full,half)));
        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(hash.calculate(new LeaveRequestPreviewRequest(1L,"사유",List.of(half,full)))).isEqualTo(first);
        assertThat(new SubmissionPayloadHash().calculate(new LeaveRequestPreviewRequest(1L,"사유",List.of(full,half)))).isEqualTo(first);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings={""," ","null","한글","a|b","a\u0000b"})
    void reasonIsNotTrimmedOrNullNormalized(String reason) {
        List<String> reasons=Arrays.asList(null,""," ","null","한글","a|b","a\u0000b");
        Set<String> hashes=new HashSet<>();
        for(String value:reasons) {
            hashes.add(hash.calculate(new LeaveRequestPreviewRequest(1L,value,List.of(full))));
        }
        assertThat(hashes).hasSize(reasons.size());
        assertThat(hash.calculate(new LeaveRequestPreviewRequest(1L,reason,List.of(full)))).isIn(hashes);
    }

    @Test
    void typeDateAndPortionAffectHash() {
        Set<String> hashes=new HashSet<>();
        hashes.add(hash.calculate(new LeaveRequestPreviewRequest(1L,null,List.of(full))));
        hashes.add(hash.calculate(new LeaveRequestPreviewRequest(2L,null,List.of(full))));
        hashes.add(hash.calculate(new LeaveRequestPreviewRequest(1L,null,List.of(half))));
        hashes.add(hash.calculate(new LeaveRequestPreviewRequest(1L,null,List.of(new LeaveRequestPreviewRequest.DateRequest(full.leaveDate(),LeaveRequestPortion.PM_HALF)))));
        assertThat(hashes).hasSize(4);
    }
}
