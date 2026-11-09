import { useState } from 'react';
import { Modal } from 'antd';
import { useHotkeys } from '@/common/useHotkeys';
import { Kbd, modKey } from '@/ui/primitives';

const GROUPS: { title: string; keys: [string[], string][] }[] = [
  { title: '전역', keys: [[['?'], '단축키 도움말']] },
  { title: '목록', keys: [[['J'], '다음 항목'], [['K'], '이전 항목'], [['Enter'], '열기'], [['/'], '검색']] },
  { title: '결재(내 차례)', keys: [[['A'], '승인'], [['R'], '반려 사유 입력'], [['F'], '전결(조건 충족 시)']] },
  { title: '입력', keys: [[[modKey, 'Enter'], '제출'], [['Esc'], '반려 입력 닫기']] },
];

/** `?` 단축키 도움말(설계 7.2). 입력 중에는 열리지 않는다. */
export function ShortcutHelp() {
  const [open, setOpen] = useState(false);
  useHotkeys({ '?': () => setOpen(true) });
  return (
    <Modal open={open} onCancel={() => setOpen(false)} footer={null} title="단축키" width={420} destroyOnHidden>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {GROUPS.map((g) => (
          <section key={g.title}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--nx-n-700)', marginBottom: 6 }}>{g.title}</div>
            <dl style={{ margin: 0, display: 'grid', gridTemplateColumns: '120px 1fr', rowGap: 6 }}>
              {g.keys.map(([ks, label]) => (
                <div key={label} style={{ display: 'contents' }}>
                  <dt>{ks.map((k, i) => <span key={k}>{i > 0 && '+'}<Kbd>{k}</Kbd></span>)}</dt>
                  <dd style={{ margin: 0, color: 'var(--nx-n-700)' }}>{label}</dd>
                </div>
              ))}
            </dl>
          </section>
        ))}
        <div style={{ fontSize: 12, color: 'var(--nx-n-500)' }}>입력창에 글자를 쓰는 동안에는 한 글자 단축키가 동작하지 않습니다.</div>
      </div>
    </Modal>
  );
}
