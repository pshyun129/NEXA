import { Button, Drawer, Tabs } from 'antd';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Card, PageHeader } from '@/ui/primitives';
import { BalanceList, useNoPolicy } from '@/leave/Balances';
import { LeaveApply } from '@/leave/Apply';
import { LeaveRequestList, LeaveRequestView } from '@/leave/Requests';
import { LeaveLedger } from '@/leave/Ledger';
import { PolicyCard } from '@/leave/PolicyCard';
import { APPLY_PATH, LEAVE_PATH, leaveRequestHref } from '@/leave/links';

/**
 * 휴가(P1, 설계 6.3·9장): 현황(잔여 + 내 신청) · 발생·변동 내역 탭, 신청은 /leave/new, 상세는 /leave/requests/:id Drawer.
 * 회수는 결재 문서 회수로 한다(휴가 신청 자체의 회수 API는 없다).
 */
export function LeavePage() {
  const { pathname } = useLocation();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const noPolicy = useNoPolicy();
  const tab = params.get('tab') === 'ledger' ? 'ledger' : '';
  const qs = params.toString() ? `?${params.toString()}` : '';

  if (pathname === APPLY_PATH) {
    return (
      <>
        <PageHeader title="휴가 신청" />
        <LeaveApply />
      </>
    );
  }

  const m = /^\/leave\/requests\/(\d+)$/.exec(pathname);
  const openId = m ? Number(m[1]) : null;
  const open = (id: number) => navigate(`${leaveRequestHref(id)}${qs}`);
  const close = () => navigate(`${LEAVE_PATH}${qs}`);

  return (
    <>
      <PageHeader
        title="휴가"
        actions={!noPolicy && <Link to={APPLY_PATH}><Button type="primary">휴가 신청</Button></Link>}
      />
      <Tabs
        activeKey={tab}
        onChange={(k) => navigate(k ? `${LEAVE_PATH}?tab=${k}` : LEAVE_PATH)}
        items={[{ key: '', label: '현황' }, { key: 'ledger', label: '발생·변동 내역' }]}
      />
      {tab === '' ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 2fr) minmax(300px, 1fr)', gap: 16, alignItems: 'start' }}>
            <Card title="휴가 잔여"><BalanceList /></Card>
            <PolicyCard />
          </div>
          <Card title="내 휴가 신청" flush><LeaveRequestList onOpen={open} /></Card>
        </div>
      ) : (
        <LeaveLedger onOpenRequest={open} />
      )}
      <Drawer open={openId != null} onClose={close} width={480} title="휴가 신청" destroyOnHidden>
        {openId != null && <LeaveRequestView key={openId} id={openId} />}
      </Drawer>
    </>
  );
}
