import { useState } from 'react';
import { Button, DatePicker, Form, Input, InputNumber, Select, Upload } from 'antd';
import type { UploadFile } from 'antd';
import dayjs from 'dayjs';
import { useDeleteRunFile, useUploadRunFile, type FormField } from '@/api/ai';
import { useNotify } from '@/ui/useNotify';
import { Kbd, modKey } from '@/ui/primitives';
import { usePreviewStore } from './previewStore';
import s from './automation.module.css';

const MAX_BYTES = 20 * 1024 * 1024;

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

  const finish = (values: Record<string, unknown>) => {
    const inputs: Record<string, unknown> = {};
    for (const f of fields) {
      if (f.type === 'file') continue;
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
    const uid = `${Date.now()}-${file.name}`;
    const item: UploadFile & { fileId?: number } = { uid, name: file.name, status: 'uploading' };
    setFiles((m) => ({ ...m, [key]: [...(m[key] ?? []), item] }));
    upload.mutate(file, {
      onSuccess: (res) => {
        addPreview(res.id, file);
        setFiles((m) => ({ ...m, [key]: (m[key] ?? []).map((x) => (x.uid === uid ? { ...x, status: 'done', fileId: res.id } : x)) }));
        form.setFieldValue(key, true);
        form.validateFields([key]).catch(() => undefined);
      },
      onError: (e) => {
        notify.error(e, '파일을 올리지 못했습니다');
        setFiles((m) => ({ ...m, [key]: (m[key] ?? []).filter((x) => x.uid !== uid) }));
      },
    });
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
        if (f.type === 'file') {
          return (
            <Form.Item key={f.key} name={f.key} label={f.label} rules={f.required ? [{ required: true, message: `${f.label}을(를) 올려 주세요` }] : []}>
              <Upload.Dragger multiple fileList={files[f.key] ?? []} beforeUpload={beforeUpload(f.key)} onRemove={removeFile(f.key)}>
                <div className={s.drop}>
                  <b>파일을 끌어 놓거나 눌러서 고르세요</b>
                  <span className={s.muted}>20MB 이하 · 이미지(jpg·png·bmp·webp)는 글자 인식에 쓰입니다</span>
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
