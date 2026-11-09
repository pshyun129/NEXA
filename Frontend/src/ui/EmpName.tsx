import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import type { Employee } from '@/api/types';
import s from './ui.module.css';

// 16ms 동안 모은 id를 employees?ids= (100개 단위)로 한 번에 조회한다(설계 7.1).
const pending = new Map<number, { resolve: (e: Employee | null) => void; reject: (err: unknown) => void }[]>();
let timer: ReturnType<typeof setTimeout> | null = null;

function flush() {
  timer = null;
  const ids = [...pending.keys()];
  const waiters = new Map(pending);
  pending.clear();
  for (let i = 0; i < ids.length; i += 100) {
    const chunk = ids.slice(i, i + 100);
    api
      .get<{ employees: Employee[] }>('/api/v1/employees', { params: { ids: chunk.join(',') } })
      .then(({ employees }) => {
        const byId = new Map(employees.map((e) => [e.empId, e]));
        // 없는 ID는 응답에서 조용히 빠진다(요구사항 2.3) → null
        chunk.forEach((id) => waiters.get(id)?.forEach((w) => w.resolve(byId.get(id) ?? null)));
      })
      .catch((err) => chunk.forEach((id) => waiters.get(id)?.forEach((w) => w.reject(err))));
  }
}

export function loadEmployee(id: number): Promise<Employee | null> {
  return new Promise((resolve, reject) => {
    const list = pending.get(id) ?? [];
    list.push({ resolve, reject });
    pending.set(id, list);
    if (!timer) timer = setTimeout(flush, 16);
  });
}

export function useEmployee(id: number | null | undefined) {
  return useQuery({
    queryKey: ['employee', id],
    queryFn: () => loadEmployee(id as number),
    enabled: id != null,
    staleTime: 10 * 60_000,
    gcTime: 30 * 60_000,
  });
}

/** 사원 이름. 로딩 중에는 회색 텍스트 바, 못 찾으면 #ID(neutral). */
export function EmpName({ id }: { id: number | null | undefined }) {
  const { data, isPending, isError } = useEmployee(id);
  if (id == null) return <span className={s.nameUnknown}>—</span>;
  if (isPending) return <span className={s.nameLoading} aria-label="불러오는 중" />;
  if (isError || !data) return <span className={`${s.nameUnknown} nx-num`}>#{id}</span>;
  return <span>{data.name}</span>;
}
