#!/usr/bin/env node
// 시연 워크플로 시드 (요구사항 8장 #8·#9, 설계 9장 5주차 B "시드 스크립트 작성").
// 리허설마다 다시 돌릴 수 있게 이름으로 찾아서 있으면 덮어쓰고, 없으면 만든다.
//
//   node scripts/seed-demo-workflows.mjs
//   GATEWAY_URL=http://localhost:8080 ADMIN_EMAIL=admin@local.dev ADMIN_PASS=... node scripts/seed-demo-workflows.mjs
//
// 만드는 것
//   #8 "영수증 경비 정산": 그래프 + 입력 폼 + 노출 화면(HOME) + 배포. 데모 3·4번(Run Theater, HITL)
//   #9 "검사 데모": 일부러 틀린 초안 그래프(배포·노출 안 함). 데모 8번([검사] → 이슈 2건 + 클라이언트 순서 경고)
//
// 노드 종류·설정 키·분기 이름은 FastAPI nodes/impl/*.py(develop b772e32) 기준이다. 바뀌면 여기도 고친다.

const GATEWAY = process.env.GATEWAY_URL ?? 'http://localhost:8080';
const ADMIN_EMAIL = process.env.ADMIN_EMAIL ?? 'admin@local.dev';
const ADMIN_PASS = process.env.ADMIN_PASS ?? 'admin1234!'; // 로컬 dev 시드(R__dev_seed.sql) 계정
const AI = '/api/v1/ai';

let cookie = '';

async function call(method, path, body) {
  const res = await fetch(GATEWAY + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(cookie && { Cookie: cookie }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : null; } catch { json = null; }
  if (!res.ok || (json && json.success === false)) {
    const err = json?.error ?? json;
    throw new Error(`${method} ${path} → ${res.status} ${err?.code ?? ''} ${err?.message ?? text.slice(0, 200)}`);
  }
  return { res, data: json && 'success' in json ? json.data : json };
}

async function login() {
  const { res } = await call('POST', '/api/v1/auth/login', { email: ADMIN_EMAIL, pass: ADMIN_PASS });
  // 토큰은 HttpOnly 쿠키로 온다. 이름=값 부분만 모아 다음 요청에 붙인다
  cookie = res.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
  if (!cookie.includes('accessToken=')) throw new Error('로그인 응답에 accessToken 쿠키가 없습니다');
}

/** 이름이 같은 워크플로를 찾아 쓰고, 없으면 만든다 */
async function upsertWorkflow(name, description) {
  const { data: list } = await call('GET', `${AI}/workflows`);
  const found = list.find((w) => w.name === name);
  if (found) {
    await call('PATCH', `${AI}/workflows/${found.id}`, { name, description });
    return { id: found.id, created: false };
  }
  const { data } = await call('POST', `${AI}/workflows`, { name, description });
  return { id: data.id, created: true };
}

// 노드 폭 240(style-guide 8.13) + 간격 100: 연결선과 분기 라벨 칩이 들어갈 자리
const at = (col, row = 0) => ({ x: 80 + col * 340, y: 200 + row * 160 });

// ── #8 영수증 경비 정산 ─────────────────────────────────────────────
// 파일 업로드 → 사원 조회 → OCR → 서류 항목 추출 → 자동 검증 ─자동 확정→ 결재 상신 → 결재 대기
//                                  │확인 필요        └사람 확인→ 사람 확인 ─확인→ 결재 상신
//                                  └────────────────────────→ 사람 확인
// - 사원 조회는 결재 대기 앞에 둔다(결재 대기 뒤에는 사용자 토큰이 없다, wait_approval.py).
// - OCR '확인 필요'는 연결하지 않아 그 자리에서 끝난다(앞에 추출 결과가 없으면 사람 확인이 실패, 요구사항 4.6).
// - 참조(ref) 설정은 비워 두고 "앞 노드의 같은 키" 기본 동작을 쓴다(사람 확인을 거쳤으면 그 확정값).
const RECEIPT = {
  name: '영수증 경비 정산',
  description: '영수증을 올리면 금액·가게·날짜를 읽어 경비 결재를 올립니다. 확신이 없는 칸만 사람에게 묻습니다.',
  graph: {
    nodes: [
      { id: 'n1', type: 'file_upload', position: at(0), config: { accept: '이미지' } },
      { id: 'n2', type: 'employee_lookup', position: at(1), config: {} },
      { id: 'n3', type: 'ocr_extract', position: at(2), config: {} },
      { id: 'n4', type: 'structured_extract', position: at(3), config: { doc_type: '영수증' } },
      // 리허설에서 A 영수증이 '사람 확인'으로 안 가면 min_confidence: 0.99로 강제(요구사항 8장 #10)
      { id: 'n5', type: 'llm_verify', position: at(4), config: { use_gms: '사용' } },
      { id: 'n6', type: 'human_confirm', position: at(4.5, 1), config: { message: '금액·가게 이름·날짜가 영수증과 같은지 확인해 주세요.' } },
      { id: 'n7', type: 'approval_draft', position: at(5.5), config: { form_type: '일반 기안', title: '[경비] {store} {total:,}원' } },
      { id: 'n8', type: 'wait_approval', position: at(6.5), config: {} },
    ],
    edges: [
      { id: 'e1', source: 'n1', target: 'n2' },
      { id: 'e2', source: 'n2', target: 'n3' },
      { id: 'e3', source: 'n3', target: 'n4', branch: '성공' },
      { id: 'e4', source: 'n4', target: 'n5', branch: '성공' },
      { id: 'e5', source: 'n4', target: 'n6', branch: '확인 필요' },
      { id: 'e6', source: 'n5', target: 'n7', branch: '자동 확정' },
      { id: 'e7', source: 'n5', target: 'n6', branch: '사람 확인' },
      { id: 'e8', source: 'n6', target: 'n7', branch: '확인' },
      { id: 'e9', source: 'n7', target: 'n8' },
    ],
  },
  inputForm: [
    { key: 'receipt', label: '영수증', type: 'file', required: true },
  ],
  screens: ['HOME'],
};

// ── #9 검사 데모 ─────────────────────────────────────────────────
// 설계 8.3 #8: 결재 상신 '제목'이 비어 있고, OCR 대신 파일 업로드 → 서류 항목 추출로 연결된 그래프.
// 서버 이슈 2건: ① 결재 상신 '제목' 설정이 비어 있음 ② OCR이 떨어져 있어 시작 노드가 2개.
// 클라이언트 순서 경고: 서류 항목 추출 앞에 OCR이 없음(서버는 순서를 검사하지 않는다).
const CHECK_DEMO = {
  name: '검사 데모',
  description: '빌더 [검사] 시연용. 일부러 틀린 그래프라 배포하지 않습니다.',
  graph: {
    nodes: [
      { id: 'n1', type: 'file_upload', position: at(0), config: { accept: '이미지' } },
      { id: 'n2', type: 'employee_lookup', position: at(1), config: {} },
      { id: 'n3', type: 'ocr_extract', position: at(2, 1), config: {} },
      { id: 'n4', type: 'structured_extract', position: at(3), config: { doc_type: '영수증' } },
      { id: 'n5', type: 'llm_verify', position: at(4), config: { use_gms: '사용' } },
      { id: 'n6', type: 'human_confirm', position: at(4.5, 1), config: {} },
      { id: 'n7', type: 'approval_draft', position: at(5.5), config: { form_type: '일반 기안', title: '' } },
    ],
    edges: [
      { id: 'e1', source: 'n1', target: 'n2' },
      { id: 'e2', source: 'n2', target: 'n4' },
      { id: 'e4', source: 'n4', target: 'n5', branch: '성공' },
      { id: 'e6', source: 'n5', target: 'n7', branch: '자동 확정' },
      { id: 'e7', source: 'n5', target: 'n6', branch: '사람 확인' },
      { id: 'e8', source: 'n6', target: 'n7', branch: '확인' },
    ],
  },
};

async function seedReceipt() {
  const w = RECEIPT;
  const { id, created } = await upsertWorkflow(w.name, w.description);
  await call('PUT', `${AI}/workflows/${id}/graph`, w.graph);
  const { data: v } = await call('POST', `${AI}/workflows/${id}/validate`);
  if (!v.valid) throw new Error(`#8 그래프 검사 실패: ${v.issues.map((i) => i.message).join(' / ')}`);
  await call('PUT', `${AI}/workflows/${id}/input-form`, { fields: w.inputForm });
  await call('PUT', `${AI}/workflows/${id}/placements`, { screens: w.screens });
  const { data: d } = await call('POST', `${AI}/workflows/${id}/deploy`);
  console.log(`#8 ${w.name}: id=${id} ${created ? '생성' : '갱신'}, 배포 v${d.version}, 노출 ${w.screens.join(',')}`);
}

async function seedCheckDemo() {
  const w = CHECK_DEMO;
  const { id, created } = await upsertWorkflow(w.name, w.description);
  await call('PUT', `${AI}/workflows/${id}/graph`, w.graph);
  const { data: v } = await call('POST', `${AI}/workflows/${id}/validate`);
  console.log(`#9 ${w.name}: id=${id} ${created ? '생성' : '갱신'}, 검사 이슈 ${v.issues.length}건`);
  for (const i of v.issues) console.log(`   - ${i.nodeId ?? i.edgeId ?? '전체'}: ${i.message}`);
  if (v.issues.length < 2) throw new Error('#9 검사 이슈가 2건 미만입니다(요구사항 8장 #9)');
}

async function verify() {
  const { data } = await call('GET', `${AI}/workflows/available?screen=HOME`);
  const ok = data.some((w) => w.name === RECEIPT.name);
  console.log(`확인: /workflows/available?screen=HOME → ${data.map((w) => w.name).join(', ') || '(없음)'} ${ok ? '✓' : '✗'}`);
  if (!ok) throw new Error('#8 워크플로가 HOME 진입 목록에 없습니다');
}

try {
  await login();
  await seedReceipt();
  await seedCheckDemo();
  await verify();
} catch (e) {
  console.error(`시드 실패: ${e.message}`);
  process.exit(1);
}
