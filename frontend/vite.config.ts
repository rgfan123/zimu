import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// 本地开发也只经过真实管理网关：服务端操作人/后端凭据注入和命名空间隔离均由 Nginx 执行。
// 浏览器开发服务器不持有管理凭据，也不直连 Spring Boot。
const managementGateway = process.env.DEV_MANAGEMENT_GATEWAY_URL ?? 'http://127.0.0.1:8088';

// 仅本地验收场景：DEV_MANAGEMENT_GATEWAY_URL 指向无 Nginx 的后端（如 8081 验收实例）时，
// 用 DEV_GATEWAY_BASIC_AUTH（user:password，勿提交）在代理层注入 Basic 凭据。
const gatewayBasicAuth = process.env.DEV_GATEWAY_BASIC_AUTH;

function withGatewayAuth(): Record<string, unknown> {
  const proxy: Record<string, unknown> = { target: managementGateway, changeOrigin: true };
  if (gatewayBasicAuth) {
    const [user] = gatewayBasicAuth.split(':');
    const credentials = Buffer.from(gatewayBasicAuth).toString('base64');
    proxy.configure = (proxyServer: { on: (event: string, cb: (proxyReq: { setHeader: (k: string, v: string) => void }, req: unknown, res: unknown) => void) => void }) => {
      proxyServer.on('proxyReq', (proxyReq) => {
        proxyReq.setHeader('Authorization', `Basic ${credentials}`);
        // 后端要求 X-Operator 与 Basic 凭据用户名一致（无 Nginx 网关时由代理补齐）
        if (user) {
          proxyReq.setHeader('X-Operator', user);
        }
      });
    };
  }
  return proxy;
}

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': withGatewayAuth(),
      '/demo/v1': withGatewayAuth(),
      '/actuator': withGatewayAuth(),
      '/customer/v1/order-assistant': withGatewayAuth(),
      '/metabase': withGatewayAuth(),
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    rollupOptions: {
      output: {
        // 按框架拆分 vendor chunk，避免单个超大 bundle（antd + echarts 体积大头）
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd', '@ant-design/icons'],
          echarts: ['echarts'],
        },
      },
    },
  },
});
