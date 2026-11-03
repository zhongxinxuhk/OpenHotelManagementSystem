package cn.sec.hn.openbackupsaas.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * <p>{@code tenantCode} 出现在请求体里是**唯一例外**：登录前还没有身份，
 * 只能用租户编码定位账号所在的租户。登录之后所有请求的租户都来自会话，不再读请求体（B-06）。
 */
public record LoginRequest(
        @NotBlank(message = "租户编码不能为空")
        @Size(max = 32, message = "租户编码过长")
        String tenantCode,

        @NotBlank(message = "登录名不能为空")
        @Size(max = 64, message = "登录名过长")
        String username,

        @NotBlank(message = "口令不能为空")
        @Size(max = 128, message = "口令过长")
        String password
) {
}
