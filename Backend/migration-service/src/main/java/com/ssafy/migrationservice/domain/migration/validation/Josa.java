package com.ssafy.migrationservice.domain.migration.validation;

/** 받침 유무에 맞춰 조사를 붙인다. 한글이 아니면 "이(가)"처럼 둘 다 쓴다. */
public final class Josa {

    private Josa() {
    }

    /** 입사일이 / 이메일이 / 직위가 */
    public static String iGa(String word) {
        return word + pick(word, "이", "가");
    }

    /** 사번은 / 직위는 */
    public static String eunNeun(String word) {
        return word + pick(word, "은", "는");
    }

    /** 이메일을 / 직위를 */
    public static String eulReul(String word) {
        return word + pick(word, "을", "를");
    }

    private static String pick(String word, String withBatchim, String without) {
        if (word == null || word.isEmpty()) {
            return withBatchim + "(" + without + ")";
        }
        char last = word.charAt(word.length() - 1);
        if (last < '가' || last > '힣') {
            return withBatchim + "(" + without + ")";
        }
        return (last - '가') % 28 == 0 ? without : withBatchim;
    }
}
