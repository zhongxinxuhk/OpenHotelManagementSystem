package cn.sec.hn.openbackupsaas.command.service;

import cn.sec.hn.openbackupsaas.command.domain.IdempotencyRecord;
import cn.sec.hn.openbackupsaas.command.domain.IdempotencyStatus;
import cn.sec.hn.openbackupsaas.command.domain.RequestDigest;
import cn.sec.hn.openbackupsaas.command.mapper.IdempotencyMapper;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 幂等记录与命令入口（S1-07）。
 *
 * <p><b>时间线（为什么这样就不会出现半截事实）：</b>
 * <ol>
 *   <li>事务开始 → 插入 {@code IN_PROGRESS}（唯一键占位）；</li>
 *   <li>执行业务（占用、流水、审计、事件都在同一事务里）；</li>
 *   <li>标记 {@code SUCCEEDED} 并写结果引用 → 提交。</li>
 * </ol>
 * 要么三步一起成功（重试能查回同一个结果），要么一起回滚（重试从零开始）。
 * 不存在"业务成了但记录没成"这种最难排查的状态。
 *
 * <p><b>并发：</b>第二个请求撞唯一键 → 拒绝（409），不等待、不排队、不重复执行。
 * 这比"加锁等第一个做完"更简单，也避免了长事务持锁。
 */
@Service
public class IdempotencyService {

    /** 内部/系统路径的命名空间：客户端可重放的命令必须用自己的命名空间，不得冒用。 */
    public static final String SYSTEM_NAMESPACE = "system";

    private final IdempotencyMapper idempotencyMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyMapper idempotencyMapper, ObjectMapper objectMapper, Clock clock) {
        this.idempotencyMapper = idempotencyMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 开启一次**内部**操作：不可被客户端键重放，仅用于给流水/事件一个真实的 operation_id。
     *
     * <p>客户端可重放的命令请用 {@link #execute}，不要拿这个方法冒充幂等入口。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID beginInternal(UUID tenantId, UUID hotelId, String commandName, String requestHash) {
        IdempotencyRecord record = IdempotencyRecord.begin(UUID.randomUUID(), tenantId, hotelId,
                SYSTEM_NAMESPACE, commandName, UUID.randomUUID().toString(), RequestDigest.CURRENT_VERSION,
                requestHash);
        idempotencyMapper.insertInProgress(record);
        return record.operationId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void succeed(UUID tenantId, UUID operationId, UUID resultRef, Map<String, Object> snapshot) {
        idempotencyMapper.markSucceeded(tenantId, operationId, resultRef, toJson(snapshot),
                OffsetDateTime.now(clock));
    }

    /**
     * 把"还没产生副作用就被拒绝"的命令记成 FAILED，让重试直接拿到同一个结论。
     *
     * <p>只对**确定没有副作用**的失败使用（前置校验、明确冲突）。
     * 业务执行到一半失败时不要用——那种情况事务会回滚，本来就该允许重试。
     */
    @Transactional
    public void recordRejected(CommandRequest request, String resultCode) {
        String hash = RequestDigest.sha256Hex(request.payload());
        IdempotencyRecord rejected = new IdempotencyRecord(UUID.randomUUID(), request.tenantId(),
                request.hotelId(), request.callerNamespace(), request.commandName(), request.idempotencyKey(),
                RequestDigest.CURRENT_VERSION, hash, IdempotencyStatus.FAILED, resultCode, null, null,
                OffsetDateTime.now(clock), OffsetDateTime.now(clock));
        if (idempotencyMapper.insertFailed(rejected) == 0) {
            throw new ConflictException("幂等键已存在且状态不同，不能复用：" + request.idempotencyKey());
        }
    }

    /** 把一次已存在的 IN_PROGRESS 操作置为终态失败；用于调用方已确认没有副作用时。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID tenantId, UUID operationId, String resultCode) {
        idempotencyMapper.markFailed(tenantId, operationId, resultCode, OffsetDateTime.now(clock));
    }

    /**
     * 命令入口：同一作用域键 + 同一内容只产生一次效果。
     *
     * @param business 业务实现，参数是本次操作的 operation_id；返回业务结果（含结果引用）
     * @return 执行结果；命中既有记录时 {@code replayed = true}
     */
    @Transactional
    public <T> CommandResult<T> execute(CommandRequest request, java.util.function.Function<UUID, CommandOutcome<T>> business) {
        String hash = RequestDigest.sha256Hex(request.payload());
        IdempotencyRecord pending = IdempotencyRecord.begin(UUID.randomUUID(), request.tenantId(),
                request.hotelId(), request.callerNamespace(), request.commandName(), request.idempotencyKey(),
                RequestDigest.CURRENT_VERSION, hash);

        // 用 ON CONFLICT DO NOTHING 判断"是不是第一次"，而不是靠捕获唯一键异常：
        // 捕获异常后事务已被 PostgreSQL 标记为中止，后面的查询会全部失败。
        int inserted = idempotencyMapper.insertInProgress(pending);
        if (inserted == 0) {
            IdempotencyRecord existing = idempotencyMapper.findByScope(request.tenantId(), request.hotelId(),
                    request.callerNamespace(), request.commandName(), request.idempotencyKey())
                    .orElseThrow(() -> new ConflictException("同一命令正在处理中，请稍后重试"));
            return replay(existing, hash);
        }

        // 业务抛异常时**不做任何补偿写**：外层事务必然回滚，IN_PROGRESS 行随之消失，
        // 于是重试是从零开始而不是"接着一个不确定的状态"。想记录终态失败请用 recordRejected。
        CommandOutcome<T> outcome = business.apply(pending.operationId());
        succeed(request.tenantId(), pending.operationId(), outcome.resultRef(), outcome.snapshot());
        return new CommandResult<>(pending.operationId(), outcome.value(), outcome.resultRef(), false);
    }

    @SuppressWarnings("unchecked")
    private <T> CommandResult<T> replay(IdempotencyRecord existing, String hash) {
        if (!existing.requestHash().equals(hash) || existing.hashVersion() != RequestDigest.CURRENT_VERSION) {
            throw new ConflictException("幂等键已用于不同的请求内容，不能复用：" + existing.idempotencyKey());
        }
        if (existing.status() == IdempotencyStatus.IN_PROGRESS) {
            throw new ConflictException("同一命令正在处理中，请稍后重试");
        }
        if (existing.status() == IdempotencyStatus.FAILED) {
            throw new ConflictException("该命令此前已失败（" + existing.resultCode() + "），不得用同一键重放");
        }
        // SUCCEEDED：返回同一个结果引用，客户端据此拿回原结果
        return new CommandResult<>(existing.operationId(), null, existing.resultRef(), true);
    }

    /** 按操作标识查询（租户内）。 */
    @Transactional(readOnly = true)
    public IdempotencyRecord requireOperation(UUID tenantId, UUID operationId) {
        return idempotencyMapper.findByOperation(tenantId, operationId)
                .orElseThrow(() -> new NotFoundException("操作不存在：" + operationId));
    }

    /** 按作用域键查询，供幂等重试前的"先查一次"使用。 */
    @Transactional(readOnly = true)
    public java.util.Optional<IdempotencyRecord> findByScope(UUID tenantId, UUID hotelId, String namespace,
                                                             String commandName, String key) {
        return idempotencyMapper.findByScope(tenantId, hotelId, namespace, commandName, key);
    }

    private String toJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot == null ? Map.of() : snapshot);
        } catch (Exception ex) {
            throw new IllegalStateException("响应快照无法序列化", ex);
        }
    }

    /** 命令请求：作用域五元组 + 规范化前的原始载荷。 */
    public record CommandRequest(UUID tenantId, UUID hotelId, String callerNamespace, String commandName,
                                 String idempotencyKey, Object payload) {

        public CommandRequest {
            if (tenantId == null || hotelId == null) {
                throw new IllegalArgumentException("幂等作用域必须包含租户与门店");
            }
            if (callerNamespace == null || callerNamespace.isBlank()) {
                throw new IllegalArgumentException("调用主体命名空间不能为空");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("幂等键不能为空");
            }
        }
    }

    /** 业务结果：结果引用必须给出，否则下次无法还原。 */
    public record CommandOutcome<T>(T value, UUID resultRef, Map<String, Object> snapshot) {

        public CommandOutcome {
            if (resultRef == null) {
                throw new IllegalArgumentException("业务结果必须给出结果引用");
            }
        }
    }

    /** 命令执行结果。{@code replayed} 为 true 表示命中既有记录，业务没有再次执行。 */
    public record CommandResult<T>(UUID operationId, T value, UUID resultRef, boolean replayed) {
    }
}
