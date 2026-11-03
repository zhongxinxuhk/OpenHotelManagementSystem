package cn.sec.hn.openbackupsaas.shared.error;

/**
 * 登录方式未开放（ADR-019 §2.9）。
 *
 * <p>与"凭据无效"（401）是两类错误：前者说的是**方式**，不涉及任何具体账号，
 * 因此可以给出明确文案；后者说的是**账号**，必须统一话术防枚举。
 * 把两者混成一个响应，要么泄露"哪些方式关了"，要么让被关方式的使用者
 * 误以为自己密码错了——两种都难排查。
 */
public class LoginMethodUnavailableException extends RuntimeException {

    public LoginMethodUnavailableException(String message) {
        super(message);
    }
}
