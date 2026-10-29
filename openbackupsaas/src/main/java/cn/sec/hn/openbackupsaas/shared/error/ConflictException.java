package cn.sec.hn.openbackupsaas.shared.error;

/**
 * 业务冲突：请求本身可理解，但与当前数据状态冲突（编码重复、对象已被占用、状态不允许）。
 *
 * <p>与 {@link IllegalArgumentException}（请求不合法，400）区分：冲突对外映射为 409，
 * 客户端应提示用户处理而不是改输入格式。错误码目录在 S1-11 冻结。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
