package cn.sec.hn.openbackupsaas.command.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规范化请求摘要（S1-07）。
 *
 * <p>为什么要规范化：JSON 字段顺序不同、键名大小写不同，语义却一样。
 * 不规范化会让"同样的请求"因为序列化差异被判成不同内容，幂等直接失效。
 *
 * <p>{@code hashVersion} 存在的意义：将来换摘要算法时，同一个键在新旧版本下必然算出不同摘要，
 * 于是被当成"同键不同内容"拒绝——这比"静默放行一个旧算法算出来的请求"安全得多。
 */
public final class RequestDigest {

    public static final int CURRENT_VERSION = 1;

    private RequestDigest() {
    }

    /** 递归规范化：Map 按键排序、null 记为显式空串，避免"缺字段"与"空值"混淆。 */
    public static String canonicalize(Object value) {
        return switch (value) {
            case null -> "\u0000null";
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    sorted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                StringBuilder builder = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append(entry.getKey()).append(':').append(canonicalize(entry.getValue()));
                }
                yield builder.append('}').toString();
            }
            case Iterable<?> items -> {
                StringBuilder builder = new StringBuilder("[");
                boolean first = true;
                for (Object item : items) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append(canonicalize(item));
                }
                yield builder.append(']').toString();
            }
            default -> String.valueOf(value);
        };
    }

    public static String sha256Hex(Object request) {
        return sha256Hex(canonicalize(request));
    }

    public static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不提供 SHA-256", ex);
        }
    }
}
