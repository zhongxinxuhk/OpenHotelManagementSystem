/**
 * 开发/联调代理（S3-01）。
 *
 * 前端与后端同源部署（生产由 nginx 反代 `/api`），所以开发时也要把
 * `/api` 代理到本地后端——否则前端请求会打到模板示例地址上，
 * 联调时看到的是"接口 404"，很容易误判成后端没实现。
 *
 * 目标地址从环境变量读，默认 127.0.0.1:8080（与后端 PMS_SERVER_PORT 默认值一致）。
 * 两者必须跟着各自的口径走，不要在这里写死一个跟后端不一致的端口。
 *
 * @doc https://umijs.org/docs/guides/proxy
 */
const backendTarget = process.env.PMS_API_TARGET || 'http://127.0.0.1:8080';

export default {
  dev: {
    '/api/': {
      target: backendTarget,
      changeOrigin: true,
    },
    // 健康检查与指标：联调时可以直接在浏览器里看后端状态
    '/actuator': {
      target: backendTarget,
      changeOrigin: true,
    },
  },
  test: {
    '/api/': {
      target: backendTarget,
      changeOrigin: true,
    },
    '/actuator': {
      target: backendTarget,
      changeOrigin: true,
    },
  },
  pre: {
    '/api/': {
      target: backendTarget,
      changeOrigin: true,
    },
    '/actuator': {
      target: backendTarget,
      changeOrigin: true,
    },
  },
};
