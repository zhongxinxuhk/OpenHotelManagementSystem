package cn.sec.hn.openbackupsaas.guest;

import cn.sec.hn.openbackupsaas.guest.domain.GuestAccount;
import cn.sec.hn.openbackupsaas.guest.domain.GuestProvider;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerification;
import cn.sec.hn.openbackupsaas.guest.domain.GuestVerificationStatus;
import cn.sec.hn.openbackupsaas.guest.security.GuestPiiCipher;
import cn.sec.hn.openbackupsaas.guest.service.GuestAccountService;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * 住客身份与实名验收（ADR-018）。
 *
 * <h2>这个类在防什么</h2>
 * 身份与实名做错的代价和其它模块不同：**泄漏不可撤回**。
 * <ul>
 *   <li>身份证号明文落库——一次拖库就是全量泄露，且无法"删了重来"；</li>
 *   <li>判重失效——同一个人能注册多个账户，绕过实名与风控；</li>
 *   <li>凭证规范化不严——{@code +8613...} 与 {@code 8613...} 各注册一个，唯一约束形同虚设；</li>
 *   <li>把"采集了"当成"验过了"——没有核验渠道就置 VERIFIED，等于给假证件开后门。</li>
 * </ul>
 * 每一条都只有直接查库或断言行状态才能发现。
 */
class GuestIdentityIntegrationTest extends PostgresIntegrationTest {

    /** 通过 ISO 7064 MOD 11-2 校验位的测试号码（随手编的号会被校验位挡住，反而测不了正常路径）。 */
    private static final String ID_NUMBER = "11010519491231002X";
    private static final String ID_NUMBER_2 = "310101199003070051";
    private static final String PHONE = "+8613800138000";
    private static final String EMAIL = "Guest.Example@Example.COM";

    @Autowired
    private GuestAccountService guestService;

    @Autowired
    private GuestPiiCipher cipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 主身份与凭证 ====================

    @Test
    void registerThenResolveByCredential() {
        GuestAccount account = guestService.register(GuestProvider.PHONE, PHONE, "旅客甲");

        Assertions.assertEquals(account.id(),
                guestService.resolveAccountId(GuestProvider.PHONE, PHONE).orElseThrow(),
                "注册后应能用同一凭证解析回同一主身份");
        Assertions.assertEquals(GuestVerificationStatus.UNVERIFIED,
                guestService.verificationStatus(account.id()),
                "刚注册应为未实名（没有实名档案即 UNVERIFIED）");
    }

    @Test
    void phoneMustBeE164() {
        // 强制 E.164 的理由：唯一索引建在 (provider, subject) 上，
        // "+8613..." 与 "8613..." 是两个字符串，都接受就等于同一人能注册两个账户。
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> guestService.register(GuestProvider.PHONE, "13800138000", "旅客"));
        // 带分隔符可以，会被规范化掉
        GuestAccount account = guestService.register(GuestProvider.PHONE, "+86 138-0013-8000", "旅客乙");
        Assertions.assertTrue(guestService.resolveAccountId(GuestProvider.PHONE, PHONE).isPresent(),
                "带分隔符的写法应归一到同一凭证上");
        Assertions.assertNotNull(account.id());
    }

    @Test
    void bindingSecondCredentialResolvesToSameAccount() {
        // 需求：有账户后可以再绑其他登录源。手机 + 邮箱应指向同一个人。
        GuestAccount account = guestService.register(GuestProvider.PHONE, PHONE, "旅客丙");
        guestService.bindCredential(account.id(), GuestProvider.EMAIL, EMAIL);

        Assertions.assertEquals(account.id(),
                guestService.resolveAccountId(GuestProvider.PHONE, PHONE).orElseThrow());
        Assertions.assertEquals(account.id(),
                guestService.resolveAccountId(GuestProvider.EMAIL, "guest.example@example.com").orElseThrow(),
                "邮箱应大小写不敏感地解析到同一账户");
    }

    @Test
    void sameCredentialCannotBelongToTwoAccounts() {
        GuestAccount first = guestService.register(GuestProvider.PHONE, PHONE, "旅客丁");
        Assertions.assertThrows(ConflictException.class,
                () -> guestService.register(GuestProvider.PHONE, PHONE, "另一个人"));
        Assertions.assertThrows(ConflictException.class,
                () -> guestService.bindCredential(first.id(), GuestProvider.PHONE, PHONE),
                "同一账户重复绑定也应被拒（唯一约束在库层）");
    }

    // ==================== 实名：库内不得有明文 ====================

    @Test
    void verificationStoresNoPlaintextIdNumber() {
        GuestAccount account = guestService.register(GuestProvider.PHONE, PHONE, "旅客戊");
        GuestVerification submitted =
                guestService.submitVerification(account.id(), "张三丰", ID_NUMBER);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id_number_enc, id_number_hmac, id_number_mask, real_name_enc, real_name_mask "
                        + "FROM guest_id_verification WHERE account_id = ?", account.id());

        String idEnc = (String) row.get("id_number_enc");
        String nameEnc = (String) row.get("real_name_enc");

        // 1) 库里没有明文：整行拼起来也不该出现证件号或姓名
        String wholeRow = row.values().stream().map(String::valueOf).reduce("", String::concat);
        Assertions.assertFalse(wholeRow.contains(ID_NUMBER), "库内出现了身份证明文");
        Assertions.assertFalse(wholeRow.contains("张三丰"), "库内出现了姓名明文");

        // 2) 密文带版本前缀（便于将来换算法），且真的能解开
        Assertions.assertTrue(idEnc.startsWith("v1:"));
        Assertions.assertTrue(nameEnc.startsWith("v1:"));
        Assertions.assertEquals(ID_NUMBER, cipher.decrypt(idEnc));
        Assertions.assertEquals("张三丰", cipher.decrypt(nameEnc));

        // 3) 判重摘要存在且与规范化号码一致
        Assertions.assertEquals(cipher.index(ID_NUMBER), row.get("id_number_hmac"));

        // 4) 掩码满足人工核对需要，但不泄露完整号码
        Assertions.assertEquals("110105********002X", row.get("id_number_mask"));
        Assertions.assertEquals("张**", row.get("real_name_mask"));

        // 5) 对外视图只有掩码，没有密文也没有原文
        Assertions.assertEquals("110105********002X", submitted.idNumberMask());
        Assertions.assertEquals(GuestVerificationStatus.PENDING, submitted.status(),
                "没有核验渠道时不得置 VERIFIED（ADR-018 §2.5）");
    }

    @Test
    void sameIdNumberCannotBelongToTwoAccounts() {
        // 一人一号：否则同一个人可以注册多个账户绕过风控与实名限制。
        GuestAccount first = guestService.register(GuestProvider.PHONE, PHONE, "旅客己");
        guestService.submitVerification(first.id(), "李四", ID_NUMBER);

        GuestAccount second = guestService.register(GuestProvider.PHONE, "+8613900139000", "旅客庚");
        Assertions.assertThrows(ConflictException.class,
                () -> guestService.submitVerification(second.id(), "李四", ID_NUMBER));
    }

    @Test
    void invalidIdNumberIsRejected() {
        GuestAccount account = guestService.register(GuestProvider.PHONE, PHONE, "旅客辛");

        // 校验位错
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> guestService.submitVerification(account.id(), "王五", "110105194912310021"));
        // 长度不对
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> guestService.submitVerification(account.id(), "王五", "1101051949123"));
        // 姓名过短
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> guestService.submitVerification(account.id(), "王", ID_NUMBER));
    }

    @Test
    void resubmittingSameVerifiedProfileDoesNotResetStatus() {
        // 已经核验通过的资料再次提交（例如客户端重试）不应把状态打回 PENDING。
        // 但这必须用"同一份资料"作为前提：换号要重新核验。
        GuestAccount account = guestService.register(GuestProvider.PHONE, PHONE, "旅客壬");
        guestService.submitVerification(account.id(), "赵六", ID_NUMBER);

        // 模拟核验通过（真实核验渠道未接入，见 ADR-018 §6）
        jdbcTemplate.update(
                "UPDATE guest_id_verification SET status = 'VERIFIED', verified_at = now() "
                        + "WHERE account_id = ?", account.id());

        GuestVerification again = guestService.submitVerification(account.id(), "赵六", ID_NUMBER);
        Assertions.assertEquals(GuestVerificationStatus.VERIFIED, again.status(),
                "同一份已核验资料重复提交应幂等");

        // 换一份资料（另一个身份证）必须回到 PENDING
        GuestVerification changed =
                guestService.submitVerification(account.id(), "赵六", ID_NUMBER_2);
        Assertions.assertEquals(GuestVerificationStatus.PENDING, changed.status(),
                "换了证件号还保留 VERIFIED，等于改个号就自动通过核验");
        Assertions.assertNull(changed.verifiedAt());
    }

    @Test
    void accountStatusAndVerificationAreIndependent() {
        // 已实名但账户被关闭是正常状态；两者混成一个枚举就表达不了。
        GuestAccount account = guestService.register(GuestProvider.PHONE, PHONE, "旅客癸");
        guestService.submitVerification(account.id(), "钱七", ID_NUMBER);
        Assertions.assertNotNull(account.status());
        Assertions.assertEquals(GuestVerificationStatus.PENDING,
                guestService.verificationStatus(account.id()));
    }
}
