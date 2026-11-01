/**
 * 后端错误契约（S3-01）。
 *
 * 后端所有错误都返回 RFC 9457 ProblemDetail，并附带一个 `outcome` 字段做**三分类**
 * （见后端 ErrorOutcome）：
 *
 * | outcome   | 含义                                   | 前端该怎么显示           |
 * | --------- | -------------------------------------- | ------------------------ |
 * | REJECTED  | 输入/权限问题，改输入或换账号才能成功   | 直接报错误原因           |
 * | CONFLICT  | 与当前状态冲突（占用冲突、版本过期）    | 报冲突，并给可执行的下一步 |
 * | UNKNOWN   | 服务端未预期失败，**结果不确定**        | 不能显示"失败"，必须给"查询原交易结果"入口 |
 *
 * 前端规范 §4.2 要求这三类必须可区分。第三类最关键：把"结果不确定"显示成"失败"，
 * 会让操作员重复下单，造成真实库存损失。
 */
export type ErrorOutcome = 'REJECTED' | 'CONFLICT' | 'UNKNOWN';

/** ProblemDetail 结构（只列前端用得到的字段）。 */
export interface PmsProblem {
  status?: number;
  title?: string;
  detail?: string;
  instance?: string;
  requestId?: string;
  outcome?: ErrorOutcome;
  /**
   * 稳定的机读错误码（部分错误才有）。
   *
   * 目前用于 `PASSWORD_CHANGE_REQUIRED`：账号还在用管理员发的初始口令。
   * 这类 403 与"没权限"必须分开处理——前者该跳改密页，后者该停在原地报错。
   * 只靠状态码分不出来，所以后端专门给了一个码。
   */
  code?: string;
  /**
   * 被限流时后端建议的重试间隔（秒），与 `Retry-After` 响应头同值。
   *
   * 只有 429 才有。这个数字必须显示给用户——"请求过于频繁"但不给等多久，
   * 用户只会立刻再点一次，然后再次被拒。
   */
  retryAfterSeconds?: number;
}

/** 后端约定的"待改密"错误码，与 PasswordChangeRequiredException.CODE 一致。 */
export const PASSWORD_CHANGE_REQUIRED = 'PASSWORD_CHANGE_REQUIRED';

/** 限流错误码，与后端 RateLimitFilter.CODE 一致。 */
export const RATE_LIMITED = 'RATE_LIMITED';

/**
 * 从任意异常里提取 ProblemDetail。
 *
 * 后端的错误体是 `application/problem+json`，axios 会把它放在 `error.response.data`；
 * 但网络层错误、超时没有 response，所以要兼容多种形态。
 */
export function toProblem(error: unknown): PmsProblem {
  const candidate = error as {
    response?: { status?: number; data?: unknown };
    status?: number;
    message?: string;
  };

  const data = candidate?.response?.data;
  if (data && typeof data === 'object') {
    const problem = data as PmsProblem;
    return {
      status: problem.status ?? candidate.response?.status,
      title: problem.title,
      detail: problem.detail,
      instance: problem.instance,
      requestId: problem.requestId,
      outcome: problem.outcome,
      code: problem.code,
      retryAfterSeconds: problem.retryAfterSeconds,
    };
  }

  // 没有响应体：网络不可达或请求被中断。结果不确定，按 UNKNOWN 处理。
  return {
    status: candidate?.response?.status ?? candidate?.status,
    title: '请求未完成',
    detail: candidate?.message,
    outcome: 'UNKNOWN',
  };
}

/**
 * 该错误是否需要"查询原交易结果"入口。
 *
 * 只有 UNKNOWN 才需要：此时服务端可能已经写库成功、只是响应没回来。
 * 让操作员重试会造成重复下单，正确动作是先用幂等键查回原结果。
 */
export function needsOperationLookup(problem: PmsProblem): boolean {
  return problem.outcome === 'UNKNOWN';
}

/** 面向用户的一句话说明。取后端 detail（已是中文业务描述），兜底用 title。 */
export function describeProblem(problem: PmsProblem): string {
  return problem.detail || problem.title || '请求未完成';
}

/**
 * 是否需要先把初始口令改掉。
 *
 * 判的是后端给的错误码而不是状态码：这类响应也是 403，
 * 但把它当成"没权限"处理会让人对着错误提示反复试，永远改不对。
 */
export function isPasswordChangeRequired(problem: PmsProblem): boolean {
  return problem.code === PASSWORD_CHANGE_REQUIRED;
}
