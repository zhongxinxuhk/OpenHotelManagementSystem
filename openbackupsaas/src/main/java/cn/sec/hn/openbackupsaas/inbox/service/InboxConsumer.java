package cn.sec.hn.openbackupsaas.inbox.service;

import cn.sec.hn.openbackupsaas.inbox.domain.InboxMessage;

import java.util.Set;

/**
 * 消费者模板（S2-02）。
 *
 * <p>刻意做成<b>抽象类</b>而不是注解驱动的回调：消费的关键纪律不是"收到消息就干活"，
 * 而是"去重、版本判定、位点推进、失败回滚"这四件事必须由框架统一做，
 * 业务只写 {@link #apply}。<b>让每个消费者自己去记得做去重，等于把正确性交给记性。</b>
 *
 * <p>{@link #apply} 在<b>调用方的事务里</b>执行：抛异常会连同 Inbox 台账与位点一起回滚，
 * 消息会被重新投递。因此这里<b>不需要也不允许</b>自己捕获异常当作成功——
 * "处理失败但记成已处理"会让那条消息永久丢失。
 */
public abstract class InboxConsumer {

    /** 消费者名。写进 Inbox 台账，改名等于换了一份去重依据，属破坏性变更。 */
    public abstract String name();

    /** 订阅的事件类型。未订阅的类型会被记为 IGNORED 而不是失败。 */
    public abstract Set<String> handledEventTypes();

    /** 业务应用。运行在 Inbox 事务内，抛异常即整体回滚。 */
    public abstract void apply(InboxMessage message);

    public final boolean supports(String eventType) {
        return handledEventTypes().contains(eventType);
    }
}
