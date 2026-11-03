package cn.sec.hn.openbackupsaas.integration.domain;

/**
 * 一次外部调用的结果分类（S2-03）。
 *
 * <p>分类不是为了好看，而是因为<b>四个类别对应四种完全不同的处置</b>：
 * <table border="1">
 *   <caption>分类与处置</caption>
 *   <tr><th>类别</th><th>含义</th><th>处置</th></tr>
 *   <tr><td>{@link Kind#SUCCESS}</td><td>对方明确成功</td><td>标记成功</td></tr>
 *   <tr><td>{@link Kind#RETRYABLE}</td><td>确定没生效（连接被拒、5xx、限流）</td><td>退避后重试</td></tr>
 *   <tr><td>{@link Kind#UNKNOWN}</td><td><b>可能已生效</b>（超时、连接中断、回包超时）</td>
 *       <td>先查询；不能查就转人工。<b>绝不能直接重发</b></td></tr>
 *   <tr><td>{@link Kind#PERMANENT}</td><td>对方明确拒绝（4xx 业务错误、参数错）</td><td>不再重试</td></tr>
 * </table>
 *
 * <p>{@code UNKNOWN} 是这一层存在的主要理由：不区分"确定失败"和"不知道"，
 * 重试就会要么丢单、要么重复扣款。绝大多数对接事故都出在这一格。
 */
public record TaskOutcome(Kind kind, String detail, String resultRef) {

    public enum Kind {
        SUCCESS,
        RETRYABLE,
        UNKNOWN,
        PERMANENT,
        RATE_LIMITED
    }

    public static TaskOutcome success(String resultRef) {
        return new TaskOutcome(Kind.SUCCESS, null, resultRef);
    }

    public static TaskOutcome retryable(String detail) {
        return new TaskOutcome(Kind.RETRYABLE, detail, null);
    }

    /** 请求可能已经到达对方：既不能算成功，也不能当失败重发。 */
    public static TaskOutcome unknown(String detail) {
        return new TaskOutcome(Kind.UNKNOWN, detail, null);
    }

    public static TaskOutcome permanent(String detail) {
        return new TaskOutcome(Kind.PERMANENT, detail, null);
    }

    public static TaskOutcome rateLimited(String detail) {
        return new TaskOutcome(Kind.RATE_LIMITED, detail, null);
    }

    public ErrorClass errorClass() {
        return switch (kind) {
            case SUCCESS -> null;
            case RETRYABLE -> ErrorClass.RETRYABLE;
            case UNKNOWN -> ErrorClass.UNKNOWN;
            case PERMANENT -> ErrorClass.PERMANENT;
            case RATE_LIMITED -> ErrorClass.RATE_LIMITED;
        };
    }
}
