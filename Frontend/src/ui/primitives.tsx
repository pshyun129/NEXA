import type { ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';
import { Icon } from './Icon';
import { Sparkles, Inbox } from './icons';
import type { Tone } from '@/styles/tokens';
import s from './ui.module.css';

/* ---------- Counter (style-guide 8.4) ---------- */
export function Counter({ value, tone = 'brand', label }: { value: number; tone?: 'brand' | 'warning'; label?: string }) {
  const active = value > 0;
  const cls = ['nx-counter', s.counter, active ? (tone === 'brand' ? `${s.counterBrand} nx-counter-brand` : s.counterWarning) : ''].join(' ');
  return <span className={cls} aria-label={label ? `${label} ${value}건` : undefined}>{value}</span>;
}

/* ---------- Kbd (style-guide 8.1) ---------- */
const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);
export const modKey = isMac ? '⌘' : 'Ctrl';
export function Kbd({ children, onPrimary }: { children: ReactNode; onPrimary?: boolean }) {
  return <kbd className={[s.kbd, onPrimary ? s.kbdOnPrimary : ''].join(' ')}>{children}</kbd>;
}

/* ---------- AiMark (style-guide 9.2, 라벨 사전은 닫힌 목록) ---------- */
export type AiLabel = 'AI 추출' | 'AI 검증' | 'AI 추천' | 'AI 요약' | '자동화로 생성됨';
export function AiMark({ label }: { label: AiLabel }) {
  return (
    <span className={s.aiMark} aria-label={`AI가 생성한 값: ${label}`}>
      <Icon as={Sparkles} size={12} />
      {label}
    </span>
  );
}

/* ---------- 점 칩(근무 상태) ---------- */
const dotColor: Record<Tone | 'outline', string> = {
  success: 'var(--nx-success-fg)', warning: 'var(--nx-warning-fg)', danger: 'var(--nx-danger-fg)',
  info: 'var(--nx-info-fg)', neutral: 'var(--nx-n-500)', ai: 'var(--nx-ai-500)', outline: 'transparent',
};
export function DotChip({ tone, children }: { tone: Tone | 'outline'; children: ReactNode }) {
  return (
    <span className={s.dotChip}>
      <span className={s.dot} style={{ background: dotColor[tone], border: tone === 'outline' ? '1.5px solid var(--nx-n-450)' : undefined }} />
      {children}
    </span>
  );
}

/* ---------- EmptyState (style-guide 6.2) ---------- */
export function EmptyState({ icon = Inbox, title, description, action }:
  { icon?: LucideIcon; title: string; description?: string; action?: ReactNode }) {
  return (
    <div className={s.empty}>
      <div className={s.emptyIcon}><Icon as={icon} size={24} /></div>
      <div className={s.emptyTitle}>{title}</div>
      {description && <div className={s.emptyDesc}>{description}</div>}
      {action && <div className={s.emptyAction}>{action}</div>}
    </div>
  );
}

/* ---------- PageHeader ---------- */
/** 제목은 단어마다 흐림에서 또렷해진다(React Bits BlurText). 스크린리더에는 제목 한 덩어리로 읽힌다 */
export function BlurTitle({ text, className }: { text: string; className?: string }) {
  const words = text.split(' ');
  return (
    <h1 className={className} aria-label={text}>
      {words.map((w, i) => (
        <span key={`${w}-${i}`} aria-hidden>
          <span className="nx-blur-word" style={{ animationDelay: `${i * 110}ms` }}>{w}</span>{i < words.length - 1 ? ' ' : ''}
        </span>
      ))}
    </h1>
  );
}

export function PageHeader({ title, description, actions, eyebrow }:
  { title: string; description?: ReactNode; actions?: ReactNode; eyebrow?: string }) {
  return (
    <div className={s.pageHeader}>
      <div className={s.pageHeadText}>
        {eyebrow && <div className={s.eyebrow}>{eyebrow}</div>}
        <BlurTitle key={title} text={title} className={s.pageTitle} />
        {description && <div className={s.pageDesc}>{description}</div>}
      </div>
      {actions && <div className={s.pageActions}>{actions}</div>}
    </div>
  );
}

/* ---------- Card (style-guide 8.5) ---------- */
export function Card({ title, meta, children, flush }: { title?: ReactNode; meta?: ReactNode; children: ReactNode; flush?: boolean }) {
  return (
    <section className={`${s.card} nx-rise`}>
      {(title || meta) && (
        <div className={s.cardHead}>
          {title && <h3 className={s.cardTitle}>{title}</h3>}
          {meta && <div className={s.cardMeta}>{meta}</div>}
        </div>
      )}
      <div className={flush ? s.cardBodyFlush : s.cardBody}>{children}</div>
    </section>
  );
}

/* ---------- BarSegments (차트 라이브러리 없음, style-guide 3.5) ---------- */
export interface Segment { value: number; color: string; label?: string }
export function BarSegments({ segments, total, ariaLabel }: { segments: Segment[]; total?: number; ariaLabel: string }) {
  const sum = total ?? segments.reduce((a, b) => a + b.value, 0);
  return (
    <div className={s.bar} role="img" aria-label={ariaLabel}>
      {segments.map((seg, i) => (
        <div key={i} className={s.barSeg} title={seg.label}
             style={{ width: `${sum > 0 ? (seg.value / sum) * 100 : 0}%`, background: seg.color }} />
      ))}
    </div>
  );
}
