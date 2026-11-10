import { useRef, useState, type ReactNode } from 'react';
import { Alert, Button, Modal } from 'antd';
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { isApiError } from '@/api/ApiError';
import { useMyId } from '@/auth/session';
import { ErrorPanel } from '@/ui/ErrorPanel';

export const requiredName = [{ required: true, whitespace: true, message: '이름을 입력하세요' }, { max: 100, message: '최대 100자입니다' }];
export const reasonRules = [{ required: true, whitespace: true, message: '사유를 입력하세요' }, { max: 500 }];
export const positiveId = [{ required: true, type: 'integer' as const, min: 1, max: Number.MAX_SAFE_INTEGER, message: '정확히 표현 가능한 양의 정수를 입력하세요' }];
export const employeeKeys = (id: number): QueryKey[] => [['leaveAdmin', 'emp', id]];
export const policyKeys = (id?: number): QueryKey[] => id == null ? [['leaveAdmin', 'policies']]
  : [['leaveAdmin', 'policies'], ['leaveAdmin', 'policy', id], ['leaveAdmin', 'versions', id], ['leaveAdmin', 'version', id],
    ['leaveAdmin', 'accrualRules', id], ['leaveAdmin', 'typeRules', id], ['leave', 'types'], ['leave', 'policy']];

export function ActionModal({ title, pending, error, onClose, onSubmit, children, label = '저장', danger = false, allowUnknownRetry = false }: {
  title: string; pending: boolean; error: unknown; onClose: () => void; onSubmit: () => void;
  children: ReactNode; label?: string; danger?: boolean; allowUnknownRetry?: boolean;
}) {
  const uncertain = error != null && (!isApiError(error) || error.status === 0 || error.status >= 500);
  return <Modal open title={title} destroyOnHidden maskClosable={false} onCancel={() => !pending && onClose()}
    footer={<><Button autoFocus disabled={pending} onClick={onClose}>닫기</Button>
      <Button type="primary" danger={danger} loading={pending} disabled={uncertain && !allowUnknownRetry} onClick={onSubmit}>{label}</Button></>}>
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {children}
      {error != null && <ErrorPanel error={error} />}
      {uncertain && !allowUnknownRetry && <Alert type="warning" message="요청 결과가 불명확합니다. 이 창에서 다시 저장하지 않습니다. 목록을 새로 조회해 반영 여부를 확인하세요."
        description="일반 생성 API에는 요청 키 조회 계약이 없습니다. 새 작업을 시작하기 전에 생성·변경 여부를 먼저 확인해야 합니다." />}
    </div>
  </Modal>;
}

interface Intent<T> { key: string; body: T }

/** 지급·조정·취소는 전송 전에 원문을 보관한다. Timeout/재방문에도 같은 키와 Body만 재전송한다. */
export function usePersistentAdminRequest<TInput, TResult>(
  operation: 'grant' | 'adjust' | 'cancel', targetId: number,
  action: (key: string, body: TInput) => Promise<TResult>, affected: readonly QueryKey[],
  complete: (result: TResult) => boolean = () => true,
) {
  const actorId = useMyId();
  const storageKey = `nexa.leave.admin:${actorId}:${operation}:${targetId}`;
  const [intent, setIntent] = useState<Intent<TInput> | null>(() => {
    try {
      const saved = sessionStorage.getItem(storageKey);
      if (!saved) return null;
      const value = JSON.parse(saved);
      return typeof value.key === 'string' && value.body && typeof value.body === 'object' ? value : null;
    } catch { return null; }
  });
  const busy = useRef(false);
  const [localError, setLocalError] = useState<unknown>(null);
  const qc = useQueryClient();
  const mutation = useMutation({ mutationFn: ({ key, body }: Intent<TInput>) => action(key, body), retry: false });
  const execute = async (body: TInput): Promise<TResult | undefined> => {
    if (busy.current || actorId == null) return undefined;
    busy.current = true;
    setLocalError(null);
    let request = intent;
    try {
      if (request == null && sessionStorage.getItem(storageKey) != null) {
        throw new Error('이전 요청을 읽을 수 없습니다. 중복 처리를 막기 위해 전송을 중단했습니다. 기존 결과를 먼저 확인하세요.');
      }
      request ??= { key: operation === 'cancel' ? `cancel:${targetId}` : crypto.randomUUID(), body };
      // 보관이 불가능하면 쓰기부터 차단한다. 토큰·쿠키·비밀번호는 저장하지 않는다.
      sessionStorage.setItem(storageKey, JSON.stringify(request));
      setIntent(request);
      const result = await mutation.mutateAsync(request);
      const keys = actorId === targetId && operation !== 'cancel'
        ? [...affected, ['leave', 'summary'], ['leave', 'grants'], ['leave', 'ledger']] : affected;
      await Promise.all(keys.map((queryKey) => qc.invalidateQueries({ queryKey })));
      if (complete(result)) {
        sessionStorage.removeItem(storageKey);
        setIntent(null);
      }
      return result;
    } catch (error) {
      setLocalError(error);
      // 명확한 입력/권한/상태 거절만 새 입력을 받는다. 불명·키 충돌·취소 의도는 유지한다.
      if (operation !== 'cancel' && isApiError(error) && error.status >= 400 && error.status < 500
          && !['LEAVE_019', 'LEAVE_024'].includes(error.code)) {
        sessionStorage.removeItem(storageKey);
        setIntent(null);
      }
      return undefined;
    } finally { busy.current = false; }
  };
  return { execute, intent, pending: mutation.isPending, error: localError, result: mutation.data };
}

export function PendingIntentNotice({ locked }: { locked: boolean }) {
  return locked ? <Alert type="warning" showIcon message="접수된 요청의 내용과 키를 유지합니다. 같은 요청 다시 확인으로 결과를 확인하세요."
    description="화면을 닫아도 이 브라우저 탭에 요청 내용이 보관됩니다. 결과가 확인되기 전 새 요청으로 반복 처리하지 마세요." /> : null;
}
