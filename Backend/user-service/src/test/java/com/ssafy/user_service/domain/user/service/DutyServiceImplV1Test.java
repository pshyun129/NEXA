package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.DutyResponse;
import com.ssafy.user_service.domain.user.entity.Duty;
import com.ssafy.user_service.domain.user.repository.DutyRepository;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DutyServiceImplV1Test {

    private final DutyRepository dutyRepository = mock(DutyRepository.class);
    private final DutyService service = new DutyServiceImplV1(dutyRepository);

    @Test
    void 직책_ID로_직책명을_조회한다() {
        Duty duty = Duty.builder().name("팀장").build();
        ReflectionTestUtils.setField(duty, "dutyId", 5L);
        when(dutyRepository.findById(5L)).thenReturn(Optional.of(duty));

        DutyResponse res = service.getDuty(5L);

        assertThat(res.dutyId()).isEqualTo(5L);
        assertThat(res.name()).isEqualTo("팀장");
    }

    @Test
    void 없는_직책이면_NOT_FOUND() {
        when(dutyRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDuty(999L))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getErrorCode() == ErrorCode.NOT_FOUND);
    }
}
