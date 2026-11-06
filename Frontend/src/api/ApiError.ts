// 서비스 봉투 오류와 gateway 직접 오류를 하나로 다룬다(요구사항 1.3)
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | null;
  /** 서비스 봉투 error.details(오류별 추가 정보). 없으면 {} */
  readonly details: Record<string, unknown>;

  constructor(status: number, code: string, message: string, requestId: string | null,
              details?: Record<string, unknown> | null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.details = details ?? {};
  }

  /** details의 숫자 값. 없거나 숫자가 아니면 undefined */
  detailNumber(key: string): number | undefined {
    const v = this.details[key];
    return typeof v === 'number' && Number.isFinite(v) ? v : undefined;
  }

  /** COMMON_001 "field: msg, field2: msg" → [{name, errors}] (antd form.setFields 형식) */
  fieldErrors(): { name: string; errors: string[] }[] {
    if (this.code !== 'COMMON_001') return [];
    return this.message
      .split(/,\s*(?=[\w.[\]]+:\s)/)
      .map((part) => {
        const idx = part.indexOf(':');
        if (idx < 0) return null;
        return { name: part.slice(0, idx).trim(), errors: [part.slice(idx + 1).trim()] };
      })
      .filter((v): v is { name: string; errors: string[] } => v !== null && /^[\w.[\]]+$/.test(v.name));
  }
}

export const isApiError = (e: unknown): e is ApiError => e instanceof ApiError;
