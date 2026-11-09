// src/styles/tokens.ts — JS 쪽 값(style-guide 12.3). 값을 바꿀 때는 이 파일과 tokens.css를 같은 커밋에서 함께 고친다.
export const color = {
  brand: { 50:'#EEF2FD',100:'#DCE4FB',200:'#B8C8F5',300:'#8EA6EC',400:'#5F80E0',
           500:'#3A5FCF',600:'#2A4BB5',700:'#213C93',800:'#1C3174',900:'#16255A' },
  ai:    { 50:'#F4EEFC',100:'#ECE2FE',200:'#D9C7F8',500:'#8E5CCC',700:'#6630A8',800:'#532583' },
  n:     { 0:'#FFFFFF',25:'#FAFBFC',50:'#F4F6F8',100:'#EAEDF1',200:'#DCE0E6',300:'#C3C9D2',
           400:'#969DAA',450:'#878F9C',500:'#6A7180',600:'#4A5160',700:'#343A46',800:'#22262E',900:'#14171C' },
  status: {
    success:{ fg:'#1A7540', bg:'#E7F4EC', bd:'#A9D8BB' },
    warning:{ fg:'#A15C00', bg:'#FFF3DC', bd:'#F2CF8F' },
    danger: { fg:'#C2352A', bg:'#FCEBE9', bd:'#F1B4AE' },
    info:   { fg:'#0A6F96', bg:'#E4F2F8', bd:'#A3D2E5' },
    neutral:{ fg:'#4A5160', bg:'#EEF0F3', bd:'#D3D8DF' },
    ai:     { fg:'#6630A8', bg:'#F4EEFC', bd:'#D9C7F8' },
  },
  mask: 'rgba(20,23,28,0.45)',
} as const;

const { n, brand: b } = color;
export const semantic = {
  text:   { strong:n[800], default:n[700], label:n[600], sub:n[500], subOnTint:n[600], disabled:n[400], link:b[600] },
  bg:     { app:n[25], card:n[0], subtle:n[50], selected:b[50], sidebarAdmin:b[900] },
  border: { default:n[200], input:n[450], strong:n[300], divider:n[100] },
  focus:  { color:b[600], colorOnDark:n[0], width:2, offset:2 },
} as const;

export const font = {
  sans: '"Pretendard Variable", Pretendard, -apple-system, "Malgun Gothic", sans-serif',
  mono: '"JetBrains Mono", ui-monospace, Consolas, monospace',
} as const;
export const size = { topbar:56, sidebar:232, sidebarCollapsed:64, row:40, control:36, controlSM:28, controlLG:44,
                      badge:22, counter:20, hit:24, cardHeader:48, actionBar:56 } as const;
export const radius = { sm:4, md:6, lg:8 } as const;
export const shadow = { sticky:'0 1px 2px rgba(20,23,28,.06)', pop:'0 4px 12px rgba(20,23,28,.10)',
                        modal:'0 12px 32px rgba(20,23,28,.16)' } as const;
export const motion = { fast:120, base:160, slow:200, countMax:800, pulse:1200,
                        ease:'cubic-bezier(.2,.8,.2,1)', easeInOut:'cubic-bezier(.4,0,.2,1)' } as const;

export type Tone = keyof typeof color.status;
