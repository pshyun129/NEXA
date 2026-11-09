// 백엔드 갭 화면 플래그(설계 9장 표, 요구사항 6장과 1:1). MSW와 연결하지 않는 정적 boolean이다.
// 블로커가 해소되면 true로 바꾼다.
export const features = {
  /** #2·#3 휴가 공휴일 경로, 결재 연동 설정. 2026-11-09 서버 연동 켜짐 확인(꺼져 있으면 제출이 LEAVE_016으로 거절된다) */
  leaveSubmit: true,
  /** #4 grant 생성 기능 없음. 2026-11-09 수동 지급 API로 해소 */
  leaveBalance: true,
  /** #5 직위·직급·직무 목록 API 없음 */
  refCodes: false,
} as const;

export type FeatureFlag = keyof typeof features;

export const featureNotice: Record<FeatureFlag, { title: string; description: string }> = {
  leaveSubmit: { title: '제출 연동 대기', description: '휴가 제출은 백엔드 결재 연동이 준비되면 열립니다.' },
  leaveBalance: { title: '발생 내역 연동 대기', description: '휴가 발생 기능이 준비되면 잔여 일수가 표시됩니다.' },
  refCodes: { title: '코드 목록 연동 대기', description: '직위·직급·직무 목록 API가 준비되면 이름으로 표시됩니다.' },
};
