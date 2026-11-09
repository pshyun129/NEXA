import { useState } from 'react';
import { Alert, Button, DatePicker, Drawer, Select, Skeleton, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useSearchParams } from 'react-router-dom';
import {
  payslipPdfUrl, useContract, usePayslip, usePayslips, useRetirementEstimate,
  type PayItem, type PayslipListItem,
} from '@/api/pay';
import { isApiError } from '@/api/ApiError';
import { useSession } from '@/auth/session';
import { Card, PageHeader } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { formatDate, formatDurationShort, formatMoney, formatNumber, nowKst } from '@/format';
import s from './pay.module.css';

/** 예상된 업무 오류(PAY_002 직급 기본급 없음, PAY_007 명세서 없음)는 서버 문구를 안내로 보여 준다 */
function ExpectedError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (isApiError(error) && (error.code === 'PAY_002' || error.code === 'PAY_007')) {
    return <Alert type="info" showIcon message={error.message} />;
  }
  return <ErrorPanel error={error} onRetry={onRetry} />;
}

/** 내 직급 급여 정보(토큰 직급 기준 기본급, 개인 계약 아님) */
function ContractCard() {
  const q = useContract();
  const gradeName = useSession((st) => st.me?.gradeName);
  return (
    <Card title="내 직급 급여" meta={gradeName && <span className={s.muted}>{gradeName}</span>}>
      {q.isError ? <ExpectedError error={q.error} onRetry={() => q.refetch()} />
        : !q.data ? <Skeleton active={false} paragraph={{ rows: 2 }} />
        : (
          <dl className={s.props}>
            <dt>월 기본급</dt><dd className={`${s.amount} nx-num`}>{formatMoney(q.data.monthlyBasePay)}</dd>
            <dt>연 환산</dt><dd className="nx-num">{formatMoney(q.data.annualSalary)} <span className={s.muted}>(월 기본급 × 12)</span></dd>
          </dl>
        )}
      <p className={s.muted}>직급별 기본급 기준이며, 수당·상여는 명세서에서 확인하세요.</p>
    </Card>
  );
}

/** 퇴직금 예상: 퇴직일(마지막 근무일 다음 날)을 고르면 계산 */
function RetirementCard() {
  const [resignDate, setResignDate] = useState<string | null>(null);
  const q = useRetirementEstimate(resignDate);
  const r = q.data;
  return (
    <Card title="퇴직금 예상">
      <div className={s.row}>
        <DatePicker value={resignDate ? dayjs(resignDate) : null} onChange={(v) => setResignDate(v ? v.format('YYYY-MM-DD') : null)}
                    placeholder="퇴직일" aria-label="퇴직일" />
        <span className={s.muted}>퇴직일은 마지막 근무일 다음 날입니다.</span>
      </div>
      {!resignDate ? null
        : q.isError ? <ExpectedError error={q.error} onRetry={() => q.refetch()} />
        : !r ? <Skeleton active={false} paragraph={{ rows: 3 }} />
        : (
          <>
            <div className={`${s.kpi} nx-num`}>{formatMoney(r.retirementPay)}</div>
            <dl className={s.props}>
              <dt>근속</dt><dd className="nx-num">{formatDate(r.settlementStart)} ~ {formatDate(r.settlementEnd)} · {formatNumber(r.serviceDays)}일</dd>
              <dt>1일 평균임금</dt><dd className="nx-num">{formatMoney(r.dailyAverageWage)}</dd>
              <dt>3개월 급여</dt>
              <dd className="nx-num">{formatMoney(r.threeMonthPay)} <span className={s.muted}>({formatDate(r.periodStart)} ~ {formatDate(r.periodEnd)}, {r.periodDays}일)</span></dd>
              <dt>상여·연차</dt><dd className="nx-num">상여 {formatMoney(r.annualBonus)} · 연차수당 {formatMoney(r.annualLeavePay)}</dd>
            </dl>
            {r.serviceDays < 365 && <Alert type="info" showIcon message="근속 1년 미만은 퇴직금이 없습니다." />}
            {r.basePayslipCount < 3 && (
              <p className={s.muted}>최근 3개월 중 명세서가 {r.basePayslipCount}개뿐이라 월평균 × 3으로 환산했습니다.</p>
            )}
          </>
        )}
    </Card>
  );
}

function ItemTable({ title, items, total }: { title: string; items: PayItem[]; total: number }) {
  return (
    <table className={s.items}>
      <thead><tr><th>{title}</th><th className="nx-num">금액</th></tr></thead>
      <tbody>
        {items.map((i) => (
          <tr key={i.itemCode} className={i.amount === 0 ? s.zero : ''}><td>{i.name}</td><td className="nx-num">{formatNumber(i.amount)}</td></tr>
        ))}
      </tbody>
      <tfoot><tr><th>합계</th><th className="nx-num">{formatNumber(total)}</th></tr></tfoot>
    </table>
  );
}

function PayslipView({ id }: { id: number }) {
  const q = usePayslip(id);
  if (q.isError) return <ErrorPanel error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  if (!d) return <Skeleton active={false} paragraph={{ rows: 10 }} />;
  const wt = d.workTime;
  return (
    <div className={s.stack}>
      <div className={s.netRow}>
        <span className={s.muted}>실지급액</span>
        <span className={`${s.kpi} nx-num`}>{formatMoney(d.netPay)}</span>
        <span className={s.muted}>지급일 {formatDate(d.payDate)}</span>
      </div>
      <dl className={s.props}>
        <dt>사원</dt><dd>{d.employee.empName} · {d.employee.deptName}{d.employee.positionName && ` · ${d.employee.positionName}`}</dd>
        <dt>입사일</dt><dd className="nx-num">{formatDate(d.employee.hireDate)}</dd>
        <dt>근로</dt>
        <dd className="nx-num">
          근무 {wt.workDays}일 · 총 {formatDurationShort(wt.totalMin)} (기본 {formatDurationShort(wt.regularMin)}
          {wt.otExtendedMin > 0 && ` · 연장 ${formatDurationShort(wt.otExtendedMin)}`}
          {wt.otNightMin > 0 && ` · 야간 ${formatDurationShort(wt.otNightMin)}`}
          {wt.otHolidayMin > 0 && ` · 휴일 ${formatDurationShort(wt.otHolidayMin)}`}
          {wt.leaveMin > 0 && ` · 휴가 ${formatDurationShort(wt.leaveMin)}`})
        </dd>
      </dl>
      <div className={s.itemsGrid}>
        <ItemTable title="지급" items={d.earnings} total={d.totalPay} />
        <ItemTable title="공제" items={d.deductions} total={d.totalDeduction} />
      </div>
      <p className={s.muted}>명세서는 만들어진 시점의 사원 정보와 근태 집계로 계산됩니다.</p>
    </div>
  );
}

/** 명세서 목록(연도별, 최근 월부터) + 연간 합계. 행을 누르면 상세 Drawer(?payslip=id) */
function Payslips() {
  const [params, setParams] = useSearchParams();
  const thisYear = nowKst().year();
  const year = Number(params.get('year')) || thisYear;
  const openId = Number(params.get('payslip')) || null;
  const q = usePayslips(year);
  const set = (next: Record<string, string | null>) => {
    const p = new URLSearchParams(params);
    Object.entries(next).forEach(([k, v]) => (v == null ? p.delete(k) : p.set(k, v)));
    setParams(p, { replace: true });
  };
  const cols: ColumnsType<PayslipListItem> = [
    { title: '급여 월', dataIndex: 'payMonth', width: 100, render: (m: number) => `${year}년 ${m}월` },
    { title: '지급일', dataIndex: 'payDate', width: 140, render: (v: string) => <span className="nx-num">{formatDate(v)}</span> },
    { title: '지급합계', dataIndex: 'totalPay', align: 'right', render: (v: number) => <span className="nx-num">{formatNumber(v)}</span> },
    { title: '공제합계', dataIndex: 'totalDeduction', align: 'right', render: (v: number) => <span className="nx-num">{formatNumber(v)}</span> },
    { title: '실지급액', dataIndex: 'netPay', align: 'right', render: (v: number) => <b className="nx-num">{formatNumber(v)}</b> },
  ];
  return (
    <Card
      title="급여명세서"
      meta={
        <Select<number> value={year} style={{ width: 110 }} aria-label="연도"
                        options={Array.from({ length: 5 }, (_, i) => ({ value: thisYear - i, label: `${thisYear - i}년` }))}
                        onChange={(y) => set({ year: y === thisYear ? null : String(y), payslip: null })} />
      }
    >
      {q.isError ? <ErrorPanel error={q.error} onRetry={() => q.refetch()} /> : (
        <Table<PayslipListItem>
          rowKey="payslipId"
          size="middle"
          loading={q.isPending}
          pagination={false}
          columns={cols}
          dataSource={q.data?.payslips}
          locale={{ emptyText: `${year}년 명세서가 없습니다` }}
          onRow={(r) => ({
            onClick: () => set({ payslip: String(r.payslipId) }),
            onKeyDown: (e) => { if (e.key === 'Enter') set({ payslip: String(r.payslipId) }); },
            tabIndex: 0,
            style: { cursor: 'pointer' },
          })}
          summary={() => q.data && q.data.payslips.length > 0 && (
            <Table.Summary.Row>
              <Table.Summary.Cell index={0} colSpan={2}><b>{year}년 합계</b></Table.Summary.Cell>
              <Table.Summary.Cell index={2} align="right"><span className="nx-num">{formatNumber(q.data.total.totalPay)}</span></Table.Summary.Cell>
              <Table.Summary.Cell index={3} align="right"><span className="nx-num">{formatNumber(q.data.total.totalDeduction)}</span></Table.Summary.Cell>
              <Table.Summary.Cell index={4} align="right"><b className="nx-num">{formatNumber(q.data.total.netPay)}</b></Table.Summary.Cell>
            </Table.Summary.Row>
          )}
        />
      )}
      <Drawer
        open={openId != null}
        onClose={() => set({ payslip: null })}
        width={640}
        destroyOnHidden
        title={openId ? `${year}년 ${q.data?.payslips.find((p) => p.payslipId === openId)?.payMonth ?? ''}월 급여명세서` : ''}
        extra={openId && <Button href={payslipPdfUrl(openId)} download>PDF 내려받기</Button>}
      >
        {openId != null && <PayslipView id={openId} />}
      </Drawer>
    </Card>
  );
}

/** 급여(P1, 요구사항 3.6): 명세서 목록·상세(PDF), 내 직급 급여, 퇴직금 예상 */
export function PayPage() {
  return (
    <>
      <PageHeader title="급여" />
      <div className={s.stack}>
        <div className={s.cards}>
          <ContractCard />
          <RetirementCard />
        </div>
        <Payslips />
      </div>
    </>
  );
}
