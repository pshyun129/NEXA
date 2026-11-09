import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp, ConfigProvider } from 'antd';
import koKR from 'antd/locale/ko_KR';
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css';
import '@fontsource/jetbrains-mono/500.css';
import './styles/tokens.css';
import './styles/global.css';
import '@/format'; // dayjs 플러그인·로케일·Asia/Seoul 초기화
import { antdTheme } from './styles/antdTheme';
import { queryClient } from './api/queryClient';
import { setSessionExpiredHandler } from './api/client';
import { useSession } from './auth/session';
import { EXPIRED_FLAG } from './pages/login/LoginPage';
import { App } from './App';

// reissue 실패: 세션을 비우면 SessionGate가 로그인(돌아올 URL 보존)으로 보낸다(설계 7.2)
setSessionExpiredHandler(() => {
  const { status } = useSession.getState();
  if (status === 'anonymous') return; // 한 번만
  // 쓰던 세션이 끊긴 경우만 "세션 만료"를 띄운다. 앱 시작 시 /me 확인 실패(처음 방문)는 안내하지 않는다.
  if (status === 'authenticated') {
    try { sessionStorage.setItem(EXPIRED_FLAG, '1'); } catch { /* noop */ }
  }
  queryClient.clear();
  useSession.getState().clear();
});

// reduced-motion은 앱 시작 시 1회 판정한다(style-guide 12.5)
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider theme={antdTheme(reduced)} locale={koKR} wave={{ disabled: true }}>
      <AntApp notification={{ placement: 'bottomRight', maxCount: 3 }}>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);
