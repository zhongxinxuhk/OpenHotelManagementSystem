package cn.sec.hn.openbackupsaas.contract;

import cn.sec.hn.openbackupsaas.support.NoDatabaseStubConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI v1 契约测试（S1-11）。
 *
 * <p><b>它防的是什么：</b>接口形状被"顺手改掉"而没人发现。注解驱动的文档天然会跟着代码漂移——
 * 删一个查询参数、把 200 改成 201、给响应加个字段，编译和单元测试都不会拦，
 * 但前端的类型、外部渠道的适配器、以及已经发出去的示例全会失效。
 *
 * <p><b>只比对结构，不比对文案</b>：描述、summary、示例都可以随手改（它们不影响调用方代码），
 * 一旦纳入比对，改个错别字就要动基线，最后所有人都会习惯性"重新生成基线"，契约就名存实亡。
 * 比对的是：HTTP 方法集合、每操作的状态码集合、参数（位置+名字+是否必填）、
 * 以及 schema 的字段集合与必填项。
 *
 * <p><b>怎么改契约：</b>确实需要变更时，先改代码 → 重新导出
 * （`curl http://127.0.0.1:8080/v3/api-docs`，去掉 servers、格式化）→ 覆盖
 * {@code docs/api/openapi-v1.json} → 在 progress 的本轮条目里写明"契约变更"，
 * 让这次变更是<b>有意识</b>的，而不是被测试逼着点了一下"更新"。
 */
@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(NoDatabaseStubConfig.class)
class OpenApiContractTest {

    /** surefire 的工作目录是模块根（openbackupsaas/），基线在仓库的 docs/api 下。 */
    private static final Path BASELINE = Path.of("..", "docs", "api", "openapi-v1.json");

    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void liveApiMatchesFrozenBaseline() throws Exception {
        assertTrue(Files.exists(BASELINE),
                "缺少契约基线文件：" + BASELINE.toAbsolutePath().normalize()
                        + "（首次使用时从 /v3/api-docs 导出）");

        Map<String, Object> live = readJson(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        Map<String, Object> baseline = readJson(Files.readString(BASELINE, StandardCharsets.UTF_8));

        List<String> differences = new ArrayList<>();
        diff("/", contractOf(baseline), contractOf(live), differences);

        assertTrue(differences.isEmpty(),
                "接口契约与冻结基线不一致（共 " + differences.size() + " 处）：\n  - "
                        + String.join("\n  - ", differences)
                        + "\n\n若这是有意的契约变更，请重新导出 docs/api/openapi-v1.json 并在本轮进度条目里记录。");
    }

    /**
     * 基线本身也应当是一份"能用的"文档：至少要有我们关心的那些接口。
     * 这条用例看着像自检，实际防的是"基线被一份空文档覆盖"。
     */
    @Test
    void baselineStillCoversCoreEndpoints() throws Exception {
        Map<String, Object> contract = contractOf(readJson(Files.readString(BASELINE, StandardCharsets.UTF_8)));
        @SuppressWarnings("unchecked")
        Map<String, Object> operations = (Map<String, Object>) contract.get("operations");

        List<String> required = List.of(
                "GET /api/v1/hotels/{hotelId}/reservations",
                "POST /api/v1/hotels/{hotelId}/reservations",
                "GET /api/v1/hotels/{hotelId}/reservations/{reservationId}",
                "POST /api/v1/hotels/{hotelId}/reservations/{reservationId}/confirm",
                "POST /api/v1/hotels/{hotelId}/reservations/{reservationId}/cancel",
                "GET /api/v1/operations/{operationId}");
        for (String op : required) {
            assertTrue(operations.containsKey(op), "基线缺少核心接口：" + op);
        }
    }

    // ===================== 契约提取 =====================

    /**
     * 把 OpenAPI 文档压缩成"结构指纹"：只保留会影响调用方代码的部分。
     * version / description / summary / example 一律不进指纹。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> contractOf(Map<String, Object> document) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("openapiMajor", String.valueOf(document.get("openapi")).split("\\.")[0]);

        Map<String, Object> operations = new TreeMap<>();
        Map<String, Object> paths = (Map<String, Object>) document.getOrDefault("paths", Map.of());
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) entry.getValue();
            for (String method : METHODS) {
                if (!(item.get(method) instanceof Map<?, ?> rawOperation)) {
                    continue;
                }
                Map<String, Object> operation = (Map<String, Object>) rawOperation;
                Map<String, Object> shape = new LinkedHashMap<>();

                Map<String, Object> responses =
                        (Map<String, Object>) operation.getOrDefault("responses", Map.of());
                shape.put("responses", new ArrayList<>(new TreeSet<>(responses.keySet())));

                List<String> parameters = new ArrayList<>();
                for (Object parameter : (List<Object>) operation.getOrDefault("parameters", List.of())) {
                    Map<String, Object> spec = (Map<String, Object>) parameter;
                    parameters.add(spec.get("in") + ":" + spec.get("name")
                            + ":" + Boolean.TRUE.equals(spec.get("required")));
                }
                Collections.sort(parameters);
                shape.put("parameters", parameters);

                operations.put(method.toUpperCase() + " " + entry.getKey(), shape);
            }
        }
        contract.put("operations", operations);

        Map<String, Object> schemas = new TreeMap<>();
        Map<String, Object> components = (Map<String, Object>) document.getOrDefault("components", Map.of());
        Map<String, Object> rawSchemas = (Map<String, Object>) components.getOrDefault("schemas", Map.of());
        for (Map.Entry<String, Object> entry : rawSchemas.entrySet()) {
            Map<String, Object> schema = (Map<String, Object>) entry.getValue();
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("required", new ArrayList<>(new TreeSet<>(
                    (List<String>) schema.getOrDefault("required", List.of()))));
            Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            shape.put("properties", new ArrayList<>(new TreeSet<>(properties.keySet())));
            schemas.put(entry.getKey(), shape);
        }
        contract.put("schemas", schemas);
        return contract;
    }

    // ===================== 差异比较 =====================

    private static void diff(String path, Object expected, Object actual, List<String> out) {
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            for (Object key : expectedMap.keySet()) {
                if (!actualMap.containsKey(key)) {
                    out.add(path + key + "：运行时缺失（基线里有）");
                } else {
                    diff(path + key + "/", expectedMap.get(key), actualMap.get(key), out);
                }
            }
            for (Object key : actualMap.keySet()) {
                if (!expectedMap.containsKey(key)) {
                    out.add(path + key + "：运行时新增（基线里没有）");
                }
            }
            return;
        }
        if (!Objects.equals(expected, actual)) {
            out.add(path + "：基线=" + expected + "，运行时=" + actual);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        return objectMapper.readValue(json, Map.class);
    }
}
