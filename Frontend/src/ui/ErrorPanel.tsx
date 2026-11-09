import { Button, App } from 'antd';
import { isApiError } from '@/api/ApiError';
import { Icon } from './Icon';
import { CircleAlert, Copy } from './icons';
import s from './ui.module.css';

/** 화면 오류(설계 7.2): error.message + 코드 + X-Request-Id(복사) + [다시 시도]. 404는 문구를 통일한다. */
export function ErrorPanel({ error, onRetry, title = '불러오지 못했습니다' }:
  { error: unknown; onRetry?: () => void; title?: string }) {
  const { message } = App.useApp();
  const api = isApiError(error) ? error : null;
  const msg = api?.status === 404
    ? '없거나 볼 권한이 없습니다.'
    : api?.message ?? '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.';

  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      message.success('Request-Id를 복사했습니다');
    } catch {
      message.error('복사하지 못했습니다');
    }
  };

  return (
    <div className={s.panel} role="alert">
      <Icon as={CircleAlert} size={20} className={s.panelDanger} />
      <div className={s.panelBody}>
        <div className={s.panelTitle}>{title}</div>
        <div className={s.panelMsg}>{msg}</div>
        <div className={s.panelMeta}>
          {api && <span className="nx-mono">{api.code}</span>}
          {api?.requestId && (
            <>
              <span className="nx-mono">{api.requestId}</span>
              <Button size="small" type="text" icon={<Icon as={Copy} size={12} />} onClick={() => copy(api.requestId!)}>
                복사
              </Button>
            </>
          )}
          {onRetry && <Button size="small" onClick={onRetry}>다시 시도</Button>}
        </div>
      </div>
    </div>
  );
}
