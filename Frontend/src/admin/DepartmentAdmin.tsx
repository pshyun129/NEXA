import { useMemo, useState } from 'react';
import { Alert, Button, Select, Skeleton, Tree } from 'antd';
import type { DataNode } from 'antd/es/tree';
import {
  useAllEmployees, useAssignHead, useChangeDeptType, useClearHead, useDepartments,
  DEPT_TYPE_LABEL, type DeptType, type Department,
} from '@/api/org';
import type { Employee } from '@/api/types';
import { Card, EmptyState } from '@/ui/primitives';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { StatusBadge } from '@/ui/StatusBadge';
import { ConfirmAction } from '@/ui/ConfirmAction';
import { useNotify } from '@/ui/useNotify';
import { Icon } from '@/ui/Icon';
import { Network, TriangleAlert } from '@/ui/icons';
import { formatNumber } from '@/format';
import s from './admin.module.css';

// 관리자 부서 트리·부서장·유형(P1, 요구사항 4.1). 결재선 규칙은 approval-service ApproverResolver 기준:
// 공석(또는 재직 아닌 부서장)은 건너뛰고 상위 부서장이 결재한다. 전결 단계 이상의 부서장이 위로 끝까지 없으면 APPROVAL_022.
// 유형이 비면 팀으로 본다(ApprovalLevel.ofDeptType).

const TYPE_LABEL = DEPT_TYPE_LABEL;
/** 높은 단계부터(서버 DeptType 선언 순서) */
const TYPE_ORDER: DeptType[] = ['COMPANY', 'DIVISION', 'TEAM'];
const rank = (t: DeptType) => TYPE_ORDER.indexOf(t);

interface Org {
  byId: Map<number, Department>;
  children: Map<number | null, Department[]>;
  emp: Map<number, Employee>;
}

function buildOrg(depts: Department[], emps: Employee[]): Org {
  const byId = new Map(depts.map((d) => [d.deptId, d]));
  const children = new Map<number | null, Department[]>();
  for (const d of depts) {
    // 상위가 목록에 없으면 최상위로 둔다
    const p = d.parentDeptId != null && byId.has(d.parentDeptId) ? d.parentDeptId : null;
    children.set(p, [...(children.get(p) ?? []), d]);
  }
  for (const list of children.values()) list.sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.name.localeCompare(b.name, 'ko'));
  return { byId, children, emp: new Map(emps.map((e) => [e.empId, e])) };
}

/** 부서와 그 하위 부서 ID */
function subtree(org: Org, root: number): Set<number> {
  const out = new Set<number>();
  const walk = (id: number) => {
    if (out.has(id)) return;
    out.add(id);
    (org.children.get(id) ?? []).forEach((c) => walk(c.deptId));
  };
  walk(root);
  return out;
}

/** 결재선에서 실제로 결재하는 부서장(재직 중일 때만) */
const activeHead = (org: Org, d: Department) => {
  const e = d.headEmpId != null ? org.emp.get(d.headEmpId) : undefined;
  return e?.status === 'EMPLOYED' ? e : undefined;
};

/** 트리·패널에 붙이는 표식. danger면 상신이 막힐 수 있는 상태 */
function flags(org: Org, d: Department): { text: string; tone: 'warning' | 'danger' | 'muted' }[] {
  const out: { text: string; tone: 'warning' | 'danger' | 'muted' }[] = [];
  if (!d.deptType) out.push({ text: '유형 없음(팀으로 처리)', tone: 'warning' });
  if (d.headEmpId == null) {
    out.push(d.parentDeptId == null
      ? { text: '최상위 부서장 공석', tone: 'danger' }
      : { text: '공석', tone: 'muted' });
  } else if (!activeHead(org, d)) {
    out.push({ text: '부서장 재직 아님(공석으로 처리)', tone: d.parentDeptId == null ? 'danger' : 'warning' });
  }
  return out;
}

function Flags({ list }: { list: ReturnType<typeof flags> }) {
  return (
    <>
      {list.map((f) => (
        <span key={f.text} className={f.tone === 'muted' ? s.flagMuted : f.tone === 'danger' ? s.flagDanger : s.flagWarn}>
          {f.tone !== 'muted' && <Icon as={TriangleAlert} size={12} />}{f.text}
        </span>
      ))}
    </>
  );
}

/** 유형 선택지: 서버 DEPT_004 규칙을 미리 반영(최상위만 회사, 상위보다 높게·하위보다 낮게 불가). 서버가 최종 판단 */
function typeOptions(org: Org, d: Department) {
  const parent = d.parentDeptId != null ? org.byId.get(d.parentDeptId) : undefined;
  const kids = (org.children.get(d.deptId) ?? []).map((c) => c.deptType).filter((t): t is DeptType => !!t);
  return TYPE_ORDER.map((t) => {
    const reason = (d.parentDeptId == null) !== (t === 'COMPANY') ? (t === 'COMPANY' ? '회사는 최상위 부서만' : '최상위 부서는 회사만')
      : parent?.deptType && rank(t) < rank(parent.deptType) ? `상위(${TYPE_LABEL[parent.deptType]})보다 높을 수 없음`
      : kids.some((k) => rank(k) < rank(t)) ? '하위 부서보다 낮을 수 없음'
      : null;
    return { value: t, label: reason ? `${TYPE_LABEL[t]} · ${reason}` : TYPE_LABEL[t], disabled: !!reason };
  });
}

function DeptPanel({ org, d }: { org: Org; d: Department }) {
  const notify = useNotify();
  const assign = useAssignHead();
  const clear = useClearHead();
  const changeType = useChangeDeptType();
  const [pick, setPick] = useState<number | null>(null);

  const ids = subtree(org, d.deptId);
  const candidates = [...org.emp.values()].filter((e) => e.status === 'EMPLOYED' && ids.has(e.deptId));
  const members = candidates.filter((e) => e.deptId === d.deptId).length;
  const head = d.headEmpId != null ? org.emp.get(d.headEmpId) : undefined;
  const parent = d.parentDeptId != null ? org.byId.get(d.parentDeptId) : undefined;
  const label = (e: Employee) => [e.name, e.deptName, e.positionName ?? e.dutyName].filter(Boolean).join(' · ');

  const doAssign = () => pick != null && assign.mutate({ deptId: d.deptId, empId: pick }, {
    onSuccess: () => { setPick(null); notify.success(`${d.name} 부서장을 지정했습니다`, '이후 상신하는 결재 문서부터 적용됩니다'); },
    onError: (e) => notify.error(e, '부서장을 지정하지 못했습니다'),
  });

  return (
    <Card title={d.name} meta={<span className={s.muted}>#{d.deptId}</span>}>
      <dl className={s.props}>
        <dt>상위 부서</dt><dd>{parent?.name ?? '— (최상위)'}</dd>
        <dt>소속 재직자</dt><dd className="nx-num">{formatNumber(members)}명 <span className={s.muted}>(하위 포함 {formatNumber(candidates.length)}명)</span></dd>
        <dt>상태</dt><dd>{flags(org, d).length ? <Flags list={flags(org, d)} /> : <span className={s.muted}>정상</span>}</dd>
      </dl>

      <h4 className={s.h4}>부서 유형</h4>
      <Select<DeptType>
        style={{ width: 320 }}
        value={d.deptType ?? undefined}
        placeholder="유형 없음"
        options={typeOptions(org, d)}
        loading={changeType.isPending}
        aria-label="부서 유형"
        onChange={(t) => changeType.mutate({ deptId: d.deptId, deptType: t }, {
          onSuccess: () => notify.success(`${d.name} 유형을 ${TYPE_LABEL[t]}(으)로 바꿨습니다`),
          onError: (e) => notify.error(e, '유형을 바꾸지 못했습니다'),
        })}
      />

      <h4 className={s.h4}>부서장</h4>
      <div className={s.row}>
        {head ? <><b>{head.name}</b><span className={s.muted}>{head.deptName}</span>
          {head.status !== 'EMPLOYED' && <StatusBadge domain="EmploymentStatus" value={head.status} />}</>
          : d.headEmpId != null ? <span className="nx-num">#{d.headEmpId}</span>
          : <span className={s.muted}>공석</span>}
        {d.headEmpId != null && (
          <ConfirmAction
            title={`${d.name} 부서장을 해제합니다`}
            confirmLabel="해제"
            danger
            onConfirm={() => clear.mutateAsync(d.deptId).then(
              () => notify.success(`${d.name} 부서장을 해제했습니다`),
              (e) => notify.error(e, '부서장을 해제하지 못했습니다'),
            )}
            trigger={(open) => <Button size="small" onClick={open}>해제</Button>}
          >
            {d.parentDeptId == null
              ? <p>최상위 부서장이 비면 위로 올라갈 결재자가 없어, 이 단계 결재가 필요한 문서는 상신할 수 없습니다(APPROVAL_022).</p>
              : <p>해제하면 이후 상신하는 문서의 이 부서 결재는 상위 부서({parent?.name}) 부서장이 맡습니다.</p>}
          </ConfirmAction>
        )}
      </div>
      <div className={s.row}>
        <Select<number>
          showSearch
          allowClear
          style={{ width: 320 }}
          placeholder={candidates.length ? '재직자 검색(이 부서와 하위 부서)' : '지정할 수 있는 재직자가 없습니다'}
          disabled={!candidates.length}
          value={pick ?? undefined}
          onChange={(v) => setPick(v ?? null)}
          optionFilterProp="label"
          options={candidates.filter((e) => e.empId !== d.headEmpId).map((e) => ({ value: e.empId, label: label(e) }))}
          aria-label="부서장으로 지정할 사원"
        />
        <Button type="primary" disabled={pick == null} loading={assign.isPending} onClick={doAssign}>
          {d.headEmpId != null ? '교체' : '지정'}
        </Button>
      </div>
      <p className={s.muted}>부서장·유형 변경은 이후 상신하는 결재 문서부터 적용됩니다. 공석이면 결재는 상위 부서장에게 올라갑니다.</p>
    </Card>
  );
}

/** 부서 트리(왼쪽) + 선택한 부서 설정(오른쪽) */
export function DepartmentAdmin() {
  const depts = useDepartments();
  const emps = useAllEmployees();
  const [selected, setSelected] = useState<number | null>(null);
  const org = useMemo(() => (depts.data && emps.data ? buildOrg(depts.data, emps.data) : null), [depts.data, emps.data]);

  const treeData = useMemo<DataNode[]>(() => {
    if (!org) return [];
    const toNode = (d: Department): DataNode => ({
      key: d.deptId,
      title: (
        <span className={s.node}>
          <span>{d.name}</span>
          {d.deptType && <span className={s.type}>{TYPE_LABEL[d.deptType]}</span>}
          <span className={s.muted}>{activeHead(org, d)?.name ?? ''}</span>
          <Flags list={flags(org, d)} />
        </span>
      ),
      children: (org.children.get(d.deptId) ?? []).map(toNode),
    });
    return (org.children.get(null) ?? []).map(toNode);
  }, [org]);

  const err = depts.error ?? emps.error;
  if (err) return <ErrorPanel error={err} onRetry={() => { depts.refetch(); emps.refetch(); }} />;
  if (!org) return <Skeleton active={false} paragraph={{ rows: 10 }} />;
  if (org.byId.size === 0) return <EmptyState icon={Network} title="부서가 없습니다" description="데이터 이관에서 조직 파일을 적재하면 여기에 표시됩니다" />;

  const all = [...org.byId.values()];
  const blocked = all.filter((d) => flags(org, d).some((f) => f.tone === 'danger'));
  const cur = selected != null ? org.byId.get(selected) : undefined;
  return (
    <div className={s.stack}>
      {blocked.length > 0 && (
        <Alert type="error" showIcon message={`결재선을 끝까지 만들 수 없는 부서가 있습니다: ${blocked.map((d) => d.name).join(', ')}`}
               description="최상위 부서장이 비어 있으면, 그 단계 결재가 필요한 문서는 상신할 수 없습니다(APPROVAL_022)." />
      )}
      <div className={s.split}>
        <Card title="부서" meta={<span className={s.muted}>{formatNumber(all.length)}개</span>}>
          <Tree
            blockNode
            defaultExpandAll
            showLine
            treeData={treeData}
            selectedKeys={selected != null ? [selected] : []}
            onSelect={(keys) => setSelected(keys.length ? Number(keys[0]) : null)}
          />
        </Card>
        {cur ? <DeptPanel key={cur.deptId} org={org} d={cur} />
          : <Card><EmptyState icon={Network} title="부서를 고르세요" description="부서장과 유형을 바꿀 수 있습니다" /></Card>}
      </div>
    </div>
  );
}
