package cn.sec.hn.openbackupsaas.reservation.domain;

/**
 * 一条订单当前 HELD 占用的到期状态（S1-10 的二次校验结果）。
 *
 * <p>两个计数必须一起看：
 * <ul>
 *   <li>{@code heldCount = 0}：没有可释放的 HELD 占用，本单不该由到期任务处理；</li>
 *   <li>{@code undueCount > 0}：还有未到期的 HELD，整单迁移会把没到期的房间一起放出去。</li>
 * </ul>
 * 只有 {@code heldCount > 0 且 undueCount == 0} 才允许整单到期。
 *
 * <p>字段类型用 {@link Long} 而非 {@code long}：PostgreSQL 的 {@code count(*)} 是 int8，
 * MyBatis 构造器映射要求类型精确匹配（项目已踩过这个坑，见 R-20261030-02 发现 2）。
 */
public record HoldDueState(Long heldCount, Long undueCount) {

    /** 是否整单到期：至少一条 HELD，且全部已到期。 */
    public boolean due() {
        return heldCount != null && heldCount > 0
                && (undueCount == null || undueCount == 0);
    }
}
