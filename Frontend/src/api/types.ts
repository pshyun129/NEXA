// 서비스 공통 봉투(요구사항 1.3)
export interface ApiEnvelope<T> {
  success: boolean;
  /** details: 오류별 추가 정보(선택). 예: AUTH_010 → retryAfterSeconds·lockedUntil, AUTH_005 → remainingAttempts */
  error: { code: string; message: string; details?: Record<string, unknown> | null } | null;
  data: T;
}

export type Role = 'USER' | 'ADMIN';
export type EmploymentStatus = 'EMPLOYED' | 'ON_LEAVE' | 'TERMINATED';

// user-service EmployeeInfoResponse
export interface Employee {
  empId: number;
  empNo: string | null;
  name: string;
  loginId: string;
  role: Role;
  deptId: number;
  positionId: number;
  gradeId: number;
  dutyId: number;
  empType: string | null;
  hireType: string | null;
  hireDate: string | null;
  status: EmploymentStatus;
  /** 소속 이름(user-service EmployeeInfoAssembler). 참조를 못 찾으면 null */
  deptName: string | null;
  positionName: string | null;
  gradeName: string | null;
  dutyName: string | null;
}

export interface LoginResponse {
  employeeId: number;
  deptName: string;
  gradeName: string;
}
