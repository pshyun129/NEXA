import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import { SessionGate } from '@/auth/SessionGate';
import { RequireAdmin } from '@/auth/RequireAdmin';
import { AppShell } from '@/shell/AppShell';
import { LoginPage } from '@/pages/login/LoginPage';
import { HomePage } from '@/pages/home/HomePage';
import { Placeholder } from '@/pages/placeholder/Placeholder';
import { AttendancePage } from '@/pages/attendance/AttendancePage';
import { ApprovalsPage } from '@/pages/approvals/ApprovalsPage';
import { AutomationPage } from '@/pages/automation/AutomationPage';
import { MigrationPage } from '@/pages/admin/MigrationPage';

// 캔버스(@xyflow)는 무거워서 쓰는 화면에서만 불러온다
const DevKitPage = lazy(() => import('@/pages/dev/kit/DevKitPage').then((m) => ({ default: m.DevKitPage })));

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      {/* /dev/kit은 백엔드 없이도 볼 수 있게 셸 밖에 둔다 */}
      <Route path="/dev/kit" element={<div style={{ padding: 24, maxWidth: 1600, margin: '0 auto' }}><Suspense fallback={null}><DevKitPage /></Suspense></div>} />
      <Route element={<SessionGate><AppShell /></SessionGate>}>
        <Route index element={<HomePage />} />
        <Route path="attendance/*" element={<AttendancePage />} />
        <Route path="leave/*" element={<Placeholder title="휴가" week="P1 · 이번 범위 제외(2026-11-05 결정)" />} />
        <Route path="approvals" element={<ApprovalsPage />} />
        <Route path="approvals/:docId" element={<ApprovalsPage />} />
        <Route path="pay/*" element={<Placeholder title="급여" week="P1" />} />
        <Route path="automation/*" element={<AutomationPage />} />
        <Route path="org/*" element={<Placeholder title="조직도" week="P1" />} />
        <Route path="admin" element={<RequireAdmin><Placeholder title="관리 홈" week="5주차" /></RequireAdmin>} />
        <Route path="admin/hr/*" element={<RequireAdmin><Placeholder title="인사" week="5주차(P1)" /></RequireAdmin>} />
        <Route path="admin/migration" element={<RequireAdmin><MigrationPage /></RequireAdmin>} />
        <Route path="admin/migration/:id" element={<RequireAdmin><MigrationPage /></RequireAdmin>} />
        <Route path="admin/builder/*" element={<RequireAdmin><Placeholder title="자동화 빌더" week="5주차" /></RequireAdmin>} />
        <Route path="*" element={<Placeholder title="페이지를 찾을 수 없습니다" week="—" />} />
      </Route>
    </Routes>
  );
}
