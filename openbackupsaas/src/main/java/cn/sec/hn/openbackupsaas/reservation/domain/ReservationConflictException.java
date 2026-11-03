package cn.sec.hn.openbackupsaas.reservation.domain;

import cn.sec.hn.openbackupsaas.shared.error.ConflictException;

import java.util.List;
import java.util.UUID;

/**
 * 创建订单时的结构化冲突（S1-08）。
 *
 * <p>与"占用排斥"那种一句话 409 不同，建单冲突要告诉调用方<b>是哪一行、什么房型、什么日期、需要几间、剩几间</b>，
 * 否则前端只能笼统报"库存不足"。冲突明细由 {@code lineConflicts} 承载，不塞进消息里——
 * 消息仍是给人看的，明细是给程序消费的。
 *
 * <p>本异常<b>不</b>携带任何房间 id：避免"这间房存在吗"被泄露，也避免客户端据此探测库存。
 */
public class ReservationConflictException extends ConflictException {

    private final List<UnavailableLine> lineConflicts;

    public ReservationConflictException(List<UnavailableLine> lineConflicts) {
        super("部分房型在所选日期无法提供房间：共 " + lineConflicts.size() + " 行不满足");
        this.lineConflicts = List.copyOf(lineConflicts);
    }

    public List<UnavailableLine> lineConflicts() {
        return lineConflicts;
    }

    /** 一行无法满足的明细。 */
    public record UnavailableLine(int lineNo, UUID roomTypeId, String checkIn, String checkOut,
                                  int needed, int available) {
    }
}
