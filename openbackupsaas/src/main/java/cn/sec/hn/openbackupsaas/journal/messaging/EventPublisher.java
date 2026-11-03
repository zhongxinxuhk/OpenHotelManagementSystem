package cn.sec.hn.openbackupsaas.journal.messaging;

/**
 * 消息发布端口（S2-01）。
 *
 * <p>只声明<b>一个</b>语义：调用返回正常即代表消息已被消息系统接收（收到确认），
 * 抛异常即代表<b>不能确定</b>是否已投递。发布器对后者的处理只有一种——留库重试；
 * 因此实现类不允许把"不确定"当成功返回，否则事件会静默丢失。
 *
 * <p>异常是受检的：调用方必须显式处理，避免"顺手 try/catch 吞掉"变成静默丢事件。
 */
public interface EventPublisher {

    /**
     * 发布一条事件，直到消息系统确认为止。
     *
     * @throws EventPublishException 未获得确认（含连接失败、确认超时、路由失败）。调用方可重试。
     */
    void publish(OutboxEnvelope envelope) throws EventPublishException;
}
