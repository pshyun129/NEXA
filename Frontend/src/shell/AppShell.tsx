import { useEffect, useRef, useState, type MouseEvent } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Dropdown, Tooltip } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { Icon } from '@/ui/Icon';
import { ChevronLeft, ChevronRight, LogOut } from '@/ui/icons';
import { Counter } from '@/ui/primitives';
import { useSession } from '@/auth/session';
import { authApi } from '@/auth/authApi';
import { useCan } from '@/auth/useCan';
import { adminNav, allNav, userNav, leaveAdminNav, type NavItem } from './nav';
import { useLeaveAdministrator } from '@/auth/RequireLeaveAdministrator';
import { useViewport } from './useViewport';
import { useTodoCounts } from './useTodoCounts';
import { ClockChip } from '@/attendance/Clock';
import { ShortcutHelp } from './ShortcutHelp';
import { AutomationLauncher } from './AutomationLauncher';
import { AutomationHub } from '@/automation/AutomationHub';
import { MiniRunPanel } from '@/automation/MiniRunPanel';
import { WaitingNotice } from '@/automation/WaitingNotice';
import hub from '@/automation/hub.module.css';
import { BrandMark } from '@/ui/BrandMark';
import s from './shell.module.css';

const COLLAPSE_KEY = 'nx.sidebarCollapsed';

function readCollapsed() {
  try { return localStorage.getItem(COLLAPSE_KEY) === '1'; } catch { return false; }
}

/**
 * LineSidebar(React Bits): 마우스와 가까운 메뉴일수록 오른쪽으로 밀리고 밝아진다.
 * 리렌더 없이 각 링크의 style만 바꾼다. 지금 화면 메뉴(aria-current)는 그대로 둔다.
 */
function useLineSidebar() {
  const ref = useRef<HTMLDivElement>(null);
  const reduced = typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const items = () => Array.from(ref.current?.querySelectorAll<HTMLAnchorElement>('a[data-nav]') ?? []);
  const onMouseMove = (e: MouseEvent) => {
    if (reduced) return;
    for (const a of items()) {
      if (a.getAttribute('aria-current') === 'page') { a.style.transform = ''; a.style.color = ''; continue; }
      const r = a.getBoundingClientRect();
      const k = Math.max(0, 1 - Math.abs(e.clientY - (r.top + r.height / 2)) / 100);
      a.style.transform = `translateX(${(k * 6).toFixed(1)}px)`;
      a.style.color = k > 0.55 ? '#FFFFFF' : k > 0.2 ? '#D2D3D6' : '';
    }
  };
  const onMouseLeave = () => { for (const a of items()) { a.style.transform = ''; a.style.color = ''; } };
  return { ref, onMouseMove, onMouseLeave };
}

export function AppShell() {
  const { band } = useViewport();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { me, clear } = useSession();
  const canAdmin = useCan('admin.view');
  const leaveAccess = useLeaveAdministrator();
  const canManageLeave = leaveAccess.data === true && leaveAccess.isFetchedAfterMount && !leaveAccess.isError;
  const line = useLineSidebar();

  // 모드는 URL에서 계산한다(새로고침해도 유지, 설계 5장)
  const adminMode = location.pathname === '/admin' || location.pathname.startsWith('/admin/');
  const [manualCollapsed, setManualCollapsed] = useState(readCollapsed);
  const autoCollapsed = band !== 'wide';
  const collapsed = autoCollapsed || manualCollapsed;
  const counts = useTodoCounts(adminMode);

  useEffect(() => {
    try { localStorage.setItem(COLLAPSE_KEY, manualCollapsed ? '1' : '0'); } catch { /* noop */ }
  }, [manualCollapsed]);

  // 새 WAITING이 생기면 탭 제목에 표시(설계 5장)
  useEffect(() => {
    document.title = counts.waiting ? `(${counts.waiting}) 확인 필요 · NEXA` : 'NEXA';
  }, [counts.waiting]);

  if (band === 'narrow') {
    return <div className={s.narrow}>PC(1024px 이상)에서 이용해 주세요.</div>;
  }

  const current = [...allNav]
    .sort((a, b) => b.to.length - a.to.length)
    .find((n) => (n.to === '/' ? location.pathname === '/' : location.pathname === n.to || location.pathname.startsWith(`${n.to}/`)));

  const logout = async () => {
    await authApi.logout();
    qc.clear();
    clear();
    navigate('/login', { replace: true });
  };

  const countOf = (item: NavItem) =>
    item.count === 'approval' ? counts.approval : item.count === 'waiting' ? counts.waiting : undefined;

  const renderNav = (list: NavItem[]) => list.map((item) => {
    const n = countOf(item);
    const link = (
      <NavLink
        key={item.to}
        to={item.to}
        data-nav
        end={item.to === '/' || item.to === '/admin'}
        className={({ isActive }) => [s.item, isActive ? s.itemActive : ''].join(' ')}
      >
        <Icon as={item.icon} size={20} />
        <span className={s.itemLabel}>{item.label}</span>
        {n !== undefined && n > 0 && (
          <span className={s.itemCount}><Counter value={n} tone={item.count === 'waiting' ? 'warning' : 'brand'} /></span>
        )}
      </NavLink>
    );
    return collapsed ? <Tooltip key={item.to} title={item.label} placement="right">{link}</Tooltip> : link;
  });

  return (
    <div className={s.root}>
      <aside className={[s.sidebar, collapsed ? s.collapsed : '', 'nx-sidebar--admin'].join(' ')} aria-label="주 메뉴">
        <NavLink to="/" className={s.brand} aria-label="NEXA 홈">
          <BrandMark size={collapsed ? 28 : 32} />
          {!collapsed && <span className={s.wordmark}>nexa</span>}
        </NavLink>
        {!collapsed && (
          <div className={s.workspace}>
            <span className={s.wsIcon}>N</span>
            <span>주식회사 넥사<small>NEXA ERP workspace</small></span>
          </div>
        )}
        <div className={s.navWrap} ref={line.ref} onMouseMove={line.onMouseMove} onMouseLeave={line.onMouseLeave}>
          {!collapsed && <div className={s.navLabel}>WORKSPACE</div>}
          <nav className={s.nav} aria-label="사원 메뉴">{renderNav(userNav)}</nav>
          {(canAdmin || canManageLeave) && (
            <>
              {collapsed ? <div className={s.navDivider} /> : <div className={`${s.navLabel} ${s.navLabelSecond}`}>ADMIN</div>}
              <nav className={s.nav} aria-label="관리자 메뉴">{renderNav([...(canAdmin ? adminNav : []), ...(canManageLeave ? leaveAdminNav : [])])}</nav>
            </>
          )}
        </div>
        <div className={s.foot}>
          {!collapsed && !adminMode && (counts.approval !== undefined || counts.waiting !== undefined) && (
            <div className={s.help}>
              이번 주 할 일
              <strong>
                {counts.approval !== undefined && <>결재 <span className="nx-num">{counts.approval}</span>건</>}
                {counts.approval !== undefined && counts.waiting !== undefined && ' · '}
                {counts.waiting !== undefined && <>확인 대기 <span className="nx-num">{counts.waiting}</span>건</>}
              </strong>
            </div>
          )}
          {!autoCollapsed && (
            <button type="button" className={s.collapseBtn} onClick={() => setManualCollapsed((v) => !v)}
                    aria-label={collapsed ? '메뉴 펼치기' : '메뉴 접기'}>
              <Icon as={collapsed ? ChevronRight : ChevronLeft} size={16} />
              <span className={s.collapseLabel}>메뉴 접기</span>
            </button>
          )}
        </div>
      </aside>

      <div className={s.main}>
        <header className={s.topbar}>
          <div className={s.crumbs}>
            {adminMode ? 'ADMIN' : 'NEXA'} <span className={s.crumbSep}>/</span> <b>{current?.label ?? ''}</b>
          </div>
          <div className={s.topRight}>
            <AutomationLauncher />
            {!adminMode && <ClockChip />}
            {!adminMode && counts.approval !== undefined && (
              <NavLink to="/approvals" className={`${s.todo} nx-hit24`}>
                결재 <Counter value={counts.approval} tone="brand" label="결재" />
              </NavLink>
            )}
            <span className={s.divider} aria-hidden />
            <Dropdown
              trigger={['click']}
              menu={{ items: [{ key: 'logout', label: '로그아웃', icon: <Icon as={LogOut} size={16} />, onClick: logout }] }}
            >
              <button type="button" className={s.profile} aria-label="프로필 메뉴">
                <span className={s.avatar} aria-hidden>{me?.name?.slice(0, 1) ?? '?'}</span>
                <span className={s.profileText}>
                  <span>{me?.name}</span>
                  {me?.deptName && <span className={s.profileSub}>{me.deptName}</span>}
                </span>
              </button>
            </Dropdown>
          </div>
        </header>
        <main className={`${s.content} nx-scroll`}>
          <Outlet />
        </main>
        <footer className={s.pageFoot}><span>© 2026 NEXA</span><span>SSAFY 15기 자율 프로젝트 D107</span></footer>
        <ShortcutHelp />
        {/* 업무 자동화: 큰 창 + 오른쪽 위 진행 창·확인 알림(어느 화면에서든) */}
        <AutomationHub />
        <div className={hub.stack}>
          <MiniRunPanel />
          <WaitingNotice />
        </div>
      </div>
    </div>
  );
}
