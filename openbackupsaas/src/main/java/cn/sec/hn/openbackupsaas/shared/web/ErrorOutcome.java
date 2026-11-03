package cn.sec.hn.openbackupsaas.shared.web;

/**
 * 错误结果的**机器可读三分类**（S1-11 冻结）。
 *
 * <p>为什么不能只靠 HTTP 状态码让客户端判断：
 * 404 既可能是"订单不存在"，也可能是"不是你的订单"；409 既可能是库存冲突，
 * 也可能是版本过期。前端如果按状态码写分支，迟早会把"该重试的"当成"该报错的"。
 * 因此每个 ProblemDetail 都带上 {@code outcome}：
 * <ul>
 *   <li>{@link #REJECTED} —— 调用方输入或权限问题，改输入/换账号才能成功（400/401/403/404）；</li>
 *   <li>{@link #CONFLICT} —— 与当前状态冲突，客户端应提示而不是自动重试（409）；</li>
 *   <li>{@link #UNKNOWN} —— 服务端未预期失败，**结果不确定**：必须给"查询原交易结果"入口，
 *       不能显示成"失败"（500/超时）。这是 S1-07 幂等与 D-10 存在的前提。</li>
 * </ul>
 */
public final class ErrorOutcome {

    public static final String REJECTED = "REJECTED";
    public static final String CONFLICT = "CONFLICT";
    public static final String UNKNOWN = "UNKNOWN";

    private ErrorOutcome() {
    }
}
