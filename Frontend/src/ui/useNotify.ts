import { App } from 'antd';
import { isApiError } from '@/api/ApiError';

/** 토스트 규칙(설계 7.2): 성공·정보 5초 자동 닫힘, 오류는 닫히지 않고 Request-Id를 담는다. */
export function useNotify() {
  const { notification } = App.useApp();
  return {
    success: (message: string, description?: string) =>
      notification.success({ message, description, duration: 5 }),
    info: (message: string, description?: string) =>
      notification.info({ message, description, duration: 5 }),
    error: (err: unknown, title = '처리하지 못했습니다') => {
      const api = isApiError(err) ? err : null;
      const msg = api?.status === 404 ? '없거나 볼 권한이 없습니다.' : api?.message ?? '서버에 연결하지 못했습니다.';
      notification.error({
        message: title,
        description: [msg, api ? `${api.code}${api.requestId ? ` · ${api.requestId}` : ''}` : null]
          .filter(Boolean)
          .join('\n'),
        duration: 0,
        style: { whiteSpace: 'pre-line' },
      });
    },
  };
}
