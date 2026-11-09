// src/styles/tokens.ts — JS 쪽 값(style-guide 12.3). 값을 바꿀 때는 이 파일과 tokens.css를 같은 커밋에서 함께 고친다.
export const color = {
  brand: { 50:'#F3F3F4',100:'#E8E8EA',200:'#D2D3D6',300:'#A7A9AD',400:'#6E7075',
           500:'#3A3B3E',600:'#232425',700:'#151618',800:'#111213',900:'#0B0B0C' },
  ai:    { 50:'#F6F0FC',100:'#EFE5FA',200:'#E2D2F3',500:'#8E6CC4',700:'#6E4C9E',800:'#573C7D' },
  n:     { 0:'#FFFFFF',25:'#F8F9FB',50:'#F3F4F6',100:'#EEEFF1',200:'#E3E5E8',300:'#CBCED3',
           400:'#9A9DA3',450:'#8B8E94',500:'#6C7078',600:'#4B4F57',700:'#33363C',800:'#232323',900:'#151618' },
  status: {
    success:{ fg:'#2A735F', bg:'#EEF8F4', bd:'#CDE8DC' },
    warning:{ fg:'#8A6327', bg:'#FFF7E9', bd:'#EEDDBB' },
    danger: { fg:'#B1443A', bg:'#FDF0EE', bd:'#F1CBC6' },
    info:   { fg:'#4560AD', bg:'#EFF3FF', bd:'#D7E0F8' },
    neutral:{ fg:'#4B4F57', bg:'#F3F4F6', bd:'#E1E3E6' },
    ai:     { fg:'#6E4C9E', bg:'#F6F0FC', bd:'#E6D9F4' },
  },
  mask: 'rgba(20,23,28,0.45)',
} as const;

const { n, brand: b } = color;
export const semantic = {
  text:   { strong:n[800], default:n[700], label:n[600], sub:n[500], subOnTint:n[600], disabled:n[400], link:b[600] },
  bg:     { app:n[25], card:n[0], subtle:n[50], selected:b[50], sidebarAdmin:'#151618' },
  border: { default:n[200], input:n[450], strong:n[300], divider:n[100] },
  focus:  { color:b[600], colorOnDark:n[0], width:2, offset:2 },
} as const;

export const font = {
  sans: '"Pretendard Variable", Pretendard, -apple-system, "Malgun Gothic", sans-serif',
  mono: '"JetBrains Mono", ui-monospace, Consolas, monospace',
} as const;
export const size = { topbar:64, sidebar:236, sidebarCollapsed:64, row:40, control:36, controlSM:28, controlLG:44,
                      badge:22, counter:20, hit:24, cardHeader:48, actionBar:56 } as const;
export const radius = { sm:4, md:6, lg:10 } as const;
export const shadow = { sticky:'0 1px 2px rgba(20,23,28,.05)', pop:'0 8px 24px rgba(20,23,28,.09)',
                        modal:'0 20px 80px rgba(21,32,44,.18)' } as const;
export const motion = { fast:120, base:160, slow:200, countMax:800, pulse:1200,
                        ease:'cubic-bezier(.22,1,.36,1)', easeInOut:'cubic-bezier(.4,0,.2,1)' } as const;

export type Tone = keyof typeof color.status;
