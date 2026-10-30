package cn.sec.hn.openbackupsaas.journal.messaging;

/**
 * 发布未获得确认（S2-01）。
 *
 * <p>刻意不使用 {@code RuntimeException}：发布失败是<b>预期内的运行状态</b>
 * （Broker 停机、网络抖动、交换机未建），不是程序缺陷。
 * 用受检异常强迫调用链上的每一层做决定：重试、跳过、还是升级告警。
 *
 * <p>消息文本会落到 {@code outbox_event.last_error}，所以必须是<b>可读且稳定</b>的
 * 一句话，不塞堆栈（堆栈在日志里，库里只留结论）。落库前会截断，见发布器。
 */
public class EventPublishException extends Exception {

    public EventPublishException(String message) {
        super(message);
    }

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
