import { useEffect, useState } from 'react';
import { ErrorPanel } from './ErrorPanel';
import s from './ui.module.css';

/** 셸을 그리기 전(신원 확인 단계) 오류. 메뉴는 /me가 있어야 그리므로 전체 화면 가운데에 ErrorPanel만 둔다 */
export function FullPageError({ error, onRetry, title }: { error: unknown; onRetry: () => void; title?: string }) {
  return (
    <div className={s.full}>
      <div style={{ width: 'min(480px, calc(100% - 32px))' }}>
        <ErrorPanel error={error} onRetry={onRetry} title={title} />
      </div>
    </div>
  );
}

/** 세션 복원 중. 300ms 안에 끝나면 아무것도 보여 주지 않는다(전체 화면 스피너 금지, 설계 7.2). */
export function FullPageNotice({ text = '불러오는 중…' }: { text?: string }) {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setShow(true), 300);
    return () => clearTimeout(t);
  }, []);
  return <div className={s.full} aria-busy="true">{show ? text : null}</div>;
}
