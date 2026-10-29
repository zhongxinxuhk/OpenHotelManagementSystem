package cn.sec.hn.openbackupsaas.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 改密请求：必须提供原口令，避免会话被盗后直接改密锁死原主。 */
public record ChangePasswordRequest(
        @NotBlank(message = "原口令不能为空")
        @Size(max = 128, message = "原口令过长")
        String currentPassword,

        @NotBlank(message = "新口令不能为空")
        @Size(max = 128, message = "新口令过长")
        String newPassword
) {
}
