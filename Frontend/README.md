# NEXA Frontend

React 18 + TypeScript 5 + Vite 5 + antd 6. 설계 기준은 `../DOCS/frontend-design.md`, 시각 값은 `../DOCS/style-guide.md`, API는 `../DOCS/frontend-requirements.md`.

## 실행

```bash
npm ci
cp .env.example .env    # gateway 주소가 다르면 VITE_GATEWAY_URL 수정
npm run dev             # http://localhost:5173 (포트 고정: gateway CORS 허용 origin)
```

- 브라우저는 항상 같은 origin(`/api`)으로 요청하고 Vite proxy가 gateway로 넘긴다. `Authorization` 헤더는 붙이지 않는다(쿠키 인증).
- `/dev/kit`: 백엔드 없이 열리는 검수 페이지. 배지·아이콘 전부, 표 행 높이(40±0.5px) 실측, 뷰포트 폭 실측.
- 개발 계정: `admin@local.dev` / `admin1234!`, `user1~9@local.dev` (같은 비밀번호).

## 스크립트

| 명령 | 내용 |
|---|---|
| `npm run dev` | 개발 서버 |
| `npm run typecheck` | `tsc -b` |
| `npm run build` | 타입체크 + 프로덕션 빌드(`dist/`) |

## 구조

```
src/
  api/        axios 클라이언트(봉투 해제, 401 → reissue 단일화), ApiError, QueryClient
  auth/       세션 store(zustand, localStorage nx.employeeId), SessionGate, useCan, RequireAdmin
  shell/      AppShell(사이드바·탑바·사원/관리 전환), 메뉴 정의, 반응형 구간
  status/     statusMap.ts — 상태 라벨·톤·아이콘의 단일 원천
  styles/     tokens.ts / tokens.css / global.css / antdTheme.ts (style-guide 12장 그대로)
  ui/         StatusBadge, Icon + icons.ts, Counter, Kbd, AiMark, EmptyState, ErrorPanel,
              FeatureGate, ConfirmAction, DataTable, EmpName(배치 조회)
  flow/       FlowNode(빌더·실행 모니터 공용 커스텀 노드)
  format/     금액·시간·날짜 표기(Asia/Seoul)
  config/     features.ts — 백엔드 갭 플래그
  pages/      login, home, dev/kit, placeholder
```

## 코드 리뷰 체크리스트 (lint 대신)

- 색은 hex 리터럴 대신 `var(--nx-*)` 또는 `tokens.ts`를 쓴다.
- 아이콘은 `lucide-react`가 아니라 `@/ui/icons`에서 import하고 `<Icon as={...} />`로 그린다.
- 상태 표시는 `<StatusBadge domain value />`만 쓴다.
- 표기는 `@/format`만 쓴다(`toLocaleString` 직접 호출 금지).
- AntD 내부 클래스(`.ant-*`)를 CSS로 건드리지 않는다.
- 의존성 버전은 caret 없이 고정한다. 메이저 업그레이드 금지.
