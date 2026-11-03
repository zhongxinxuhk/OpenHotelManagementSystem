package cn.sec.hn.openbackupsaas.shared.error;

/**
 * 目标对象不存在（S1-03 引入，供身份接口使用）。
 *
 * <p>与 403 的分工必须严格：
 * <ul>
 *   <li><b>不属于当前租户</b>的对象视为不存在（404），不是 403——
 *       403 会告诉调用方"这个 id 确实存在，只是你的权限不够"，属于对象存在性泄露（BL-07）。</li>
 *   <li>确实存在、但当前身份无权操作 → 403（{@code AccessDeniedException}）。</li>
 * </ul>
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
