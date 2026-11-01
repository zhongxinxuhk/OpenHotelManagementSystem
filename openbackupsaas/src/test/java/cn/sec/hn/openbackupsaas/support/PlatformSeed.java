package cn.sec.hn.openbackupsaas.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

/**
 * 平台账号夹具（ADR-019）。
 *
 * <p>为什么直接写库而不是走接口：本轮**没有**"创建平台账号"的接口——平台账号由
 * 种子脚本建立（`scripts/seed-platform-admin.sql`），日常不通过 API 开通。
 * 测试需要一个能登录的账号来证明平面隔离与审核闭环，所以在这里造。
 *
 * <p>口令统一用 {@link IdentitySeed#RAW_PASSWORD}：跨夹具一个口令，测试里少一个变量。
 */
public final class PlatformSeed {

    private PlatformSeed() {
    }

    /**
     * 建一个平台账号并授予若干动作码。
     *
     * @return 新建账号的 id
     */
    public static UUID create(JdbcTemplate jdbcTemplate,
                              PasswordEncoder passwordEncoder,
                              String username,
                              String displayName,
                              String... actions) {
        return create(jdbcTemplate, passwordEncoder, username, displayName, false, actions);
    }

    /** 带 {@code mustChangePassword} 的变体：验证强制首改拦截时用。 */
    public static UUID create(JdbcTemplate jdbcTemplate,
                              PasswordEncoder passwordEncoder,
                              String username,
                              String displayName,
                              boolean mustChangePassword,
                              String... actions) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO platform_user (id, username, display_name, password_hash,
                                                   status, must_change_password, version)
                        VALUES (?, ?, ?, ?, 'ACTIVE', ?, 0)
                        """,
                userId, username, displayName, passwordEncoder.encode(IdentitySeed.RAW_PASSWORD),
                mustChangePassword);
        for (String action : actions) {
            // 动作码有外键到字典：拼错会直接插入失败，不会静默授予一个不存在的权限
            jdbcTemplate.update(
                    "INSERT INTO platform_user_action (platform_user_id, action_code) VALUES (?, ?)",
                    userId, action);
        }
        return userId;
    }
}
