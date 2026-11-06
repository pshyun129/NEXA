package com.ssafy.user_service.domain.migration.service;

import com.ssafy.user_service.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertResponse;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository.Target;
import com.ssafy.user_service.domain.user.entity.Duty;
import com.ssafy.user_service.domain.user.entity.Grade;
import com.ssafy.user_service.domain.user.entity.JobCode;
import com.ssafy.user_service.domain.user.entity.Position;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;
import com.ssafy.user_service.domain.user.repository.DutyRepository;
import com.ssafy.user_service.domain.user.repository.GradeRepository;
import com.ssafy.user_service.domain.user.repository.JobCodeRepository;
import com.ssafy.user_service.domain.user.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기준정보(직위·직급·직책·직무 코드) 이관 upsert. 회사마다 체계가 달라서 사원보다 먼저 넣는다.
 * 한 행이 값 하나이고 type 으로 종류를 나눈다.
 * - POSITION {name, rankOrder} / DUTY {name}: 이름으로 찾는다
 * - GRADE {name, level}: 이름 + 호봉으로 찾는다 (호봉이 없으면 호봉 없는 직급)
 * - JOB_CODE {family, kind, role}: 직군 → 직종 → 직무 경로. 없는 단계는 만들고, 원본 행 번호는 직무에만 붙인다
 * 이미 있는 값은 바꾸지 않는다 (updated = 이미 있어서 그대로 둔 수). 운영 중인 서열·이름을 이관이 덮어쓰지 않게.
 */
@Component
@RequiredArgsConstructor
class ReferenceMigrationLoader {

    private static final Map<String, String> LABELS = Map.of(
            "type", "종류", "name", "이름", "rankOrder", "서열", "level", "호봉",
            "family", "직군", "kind", "직종", "role", "직무");

    private final PositionRepository positionRepository;
    private final GradeRepository gradeRepository;
    private final DutyRepository dutyRepository;
    private final JobCodeRepository jobCodeRepository;
    private final MigrationBatchRowRepository batchRowRepository;

    MigrationUpsertResponse upsert(String batchId, List<MigrationUpsertRequest.Row> rows) {
        Codes codes = new Codes();
        int created = 0;
        for (MigrationUpsertRequest.Row raw : rows) {
            MigrationRow row = new MigrationRow(raw, LABELS);
            String type = row.requiredString("type");
            boolean isNew = switch (type) {
                case "POSITION" -> position(batchId, row, codes);
                case "GRADE" -> grade(batchId, row, codes);
                case "DUTY" -> duty(batchId, row, codes);
                case "JOB_CODE" -> jobCode(batchId, row, codes);
                default -> throw row.error("종류 '" + type + "'을(를) 알 수 없습니다 (허용: POSITION, GRADE, DUTY, JOB_CODE)");
            };
            if (isNew) {
                created++;
            }
        }
        return new MigrationUpsertResponse(created, rows.size() - created);
    }

    private boolean position(String batchId, MigrationRow row, Codes codes) {
        String name = row.requiredString("name");
        Position found = codes.positions.get(name);
        if (found != null) {
            batchRowRepository.recordUpdateIfAbsent(batchId, Target.POSITION, found.getPositionId(), row.rowNo());
            return false;
        }
        Position saved = positionRepository.save(Position.builder().name(name).rankOrder(row.integer("rankOrder")).build());
        codes.positions.put(name, saved);
        batchRowRepository.recordCreated(batchId, Target.POSITION, saved.getPositionId(), row.rowNo());
        return true;
    }

    private boolean grade(String batchId, MigrationRow row, Codes codes) {
        String name = row.requiredString("name");
        Integer level = row.integer("level");
        if (level != null && (level < 1 || level > Short.MAX_VALUE)) {
            throw row.error(row.label("level") + " 값 " + level + "은(는) 1 이상이어야 합니다");
        }
        String key = gradeKey(name, level == null ? null : level.shortValue());
        Grade found = codes.grades.get(key);
        if (found != null) {
            batchRowRepository.recordUpdateIfAbsent(batchId, Target.GRADE, found.getGradeId(), row.rowNo());
            return false;
        }
        Grade saved = gradeRepository.save(Grade.builder().name(name).level(level == null ? null : level.shortValue()).build());
        codes.grades.put(key, saved);
        batchRowRepository.recordCreated(batchId, Target.GRADE, saved.getGradeId(), row.rowNo());
        return true;
    }

    private boolean duty(String batchId, MigrationRow row, Codes codes) {
        String name = row.requiredString("name");
        Duty found = codes.duties.get(name);
        if (found != null) {
            batchRowRepository.recordUpdateIfAbsent(batchId, Target.DUTY, found.getDutyId(), row.rowNo());
            return false;
        }
        Duty saved = dutyRepository.save(Duty.builder().name(name).build());
        codes.duties.put(name, saved);
        batchRowRepository.recordCreated(batchId, Target.DUTY, saved.getDutyId(), row.rowNo());
        return true;
    }

    /** 직군·직종은 없을 때만 만들고 행 번호 없이 기록한다 (대사에는 직무 행만 센다) */
    private boolean jobCode(String batchId, MigrationRow row, Codes codes) {
        JobCode family = codes.jobCode(batchId, JobCodeType.FAMILY, row.requiredString("family"), null);
        JobCode kind = codes.jobCode(batchId, JobCodeType.KIND, row.requiredString("kind"), family);
        String role = row.requiredString("role");
        JobCode found = codes.jobCodes.get(jobKey(JobCodeType.ROLE, role, kind));
        if (found != null) {
            batchRowRepository.recordUpdateIfAbsent(batchId, Target.JOB_CODE, found.getJobCodeId(), row.rowNo());
            return false;
        }
        JobCode saved = codes.createJobCode(JobCodeType.ROLE, role, kind);
        batchRowRepository.recordCreated(batchId, Target.JOB_CODE, saved.getJobCodeId(), row.rowNo());
        return true;
    }

    private static String gradeKey(String name, Short level) {
        return name + "|" + level;
    }

    private static String jobKey(JobCodeType type, String name, JobCode parent) {
        return type + "|" + (parent == null ? null : parent.getJobCodeId()) + "|" + name;
    }

    /** 한 요청 동안 쓰는 기존 코드 목록 (이름이 겹치는 기존 행이 있으면 먼저 것으로 본다) */
    private final class Codes {

        private final Map<String, Position> positions = new HashMap<>();
        private final Map<String, Grade> grades = new HashMap<>();
        private final Map<String, Duty> duties = new HashMap<>();
        private final Map<String, JobCode> jobCodes = new HashMap<>();

        private Codes() {
            positionRepository.findAll().forEach(p -> positions.putIfAbsent(trim(p.getName()), p));
            gradeRepository.findAll().forEach(g -> grades.putIfAbsent(gradeKey(trim(g.getName()), g.getLevel()), g));
            dutyRepository.findAll().forEach(d -> duties.putIfAbsent(trim(d.getName()), d));
            jobCodeRepository.findAll().forEach(c -> jobCodes.putIfAbsent(jobKey(c.getType(), trim(c.getName()), c.getParent()), c));
        }

        private JobCode jobCode(String batchId, JobCodeType type, String name, JobCode parent) {
            JobCode found = jobCodes.get(jobKey(type, name, parent));
            if (found != null) {
                return found;
            }
            JobCode saved = createJobCode(type, name, parent);
            batchRowRepository.recordCreated(batchId, Target.JOB_CODE, saved.getJobCodeId(), null);
            return saved;
        }

        private JobCode createJobCode(JobCodeType type, String name, JobCode parent) {
            JobCode saved = jobCodeRepository.save(JobCode.builder().type(type).name(name).parent(parent).build());
            jobCodes.put(jobKey(type, name, parent), saved);
            return saved;
        }

        private static String trim(String s) {
            return s == null ? null : s.trim();
        }
    }
}
