package cn.sec.hn.openbackupsaas.shared.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * 一个地址或 CIDR 网段（J-05）。
 *
 * <h2>为什么不用 {@code InetAddress.getByName} 直接解析</h2>
 * 它接受**主机名**并对之做 DNS 解析。配置里写错一个字母，就会变成
 * "启动时去查一个不存在的外网域名"——启动慢、内网环境卡住，
 * 而错误信息只会说 {@code UnknownHostException}，和"网段写错了"看起来毫无关系。
 * 所以先按字面量校验形态，再交给 JDK：不合法就在配置解析阶段直接失败。
 *
 * <p>JDK 21 没有 {@code InetAddress.ofLiteral}（Java 22 才有），因此这里的正则就是那道闸门：
 * 只接受 IPv4/IPv6 字面量，任何主机名都进不来。
 */
final class IpRange {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    /** IPv6 允许带内嵌 IPv4（{@code ::ffff:192.0.2.1}），因此字符集里含点。 */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    private final byte[] network;
    private final int prefixBits;

    private IpRange(byte[] network, int prefixBits) {
        this.network = network;
        this.prefixBits = prefixBits;
    }

    static IpRange parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("网段不能为空");
        }
        String value = text.trim();
        int slash = value.indexOf('/');
        String addressPart = slash < 0 ? value : value.substring(0, slash);

        InetAddress address = literal(addressPart);
        int maxPrefix = address.getAddress().length * 8;
        int prefix = maxPrefix;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(value.substring(slash + 1));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("网段前缀不是整数：" + text);
            }
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException("网段前缀越界（0—" + maxPrefix + "）：" + text);
            }
        }
        return new IpRange(address.getAddress(), prefix);
    }

    /** 只接受字面量：不是 IPv4/IPv6 形态的一律拒绝，绝不触发 DNS。 */
    static InetAddress literal(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("地址不能为空");
        }
        String value = candidate.trim();
        boolean ipv4 = IPV4.matcher(value).matches();
        // 注意用 >= 0 而不是 > 0：{@code ::1} 的冒号就在第 0 位，写 > 0 会把环回地址判成非法
        boolean ipv6 = value.indexOf(':') >= 0 && IPV6.matcher(value).matches();
        if (!ipv4 && !ipv6) {
            throw new IllegalArgumentException("不是 IP 字面量（拒绝触发 DNS 解析）：" + candidate);
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("非法 IP 字面量：" + candidate, ex);
        }
    }

    boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != network.length) {
            // 4 字节（IPv4）与 16 字节（IPv6）不互通；不做 ::ffff: 折叠，避免"看起来配了却匹配不上"
            return false;
        }
        int fullBytes = prefixBits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (bytes[i] != network[i]) {
                return false;
            }
        }
        int remainder = prefixBits % 8;
        if (remainder == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainder);
        return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    @Override
    public String toString() {
        return toText(network) + "/" + prefixBits;
    }

    private static String toText(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException ex) {
            return "?";
        }
    }
}
