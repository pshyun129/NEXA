import { create } from 'zustand';
import type { Employee } from '@/api/types';

// 1주차에 쓰던 localStorage 키(employeeId 기반 복원). /me로 바꾸면서 남은 값을 지운다.
try { localStorage.removeItem('nx.employeeId'); } catch { /* 저장소 접근 불가 환경 */ }

/**
 * unknown: 아직 확인 안 함(앱 시작, 로그인 직후) → SessionGate가 GET /employees/me로 확인한다
 * authenticated: /me 성공
 * anonymous: /me 실패(쿠키 없음·만료·퇴직) 또는 로그아웃
 */
export type SessionStatus = 'unknown' | 'authenticated' | 'anonymous';

interface SessionState {
  status: SessionStatus;
  me: Employee | null;
  /** 로그인 성공 직후: 신원은 /me로 다시 확인한다(토큰의 주체만 믿는다) */
  markLoggedIn: () => void;
  setMe: (me: Employee) => void;
  clear: () => void;
}

// 신원은 access 토큰의 주체로만 정한다(GET /api/v1/employees/me). 브라우저 저장소에 ID를 두지 않는다.
export const useSession = create<SessionState>((set) => ({
  status: 'unknown',
  me: null,
  markLoggedIn: () => set({ status: 'unknown', me: null }),
  setMe: (me) => set({ me, status: 'authenticated' }),
  clear: () => set({ status: 'anonymous', me: null }),
}));

/** 로그인한 본인 사원 ID */
export const useMyId = () => useSession((s) => s.me?.empId ?? null);

export const isAdmin = (me: Employee | null) => me?.role === 'ADMIN';
