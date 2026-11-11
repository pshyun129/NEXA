import { useState } from 'react';
import { Button, DatePicker, Form, Input, InputNumber, Select, Upload } from 'antd';
import type { UploadFile } from 'antd';
import dayjs from 'dayjs';
import { useDeleteRunFile, useUploadRunFile, type FormField } from '@/api/ai';
import { useNotify } from '@/ui/useNotify';
import { Kbd, modKey } from '@/ui/primitives';
import { usePreviewStore } from './previewStore';
import { PURPOSES, ReceiptFactsTable, receiptRows, type ReceiptFactRow } from './ReceiptFactsTable';
import s from './automation.module.css';

const MAX_BYTES = 20 * 1024 * 1024;

/** 파일 업로드 노드의 허용 형식(FastAPI nodes/impl/file_upload.py ACCEPT_EXTS와 같은 값) */
const ACCEPT: Record<string, { exts: string; hint: string }> = {
  이미지: { exts: '.jpg,.jpeg,.png,.bmp,.webp', hint: '이미지(jpg·png·bmp·webp)' },
  PDF: { exts: '.pdf', hint: 'PDF' },
  엑셀: { exts: '.xlsx,.xls,.csv', hint: '엑셀(xlsx·xls·csv)' },
};

/**
 * 첨부가 필요한 흐름이면 파일 칸을 반드시 넣고 필수로 만든다(서버 workflows/service.py effective_input_form과 같은 규칙).
 * 영수증 규정 검토가 있으면 첨부 칸 뒤에 '영수증별 정보' 표를 넣는다.
 * 사원 실행 폼은 서버가 이미 넣어 주고, 빌더 테스트 실행 폼(초안 그래프)이 이걸 쓴다
 */
export function withFileField(fields: FormField[], accepts: string[], receiptReview = false): FormField[] {
  if (accepts.length === 0) return fields;
  let out = fields.some((f) => f.type === 'file')
    ? fields.map((f) => (f.type === 'file' ? { ...f, required: true, options: f.options.length ? f.options : accepts } : f))
    : [{ key: 'files', label: '첨부 파일', type: 'file' as const, required: true, options: accepts, prefill: null }, ...fields];
  if (receiptReview && !out.some((f) => f.type === 'receipt_facts')) {
    const at = out.map((f) => f.type).lastIndexOf('file') + 1;
    out = [...out.slice(0, at), { key: 'receipts', label: '영수증별 정보', type: 'receipt_facts', required: false, options: PURPOSES, prefill: null },
      ...out.slice(at)];
  }
  return out;
}

export interface SchemaFormValue { inputs: Record<string, unknown>; fileIds: number[] }

/**
 * 입력 폼(요구사항 3.7, 설계 7.1 SchemaForm). 서버 fields[]로 그린다.
 * 파일은 고르는 즉시 POST /runs/files로 올려 fileId를 받는다(서버는 파일을 필드에 묶지 않고 fileIds로만 받는다).
 */
export function SchemaForm({ fields, submitting, submitLabel, onSubmit }:
  { fields: FormField[]; submitting: boolean; submitLabel: string; onSubmit: (v: SchemaFormValue) => void }) {
  const [form] = Form.useForm();
  const upload = useUploadRunFile();
  const del = useDeleteRunFile();
  const notify = useNotify();
  const addPreview = usePreviewStore((st) => st.addFile);
  const removePreview = usePreviewStore((st) => st.removeFile);
  // 필드 key → 올린 파일들(uid, fileId)
  const [files, setFiles] = useState<Record<string, (UploadFile & { fileId?: number })[]>>({});
  const uploading = Object.values(files).flat().some((f) => f.status === 'uploading');
  // 영수증별 정보 표: 파일 uid → 줄, 그리고 모든 장 공통 값
  const [factRows, setFactRows] = useState<Record<string, ReceiptFactRow>>({});
  const [factCommon, setFactCommon] = useState<ReceiptFactRow>({});
  const allFiles = Object.values(files).flat().filter((f) => f.status !== 'error');

  const finish = (values: Record<string, unknown>) => {
    const inputs: Record<string, unknown> = {};
    for (const f of fields) {
      if (f.type === 'file') continue;
      if (f.type === 'receipt_facts') {
        const rows = receiptRows(allFiles.filter((x) => x.fileId != null).map((x) => ({ uid: x.uid, name: x.name, fileId: x.fileId })), factRows, factCommon);
        if (rows) inputs[f.key] = rows;
        continue;
      }
      const v = values[f.key];
      if (v == null || v === '') continue;
      inputs[f.key] = f.type === 'date' && dayjs.isDayjs(v) ? v.format('YYYY-MM-DD') : v;
    }
    const fileIds = Object.values(files).flat().map((f) => f.fileId).filter((x): x is number => x != null);
    onSubmit({ inputs, fileIds });
  };

  const beforeUpload = (key: string) => (file: File) => {
    if (file.size > MAX_BYTES) {
      notify.info('20MB 이하 파일만 올릴 수 있습니다', file.name);
      return Upload.LIST_IGNORE;
    }
    const uid = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}-${file.name}`;
    const item: UploadFile & { fileId?: number } = { uid, name: file.name, status: 'uploading' };
    setFiles((m) => ({ ...m, [key]: [...(m[key] ?? []), item] }));
    // mutate(…, { onSuccess })는 같은 mutation을 연달아 부르면 마지막 호출의 콜백만 불린다(TanStack Query) →
    // 여러 장을 한 번에 고르면 앞 파일들이 '올리는 중'에 머물러 실행 버튼이 안 켜졌다. 파일마다 promise로 받는다
    upload.mutateAsync(file).then(
      (res) => {
        addPreview(res.id, file);
        setFiles((m) => ({ ...m, [key]: (m[key] ?? []).map((x) => (x.uid === uid ? { ...x, status: 'done', fileId: res.id } : x)) }));
        form.setFieldValue(key, true);
        form.validateFields([key]).catch(() => undefined);
      },
      (e: unknown) => {
        notify.error(e, '파일을 올리지 못했습니다');
        setFiles((m) => ({ ...m, [key]: (m[key] ?? []).filter((x) => x.uid !== uid) }));
      },
    );
    return false; // 업로드는 위 mutation으로 직접 한다
  };

  const removeFile = (key: string) => (f: UploadFile & { fileId?: number }) => {
    if (f.fileId != null) { del.mutate(f.fileId); removePreview(f.fileId); }
    setFiles((m) => {
      const rest = (m[key] ?? []).filter((x) => x.uid !== f.uid);
      if (rest.length === 0) form.setFieldValue(key, undefined);
      return { ...m, [key]: rest };
    });
    return true;
  };

  return (
    <Form form={form} layout="vertical" requiredMark onFinish={finish}
          onKeyDown={(e) => {
            if (e.key !== 'Enter') return;
            if (e.ctrlKey || e.metaKey) { e.preventDefault(); form.submit(); return; }
            // Enter만 누르면 브라우저가 폼을 바로 제출한다(날짜 칸에서 날짜를 고르다 실행됨, QA AUTO-B4) → 실행은 버튼·Ctrl+Enter로만
            if (!(e.target instanceof HTMLTextAreaElement)) e.preventDefault();
          }}>
      {fields.map((f) => {
        const rules = f.required ? [{ required: true, message: `${f.label}을(를) 입력해 주세요` }] : [];
        if (f.type === 'receipt_facts') {
          return (
            <Form.Item key={f.key} label={<>{f.label}<span className={s.optional}>선택</span></>}>
              <ReceiptFactsTable files={allFiles.map((x) => ({ uid: x.uid, name: x.name, fileId: x.fileId }))} purposes={f.options}
                                 rows={factRows} common={factCommon} onRows={setFactRows} onCommon={setFactCommon} />
            </Form.Item>
          );
        }
        if (f.type === 'file') {
          // 허용 형식이 정해져 있으면 파일 고르기 창도 그 형식만 보여 준다(서버가 실행 시작 때 다시 검사한다)
          const kinds = f.options.filter((o) => ACCEPT[o]);
          return (
            <Form.Item key={f.key} name={f.key} label={f.label} rules={f.required ? [{ required: true, message: `${f.label}을(를) 올려 주세요` }] : []}>
              <Upload.Dragger multiple fileList={files[f.key] ?? []} beforeUpload={beforeUpload(f.key)} onRemove={removeFile(f.key)}
                              accept={kinds.length ? kinds.map((k) => ACCEPT[k].exts).join(',') : undefined}>
                <div className={s.drop}>
                  <b>{kinds.includes('이미지') ? '영수증·증빙 사진을 끌어 놓거나 눌러서 고르세요' : '파일을 끌어 놓거나 눌러서 고르세요'}</b>
                  <span className={s.muted}>
                    {kinds.length ? `${kinds.map((k) => ACCEPT[k].hint).join(' · ')} · ` : ''}20MB 이하{f.required ? ' · 첨부해야 실행할 수 있어요' : ''}
                  </span>
                </div>
              </Upload.Dragger>
            </Form.Item>
          );
        }
        return (
          <Form.Item key={f.key} name={f.key} label={f.label} rules={rules}>
            {f.type === 'number' ? <InputNumber style={{ width: 200 }} />
              : f.type === 'date' ? <DatePicker />
              : f.type === 'select' ? <Select style={{ width: 240 }} options={f.options.map((o) => ({ value: o, label: o }))} />
              : <Input maxLength={500} />}
          </Form.Item>
        );
      })}
      <div className={s.formFoot}>
        <Button type="primary" htmlType="submit" loading={submitting} disabled={uploading}>
          {submitLabel} <Kbd onPrimary>{modKey}</Kbd>+<Kbd onPrimary>Enter</Kbd>
        </Button>
        {uploading && <span className={s.muted}>파일을 올리는 중입니다</span>}
      </div>
    </Form>
  );
}
