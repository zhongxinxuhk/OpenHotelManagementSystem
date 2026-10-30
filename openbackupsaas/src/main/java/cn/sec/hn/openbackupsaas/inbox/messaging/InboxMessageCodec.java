package cn.sec.hn.openbackupsaas.inbox.messaging;

import cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 把消息体解析成 {@link InboxMessage}（S2-02）。
 *
 * <p>手写解析而不是让消息转换器直接反序列化成 record：<b>坏消息的处理方式必须是明确的</b>。
 * 转换器抛出的异常通常只带一句笼统的转换失败，而这里每个缺失字段都给出字段名，
 * 排查"为什么这条消息老是进不去"时差别很大。
 *
 * <p>解析失败属于<b>不可重试</b>错误（同一条消息再投一万次还是解析不了），
 * 调用方据此决定丢弃并告警，而不是无限重投把队列堵死。
 */
public final class InboxMessageCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private InboxMessageCodec() {
    }

    public static InboxMessage parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("消息体为空");
        }
        JsonNode root = JSON.readTree(body);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("消息体不是 JSON 对象");
        }

        JsonNode payloadNode = root.get("payload");
        return new InboxMessage(
                uuid(root, "eventId"),
                uuid(root, "tenantId"),
                optionalUuid(root, "hotelId"),
                text(root, "aggregateType"),
                uuid(root, "aggregateId"),
                longValue(root, "aggregateVersion"),
                intValue(root, "eventOrdinal", 1),
                text(root, "eventType"),
                intValue(root, "schemaVersion", 1),
                payloadNode == null || payloadNode.isNull() ? null : payloadNode.toString(),
                occurredAt(root));
    }

    private static UUID uuid(JsonNode root, String field) {
        String value = text(root, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("字段 " + field + " 不是合法 UUID：" + value, ex);
        }
    }

    private static UUID optionalUuid(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return uuid(root, field);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asString().isBlank()) {
            throw new IllegalArgumentException("消息缺少字段 " + field);
        }
        return node.asString();
    }

    private static long longValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isNumber()) {
            throw new IllegalArgumentException("消息缺少数值字段 " + field);
        }
        return node.asLong();
    }

    private static int intValue(JsonNode root, String field, int fallback) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isNumber()) {
            throw new IllegalArgumentException("字段 " + field + " 不是数值");
        }
        return node.asInt();
    }

    private static OffsetDateTime occurredAt(JsonNode root) {
        JsonNode node = root.get("occurredAt");
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asString());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("字段 occurredAt 不是 ISO-8601 时间：" + node.asString(), ex);
        }
    }
}
