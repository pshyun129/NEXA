import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { ApiError, isApiError } from './ApiError';
import type { ApiEnvelope } from './types';

// 같은 origin(/api → Vite proxy / nginx). Authorization 헤더는 붙이지 않는다(gateway가 쿠키로 처리).
export const http = axios.create({ baseURL: '', withCredentials: true, timeout: 30_000 });

type RetryConfig = InternalAxiosRequestConfig & { _retry?: boolean };

const AUTH_PATH = '/api/v1/auth/';
let reissuePromise: Promise<void> | null = null;
/** ended: 쓰던 세션이 서버에서 끊김(다른 기기 로그인·퇴직 등) / no-session: refresh 쿠키 자체가 없음 */
export type SessionEndReason = 'ended' | 'no-session';
let onSessionExpired: ((reason: SessionEndReason) => void) | null = null;

/** reissue가 401로 실패하면 호출된다(세션 정리 + 로그인 이동). 앱 시작 시 한 번 등록한다. */
export function setSessionExpiredHandler(fn: (reason: SessionEndReason) => void) {
  onSessionExpired = fn;
}

/** 탭 안에서 reissue를 한 번만 수행한다(refresh 회전: 중복 호출 시 세션이 삭제된다, 요구사항 1.1) */
function reissueOnce(): Promise<void> {
  if (!reissuePromise) {
    reissuePromise = http
      .post(`${AUTH_PATH}reissue`)
      .then(() => undefined)
      .finally(() => {
        reissuePromise = null;
      });
  }
  return reissuePromise;
}

function toApiError(err: AxiosError): ApiError {
  const res = err.response;
  if (!res) {
    return new ApiError(0, 'NETWORK', '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.', null);
  }
  const requestId = (res.headers?.['x-request-id'] as string | undefined) ?? null;
  const body = res.data as Partial<ApiEnvelope<unknown>> & { code?: string; message?: string; requestId?: string };
  if (body && typeof body === 'object') {
    if (body.error && typeof body.error === 'object') {
      return new ApiError(res.status, body.error.code, body.error.message, requestId, body.error.details);
    }
    if (typeof body.code === 'string') {
      // gateway 직접 응답 { code, message, requestId }
      return new ApiError(res.status, body.code, body.message ?? '요청을 처리하지 못했습니다.', body.requestId ?? requestId);
    }
  }
  return new ApiError(res.status, `HTTP_${res.status}`, '요청을 처리하지 못했습니다.', requestId);
}

http.interceptors.response.use(
  (res) => res,
  async (err: AxiosError) => {
    const config = err.config as RetryConfig | undefined;
    const url = config?.url ?? '';
    const shouldReissue =
      err.response?.status === 401 && config && !config._retry && !url.startsWith(AUTH_PATH);

    if (shouldReissue) {
      config._retry = true;
      try {
        await reissueOnce();
      } catch (e) {
        // reissue 요청도 이 인터셉터를 지나므로 ApiError로 온다(/auth/는 reissue 대상 아님).
        // 401만 세션이 끝난 것이다. 5xx·네트워크는 user-service 장애라 로그아웃하지 않고
        // 원 요청의 오류로 돌려 화면이 ErrorPanel·[다시 시도]를 보이게 한다(QA CM-B1/OG-18)
        if (!isApiError(e) || e.status !== 401) throw e;
        // AUTH_007: refresh 쿠키 없음(첫 방문·로그아웃). 그 밖(AUTH_008 다른 기기·회전, AUTH_006 퇴직)은 쓰던 세션이 끊긴 것
        onSessionExpired?.(e.code === 'AUTH_007' ? 'no-session' : 'ended');
        throw new ApiError(401, 'SESSION_EXPIRED', '세션이 만료되었습니다(다른 기기 로그인 포함). 다시 로그인해 주세요.', null);
      }
      return http(config);
    }
    throw toApiError(err);
  },
);

/** 봉투를 벗겨 data만 돌려준다. success=false인데 2xx로 온 경우도 오류로 바꾼다. */
async function unwrap<T>(p: Promise<{ data: ApiEnvelope<T>; headers: Record<string, unknown> }>): Promise<T> {
  const res = await p;
  const body = res.data;
  if (body && typeof body === 'object' && 'success' in body) {
    if (!body.success) {
      throw new ApiError(200, body.error?.code ?? 'UNKNOWN', body.error?.message ?? '요청을 처리하지 못했습니다.',
        (res.headers['x-request-id'] as string | undefined) ?? null, body.error?.details);
    }
    return body.data;
  }
  return body as unknown as T;
}

export const api = {
  get: <T>(url: string, config?: AxiosRequestConfig) => unwrap<T>(http.get(url, config)),
  post: <T>(url: string, body?: unknown, config?: AxiosRequestConfig) => unwrap<T>(http.post(url, body, config)),
  put: <T>(url: string, body?: unknown, config?: AxiosRequestConfig) => unwrap<T>(http.put(url, body, config)),
  patch: <T>(url: string, body?: unknown, config?: AxiosRequestConfig) => unwrap<T>(http.patch(url, body, config)),
  delete: <T>(url: string, config?: AxiosRequestConfig) => unwrap<T>(http.delete(url, config)),
};
