import { create } from 'zustand';

// 원본 미리보기(설계 6.5). 원본은 다운로드 API(runFileDownloadUrl)로 보여 준다.
// 이 브라우저에서 방금 올린 파일은 내려받지 않고 ObjectURL을 먼저 쓴다(메모리에만, 새로고침하면 사라짐).

export interface LocalPreview { name: string; url: string; type: string }

interface State {
  byFile: Record<number, LocalPreview>;
  addFile: (fileId: number, file: File) => void;
  removeFile: (fileId: number) => void;
}

export const usePreviewStore = create<State>((set, get) => ({
  byFile: {},
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
}));
