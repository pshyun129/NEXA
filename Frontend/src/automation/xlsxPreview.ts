/**
 * .xlsx 미리보기용 최소 읽기(라이브러리 없이). xlsx는 ZIP 안의 XML이라
 * ① ZIP 목차를 읽고 ② 브라우저 DecompressionStream('deflate-raw')으로 풀고 ③ DOMParser로 시트·공유 문자열을 읽는다.
 * 미리보기 전용이라 서식은 숫자 천 단위(#,##0)와 날짜 서식만 반영하고, 행·열 수를 잘라서 그린다.
 */

export interface SheetPreview { name: string; rows: string[][]; totalRows: number; totalCols: number }

const MAX_ROWS = 200;
const MAX_COLS = 30;

/** ZIP 목차에서 파일 이름 → 압축 해제한 내용 */
async function unzip(buf: ArrayBuffer, wanted: (name: string) => boolean): Promise<Map<string, string>> {
  const view = new DataView(buf);
  // 끝에서 중앙 디렉터리 끝 표시(0x06054b50)를 찾는다(주석이 붙어 있을 수 있어 뒤에서부터)
  let eocd = -1;
  for (let i = buf.byteLength - 22; i >= Math.max(0, buf.byteLength - 65_557); i--) {
    if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('엑셀(xlsx) 파일 형식이 아닙니다');
  const count = view.getUint16(eocd + 10, true);
  let p = view.getUint32(eocd + 16, true);
  const dec = new TextDecoder();
  const out = new Map<string, string>();
  for (let n = 0; n < count; n++) {
    if (view.getUint32(p, true) !== 0x02014b50) break;
    const method = view.getUint16(p + 10, true);
    const size = view.getUint32(p + 20, true);
    const nameLen = view.getUint16(p + 28, true);
    const extraLen = view.getUint16(p + 30, true);
    const commentLen = view.getUint16(p + 32, true);
    const local = view.getUint32(p + 42, true);
    const name = dec.decode(new Uint8Array(buf, p + 46, nameLen));
    p += 46 + nameLen + extraLen + commentLen;
    if (!wanted(name)) continue;
    // 지역 헤더의 이름·추가 필드 길이는 중앙 목차와 다를 수 있어 다시 읽는다
    const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
    const raw = new Uint8Array(buf, start, size);
    if (method === 0) { out.set(name, dec.decode(raw)); continue; }
    if (method !== 8) continue;
    const stream = new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    out.set(name, await new Response(stream).text());
  }
  return out;
}

const xml = (text: string | undefined) => (text ? new DOMParser().parseFromString(text, 'application/xml') : null);
/** 네임스페이스와 상관없이 태그 이름으로 찾는다 */
const all = (el: Document | Element | null, tag: string) => (el ? Array.from(el.getElementsByTagNameNS('*', tag)) : []);

/** "C12" → 열 번호 2(0부터) */
function colIndex(ref: string) {
  let n = 0;
  for (const ch of ref.replace(/\d+$/, '')) n = n * 26 + (ch.charCodeAt(0) - 64);
  return n - 1;
}

/** 엑셀 날짜 일련번호 → YYYY-MM-DD (1900 날짜 체계) */
function serialDate(v: number) {
  const d = new Date(Date.UTC(1899, 11, 30) + Math.round(v * 86_400_000));
  return d.toISOString().slice(0, v % 1 ? 16 : 10).replace('T', ' ');
}

/** styles.xml → 셀 서식 번호(s)마다 '숫자 쉼표'/'날짜'/없음 */
function readStyles(doc: Document | null): ('comma' | 'date' | null)[] {
  if (!doc) return [];
  const custom = new Map(all(doc, 'numFmt').map((f) => [Number(f.getAttribute('numFmtId')), f.getAttribute('formatCode') ?? '']));
  const xfs = all(all(doc, 'cellXfs')[0] ?? null, 'xf');
  return xfs.map((xf) => {
    const id = Number(xf.getAttribute('numFmtId') ?? 0);
    const code = custom.get(id) ?? '';
    if ((id >= 14 && id <= 22) || /[yd]/i.test(code.replace(/"[^"]*"/g, ''))) return 'date';
    if (id === 3 || id === 4 || code.includes('#,##')) return 'comma';
    return null;
  });
}

export async function readXlsx(blob: Blob): Promise<SheetPreview[]> {
  const files = await unzip(await blob.arrayBuffer(), (n) => n.startsWith('xl/'));
  const shared = all(xml(files.get('xl/sharedStrings.xml')), 'si').map((si) => all(si, 't').map((t) => t.textContent ?? '').join(''));
  const styles = readStyles(xml(files.get('xl/styles.xml')));
  // 시트 이름 → 파일 경로(workbook.xml의 r:id → workbook.xml.rels의 Target)
  const rels = new Map(all(xml(files.get('xl/_rels/workbook.xml.rels')), 'Relationship')
    .map((r) => [r.getAttribute('Id') ?? '', (r.getAttribute('Target') ?? '').replace(/^\/?(xl\/)?/, 'xl/')]));
  const sheets = all(xml(files.get('xl/workbook.xml')), 'sheet').map((sh) => ({
    name: sh.getAttribute('name') ?? '시트',
    path: rels.get(sh.getAttributeNS('http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'id') ?? sh.getAttribute('r:id') ?? '') ?? '',
  }));

  return sheets.map(({ name, path }) => {
    const rowsEl = all(xml(files.get(path)), 'row');
    let totalCols = 0;
    const rows: string[][] = [];
    for (const row of rowsEl.slice(0, MAX_ROWS)) {
      const line: string[] = [];
      for (const c of all(row, 'c')) {
        const idx = colIndex(c.getAttribute('r') ?? 'A1');
        totalCols = Math.max(totalCols, idx + 1);
        if (idx >= MAX_COLS) continue;
        const t = c.getAttribute('t');
        const raw = all(c, 'v')[0]?.textContent ?? '';
        let v = raw;
        if (t === 's') v = shared[Number(raw)] ?? '';
        else if (t === 'inlineStr') v = all(c, 't').map((x) => x.textContent ?? '').join('');
        else if (t === 'b') v = raw === '1' ? 'TRUE' : 'FALSE';
        else if (!t || t === 'n') {
          const num = Number(raw);
          const fmt = styles[Number(c.getAttribute('s') ?? 0)];
          if (raw !== '' && !Number.isNaN(num)) v = fmt === 'date' ? serialDate(num) : fmt === 'comma' ? num.toLocaleString('ko-KR') : raw;
        }
        while (line.length < idx) line.push('');
        line[idx] = v;
      }
      rows.push(line);
    }
    return { name, rows, totalRows: rowsEl.length, totalCols };
  });
}

/** CSV 미리보기(따옴표 안 쉼표·줄바꿈 처리) */
export function readCsv(text: string): SheetPreview {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  const src = text.replace(/^﻿/, '');
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"' && src[i + 1] === '"') { cell += '"'; i++; } else if (ch === '"') quoted = false; else cell += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') { row.push(cell); cell = ''; }
    else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && src[i + 1] === '\n') i++;
      row.push(cell); rows.push(row); row = []; cell = '';
    } else cell += ch;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  const totalCols = Math.max(0, ...rows.map((r) => r.length));
  return { name: 'CSV', rows: rows.slice(0, MAX_ROWS).map((r) => r.slice(0, MAX_COLS)), totalRows: rows.length, totalCols };
}
