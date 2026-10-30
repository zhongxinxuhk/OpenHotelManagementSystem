package cn.sec.hn.openbackupsaas.integration.domain;

/**
 * 查询外部系统后得到的结论（S2-03）。
 *
 * <p>为什么"查不到"要单独一种：查不到不等于失败——可能对方还没处理完，
 * 也可能确实没收到。此时既不能标成功，也不能断定失败，只能继续按未知处理。
 */
public record QueryOutcome(Kind kind, String detail, String resultRef) {

    public enum Kind {
        /** 对方确认执行成功。 */
        FOUND_SUCCESS,
        /** 对方确认执行失败（且是终局失败）。 */
        FOUND_FAILED,
        /** 对方明确没有这条记录：可以安全重发。 */
        NOT_FOUND,
        /** 查不到结论（对方也说不清 / 查询本身超时）。 */
        UNKNOWN
    }

    public static QueryOutcome foundSuccess(String resultRef) {
        return new QueryOutcome(Kind.FOUND_SUCCESS, null, resultRef);
    }

    public static QueryOutcome foundFailed(String detail) {
        return new QueryOutcome(Kind.FOUND_FAILED, detail, null);
    }

    public static QueryOutcome notFound() {
        return new QueryOutcome(Kind.NOT_FOUND, "对方无该笔记录，可安全重发", null);
    }

    public static QueryOutcome unknown(String detail) {
        return new QueryOutcome(Kind.UNKNOWN, detail, null);
    }
}
