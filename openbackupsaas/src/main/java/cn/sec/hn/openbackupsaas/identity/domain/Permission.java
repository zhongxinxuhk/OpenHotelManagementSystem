package cn.sec.hn.openbackupsaas.identity.domain;

/**
 * 权限点（全局字典项）。
 *
 * <p><b>没有 tenantId。</b>权限点是代码里的常量，由各次迁移登记进 {@code permission} 表；
 * 租户差异体现在"哪个角色拿到哪些权限点"，而不是各自定义权限名。
 * 这与 {@code role} / {@code app_user} 等带租户的表不同，是本域唯一一处全局共享的数据。
 *
 * <p>命名规则「资源:动作」由数据库约束 {@code ck_permission_code_fmt} 强制
 * （{@code ^[a-z]+:[a-z]+$}），避免出现 {@code read_org} 这类风格混杂的名字。
 *
 * @param code        权限点编码，如 {@code room:read}
 * @param name        展示名，如「读取房型与房间」
 * @param description 该权限具体覆盖什么操作；可为 null
 */
public record Permission(
        String code,
        String name,
        String description
) {
}
