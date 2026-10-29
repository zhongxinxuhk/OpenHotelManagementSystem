package cn.sec.hn.openbackupsaas.shared.error;

/**
 * 未认证 / 凭据无效（S1-03）。
 *
 * <p>为什么不用 Spring Security 的 {@code BadCredentialsException}：那是认证管理器内部异常，
 * 本项目没有走 {@code AuthenticationManager}（会话由自有过滤器和会话表处理），
 * 统一用自己的异常类型，才能被 {@code GlobalExceptionHandler} 稳定映射成 401。
 *
 * <p>对外文案必须**不区分**"租户不存在 / 账号不存在 / 密码错误 / 账号停用"，
 * 否则登录接口就成了账号枚举器。
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
