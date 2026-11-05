import type { MigrationEntity } from '@/api/migration';

// 매핑 대상 필드. migration-service `validation/schema/EntitySchemas.java`(origin/develop 6e630e3)를 그대로 옮긴 것이다.
// 스키마 메타 API가 없어서 복제한다(S15P3log/frontend-backend-gaps.md B9). 서버 정의가 바뀌면 같이 바꾸고,
// API가 생기면 이 파일을 지운다. 순서도 서버와 같다.

export type FieldType = 'STRING' | 'DATE' | 'NUMBER' | 'EMAIL' | 'PHONE' | 'ENUM';
export interface TargetField { field: string; label: string; type: FieldType; required?: boolean; key?: boolean }

export const ENTITY_SCHEMAS: Record<MigrationEntity, TargetField[]> = {
  HR_ORG: [
    { field: 'deptCode', label: '부서코드', type: 'STRING', key: true },
    { field: 'name', label: '부서명', type: 'STRING', required: true },
    { field: 'parentDeptCode', label: '상위부서', type: 'STRING' },
    { field: 'sortOrder', label: '정렬순서', type: 'NUMBER' },
    { field: 'deptType', label: '부서유형', type: 'ENUM' },
    { field: 'headEmpNo', label: '부서장 사번', type: 'STRING' },
  ],
  HR_EMPLOYEE: [
    { field: 'empNo', label: '사번', type: 'STRING', key: true },
    { field: 'name', label: '이름', type: 'STRING', required: true },
    { field: 'engName', label: '영문이름', type: 'STRING' },
    { field: 'email', label: '이메일', type: 'EMAIL', required: true },
    { field: 'dept', label: '부서', type: 'STRING', required: true },
    { field: 'position', label: '직위', type: 'STRING', required: true },
    { field: 'grade', label: '직급', type: 'STRING', required: true },
    { field: 'duty', label: '직책', type: 'STRING', required: true },
    { field: 'jobFamily', label: '직군', type: 'STRING', required: true },
    { field: 'jobKind', label: '직종', type: 'STRING', required: true },
    { field: 'jobRole', label: '직무', type: 'STRING', required: true },
    { field: 'empType', label: '직원구분', type: 'ENUM' },
    { field: 'hireType', label: '채용구분', type: 'ENUM' },
    { field: 'hireDate', label: '입사일', type: 'DATE', required: true },
    { field: 'recogHireDate', label: '인정입사일', type: 'DATE' },
    { field: 'birthDate', label: '생년월일', type: 'DATE' },
    { field: 'gender', label: '성별', type: 'ENUM' },
    { field: 'mobile', label: '휴대전화', type: 'PHONE' },
    { field: 'officePhone', label: '직통번호', type: 'PHONE' },
    { field: 'nationality', label: '국적', type: 'STRING' },
    { field: 'address', label: '주소', type: 'STRING' },
    { field: 'status', label: '재직상태', type: 'ENUM' },
    { field: 'resignDate', label: '퇴사일', type: 'DATE' },
  ],
};

export const ENTITY_LABEL: Record<MigrationEntity, string> = { HR_ORG: '조직', HR_EMPLOYEE: '사원' };
/** 적재 순서(서버 MigrationEntity 선언 순서): 조직 → 사원 */
export const ENTITY_ORDER: MigrationEntity[] = ['HR_ORG', 'HR_EMPLOYEE'];
