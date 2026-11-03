package cn.sec.hn.openbackupsaas.inbox.service;

import cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage;
import cn.sec.hn.openbackupsaas.inbox.mapper.InboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 挂起事件的重放（S2-02）。
 *
 * <p>版本缺口只有在"缺的那条后来补上了"时才可能解开。所以重放不是可选的运维手段，
 * 而是<b>缺口策略的一部分</b>：只挂起不重放，等于把这些事件无限期扣在库里。
 *
 * <p>逐条独立事务（由 {@link InboxProcessor#redrive} 提供）：一条重放失败不能连累同批其他条，
 * 否则一条坏消息会让整个聚合的重放永远卡在第一条上。
 */
@Service
public class InboxIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(InboxIntegrityService.class);

    private final InboxMapper mapper;
    private final InboxProcessor processor;
    private final Map<String, InboxConsumer> consumersByName = new HashMap<>();

    public InboxIntegrityService(InboxMapper mapper, InboxProcessor processor, List<InboxConsumer> consumers) {
        this.mapper = mapper;
        this.processor = processor;
        for (InboxConsumer consumer : consumers) {
            InboxConsumer previous = consumersByName.put(consumer.name(), consumer);
            if (previous != null) {
                // 同名消费者会导致去重依据含糊：到底哪一份台账算数？宁可启动失败。
                throw new IllegalStateException("消费者名重复：" + consumer.name());
            }
        }
    }

    /**
     * 重放挂起事件。
     *
     * @param consumerName 消费者名（Inbox 台账里的键）
     * @param limit        单批上限，防止一次重放把库拖住
     */
    public RedriveReport redriveParked(String consumerName, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数，收到 " + limit);
        }
        InboxConsumer consumer = consumersByName.get(consumerName);
        if (consumer == null) {
            throw new IllegalArgumentException("未知消费者：" + consumerName);
        }

        List<InboxMessage> parked = mapper.findParked(consumerName, limit);
        int processed = 0;
        int stale = 0;
        int stillParked = 0;
        for (InboxMessage message : parked) {
            InboxProcessor.InboxResult result = processor.redrive(consumer, message);
            switch (result.outcome()) {
                case PROCESSED -> processed++;
                case SKIPPED_STALE, IGNORED -> stale++;
                default -> stillParked++;
            }
        }
        if (!parked.isEmpty()) {
            log.info("重放挂起事件（{}）：候选 {} 应用 {} 转旧/忽略 {} 仍挂起 {}",
                    consumerName, parked.size(), processed, stale, stillParked);
        }
        return new RedriveReport(parked.size(), processed, stale, stillParked);
    }

    public long parkedCount(String consumerName) {
        return mapper.countParked(consumerName);
    }

    /**
     * 一次重放的结果。
     *
     * @param candidates  取到的挂起事件数
     * @param processed   成功应用
     * @param resolved    转为旧事件/忽略（无需应用）
     * @param stillParked 缺口仍在，继续挂起
     */
    public record RedriveReport(int candidates, int processed, int resolved, int stillParked) {
    }
}
