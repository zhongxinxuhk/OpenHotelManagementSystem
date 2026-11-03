package cn.sec.hn.openbackupsaas.support;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 测试库清理（S1-13）。取代手工维护的 {@code integration-cleanup.sql}。
 *
 * <p><b>为什么重写：</b>旧脚本是一串硬编码的 {@code DELETE}。每加一张表都要记得往里加一行，
 * 忘了就是一场排查——R-20261030-10、R-20261030-13、R-20261030-14 三次都栽在同一个坑上，
 * 其中一次直接让 7 个用例连跑都没跑起来（`ScriptStatementFailed` 报的是"第 8 条语句失败"，
 * 而不是"你漏了 room_inventory_day"）。
 *
 * <p>现在的做法：
 * <ol>
 *   <li>从 {@code information_schema} 读当前所有业务表与外键依赖；</li>
 *   <li>按 child → parent 的拓扑序删除（Kahn），不依赖任何人手维护的顺序；</li>
 *   <li>逐表删除并捕获异常，失败时<b>点名是哪张表</b>；</li>
 *   <li>删完<b>断言全库为空</b>——这条断言让"漏清理"从"静默污染后续用例"变成"当场失败"。</li>
 * </ol>
 *
 * <p><b>为什么不用 TRUNCATE：</b>应用账号 {@code pms_app} 故意没有 TRUNCATE 权限
 * （V1 的账号分权设计）。测试要用真实的应用账号跑，才能同时验证"应用权限足够做业务、
 * 不足以做危险操作"，换成迁移账号清理会让这层保证消失。
 *
 * <p>不加 {@code @Component}：它是测试源码里的工具，主应用的组件扫描不会看到它，
 * 由 {@link PostgresIntegrationTest} 直接构造，避免出现"以为注册了其实没注册"的假象。
 *
 * <p><b>字典表与结构性配置不清理：</b>{@code permission} 由迁移写入、
 * {@code flyway_schema_history} 是迁移元数据，两者都不是测试数据。
 * 同理还有 {@code platform_action}（平台动作字典）与 {@code guest_verification_channel}
 * （实名核验渠道，迁移里写入了人工兜底渠道）与 {@code guest_login_policy}（登录方式开关，种子含 USERNAME=true）。
 *
 * <p>判断标准是"这张表的内容**是否属于业务数据**"：字典与结构性配置由迁移确定，
 * 业务运行过程中不会新增；如果它们在测试开始时被清掉，任何依赖字典存在的外键写入
 * 都会失败，而错误表现是"外键约束冲突"，很难让人联想到"清理范围太宽"。
 * 把它们排除在外是明确的，不是"顺手跳过"。
 *
 * <p><b>代价</b>：这些表里的测试数据会跨用例残留。所以往这类表插数据的测试
 * 必须自己清理（用固定 code 并在插入前删除），不能依赖 {@code clean()}。
 */
public class DatabaseCleaner {

    /**
     * 不参与清理的表：迁移元数据、字典、结构性配置。
     *
     * <p>加新表之前先问一句：这张表的内容是"迁移确定的"还是"业务流程产生的"？
     * 只有前者可以放进来。
     */
    private static final Set<String> PRESERVED = Set.of(
            "flyway_schema_history",
            "permission",
            "platform_action",
            "guest_verification_channel",
            "guest_login_policy");

    /** 表名来自 information_schema，仍做字符校验：拼接 SQL 时不该有任何幻想空间。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final JdbcTemplate jdbcTemplate;

    /** 表结构在测试进程内不变，删除顺序只算一次。 */
    private volatile List<String> cachedOrder;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 清空全部业务表并断言已空。
     *
     * @throws IllegalStateException 删除失败（点名表）或删除后仍有数据（点名表与行数）
     */
    public void clean() {
        List<String> failures = new ArrayList<>();
        for (String table : deletionOrder()) {
            try {
                jdbcTemplate.execute("DELETE FROM " + quote(table));
            } catch (DataAccessException ex) {
                failures.add(table + "：" + rootMessage(ex));
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("测试库清理失败（按外键拓扑序删除仍失败，检查是否新增了无法排序的约束）：\n - "
                    + String.join("\n - ", failures));
        }
        assertEmpty();
    }

    /** 删除顺序：child 先于 parent。 */
    List<String> deletionOrder() {
        List<String> cached = cachedOrder;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedOrder == null) {
                cachedOrder = computeDeletionOrder();
            }
            return cachedOrder;
        }
    }

    private List<String> computeDeletionOrder() {
        Set<String> tables = new LinkedHashSet<>(businessTables());
        // children: parent -> 引用它的子表。删除 X 会失败，当且仅当还有别的表引用 X，
        // 所以"可以删 X"的条件是：X 的所有子表都已经删掉。这与"X 依赖谁"无关——
        // 早先按"X 不依赖任何剩余表"判断，会让 tenant（不引用别人）先于 hotel 被删而立刻失败。
        Map<String, Set<String>> children = new HashMap<>();
        tables.forEach(table -> children.put(table, new LinkedHashSet<>()));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                // 用 pg_constraint 而不是 information_schema.constraint_column_usage：
                // 后者对"复合外键"与"引用唯一约束"的情形匹配不到 FK 本身（实测返回 0 行），
                // 于是排序会退化成表名字母序，先删 hotel 再删 room_type 就必然撞外键。
                "SELECT ch.relname AS child, pa.relname AS parent "
                        + "FROM pg_constraint c "
                        + "JOIN pg_class ch ON ch.oid = c.conrelid "
                        + "JOIN pg_class pa ON pa.oid = c.confrelid "
                        + "WHERE c.contype = 'f' "
                        + "  AND ch.relnamespace = current_schema()::regnamespace "
                        + "  AND pa.relnamespace = current_schema()::regnamespace")) {
            String child = String.valueOf(row.get("child"));
            String parent = String.valueOf(row.get("parent"));
            if (tables.contains(child) && tables.contains(parent) && !child.equals(parent)) {
                children.get(parent).add(child);
            }
        }

        List<String> order = new ArrayList<>(tables.size());
        Set<String> remaining = new LinkedHashSet<>(tables);
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            for (String table : List.copyOf(remaining)) {
                if (children.get(table).stream().noneMatch(remaining::contains)) {
                    order.add(table);
                    remaining.remove(table);
                    progress = true;
                }
            }
        }
        if (!remaining.isEmpty()) {
            // 循环外键（互相引用）无法靠 DELETE 解开，只能靠可延迟约束或删表；这里显式失败而不是随便删。
            throw new IllegalStateException("存在循环外键依赖，无法确定删除顺序：" + remaining);
        }
        return List.copyOf(order);
    }

    private List<String> businessTables() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' "
                        + "ORDER BY table_name", String.class).stream()
                .filter(name -> !PRESERVED.contains(name))
                .toList();
    }

    /** 删除后断言全空：漏一张表就会在这里当场点名，而不是污染后续用例。 */
    private void assertEmpty() {
        List<String> dirty = new ArrayList<>();
        for (String table : businessTables()) {
            Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + quote(table), Long.class);
            if (count != null && count > 0) {
                dirty.add(table + "（" + count + " 行）");
            }
        }
        if (!dirty.isEmpty()) {
            throw new IllegalStateException("清理后仍有数据，可能是新增表没有外键关联导致排序遗漏，"
                    + "或清理期间有并发写入：\n - " + String.join("\n - ", dirty));
        }
    }

    private static String quote(String identifier) {
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("意外的表名：" + identifier);
        }
        return '"' + identifier + '"';
    }

    private static String rootMessage(DataAccessException ex) {
        Throwable root = ex.getMostSpecificCause();
        return root == null ? ex.getClass().getSimpleName() : root.getMessage();
    }
}
