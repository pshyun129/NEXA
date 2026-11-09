import { resolveStatus, type StatusDomain } from '@/status/statusMap';
import { Icon } from './Icon';
import s from './ui.module.css';

interface Props {
  domain: StatusDomain;
  value: string;
  /** 라벨을 바꿔야 할 때만(예: "승인 · 근태 반영 10:21:04") */
  label?: string;
}

/** 앱 전체에서 상태는 이것만 쓴다(설계 4장, style-guide 8.4). 색 + 아이콘 + 텍스트를 함께 둔다. */
export function StatusBadge({ domain, value, label }: Props) {
  const spec = resolveStatus(domain, value);
  const cls = [
    s.badge,
    s[`t-${spec.tone}`],
    spec.filled ? s.filled : '',
    spec.dashed ? s.dashed : '',
    spec.strongBorder ? s.strong : '',
  ].join(' ');
  return (
    <span className={cls} data-status={value}>
      {spec.icon && <Icon as={spec.icon} size={12} className={spec.spin ? 'nx-spin' : undefined} />}
      {label ?? spec.label}
    </span>
  );
}
