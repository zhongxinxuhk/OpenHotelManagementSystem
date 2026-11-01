package cn.sec.hn.openbackupsaas.platform.mapper;

import cn.sec.hn.openbackupsaas.guest.domain.VerificationChannel;
import cn.sec.hn.openbackupsaas.platform.domain.PlatformSession;
import cn.sec.hn.openbackupsaas.platform.domain.PlatformSessionView;
import cn.sec.hn.openbackupsaas.platform.domain.PlatformUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台身份与核验渠道配置的数据访问（ADR-019）。
 *
 * <p>与 {@code IdentityMapper} 完全分开：那张表的每条语句都带 {@code tenant_id}，
 * 这张表**一条都不带**（平台没有租户，只有系统资源）。把两者合成一个 Mapper，
 * 迟早有人在一个方法里既查租户又查平台。
 *
 * <p>核验渠道配置也放在这里而不是 {@code GuestMapper}：渠道是**平台运维配置**，
 * 不是住客业务数据。放在这边有个附带好处——{@code GuestMapper} 里根本不存在
 * 访问渠道表的方法，所以"人工审核路径不读熔断状态"这件事在类型层面就成立。
 */
@Mapper
public interface PlatformMapper {

    // ---------- 账号 ----------

    Optional<PlatformUser> findUserByUsername(@Param("username") String username);

    List<String> findActions(@Param("userId") UUID userId);

    int updateLastLogin(@Param("userId") UUID userId, @Param("at") OffsetDateTime at);

    int updatePasswordHash(@Param("userId") UUID userId,
                           @Param("passwordHash") String passwordHash,
                           @Param("at") OffsetDateTime at);

    // ---------- 会话 ----------

    int insertSession(PlatformSession session);

    Optional<PlatformSessionView> findUsableSession(@Param("tokenHash") String tokenHash);

    int revokeSession(@Param("sessionId") UUID sessionId,
                      @Param("reason") String reason,
                      @Param("at") OffsetDateTime at);

    /**
     * 改密后撤销**除当前之外**的全部会话（ADR-019 §2.3）。
     *
     * <p>平台动作影响面是全局的。口令刚被改掉，最合理的解释是"原口令可能已泄露"——
     * 那么其它设备上的会话就不该继续有效。保留当前会话是唯一的例外：
     * 改密这个动作本身就是那个人发起的。
     */
    int revokeOtherSessions(@Param("userId") UUID userId,
                            @Param("keepSessionId") UUID keepSessionId,
                            @Param("reason") String reason,
                            @Param("at") OffsetDateTime at);

    // ---------- 核验渠道配置 ----------

    List<VerificationChannel> findChannels();

    Optional<VerificationChannel> findChannelByCode(@Param("code") String code);

    int insertChannel(VerificationChannel channel);

    /** 更新可变部分：地址、凭据引用、超时、优先级、状态。熔断状态不在这里改（另一个方法）。 */
    int updateChannelConfig(@Param("code") String code,
                            @Param("name") String name,
                            @Param("endpoint") String endpoint,
                            @Param("credentialRef") String credentialRef,
                            @Param("timeoutMs") int timeoutMs,
                            @Param("priority") int priority,
                            @Param("status") String status,
                            @Param("at") OffsetDateTime at);

    /**
     * 手动复位熔断（运维动作，必须给原因）。
     *
     * <p>为什么需要手动复位：自动恢复依赖"下一次调用试探"，而如果这个渠道
     * 因为配置填错（比如 endpoint 写成了别的地址）而一直失败，每次试探都会再熔断一次，
     * 运维看到的是"状态在 CLOSED 与 OPEN 之间跳"，反而看不清问题。手动复位 + 原因，
     * 目的是把"我已经修好了"这件事留在记录里。
     */
    int resetCircuit(@Param("code") String code,
                     @Param("reason") String reason,
                     @Param("at") OffsetDateTime at);
}
