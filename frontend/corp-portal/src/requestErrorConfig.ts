/**
 * 统一请求层与错误处理（S3-01）。
 *
 * 三件事在这里收口，页面里不再散落 `request()`：
 * <ol>
 *   <li><b>令牌注入</b>：每次请求带上 {@code Authorization: Bearer <token>}。
 *       后端用不透明 Bearer 令牌 + 服务端会话，令牌不携带身份信息，撤销立即生效。</li>
 *   <li><b>401 统一处理</b>：会话失效时清会话并跳登录页，带上原路径以便登录后回跳。
 *       这个判断只能在请求层做——页面各自处理必然漏。</li>
 *   <li><b>错误三分类</b>：按后端 ProblemDetail 的 {@code outcome} 区分
 *       失败 / 冲突 / 待核实。第三类（UNKNOWN）不给"失败"字样，
 *       而是提示查询原交易结果（前端规范 §4.2）。</li>
 * </ol>
 */
import type { RequestConfig, RequestOptions } from '@umijs/max';
import { message, notification } from 'antd';
import {
  describeProblem,
  type ErrorOutcome,
  isPasswordChangeRequired,
  needsOperationLookup,
  type PmsProblem,
  toProblem,
} from '@/services/pms/errors';
import { clearSession, getToken } from '@/services/pms/session';

/** 登录页路径；会话失效时跳转到这里。 */
const LOGIN_PATH = '/user/login';

/** 强制改密页路径。放在 `/user` 下且不进主框架：主框架顶栏的门店选择器会拉业务接口，
 *  而待改密状态下这些接口一律被拒，会造成"页面在报错、还不知道为什么"。 */
const PASSWORD_PATH = '/user/password';

/**
 * 后端的 baseURL 与前端同源（开发时走 dev server 代理，生产由 nginx 反代）。
 * 不用第三方示例地址——那会让联调时请求打到别人的服务器。
 */
const BASE_URL = '';

/** 401 只提示一次：并发请求同时 401 时不刷屏。 */
let unauthorizedNotified = false;

/** 跳登录并带上原路径，登录后可以回到原来要去的页面。 */
function redirectToLogin(): void {
  if (typeof window === 'undefined') return;
  const { pathname, search, hash } = window.location;
  if (pathname === LOGIN_PATH) return;
  const redirect = encodeURIComponent(`${pathname}${search}${hash}`);
  window.location.href = `${LOGIN_PATH}?redirect=${redirect}`;
}

/**
 * 跳到强制改密页，并带上"改完之后去哪"。
 *
 * 不在改密页时才是跳转：否则任何在改密页上发生的请求失败都会把页面重新加载一遍，
 * 表单里刚输入的内容全丢。
 */
function redirectToPasswordChange(): void {
  if (typeof window === 'undefined') return;
  const { pathname, search, hash } = window.location;
  if (pathname === PASSWORD_PATH) return;
  const redirect = encodeURIComponent(`${pathname}${search}${hash}`);
  window.location.href = `${PASSWORD_PATH}?redirect=${redirect}`;
}

/**
 * 错误分类 → 用户可读文案。
 *
 * 文案不写"操作失败"这类无信息内容，而是说明**发生了什么、下一步做什么**。
 */
function outcomeText(
  outcome: ErrorOutcome | undefined,
  problem: PmsProblem,
): string {
  const detail = describeProblem(problem);
  if (outcome === 'CONFLICT') {
    return `冲突：${detail}`;
  }
  if (outcome === 'UNKNOWN') {
    // 结果不确定，不能说"失败"——服务端可能已经写库成功。
    return `结果待核实：${detail}`;
  }
  // REJECTED 与未标注 outcome 都按"直接报原因"处理。
  return detail;
}

/**
 * 统一的请求拦截与错误处理。
 *
 * 注意这里**不使用** umi 默认的 `errorThrower`/`errorHandler` 那套 success/data 结构：
 * 后端返回的是 RFC 9457 ProblemDetail，形状完全不同，
 * 用模板那套会导致所有错误都落到"Response status:xxx"的兜底分支。
 */
export const errorConfig: RequestConfig = {
  baseURL: BASE_URL,

  requestInterceptors: [
    (config: RequestOptions) => {
      const token = getToken();
      if (token) {
        config.headers = {
          ...config.headers,
          Authorization: `Bearer ${token}`,
        };
      }
      return config;
    },
  ],

  // umi 的错误处理挂在 errorConfig 子对象下（见 @umijs/plugins request 插件的类型定义）。
  errorConfig: {
    errorHandler: (error: unknown, opts: RequestOptions) => {
      const problem = toProblem(error);

      /*
       * 待改密必须排在 `skipErrorHandler` 之前。
       *
       * 这个判断描述的是"整个身份处于受限状态"，不是某一次页面调用的结果：
       * 页面的 catch 里只会弹一句"请先修改口令"，然后人留在原地反复点。
       * 而各页面都传了 skipErrorHandler（它们要自己决定提示方式），
       * 放在那个判断之后等于对所有页面调用都失效。
       */
      if (isPasswordChangeRequired(problem)) {
        redirectToPasswordChange();
        throw error;
      }

      // 调用方声明自己处理时，不在这里弹提示（页面需要自定义展示）。
      if (opts?.skipErrorHandler) throw error;

      /*
       * 被限流（429）：不清会话、不跳登录页，也不说"服务异常"。
       *
       * 这三件事都会做错方向——清会话等于把一次"点太快"变成"被登出"；
       * 说服务异常会让人去联系运维，而正确动作只是等一下。
       * 后端给的 Retry-After 秒数必须显示出来，否则用户只会立刻再点一次。
       */
      if (problem.status === 429) {
        const seconds = problem.retryAfterSeconds;
        message.warning(
          seconds
            ? `操作过于频繁，请 ${seconds} 秒后再试`
            : '操作过于频繁，请稍后再试',
        );
        return;
      }

      if (problem.status === 401) {
        clearSession();
        if (!unauthorizedNotified) {
          unauthorizedNotified = true;
          message.error('登录状态已失效，请重新登录');
          setTimeout(() => {
            unauthorizedNotified = false;
          }, 3000);
        }
        redirectToLogin();
        return;
      }

      const text = outcomeText(problem.outcome, problem);

      if (needsOperationLookup(problem)) {
        // 结果不确定：给"查询原交易结果"的指引，而不是让人重试。
        notification.open({
          message: '结果待核实',
          description: `${text}。请先按幂等键查询原交易结果，确认是否已写入后再决定是否重试。`,
          duration: 8,
        });
        return;
      }

      message.error(text);
    },
  },
};

export type { ErrorOutcome, PmsProblem };
/**
 * 供页面使用的错误处理入口。
 *
 * 页面大多需要区分"冲突"与"拒绝"来给不同提示（例如冲突时刷新列表，
 * 拒绝时停在原地），所以这里把 ProblemDetail 透传给页面，
 * 而不是在这里就把所有错误压成一句话。
 */
export { describeProblem, needsOperationLookup, toProblem };
