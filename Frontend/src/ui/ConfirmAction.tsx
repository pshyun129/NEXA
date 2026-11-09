import { useState, type ReactNode } from 'react';
import { Button, Input, Modal } from 'antd';

interface Props {
  /** 동사형 제목: "배치 #3을 되돌립니다" */
  title: string;
  /** 영향 범위(숫자 포함) */
  children: ReactNode;
  /** 확정 버튼 라벨: 제목의 동사를 반복한다("되돌리기") */
  confirmLabel: string;
  danger?: boolean;
  /** L2: 이 문자열을 입력해야 확정 버튼이 열린다(적재, 배치 되돌리기) */
  typeToConfirm?: string;
  onConfirm: () => Promise<unknown> | void;
  trigger: (open: () => void) => ReactNode;
}

/** 확인 모달(설계 7.2, style-guide 8.7·9.7). 기본 포커스는 [취소]. 승인·반려에는 쓰지 않는다. */
export function ConfirmAction({ title, children, confirmLabel, danger, typeToConfirm, onConfirm, trigger }: Props) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);

  const close = () => { if (!busy) { setOpen(false); setTyped(''); } };
  const ok = async () => {
    setBusy(true);
    try {
      await onConfirm();
      setOpen(false);
      setTyped('');
    } finally {
      setBusy(false);
    }
  };
  const locked = typeToConfirm != null && typed !== typeToConfirm;

  return (
    <>
      {trigger(() => setOpen(true))}
      <Modal
        open={open}
        title={title}
        width={480}
        onCancel={close}
        destroyOnHidden
        maskClosable={!busy}
        footer={[
          <Button key="cancel" autoFocus onClick={close} disabled={busy}>취소</Button>,
          <Button key="ok" type="primary" danger={danger} loading={busy} disabled={locked} onClick={ok}>
            {confirmLabel}
          </Button>,
        ]}
      >
        <div>{children}</div>
        {typeToConfirm != null && (
          <div style={{ marginTop: 16 }}>
            <div style={{ fontSize: 13, color: 'var(--nx-n-600)', marginBottom: 6 }}>
              확인을 위해 <b className="nx-mono">{typeToConfirm}</b>을(를) 입력해 주세요.
            </div>
            <Input value={typed} onChange={(e) => setTyped(e.target.value)} aria-label="작업명 확인 입력" />
          </div>
        )}
      </Modal>
    </>
  );
}
