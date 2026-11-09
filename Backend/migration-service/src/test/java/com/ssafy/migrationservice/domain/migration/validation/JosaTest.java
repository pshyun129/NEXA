package com.ssafy.migrationservice.domain.migration.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JosaTest {

    @Test
    void 받침에_맞춰_조사를_고른다() {
        assertThat(Josa.iGa("입사일")).isEqualTo("입사일이");
        assertThat(Josa.iGa("직위")).isEqualTo("직위가");
        assertThat(Josa.eunNeun("사번")).isEqualTo("사번은");
        assertThat(Josa.eunNeun("직무")).isEqualTo("직무는");
        assertThat(Josa.eulReul("이메일")).isEqualTo("이메일을");
    }

    @Test
    void 한글이_아니면_둘_다_쓴다() {
        assertThat(Josa.iGa("D100")).isEqualTo("D100이(가)");
    }
}
