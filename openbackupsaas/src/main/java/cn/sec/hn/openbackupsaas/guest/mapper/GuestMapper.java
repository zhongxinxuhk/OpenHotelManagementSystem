package cn.sec.hn.openbackupsaas.guest.mapper;

import cn.sec.hn.openbackupsaas.guest.domain.GuestAccount;
import cn.sec.hn.openbackupsaas.guest.domain.GuestSession;
import cn.sec.hn.openbackupsaas.guest.domain.GuestSessionView;
import cn.sec.hn.openbackupsaas.guest.domain.GuestCredentialView;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerification;
import cn.sec.hn.openbackupsaas.guest.domain.OwnershipChallenge;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationReview;
import cn.sec.hn.openbackupsaas.guest.domain.PendingVerification;
import cn.sec.hn.openbackupsaas.guest.domain.UsernameCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 住客身份数据访问（ADR-018）。SQL 见 {@code resources/mapper/guest/GuestMapper.xml}。
 *
 * <p><b>本 Mapper 的所有方法都**没有** tenantId 参数——这是有意的</b>，也是全库唯一的例外：
 * 住客不属于任何租户（ADR-018 §2.6）。
 *
 * <p>因此这个接口必须遵守两条额外纪律，防止"例外"扩散：
 * <ol>
 *   <li><b>不得在这里出现任何租户业务表</b>（订单、房态、房价……）。住客表可以被人引用，
 *       但不能反过来引用别人——否则住客侧被攻破就能顺着外键读租户数据。</li>
 *   <li>实名相关的读写只能走本 Mapper，禁止在其它模块直接拼 SQL 访问
 *       {@code guest_id_verification}：那里有加密与判重的不变量，绕过去就失效了。</li>
 * </ol>
 */
@Mapper
public interface GuestMapper {

    // ---------- 主身份 ----------

    int insertAccount(GuestAccount account);

    Optional<GuestAccount> findAccount(@Param("id") UUID id);

    // ---------- 凭证 ----------

    /**
     * 登记一份凭证。
     *
     * @param secretHash 仅 {@code USERNAME} 凭证使用（BCrypt 密码）；其它类型必须传 null
     *                   ——库层 CHECK 会拒绝"给第三方凭证存密码"的写法
     */
    int insertCredential(@Param("id") UUID id,
                         @Param("accountId") UUID accountId,
                         @Param("provider") String provider,
                         @Param("subject") String subject,
                         @Param("secretHash") String secretHash,
                         @Param("verifiedAt") OffsetDateTime verifiedAt,
                         @Param("at") OffsetDateTime at);

    /**
     * 用户名登录专用：按用户名取凭证（含口令哈希）。
     *
     * <p>不复用 {@link #findAccountIdByCredential}：那条只解析账户，
     * 登录还要拿到哈希做比对。专列一条查询让"登录路径多取了一列"这件事可见。
     */
    Optional<UsernameCredential> findUsernameCredential(@Param("subject") String subject);

    /** 凭证 → 主身份。登录时用它把"手机号/微信"解析成同一个人。 */
    Optional<UUID> findAccountIdByCredential(@Param("provider") String provider,
                                             @Param("subject") String subject);

    /**
     * 标记凭证归属已验证（验证码或 OAuth 回调通过后调用）。
     *
     * <p>登录轮接入前没有调用方，但方法先留着：
     * 它让"凭证有未验证状态"这件事在接口上可见，而不是靠注释提醒。
     */
    int markCredentialVerified(@Param("accountId") UUID accountId,
                               @Param("provider") String provider,
                               @Param("subject") String subject,
                               @Param("at") OffsetDateTime at);

    // ---------- 实名档案 ----------

    int insertVerification(@Param("accountId") UUID accountId,
                           @Param("idNumberEnc") String idNumberEnc,
                           @Param("idNumberHmac") String idNumberHmac,
                           @Param("idNumberMask") String idNumberMask,
                           @Param("realNameEnc") String realNameEnc,
                           @Param("realNameMask") String realNameMask,
                           @Param("at") OffsetDateTime at);

    /** 重新提交：覆盖密文与掩码，状态回到 PENDING（旧结论不再成立）。 */
    int updateVerification(@Param("accountId") UUID accountId,
                           @Param("idNumberEnc") String idNumberEnc,
                           @Param("idNumberHmac") String idNumberHmac,
                           @Param("idNumberMask") String idNumberMask,
                           @Param("realNameEnc") String realNameEnc,
                           @Param("realNameMask") String realNameMask,
                           @Param("at") OffsetDateTime at);

    Optional<GuestVerification> findVerification(@Param("accountId") UUID accountId);

    /**
     * 按身份证 HMAC 索引反查账户。
     *
     * <p>用途是给出**可读的冲突提示**（"该证件已绑定其他账户"），
     * 而不是判重的唯一防线——唯一索引才是。服务层先查一遍只是为了把
     * {@code DataIntegrityViolationException} 换成人能看懂的话。
     */
    Optional<UUID> findAccountIdByIdNumberHmac(@Param("idNumberHmac") String idNumberHmac);

    // ---------- 会话（ADR-019） ----------

    int insertSession(GuestSession session);

    /**
     * 按令牌哈希取**可用**会话（未撤销、未过期），并顺带带出账号显示名。
     *
     * <p>把"可用"写进 SQL 而不是取出来再在 Java 里判断：判定在库里一次完成，
     * 不存在中间态，也不会出现"取出来了但忘了判"。
     */
    Optional<GuestSessionView> findUsableSession(@Param("tokenHash") String tokenHash);

    /** 撤销单个会话（登出用）。返回实际撤销条数（0 = 会话已失效）。 */
    int revokeSession(@Param("sessionId") java.util.UUID sessionId,
                      @Param("reason") String reason,
                      @Param("at") OffsetDateTime at);

    /** 撤销某住客的全部会话（关闭账户时用）。返回实际撤销条数。 */
    int revokeAllSessionsOfAccount(@Param("accountId") UUID accountId,
                                   @Param("reason") String reason,
                                   @Param("at") OffsetDateTime at);

    // ---------- 人工审核（ADR-019 §2.5） ----------

    /**
     * 待审核队列（只看 PENDING）。
     *
     * <p>{@code status} 不做成参数：这个队列存在的意义就是"待处理"，
     * 允许查其它状态会让它慢慢变成"全量档案列表"，而那需要另一套权限。
     */
    List<PendingVerification> findPendingVerifications(@Param("limit") int limit,
                                                       @Param("offset") int offset);

    long countPendingVerifications();

    /**
     * 落地一个实名决定。
     *
     * <p><b>{@code WHERE status = 'PENDING'} 是并发防线</b>：两个审核员同时点"通过"时，
     * 第二个更新影响 0 行，服务层据此报 409 —— 而不是让后一次静默覆盖前一次。
     * 没有这个条件，留痕里会出现两条互相矛盾的决定，且都写着"成功"。
     */
    int applyVerificationDecision(@Param("accountId") UUID accountId,
                                  @Param("decision") String decision,
                                  @Param("verifiedAt") OffsetDateTime verifiedAt,
                                  @Param("at") OffsetDateTime at);

    int insertReview(GuestVerificationReview review);

    List<GuestVerificationReview> findReviews(@Param("accountId") UUID accountId);

    // ---------- 凭证绑定：归属挑战（ADR-019 §2.10） ----------

    int insertChallenge(@Param("id") UUID id,
                        @Param("accountId") UUID accountId,
                        @Param("provider") String provider,
                        @Param("subject") String subject,
                        @Param("codeHash") String codeHash,
                        @Param("expiresAt") OffsetDateTime expiresAt,
                        @Param("at") OffsetDateTime at);

    Optional<OwnershipChallenge> findChallenge(@Param("id") UUID id);

    /** 记一次错误尝试。返回 0 表示挑战已消费（不能再加）。 */
    int bumpChallengeAttempts(@Param("id") UUID id, @Param("at") OffsetDateTime at);

    /**
     * 原子消费挑战：只有"未消费、未超次、未过期"才能成功。
     * 并发下第二个 confirm 拿到 0 行 → 409，不会出现同一码用两次。
     */
    int consumeChallenge(@Param("id") UUID id,
                         @Param("maxAttempts") int maxAttempts,
                         @Param("at") OffsetDateTime at);

    // ---------- 凭证绑定：列表与解绑 ----------

    List<GuestCredentialView> findCredentialsOfAccount(@Param("accountId") UUID accountId);

    long countCredentials(@Param("accountId") UUID accountId);

    int deleteCredential(@Param("accountId") UUID accountId,
                         @Param("provider") String provider,
                         @Param("subject") String subject);
}
