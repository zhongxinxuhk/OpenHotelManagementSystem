package cn.sec.hn.openbackupsaas.guest.service;

import cn.sec.hn.openbackupsaas.guest.domain.GuestAccount;
import cn.sec.hn.openbackupsaas.identity.domain.PasswordPolicy;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerification;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationStatus;
import cn.sec.hn.openbackupsaas.guest.mapper.GuestMapper;
import cn.sec.hn.openbackupsaas.guest.security.GuestPiiCipher;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 住客账户、凭证绑定与实名提交（ADR-018）。
 *
 * <h2>这个类守住的不变量</h2>
 * <ol>
 *   <li><b>一个凭证只能属于一个账户</b>：库层唯一索引 {@code uq_guest_credential} 是唯一防线，
 *       服务层先查一次只是为了把约束冲突换成人能看懂的提示。</li>
 *   <li><b>一个身份证只能属于一个账户</b>：同样由库层唯一索引（HMAC 列）保证。</li>
 *   <li><b>库内没有身份证号明文</b>：只写密文、HMAC 索引与掩码，明文连日志都不进。</li>
 *   <li><b>实名核验不得伪造</b>：没有核验渠道（ADR-018 §6 未定）时，状态停在 PENDING。
 *       本类**不提供**"直接置 VERIFIED"的方法——那会让"采集了"被当成"验过了"。</li>
 * </ol>
 *
 * <h2>为什么没有 tenantId 参数</h2>
 * 住客不属于任何租户。这是全库唯一没有租户维度的服务，配套的单向引用约束见
 * {@link GuestMapper} 的注释。
 */
@Service
public class GuestAccountService {

    private static final Logger log = LoggerFactory.getLogger(GuestAccountService.class);

    /** 18 位身份证：17 位数字 + 校验位（0-9 或 X）。15 位旧号已停用，不再接受。 */
    private static final int ID_NUMBER_LENGTH = 18;

    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** ISO 7064 MOD 11-2 的校验字符表，与上面的权重一一对应。 */
    private static final String ID_CHECK_CODES = "10X98765432";

    private final GuestMapper guestMapper;
    private final GuestPiiCipher cipher;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final Clock clock;

    public GuestAccountService(GuestMapper guestMapper, GuestPiiCipher cipher,
                               org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                               Clock clock) {
        this.guestMapper = guestMapper;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    // ==================== 注册与凭证 ====================

    /**
     * 用某个登录方式注册一个新住客账户。
     *
     * <p><b>注意凭证归属尚未验证</b>：真实注册流程要先发验证码或走 OAuth 回调，
     * 那一步在登录轮接入。本方法把凭证以 {@code verified_at = null} 登记，
     * 表示"声称拥有"而不是"已证明拥有"——不在这里假装验证过。
     *
     * @throws ConflictException 该登录方式已绑定到其他账户
     */
    @Transactional
    public GuestAccount register(GuestProvider provider, String rawSubject, String displayName) {
        String subject = provider.normalize(rawSubject);
        assertCredentialAvailable(provider, subject);

        OffsetDateTime now = now();
        GuestAccount account = GuestAccount.create(UUID.randomUUID(), trimToNull(displayName), now);
        guestMapper.insertAccount(account);
        guestMapper.insertCredential(UUID.randomUUID(), account.id(), provider.name(), subject,
                null, null, now);

        log.info("guest account registered accountId={} provider={}", account.id(), provider);
        return account;
    }

    /**
     * 用户名 + 密码注册（ADR-019 §2.9）。
     *
     * <p>与 {@link #register} 的差别有两点，都源于"密码是自持凭证"：
     * <ul>
     *   <li>凭证在登记时即为**已验证**（{@code verified_at = now}）——
     *       "知道密码"本身就是归属证明，与员工账号首次开通同理；</li>
     *   <li>口令走与员工同一套 {@link PasswordPolicy}（12 位下限、不得包含用户名），
     *       两套账号体系不该有两套口令强度。</li>
     * </ul>
     *
     * @throws ConflictException 用户名已被占用（唯一索引兜底，服务层先查只为给可读提示）
     * @throws IllegalArgumentException 用户名或口令不合规
     */
    @Transactional
    public GuestAccount registerWithUsername(String rawUsername, String rawPassword,
                                             String displayName) {
        GuestProvider provider = GuestProvider.USERNAME;
        String username = provider.normalize(rawUsername);
        PasswordPolicy.requireAcceptable(rawPassword, username);
        assertCredentialAvailable(provider, username);

        OffsetDateTime now = now();
        GuestAccount account = GuestAccount.create(UUID.randomUUID(), trimToNull(displayName), now);
        guestMapper.insertAccount(account);
        guestMapper.insertCredential(UUID.randomUUID(), account.id(), provider.name(), username,
                passwordEncoder.encode(rawPassword), now, now);
        log.info("guest account registered accountId={} provider=USERNAME", account.id());
        return account;
    }

    /**
     * 给已有账户绑定一个用户名凭证（"有账户后再加登录方式"）。
     *
     * <p>绑定成功后该用户名即可用于登录。用户名被占用时 409——
     * 与注册同一套唯一性语义。
     */
    @Transactional
    public void bindUsername(java.util.UUID accountId, String rawUsername, String rawPassword) {
        GuestAccount account = guestMapper.findAccount(accountId)
                .orElseThrow(() -> new NotFoundException("住客账户不存在"));
        if (!account.status().canLogin()) {
            throw new ConflictException("账户已关闭，不能绑定新的登录方式");
        }
        GuestProvider provider = GuestProvider.USERNAME;
        String username = provider.normalize(rawUsername);
        PasswordPolicy.requireAcceptable(rawPassword, username);
        assertCredentialAvailable(provider, username);

        guestMapper.insertCredential(UUID.randomUUID(), accountId, provider.name(), username,
                passwordEncoder.encode(rawPassword), now(), now());
        log.info("guest username credential bound accountId={}", accountId);
    }

    /**
     * 给已有账户绑定一个新的登录方式。
     *
     * <p>需求方的原话是"有账户后，可以绑定其他第三方登录源"——绑定是**主动动作**，
     * 不是"两个手机号相同就自动合并"。自动合并会让"知道你手机号"变成"能接管你账户"。
     */
    @Transactional
    public void bindCredential(UUID accountId, GuestProvider provider, String rawSubject) {
        GuestAccount account = guestMapper.findAccount(accountId)
                .orElseThrow(() -> new NotFoundException("住客账户不存在"));
        if (!account.status().canLogin()) {
            throw new ConflictException("账户已关闭，不能绑定新的登录方式");
        }
        String subject = provider.normalize(rawSubject);
        assertCredentialAvailable(provider, subject);

        guestMapper.insertCredential(UUID.randomUUID(), accountId, provider.name(), subject,
                null, null, now());
        log.info("guest credential bound accountId={} provider={}", accountId, provider);
    }

    /** 凭证 → 主身份。登录流程与"我的订单"归属校验都从这里解析。 */
    @Transactional(readOnly = true)
    public Optional<UUID> resolveAccountId(GuestProvider provider, String rawSubject) {
        return guestMapper.findAccountIdByCredential(provider.name(), provider.normalize(rawSubject));
    }

    // ==================== 实名 ====================

    /**
     * 提交实名资料。
     *
     * <p>写入库内的只有三种派生值：密文、HMAC 索引、掩码。**明文不落库、不进日志**。
     *
     * <p>重复提交同一份已核验的资料是幂等的（不把 VERIFIED 打回 PENDING）；
     * 换了证件号则必须回到 PENDING——否则改个号码就继承了旧的核验结论。
     *
     * @throws ConflictException      该身份证已绑定其他账户
     * @throws IllegalArgumentException 姓名或证件号不合规
     */
    @Transactional
    public GuestVerification submitVerification(UUID accountId, String realName, String idNumber) {
        GuestAccount account = guestMapper.findAccount(accountId)
                .orElseThrow(() -> new NotFoundException("住客账户不存在"));

        String normalizedName = requireRealName(realName);
        String normalizedId = requireIdNumber(idNumber);
        String idHmac = cipher.index(normalizedId);

        Optional<UUID> owner = guestMapper.findAccountIdByIdNumberHmac(idHmac);
        if (owner.isPresent() && owner.get().equals(accountId)) {
            GuestVerification existing = guestMapper.findVerification(accountId)
                    .orElseThrow(() -> new IllegalStateException("索引指向本账户但档案缺失"));
            if (existing.status() == GuestVerificationStatus.VERIFIED) {
                // 同一份已核验资料重复提交：不改状态、不重算，直接返回现状。
                return existing;
            }
        } else if (owner.isPresent()) {
            // 不透露"已绑定到哪个账户"——那等于给出一个"这个证件号存在"的探测接口。
            throw new ConflictException("该身份证已绑定其他账户，请使用原账户或联系客服");
        }

        String idEnc = cipher.encrypt(normalizedId);
        String nameEnc = cipher.encrypt(normalizedName);
        String idMask = maskIdNumber(normalizedId);
        String nameMask = maskRealName(normalizedName);
        OffsetDateTime now = now();

        if (guestMapper.findVerification(accountId).isPresent()) {
            guestMapper.updateVerification(accountId, idEnc, idHmac, idMask, nameEnc, nameMask, now);
        } else {
            guestMapper.insertVerification(accountId, idEnc, idHmac, idMask, nameEnc, nameMask, now);
        }

        // 只记账户与状态，不记姓名与证件号——日志往往比数据库更容易被翻。
        log.info("guest verification submitted accountId={} status=PENDING", account.id());
        return guestMapper.findVerification(accountId)
                .orElseThrow(() -> new IllegalStateException("实名档案写入后读取失败"));
    }

    /** 实名状态；没有档案即 {@code UNVERIFIED}（不落库的计算态）。 */
    @Transactional(readOnly = true)
    public GuestVerificationStatus verificationStatus(UUID accountId) {
        return guestMapper.findVerification(accountId)
                .map(GuestVerification::status)
                .orElse(GuestVerificationStatus.UNVERIFIED);
    }

    // ==================== 内部 ====================

    private void assertCredentialAvailable(GuestProvider provider, String subject) {
        // 库层唯一索引才是防线；这里先查只是为了让报错可读。
        if (guestMapper.findAccountIdByCredential(provider.name(), subject).isPresent()) {
            throw new ConflictException("该登录方式已绑定其他账户");
        }
    }

    /**
     * 规范化并校验身份证号。
     *
     * <p>做校验位（ISO 7064 MOD 11-2）不是为了好看：它能挡掉绝大多数手输错误，
     * 而实名资料的错误代价很高——一旦按错号入库，后续核验失败、客服介入、重新提交都要人力。
     */
    private static String requireIdNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("身份证号不能为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        if (normalized.length() != ID_NUMBER_LENGTH) {
            throw new IllegalArgumentException("身份证号应为 18 位");
        }
        for (int i = 0; i < ID_NUMBER_LENGTH - 1; i++) {
            if (!Character.isDigit(normalized.charAt(i))) {
                throw new IllegalArgumentException("身份证号前 17 位必须是数字");
            }
        }
        char last = normalized.charAt(ID_NUMBER_LENGTH - 1);
        if (!Character.isDigit(last) && last != 'X') {
            throw new IllegalArgumentException("身份证号最后一位只能是数字或 X");
        }

        int sum = 0;
        for (int i = 0; i < ID_WEIGHTS.length; i++) {
            sum += (normalized.charAt(i) - '0') * ID_WEIGHTS[i];
        }
        char expected = ID_CHECK_CODES.charAt(sum % 11);
        if (expected != last) {
            throw new IllegalArgumentException("身份证号校验位不正确，请核对后重新输入");
        }
        return normalized;
    }

    private static String requireRealName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("姓名不能为空");
        }
        String trimmed = raw.trim();
        if (trimmed.length() < 2 || trimmed.length() > 40) {
            throw new IllegalArgumentException("姓名长度应在 2–40 之间");
        }
        return trimmed;
    }

    /** 前 6 位（地区码）保留，中间 8 位（出生日期）打码，后 4 位保留。 */
    private static String maskIdNumber(String normalized) {
        return normalized.substring(0, 6) + "********" + normalized.substring(ID_NUMBER_LENGTH - 4);
    }

    /** 保留姓，名打码。 */
    private static String maskRealName(String realName) {
        if (realName.length() == 1) {
            return "*";
        }
        return realName.charAt(0) + "*".repeat(realName.length() - 1);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
