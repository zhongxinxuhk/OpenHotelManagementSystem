package cn.sec.hn.openbackupsaas.shared.error;

/**
 * 账号仍在使用管理员发放的初始口令，必须先改密才能使用其它功能（V13）。
 *
 * <p>为什么单独一个异常类型，而不是复用 403 的 {@code AccessDeniedException}：
 * 这两件事对客户端的含义完全不同——
 * <ul>
 *   <li>无权限：这个人不该做这件事，找管理员要授权，前端应停留在原页面报错；</li>
 *   <li>待改密：这个人有权限，但得先换口令，前端应**跳到改密页**。</li>
 * </ul>
 * 混成一个之后，前端只能靠"403 就跳改密页"来猜，结果是有权限不足的人被莫名带去改密。
 *
 * <p>响应体里带 {@code code = PASSWORD_CHANGE_REQUIRED}，客户端据此分支。
 */
public class PasswordChangeRequiredException extends RuntimeException {

    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    public PasswordChangeRequiredException(String message) {
        super(message);
    }
}
