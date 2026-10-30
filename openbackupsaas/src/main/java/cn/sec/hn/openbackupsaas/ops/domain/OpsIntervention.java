package cn.sec.hn.openbackupsaas.ops.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一次人工处置的记录（S2-04）。
 *
 * <p>为什么前后状态都要存：事后追责问的是"你动手之前它是什么样"。
 * 只记动作（"我重放了"）证明不了这次处置是必要且正确的。
 *
 * @param reviewerName 高风险处置（资金/库存类）的第二位操作者；普通处置为空
 */
public record OpsIntervention(
        UUID interventionId,
        UUID tenantId,
        TargetType targetType,
        UUID targetId,
        Action action,
        UUID operatorId,
        String operatorName,
        String reviewerName,
        String reason,
        String beforeState,
        String afterState,
        OffsetDateTime occurredAt
) {

    public enum TargetType {
        DEAD_LETTER,
        EXTERNAL_TASK,
        INBOX_EVENT
    }

    public enum Action {
        REPLAY,
        DISMISS,
        RESOLVE_SUCCEEDED,
        RESOLVE_FAILED
    }
}
