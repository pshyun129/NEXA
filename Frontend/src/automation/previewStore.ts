import { create } from 'zustand';

// 원본 미리보기(설계 6.5, 요구사항 6장 #14): 다운로드 API가 없어서 이 브라우저에서 올린 파일만
// URL.createObjectURL로 보여 준다. 메모리에만 두고(IndexedDB 안 씀) 새로고침하면 사라진다.

export interface LocalPreview { name: string; url: string; type: string }

interface State {
  byFile: Record<number, LocalPreview>;
  byRun: Record<number, LocalPreview[]>;
  addFile: (fileId: number, file: File) => void;
  removeFile: (fileId: number) => void;
  attachToRun: (runId: number, fileIds: number[]) => void;
}

export const usePreviewStore = create<State>((set, get) => ({
  byFile: {},
  byRun: {},
  addFile: (fileId, file) =>
    set((s) => ({ byFile: { ...s.byFile, [fileId]: { name: file.name, url: URL.createObjectURL(file), type: file.type } } })),
  removeFile: (fileId) => {
    const p = get().byFile[fileId];
    if (p) URL.revokeObjectURL(p.url);
    set((s) => {
      const next = { ...s.byFile };
      delete next[fileId];
      return { byFile: next };
    });
  },
  attachToRun: (runId, fileIds) =>
    set((s) => ({ byRun: { ...s.byRun, [runId]: fileIds.map((id) => s.byFile[id]).filter(Boolean) } })),
}));
