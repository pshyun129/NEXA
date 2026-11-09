import { useState } from 'react';
import { Button, DatePicker, Progress } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useAllEmployees } from '@/api/org';
import { useGeneratePayslip } from '@/api/pay';
import { isApiError } from '@/api/ApiError';
import type { Employee } from '@/api/types';
import { Card, DotChip, PageHeader } from '@/ui/primitives';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { DataTable } from '@/ui/DataTable';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { useNotify } from '@/ui/useNotify';
import { formatDateShort, formatMoney, formatNumber, nowKst } from '@/format';
import s from '@/admin/admin.module.css';

/** 사원별 생성 결과. 화면에만 두고(새로고침하면 사라짐) 이미 있는 명세서는 409로 알게 된다 */
type RowResult =
  | { kind: 'pending' }
  | { kind: 'created'; netPay: number }
  | { kind: 'exists' }
  | { kind: 'failed'; message: string; code: string };

const PAYSLIP_EXISTS = 'PAY_003';

function ResultChip({ result }: { result: RowResult | undefined }) {
  if (!result) return <span className={s.muted}>—</span>;
  switch (result.kind) {
    case 'pending': return <DotChip tone="info">생성 중</DotChip>;
    case 'created': return <DotChip tone="success">생성 · {formatMoney(result.netPay)}</DotChip>;
    case 'exists': return <DotChip tone="neutral">이미 있음</DotChip>;
    case 'failed': return <span className={s.flagDanger} title={result.code}>{result.message}</span>;
  }
}

/**
 * 관리자 급여(인사 관리자 ADMIN): 월을 골라 재직자 명세서를 만든다.
 * 행마다 [생성], 위에 [전체 생성](재직자를 한 명씩 차례로 생성, 이미 있는 사원은 이미 있음으로 표시).
 * 기본 월은 지난달(그 달 근태가 끝난 뒤 만든다)
 */
export function PayAdminPage() {
  const notify = useNotify();
  const emps = useAllEmployees();
  const generate = useGeneratePayslip();
  const [month, setMonth] = useState(nowKst().subtract(1, 'month').format('YYYY-MM'));
  const [results, setResults] = useState<Record<string, Record<number, RowResult>>>({});
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);

  const rows = (emps.data ?? []).filter((e) => e.status === 'EMPLOYED');
  const monthResults = results[month] ?? {};
  const setResult = (empId: number, result: RowResult) =>
    setResults((prev) => ({ ...prev, [month]: { ...(prev[month] ?? {}), [empId]: result } }));

  /** 한 명 생성. 결과를 행에 남기고 상태를 돌려준다(전체 생성 집계용) */
  const generateOne = async (emp: Employee): Promise<RowResult['kind']> => {
    setResult(emp.empId, { kind: 'pending' });
    try {
      const payslip = await generate.mutateAsync({ empId: emp.empId, month });
      setResult(emp.empId, { kind: 'created', netPay: payslip.netPay });
      return 'created';
    } catch (e) {
      if (isApiError(e) && e.code === PAYSLIP_EXISTS) {
        setResult(emp.empId, { kind: 'exists' });
        return 'exists';
      }
      setResult(emp.empId, {
        kind: 'failed',
        message: isApiError(e) ? e.message : '서버에 연결하지 못했습니다.',
        code: isApiError(e) ? e.code : 'NETWORK',
      });
      return 'failed';
    }
  };

  const generateAll = async () => {
    const counts = { created: 0, exists: 0, failed: 0, pending: 0 };
    setProgress({ done: 0, total: rows.length });
    for (const [i, emp] of rows.entries()) {
      counts[await generateOne(emp)] += 1;
      setProgress({ done: i + 1, total: rows.length });
    }
    setProgress(null);
    const summary = `생성 ${counts.created}명 · 이미 있음 ${counts.exists}명 · 실패 ${counts.failed}명`;
    if (counts.failed > 0) {
      notify.info(`${monthLabel(month)} 급여명세서 생성을 마쳤습니다`, `${summary}. 실패한 사원은 표에서 사유를 확인하세요.`);
    } else {
      notify.success(`${monthLabel(month)} 급여명세서를 만들었습니다`, summary);
    }
  };

  const running = progress != null;
  const cols: ColumnsType<Employee> = [
    { title: '이름', dataIndex: 'name', width: 120, render: (v: string) => <b>{v}</b> },
    { title: '사번', dataIndex: 'empNo', width: 100, render: (v: string | null) => <span className="nx-mono">{v ?? '—'}</span> },
    { title: '부서', dataIndex: 'deptName', render: (v: string | null) => v ?? '—' },
    { title: '직급', dataIndex: 'gradeName', width: 80, render: (v: string | null) => v ?? '—' },
    { title: '입사일', dataIndex: 'hireDate', width: 110, render: (v: string | null) => <span className="nx-num">{formatDateShort(v)}</span> },
    { title: '결과', key: 'result', render: (_, r) => <ResultChip result={monthResults[r.empId]} /> },
    {
      title: '', key: 'act', width: 90,
      render: (_, r) => {
        const result = monthResults[r.empId];
        const done = result?.kind === 'created' || result?.kind === 'exists';
        return (
          <Button size="small" disabled={running || done || result?.kind === 'pending'}
                  onClick={(e) => { e.stopPropagation(); void generateOne(r); }}>
            생성
          </Button>
        );
      },
    },
  ];

  return (
    <>
      <PageHeader title="급여" description="월을 골라 재직자의 급여명세서를 만듭니다. 이미 만든 사원은 건너뜁니다." />
      <Card title="급여명세서 생성" meta={<span className={s.muted}>재직자 {formatNumber(rows.length)}명</span>}>
        {emps.error ? <ErrorPanel error={emps.error} onRetry={() => emps.refetch()} /> : (
          <>
            <div className={s.row} style={{ marginBottom: 12 }}>
              <DatePicker picker="month" value={dayjs(`${month}-01`)} allowClear={false} aria-label="급여 월"
                          disabled={running} onChange={(v) => v && setMonth(v.format('YYYY-MM'))} />
              <ConfirmAction
                title={`${monthLabel(month)} 급여명세서를 만듭니다`}
                confirmLabel="전체 생성"
                onConfirm={() => { void generateAll(); }}
                trigger={(open) => (
                  <Button type="primary" onClick={open} disabled={running || rows.length === 0} loading={running}>
                    전체 생성
                  </Button>
                )}
              >
                재직자 {formatNumber(rows.length)}명의 {monthLabel(month)} 명세서를 차례로 만듭니다.
                이미 명세서가 있는 사원은 건너뛰고, 만든 명세서는 사원 급여 화면에 바로 보입니다.
              </ConfirmAction>
              {progress && (
                <Progress percent={Math.round((progress.done / Math.max(progress.total, 1)) * 100)}
                          size="small" style={{ width: 200 }}
                          format={() => `${progress.done}/${progress.total}`} />
              )}
            </div>
            <DataTable<Employee>
              data={emps.data ? rows : undefined}
              loading={emps.isPending}
              rowKey="empId"
              columns={cols}
              searchText={(e) => [e.name, e.empNo, e.deptName, e.gradeName].filter(Boolean).join(' ')}
              searchPlaceholder="이름·사번·부서 검색"
              emptyTitle="재직 중인 사원이 없습니다"
            />
          </>
        )}
      </Card>
    </>
  );
}

const monthLabel = (month: string) => dayjs(`${month}-01`).format('YYYY년 M월');
