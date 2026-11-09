import { useEffect, useState } from 'react';
import s from './ui.module.css';

/** 세션 복원 중. 300ms 안에 끝나면 아무것도 보여 주지 않는다(전체 화면 스피너 금지, 설계 7.2). */
export function FullPageNotice({ text = '불러오는 중…' }: { text?: string }) {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setShow(true), 300);
    return () => clearTimeout(t);
  }, []);
  return <div className={s.full} aria-busy="true">{show ? text : null}</div>;
}
