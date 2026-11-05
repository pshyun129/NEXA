package com.ssafy.user_service.domain.migration.service;

import com.ssafy.user_service.domain.user.entity.Department;
import com.ssafy.user_service.domain.user.entity.Duty;
import com.ssafy.user_service.domain.user.entity.Grade;
import com.ssafy.user_service.domain.user.entity.JobCode;
import com.ssafy.user_service.domain.user.entity.Position;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 이관 사원 행의 문자열(이름·코드) → 기준정보 ID. 한 요청 동안 쓰도록 기준정보를 한 번에 읽어 둔다.
 * 찾지 못하거나 여러 개가 걸리면 행 번호가 붙은 오류로 실패한다 (추측해서 고르지 않는다).
 */
final class MigrationReferenceLookup {

    // "G3 2호봉", "G3-2", "G3(2호봉)", "G3 2" → 이름 G3, 호봉 2
    private static final Pattern GRADE_WITH_STEP = Pattern.compile("^(.+?)[\\s\\-_/(]+(\\d+)\\s*(?:호봉)?\\)?$");

    private final Map<String, List<Position>> positions;
    private final Map<String, List<Duty>> duties;
    private final Map<String, List<Grade>> grades;
    private final List<JobCode> jobCodes;
    private final Map<String, Department> deptsByCode;
    private final Map<String, List<Department>> deptsByName;

    MigrationReferenceLookup(List<Position> positions, List<Duty> duties, List<Grade> grades,
                        List<JobCode> jobCodes, List<Department> departments) {
        this.positions = groupByName(positions, Position::getName);
        this.duties = groupByName(duties, Duty::getName);
        this.grades = groupByName(grades, Grade::getName);
        this.jobCodes = jobCodes;
        this.deptsByCode = departments.stream().filter(d -> d.getDeptCode() != null)
                .collect(Collectors.toMap(Department::getDeptCode, d -> d));
        this.deptsByName = groupByName(departments, Department::getName);
    }

    /** 부서: 부서코드 우선, 없으면 이름이 정확히 하나일 때만 */
    Long department(MigrationRow row, String key) {
        String value = row.requiredString(key);
        Department byCode = deptsByCode.get(value);
        if (byCode != null) {
            return byCode.getDeptId();
        }
        return single(row, key, value, deptsByName.get(value), "부서코드나 부서명").getDeptId();
    }

    Long position(MigrationRow row, String key) {
        String value = row.requiredString(key);
        return single(row, key, value, positions.get(value), "직위명").getPositionId();
    }

    Long duty(MigrationRow row, String key) {
        String value = row.requiredString(key);
        return single(row, key, value, duties.get(value), "직책명").getDutyId();
    }

    /** 직급: 이름이 하나면 그것, 호봉이 여러 개면 호봉까지 적어야 한다 ("G3 2호봉" 등) */
    Long grade(MigrationRow row, String key) {
        String value = row.requiredString(key);
        List<Grade> exact = grades.get(value);
        if (exact != null && exact.size() == 1) {
            return exact.get(0).getGradeId();
        }
        if (exact != null) {
            throw row.error(row.label(key) + " '" + value + "'은(는) 호봉이 " + exact.size()
                    + "개라 호봉까지 적어야 합니다 (예: " + value + " 1호봉)");
        }
        Matcher m = GRADE_WITH_STEP.matcher(value);
        if (m.matches()) {
            String name = m.group(1).trim();
            short level = Short.parseShort(m.group(2));
            Optional<Grade> found = grades.getOrDefault(name, List.of()).stream()
                    .filter(g -> g.getLevel() != null && g.getLevel() == level)
                    .findFirst();
            if (found.isPresent()) {
                return found.get().getGradeId();
            }
        }
        throw row.error(row.label(key) + " '" + value + "'을(를) 찾을 수 없습니다");
    }

    /** 직군·직종·직무는 이름이 겹칠 수 있어(예: 해외영업 = 직종이자 직무) 상위 코드 범위 안에서 찾는다 */
    Long jobCode(MigrationRow row, String key, JobCodeType type, Long parentId) {
        String value = row.requiredString(key);
        List<JobCode> candidates = jobCodes.stream()
                .filter(c -> c.getType() == type && value.equals(c.getName()))
                .filter(c -> Objects.equals(parentId, c.getParent() == null ? null : c.getParent().getJobCodeId()))
                .toList();
        return single(row, key, value, candidates, type == JobCodeType.FAMILY ? "직군명" : "상위 코드 아래의 이름")
                .getJobCodeId();
    }

    private static <T> T single(MigrationRow row, String key, String value, List<T> candidates, String basis) {
        if (candidates == null || candidates.isEmpty()) {
            throw row.error(row.label(key) + " '" + value + "'을(를) 찾을 수 없습니다 (" + basis + " 기준)");
        }
        if (candidates.size() > 1) {
            throw row.error(row.label(key) + " '" + value + "'이(가) " + candidates.size() + "개라 하나로 정할 수 없습니다");
        }
        return candidates.get(0);
    }

    private static <T> Map<String, List<T>> groupByName(List<T> items, Function<T, String> name) {
        return items.stream().filter(i -> name.apply(i) != null)
                .collect(Collectors.groupingBy(i -> name.apply(i).trim()));
    }
}
