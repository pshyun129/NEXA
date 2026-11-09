import { QueryClient, keepPreviousData } from '@tanstack/react-query';
import { isApiError } from './ApiError';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      placeholderData: keepPreviousData,
      refetchOnWindowFocus: true,
      staleTime: 30_000,
      // 502/504/네트워크만 GET 1회 재시도(설계 7.2). 4xx는 재시도하지 않는다.
      retry: (count, err) =>
        count < 1 && isApiError(err) && (err.status === 0 || err.status === 502 || err.status === 504),
    },
    mutations: { retry: false },
  },
});
