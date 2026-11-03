package cn.sec.hn.openbackupsaas.command.web;

import cn.sec.hn.openbackupsaas.command.domain.IdempotencyRecord;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 操作结果对外投影（S1-07）。
 *
 * <p>刻意**不回显**请求摘要与响应快照：摘要可用于探测，快照可能含业务细节。
 * 客户端要判断"是不是我那次操作"，用自己手上的键与内容算摘要后比对状态即可。
 */
public record OperationView(
        UUID operationId,
        UUID hotelId,
        String commandName,
        String status,
        String resultCode,
        UUID resultRef,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {

    public static OperationView of(IdempotencyRecord record) {
        return new OperationView(record.operationId(), record.hotelId(), record.commandName(),
                record.status().name(), record.resultCode(), record.resultRef(),
                record.createdAt(), record.completedAt());
    }
}
