import { useEffect, useMemo, useState } from 'react';
import { Button, Modal, Segmented, Skeleton } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { http } from '@/api/client';
import { runFileDownloadUrl } from '@/api/ai';
import { ErrorPanel } from '@/ui/ErrorPanel';
import { Icon } from '@/ui/Icon';
import { FileText, Upload } from '@/ui/icons';
import { readCsv, readXlsx, type SheetPreview } from './xlsxPreview';
import s from './hub.module.css';

export interface PreviewTarget {
  fileId: number;
  name: string;
  contentType?: string | null;
  /** 방금 올린 파일은 브라우저에 있는 사본(previewStore)을 먼저 쓴다 */
  localUrl?: string;
}

type Kind = 'image' | 'pdf' | 'xlsx' | 'csv' | 'text' | 'other';
export function previewKind(name: string, type?: string | null): Kind {
  const ext = name.toLowerCase().split('.').pop() ?? '';
  if (type?.startsWith('image/') || ['jpg', 'jpeg', 'png', 'bmp', 'webp', 'gif'].includes(ext)) return 'image';
  if (type === 'application/pdf' || ext === 'pdf') return 'pdf';
  if (ext === 'xlsx' || type?.includes('spreadsheetml')) return 'xlsx';
  if (ext === 'csv' || type === 'text/csv') return 'csv';
  if (ext === 'txt' || type?.startsWith('text/')) return 'text';
  return 'other';
}

/** 시트 표: 첫 행은 머리글로 */
function SheetTable({ sheet }: { sheet: SheetPreview }) {
  const [head, ...body] = sheet.rows;
  const cols = Math.max(0, ...sheet.rows.map((r) => r.length));
  const cut = sheet.totalRows > sheet.rows.length || sheet.totalCols > cols;
  return (
    <div className={s.sheetWrap}>
      <table className={s.sheet}>
        <thead><tr><th className={s.sheetNo} />{Array.from({ length: cols }, (_, i) => <th key={i}>{head?.[i] ?? ''}</th>)}</tr></thead>
        <tbody>
          {body.map((r, i) => (
            <tr key={i}>
              <td className={s.sheetNo}>{i + 2}</td>
              {Array.from({ length: cols }, (_, j) => <td key={j} className={/^-?[\d,.]+$/.test(r[j] ?? '') ? s.num : ''}>{r[j] ?? ''}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
      {body.length === 0 && <p className={s.muted}>머리글만 있고 데이터 행이 없어요.</p>}
      {cut && <p className={s.muted}>미리보기는 앞쪽 {sheet.rows.length}행·{cols}열만 보여 줘요(전체 {sheet.totalRows}행·{sheet.totalCols}열). 전체는 내려받아 확인하세요.</p>}
    </div>
  );
}

function Body({ kind, blob, url }: { kind: Kind; blob: Blob; url: string }) {
  const [sheets, setSheets] = useState<SheetPreview[] | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState(0);
  useEffect(() => {
    let alive = true;
    if (kind === 'xlsx') readXlsx(blob).then((v) => alive && setSheets(v), (e: Error) => alive && setError(e.message));
    if (kind === 'csv') blob.text().then((t) => alive && setSheets([readCsv(t)]));
    if (kind === 'text') blob.text().then((t) => alive && setText(t.slice(0, 20_000)));
    return () => { alive = false; };
  }, [kind, blob]);

  if (kind === 'image') return <div className={s.previewImgWrap}><img src={url} alt="" /></div>;
  if (kind === 'pdf') return <iframe src={url} title="PDF 미리보기" className={s.previewPdf} />;
  if (error) return <div className={s.previewNone}>미리보기를 만들지 못했어요({error}). 내려받아 확인하세요.</div>;
  if (kind === 'text') return text == null ? <Skeleton active={false} /> : <pre className={s.previewText}>{text}</pre>;
  if (kind === 'xlsx' || kind === 'csv') {
    if (!sheets) return <Skeleton active={false} paragraph={{ rows: 6 }} />;
    if (sheets.length === 0) return <div className={s.previewNone}>시트가 없어요.</div>;
    return (
      <div className={s.previewSheets}>
        {sheets.length > 1 && (
          <Segmented<number> value={tab} onChange={setTab} options={sheets.map((sh, i) => ({ label: sh.name, value: i }))} />
        )}
        <SheetTable sheet={sheets[Math.min(tab, sheets.length - 1)]} />
      </div>
    );
  }
  return <div className={s.previewNone}>이 형식은 미리보기를 지원하지 않아요. 내려받아 확인하세요.</div>;
}

/**
 * 결과·원본 파일 미리보기 창: 엑셀(xlsx)·CSV는 표로, PDF는 브라우저 PDF 보기로, 이미지는 그대로 보여 주고
 * 확인한 뒤 [내려받기]. 파일은 다운로드 주소(요청자만, 쿠키 인증)에서 한 번 받아 미리보기와 내려받기에 같이 쓴다.
 */
export function FilePreview({ target, onClose }: { target: PreviewTarget | null; onClose: () => void }) {
  const file = useQuery({
    queryKey: ['runFile', target?.fileId, target?.localUrl ?? 'server'],
    queryFn: async () => (target!.localUrl
      ? fetch(target!.localUrl).then((r) => r.blob())
      : (await http.get<Blob>(runFileDownloadUrl(target!.fileId), { responseType: 'blob' })).data),
    enabled: target != null,
    staleTime: 5 * 60_000,
    retry: false,
  });
  const url = useMemo(() => (file.data ? URL.createObjectURL(file.data) : null), [file.data]);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  const kind = target ? previewKind(target.name, target.contentType ?? file.data?.type) : 'other';

  return (
    <Modal open={target != null} onCancel={onClose} destroyOnHidden centered zIndex={1150}
           width={kind === 'image' ? 'min(900px, 92vw)' : 'min(1180px, 94vw)'}
           title={<span className={s.previewTitle}><Icon as={FileText} size={16} />{target?.name}</span>}
           footer={[
             <Button key="close" onClick={onClose}>닫기</Button>,
             // 이미 받은 파일을 그대로 저장한다(다시 요청하지 않음). 못 받았으면 서버 주소로 내려받는다
             <a key="dl" href={url ?? (target ? runFileDownloadUrl(target.fileId) : undefined)} download={target?.name}>
               <Button type="primary" icon={<Icon as={Upload} size={16} style={{ transform: 'rotate(180deg)' }} />}>내려받기</Button>
             </a>,
           ]}>
      {file.isError ? <ErrorPanel error={file.error} onRetry={() => file.refetch()} />
        : !file.data || !url ? <Skeleton active={false} paragraph={{ rows: 6 }} />
        : <Body kind={kind} blob={file.data} url={url} />}
    </Modal>
  );
}
