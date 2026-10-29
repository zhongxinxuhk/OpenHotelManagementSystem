package cn.sec.hn.openbackupsaas.command.mapper;

import cn.sec.hn.openbackupsaas.command.domain.IdempotencyRecord;
import cn.sec.hn.openbackupsaas.command.domain.IdempotencyStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 幂等记录数据访问（S1-07）。SQL 见 {@code resources/mapper/command/IdempotencyMapper.xml}。
 *
 * <p>要点：
 * <ul>
 *   <li>占位用 {@code insertInProgress}：并发的第二个请求会撞唯一键而失败，
 *       这就是"并发同键只产生一次效果"的实现点，不依赖加锁；</li>
 *   <li>没有 delete：保留期不得早于合法重试窗口，清理策略留 S6-03 定案；</li>
 *   <li>查询都带租户（操作查询再叠门店），不做跨租户扫描。</li>
 * </ul>
 */
@Mapper
public interface IdempotencyMapper {

    int insertInProgress(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByScope(@Param("tenantId") UUID tenantId,
                                            @Param("hotelId") UUID hotelId,
                                            @Param("callerNamespace") String callerNamespace,
                                            @Param("commandName") String commandName,
                                            @Param("idempotencyKey") String idempotencyKey);

    Optional<IdempotencyRecord> findByOperation(@Param("tenantId") UUID tenantId,
                                                @Param("operationId") UUID operationId);

    int insertFailed(IdempotencyRecord record);

    int markSucceeded(@Param("tenantId") UUID tenantId,
                      @Param("operationId") UUID operationId,
                      @Param("resultRef") UUID resultRef,
                      @Param("responseSnapshot") String responseSnapshot,
                      @Param("at") OffsetDateTime at);

    int markFailed(@Param("tenantId") UUID tenantId,
                   @Param("operationId") UUID operationId,
                   @Param("resultCode") String resultCode,
                   @Param("at") OffsetDateTime at);

    /** 只查状态，用于并发判断（不序列化快照，省一次大字段读取）。 */
    Optional<IdempotencyStatus> statusOf(@Param("tenantId") UUID tenantId,
                                         @Param("operationId") UUID operationId);
}
