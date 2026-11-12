/** 로고: 윤곽선 육각형(DOCS/logo 기준). 워드마크는 쓰는 곳에서 붙인다 */
export function BrandMark({ size = 30 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" aria-hidden focusable="false">
      <polygon points="50,10 85.5,30 85.5,70 50,90 14.5,70 14.5,30" fill="none" stroke="#5563C1" strokeWidth="7.4" strokeLinejoin="round" />
    </svg>
  );
}
