package cn.sec.hn.openbackupsaas.platform.service;

import cn.sec.hn.openbackupsaas.guest.domain.VerificationChannel;
import cn.sec.hn.openbackupsaas.platform.mapper.PlatformMapper;
import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 核验渠道配置的维护（ADR-019 §2.6）。
 *
 * <p>需求原话是"权威接口支持在管理员后台自定义设置，后续再说明对接哪些权威核验接口"。
 * 所以这里的职责只有"把配置存对、读得出来"，不含任何厂商对接。
 *
 * <p><b>一条硬规则：人工渠道不允许停用</b>。它是权威接口熔断时唯一的兜底路径
 * （需求明确要求"熔断时人工照常"）。停掉它，等于在权威接口抖动的那一刻
 * 让所有新住客注册全部卡死——而且这个后果在配置界面上完全看不出来。
 * 与其靠运维记得，不如在服务层直接拒绝。
 */
@Service
public class PlatformChannelService {

    private static final Logger log = LoggerFactory.getLogger(PlatformChannelService.class);

    /** 与角色码同一套约定：大写字母开头，允许数字与下划线。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    private final PlatformMapper platformMapper;
    private final Clock clock;

    public PlatformChannelService(PlatformMapper platformMapper, Clock clock) {
        this.platformMapper = platformMapper;
        this.clock = clock;
    }

    public List<VerificationChannel> list() {
        return platformMapper.findChannels();
    }

    public VerificationChannel get(String code) {
        return platformMapper.findChannelByCode(code)
                .orElseThrow(() -> new NotFoundException("核验渠道不存在：" + code));
    }

    @Transactional
    public VerificationChannel create(String rawCode,
                                      String name,
                                      VerificationChannel.ChannelKind kind,
                                      String endpoint,
                                      String credentialRef,
                                      int timeoutMs,
                                      int priority) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "渠道编码必须是大写字母开头、仅含大写字母数字下划线、长度 2–32：" + rawCode);
        }
        if (platformMapper.findChannelByCode(code).isPresent()) {
            throw new ConflictException("渠道编码已存在：" + code);
        }
        validateEndpoint(kind, endpoint);
        validateTimeout(timeoutMs);

        OffsetDateTime now = OffsetDateTime.now(clock);
        VerificationChannel channel = new VerificationChannel(
                UUID.randomUUID(), code, name, kind,
                kind == VerificationChannel.ChannelKind.MANUAL ? null : endpoint,
                credentialRef, timeoutMs, priority,
                VerificationChannel.ChannelStatus.ACTIVE,
                VerificationChannel.CircuitState.CLOSED, 0, null, null, null,
                0L, now, now);
        platformMapper.insertChannel(channel);
        log.info("verification channel created code={} kind={}", code, kind);
        return get(code);
    }

    @Transactional
    public VerificationChannel update(String code,
                                      String name,
                                      String endpoint,
                                      String credentialRef,
                                      int timeoutMs,
                                      int priority,
                                      VerificationChannel.ChannelStatus status) {
        VerificationChannel existing = get(code);
        validateEndpoint(existing.kind(), endpoint);
        validateTimeout(timeoutMs);
        if (existing.kind() == VerificationChannel.ChannelKind.MANUAL
                && status != VerificationChannel.ChannelStatus.ACTIVE) {
            // 见类注释：人工是唯一兜底。停用它不是一个"配置选择"，是把兜底删掉。
            throw new ConflictException(
                    "人工审核渠道不能停用——它是权威接口熔断时唯一的兜底路径");
        }

        platformMapper.updateChannelConfig(code, name, endpoint, credentialRef,
                timeoutMs, priority, status.name(), OffsetDateTime.now(clock));
        log.info("verification channel updated code={} status={}", code, status);
        return get(code);
    }

    /**
     * 手动复位熔断。
     *
     * <p>必须给原因：自动恢复靠"下一次调用试探"，而配置填错导致的失败会
     * 让状态在 CLOSED 与 OPEN 之间来回跳，从状态看不出"到底修没修好"。
     * 把"我已经改了什么"写进记录，下一次有人接手才看得懂。
     */
    @Transactional
    public VerificationChannel resetCircuit(String code, String reason) {
        VerificationChannel existing = get(code);
        if (existing.kind() != VerificationChannel.ChannelKind.AUTHORITY) {
            // 人工渠道没有熔断这回事，给它复位只会让人误以为它参与过熔断。
            throw new ConflictException("人工审核渠道没有熔断状态，无需复位");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("手动复位熔断必须填写原因");
        }
        platformMapper.resetCircuit(code, "MANUAL_RESET: " + reason.trim(),
                OffsetDateTime.now(clock));
        log.warn("verification channel circuit reset code={} reason={}", code, reason);
        return get(code);
    }

    private static void validateEndpoint(VerificationChannel.ChannelKind kind, String endpoint) {
        boolean blank = endpoint == null || endpoint.isBlank();
        if (kind == VerificationChannel.ChannelKind.AUTHORITY && blank) {
            throw new IllegalArgumentException("权威核验渠道必须填写接口地址");
        }
        if (kind == VerificationChannel.ChannelKind.MANUAL && !blank) {
            // 人工渠道没有外部依赖。允许它填地址会让人误以为它在调外部接口，
            // 排查"为什么没走权威核验"时先被误导一轮。
            throw new IllegalArgumentException("人工审核渠道不应填写接口地址");
        }
    }

    private static void validateTimeout(int timeoutMs) {
        if (timeoutMs < 200 || timeoutMs > 30_000) {
            throw new IllegalArgumentException("超时时间必须在 200–30000 毫秒之间：" + timeoutMs);
        }
    }
}
