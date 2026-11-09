import type { LucideIcon, LucideProps } from 'lucide-react';

type Size = 12 | 16 | 20 | 24;
type Props = Omit<LucideProps, 'size' | 'strokeWidth' | 'absoluteStrokeWidth'> & { as: LucideIcon; size?: Size; label?: string };

// 사용자 props를 먼저 펼치고 고정값을 뒤에 둬서 덮어쓸 수 없게 한다(style-guide 12.5)
export function Icon({ as: C, size = 16, label, ...rest }: Props) {
  return (
    <C {...rest} size={size} strokeWidth={1.5} absoluteStrokeWidth
       aria-hidden={label ? undefined : true} aria-label={label} role={label ? 'img' : undefined} focusable="false" />
  );
}
