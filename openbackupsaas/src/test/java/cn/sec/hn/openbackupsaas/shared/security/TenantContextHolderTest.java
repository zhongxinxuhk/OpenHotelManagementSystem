package cn.sec.hn.openbackupsaas.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文绑定与清理（纯单元测试）。
 *
 * <p>这些用例存在的理由：上下文是 ThreadLocal，容器线程会被复用。
 * "忘记清理"在单请求测试里永远看不出来，只有模拟"同一线程连续处理两个身份"才会暴露。
 */
class TenantContextHolderTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void bindThenClearLeavesNoResidue() {
        TenantContextHolder.bind(context(UUID.randomUUID(), "alice"));
        assertTrue(TenantContextHolder.isBound());

        TenantContextHolder.clear();

        assertFalse(TenantContextHolder.isBound());
        assertTrue(TenantContextHolder.current().isEmpty());
        assertThrows(IllegalStateException.class, TenantContextHolder::require);
    }

    @Test
    void secondBindReplacesFirstAndDoesNotMerge() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        TenantContextHolder.bind(context(first, "alice"));
        TenantContextHolder.bind(context(second, "bob"));

        assertEquals(second, TenantContextHolder.require().tenantId());
        assertEquals("bob", TenantContextHolder.require().username());
    }

    @Test
    void runAsRestoresPreviousContextEvenWhenActionThrows() {
        UUID callerTenant = UUID.randomUUID();
        TenantContextHolder.bind(context(callerTenant, "caller"));

        UUID taskTenant = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> TenantContextHolder.runAs(
                context(taskTenant, "worker"),
                () -> {
                    throw new IllegalStateException("任务失败");
                }));

        assertEquals(callerTenant, TenantContextHolder.require().tenantId(),
                "任务抛异常也必须恢复调用方上下文");
    }

    @Test
    void runAsWithoutCallerContextLeavesThreadClean() {
        TenantContextHolder.runAs(context(UUID.randomUUID(), "worker"), () -> {
            assertTrue(TenantContextHolder.isBound());
        });

        assertFalse(TenantContextHolder.isBound(), "调用方原本没有上下文时，任务结束后也不能留下");
    }

    @Test
    void nullContextIsRejectedInsteadOfSilentlyUnbinding() {
        assertThrows(IllegalArgumentException.class, () -> TenantContextHolder.bind(null));
    }

    @Test
    void emptyHotelScopeIsNotAllHotels() {
        TenantContext noScope = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), "nobody", Set.of(), Set.of());

        assertFalse(noScope.coversHotel(UUID.randomUUID()));
        assertFalse(noScope.coversHotel(null));
        assertFalse(noScope.hasPermission("identity:read"));
    }

    private static TenantContext context(UUID tenantId, String username) {
        return new TenantContext(tenantId, UUID.randomUUID(), username, Set.of(), Set.of("identity:read"));
    }
}
