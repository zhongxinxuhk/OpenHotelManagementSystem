package cn.sec.hn.openbackupsaas.inbox.service;

import cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage;
import cn.sec.hn.openbackupsaas.inbox.messaging.InboxMessageCodec;
import cn.sec.hn.openbackupsaas.ops.mapper.OpsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把一条原始报文分发到订阅它的消费者（S2-02 引入逻辑，S2-04 抽成可复用服务）。
 *
 * <p>抽出来的理由：监听器与"死信重放"要做完全相同的解析与分发。
 * 两处各写一遍的必然结局是重放路径与正常路径行为不一致——那是最难查的一类问题。
 *
 * <p><b>解析失败落死信而不是只写日志</b>（S2-04 修正 S2-02 的遗留做法）：
 * 只写日志意味着这条报文永久消失，连"当时收到了什么"都无从复原。
 */
@Service
public class InboxMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InboxMessageDispatcher.class);

    /** 落库的报文体上限：死信是给人看的排查材料，不是归档系统。 */
    private static final int MAX_BODY_LENGTH = 8000;
    private static final int MAX_ERROR_LENGTH = 500;

    private final List<InboxConsumer> consumers;
    private final InboxProcessor processor;
    private final OpsMapper opsMapper;

    public InboxMessageDispatcher(List<InboxConsumer> consumers, InboxProcessor processor, OpsMapper opsMapper) {
        this.consumers = consumers;
        this.processor = processor;
        this.opsMapper = opsMapper;
    }

    /**
     * 分发一条报文。
     *
     * @param body              原始报文
     * @param recordDeadLetter  解析失败时是否落死信。重放路径传 {@code false}：
     *                          原死信记录还在，再落一条只会制造重复的待处置项
     */
    public DispatchResult dispatch(String body, boolean recordDeadLetter) {
        InboxMessage message;
        try {
            message = InboxMessageCodec.parse(body);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (recordDeadLetter) {
                opsMapper.insertDeadLetter(null, "pms.inbox", truncate(body, MAX_BODY_LENGTH),
                        truncate(reason, MAX_ERROR_LENGTH));
                log.error("无法解析的事件报文已落死信（不可重试）：{}", reason);
            } else {
                log.warn("重放的报文仍然无法解析：{}", reason);
            }
            return new DispatchResult(DispatchResult.Kind.DEAD_LETTERED, 0, reason);
        }

        int applied = 0;
        boolean handled = false;
        for (InboxConsumer consumer : consumers) {
            if (!consumer.supports(message.eventType())) {
                continue;
            }
            handled = true;
            processor.process(consumer, message);
            applied++;
        }
        if (!handled) {
            log.info("事件 {}({}) 没有订阅者，已跳过", message.eventId(), message.eventType());
        }
        return new DispatchResult(DispatchResult.Kind.PROCESSED, applied, null);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 分发结果。
     *
     * @param kind            处理结论
     * @param consumersApplied 实际应用到的消费者数量
     * @param detail          失败原因（成功时为 null）
     */
    public record DispatchResult(Kind kind, int consumersApplied, String detail) {

        public enum Kind {
            PROCESSED,
            DEAD_LETTERED
        }

        public boolean processed() {
            return kind == Kind.PROCESSED;
        }
    }
}
