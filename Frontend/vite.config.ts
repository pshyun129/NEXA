import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// 요구사항 1.2: 브라우저는 항상 같은 origin(/api)으로만 요청하고, Vite가 gateway로 넘긴다.
// Origin 헤더를 지워 gateway가 CORS 요청으로 보지 않게 한다(vite --host LAN 접속 대비).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const gateway = env.VITE_GATEWAY_URL || 'http://localhost:8080';
  return {
    plugins: [react()],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: gateway,
          changeOrigin: true,
          configure: (proxy) => {
            proxy.on('proxyReq', (req) => req.removeHeader('origin'));
          },
        },
      },
    },
  };
});
