package cn.sec.hn.openbackupsaas.guest.mapper;

import cn.sec.hn.openbackupsaas.guest.domain.GuestLoginPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 住客登录方式开关的数据访问（ADR-019 §2.9）。
 *
 * <p>独立成 Mapper 而不是塞进 {@code GuestMapper}：读方有两个平面
 * （住客登录时查、平台控制台管理），塞进任何一边都会让另一个平面
 * "为了读一张小表"跨过整张业务 Mapper。独立出来，依赖关系一眼可见。
 */
@Mapper
public interface GuestLoginPolicyMapper {

    Optional<GuestLoginPolicy> find(@Param("provider") String provider);

    List<GuestLoginPolicy> findAll();

    int upsert(@Param("provider") String provider,
               @Param("enabled") boolean enabled,
               @Param("updatedBy") String updatedBy,
               @Param("at") OffsetDateTime at);
}
