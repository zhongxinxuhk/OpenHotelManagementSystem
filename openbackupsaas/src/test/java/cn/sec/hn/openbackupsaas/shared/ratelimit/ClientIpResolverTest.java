package cn.sec.hn.openbackupsaas.shared.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 来源地址解析（J-05）。
 *
 * <h2>这个类在防什么</h2>
 * 「限流按 IP 算」这句话的前提是**IP 不可由客户端决定**。一旦无条件采信
 * {@code X-Forwarded-For}，攻击者每个请求换一个假地址就得到无限额度，
 * 而且日志里每一"个"来源都只出现一两次，事后看还以为没被打。
 */
class ClientIpResolverTest {

    private static final List<String> LOOPBACK_PROXY = List.of("127.0.0.1", "::1");

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/guest/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void withoutTrustedProxyTheForwardedHeaderIsIgnored() {
        ClientIpResolver resolver = new ClientIpResolver(List.of());

        // 同一个客户端伪造不同转发头，解析结果必须不变——否则限流可被绕过
        assertEquals("203.0.113.7", resolver.resolve(request("203.0.113.7", "198.51.100.1")));
        assertEquals("203.0.113.7", resolver.resolve(request("203.0.113.7", "198.51.100.2")));
    }

    @Test
    void trustedProxyUnwrapsTheRealClient() {
        ClientIpResolver resolver = new ClientIpResolver(LOOPBACK_PROXY);

        assertEquals("198.51.100.9",
                resolver.resolve(request("127.0.0.1", "198.51.100.9")),
                "对端是可信反代时，用转发头里的客户端地址");
    }

    @Test
    void spoofedLeftmostHopIsNotUsedWhenAChainIsPresent() {
        ClientIpResolver resolver = new ClientIpResolver(LOOPBACK_PROXY);

        // 客户端自己塞了 "1.1.1.1"，反代把它追加在后面。从右往左走：127.0.0.1 是可信代理，
        // 再往左 198.51.100.9 才是真客户端；最左的 1.1.1.1 是伪造的
        assertEquals("198.51.100.9",
                resolver.resolve(request("127.0.0.1", "1.1.1.1, 198.51.100.9, 127.0.0.1")));
    }

    @Test
    void multipleTrustedHopsAreSkipped() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1", "10.0.0.0/8"));

        assertEquals("203.0.113.44",
                resolver.resolve(request("127.0.0.1", "203.0.113.44, 10.1.2.3, 10.9.9.9")));

        // 混合网段：IPv6 环回也要显式登记为可信代理，否则按不可信对端处理（安全默认）
        ClientIpResolver mixed = new ClientIpResolver(List.of("127.0.0.1", "10.0.0.0/8", "::1"));
        assertEquals("203.0.113.44",
                mixed.resolve(request("::1", "203.0.113.44, 10.7.7.7, ::1")));
        assertEquals(resolver.resolve(request("::1", null)),
                resolver.resolve(request("::1", "203.0.113.44")),
                "对端不可信时忽略转发头，直接用对端地址");
    }

    @Test
    void equivalentNotationsCollapseIntoOneKey() {
        ClientIpResolver resolver = new ClientIpResolver(List.of());

        // 同一地址的展开写法必须归一：否则改个写法就换一个令牌桶
        assertEquals(resolver.resolve(request("::1", null)),
                resolver.resolve(request("0:0:0:0:0:0:0:1", null)));
    }

    @Test
    void gatewayMetadataIsStripped() {
        ClientIpResolver resolver = new ClientIpResolver(LOOPBACK_PROXY);

        assertEquals("198.51.100.9", resolver.resolve(request("127.0.0.1", "198.51.100.9:51234")));
        // IPv6 的文本形式由 JDK 决定（Java 不压缩写法），这里只断言"带端口的写法"与"纯地址"等价
        assertEquals(resolver.resolve(request("127.0.0.1", "2001:db8::1")),
                resolver.resolve(request("127.0.0.1", "[2001:db8::1]:443")));
    }

    @Test
    void unparsableRemoteAddressFallsBackToSharedBucket() {
        ClientIpResolver resolver = new ClientIpResolver(List.of());

        // 拿不到地址时宁可所有人共用一个桶，也不要生成随机键（那会让限流变成空转）
        assertEquals("unknown", resolver.resolve(request(null, null)));
        assertEquals("unknown", resolver.resolve(request("not-an-ip", null)));
    }

    @Test
    void configurationRejectsHostnamesInsteadOfResolvingThem() {
        // 写错成主机名时必须启动期失败：InetAddress.getByName 会去做 DNS，
        // 在无外网环境里表现为"启动卡住"，而错误信息完全看不出是配置写错了
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ClientIpResolver(List.of("proxy.internal")));
        assertTrue(ex.getMessage().contains("不是 IP 字面量"), ex.getMessage());
    }

    @Test
    void cidrPrefixIsHonoured() {
        IpRange range = IpRange.parse("10.0.0.0/8");
        assertTrue(range.contains(IpRange.literal("10.255.255.255")));
        assertFalse(range.contains(IpRange.literal("11.0.0.1")));

        IpRange narrow = IpRange.parse("192.168.1.0/30");
        assertTrue(narrow.contains(IpRange.literal("192.168.1.2")));
        assertFalse(narrow.contains(IpRange.literal("192.168.1.4")));

        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("10.0.0.0/33"));
    }
}
