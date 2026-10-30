package cn.sec.hn.openbackupsaas.shared.web;

import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import cn.sec.hn.openbackupsaas.shared.error.NotFoundException;
import cn.sec.hn.openbackupsaas.shared.error.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 统一错误响应（RFC 9457 ProblemDetail），不返回堆栈、内部类名或 SQL。
 *
 * <p>继承 {@link ResponseEntityExceptionHandler} 以保留 Spring MVC 对 404/405/415 等标准异常的处理，
 * 只覆写"参数层"错误与补充兜底，避免把 404 误报成 500。
 *
 * <p><b>S1-11 起每个错误响应都带 {@code outcome}</b>（{@link ErrorOutcome}）：
 * 客户端据此区分「失败 / 冲突 / 待核实」，不用靠状态码猜。
 * 其中 {@code UNKNOWN} 尤其重要——它意味着"结果不确定"，前端必须给查询原交易结果的入口，
 * 而不是显示失败让用户重新下单（那会变成重复预订）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败");
        problem.setTitle("请求参数不合法");
        problem.setProperty("requestId", currentRequestId());
        problem.setProperty("outcome", ErrorOutcome.REJECTED);
        problem.setProperty("errors", toFieldErrors(ex));
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * 路径/查询参数类型不匹配（如 {@code ?status=FOO}、非法 UUID、非法日期）。
     *
     * <p>覆写的是父类的 {@code handleTypeMismatch}（Spring 6/7 的方法名，不是
     * {@code handleMethodArgumentTypeMismatch}）——写错名字时 {@code @Override} 会在编译期报错，
     * 但如果不加 {@code @Override} 就会变成一个永不生效的"处理器"，参数错误仍然返回
     * Spring 的英文原文且不带 requestId，与其它错误形态不一致。
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
                                                        HttpHeaders headers,
                                                        HttpStatusCode status,
                                                        WebRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : String.valueOf(ex.getPropertyName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "参数 " + field + " 的取值不合法");
        problem.setTitle("请求参数不合法");
        problem.setProperty("requestId", currentRequestId());
        problem.setProperty("outcome", ErrorOutcome.REJECTED);
        problem.setProperty("errors", List.of(Map.of(
                "field", field,
                "message", "无法解析为期望的类型")));
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** 缺少必填查询参数：同样是 400，且要指出是哪个参数。 */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                         HttpHeaders headers,
                                                                         HttpStatusCode status,
                                                                         WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "缺少必填参数 " + ex.getParameterName());
        problem.setTitle("请求参数不合法");
        problem.setProperty("requestId", currentRequestId());
        problem.setProperty("outcome", ErrorOutcome.REJECTED);
        problem.setProperty("errors", List.of(Map.of(
                "field", ex.getParameterName(),
                "message", "必填")));
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** 业务前置条件不满足（调用方输入问题），不记 error 级日志。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, String.valueOf(ex.getMessage()));
        problem.setTitle("请求无法处理");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", currentRequestId());
        problem.setProperty("outcome", ErrorOutcome.REJECTED);
        return problem;
    }

    /** 业务冲突（状态不允许、版本过期、占用冲突）：409，客户端应提示处理而不是自动重试。 */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail onConflict(ConflictException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, String.valueOf(ex.getMessage()));
        problem.setTitle("操作与当前状态冲突");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", currentRequestId());
        problem.setProperty("outcome", ErrorOutcome.CONFLICT);
        return problem;
    }

    /**
     * 未认证 / 凭据无效：401。
     *
     * <p>文案由抛出方决定且必须是统一话术（登录失败不区分账号/密码/租户），
     * 这里不追加细节，避免把失败原因变成可用信息。
     */
    @ExceptionHandler(UnauthenticatedException.class)
    public ProblemDetail onUnauthenticated(UnauthenticatedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, String.valueOf(ex.getMessage()));
        problem.setTitle("未认证");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", currentRequestId());
        problem.setProperty("outcome", ErrorOutcome.REJECTED);
        return problem;
    }

    /**
     * 已认证但无权访问：403。
     *
     * <p>必须显式声明：本类还有 {@code @ExceptionHandler(Exception.class)} 兜底，
     * 少了这一条，{@code @PreAuthorize} 拒绝抛出的 {@code AccessDeniedException}
     * 会被兜底逻辑当成"未预期异常"返回 500 —— 权限失败被误报成服务故障，
     * 既误导运维，也会让前端的 401/403 处理分支永远走不到。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail onAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                String.valueOf(ex.getMessage()));
        problem.setTitle("无访问权限");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", currentRequestId());
        problem.setProperty("outcome", ErrorOutcome.REJECTED);
        return problem;
    }

    /** 对象不存在：404。跨租户 id 一律走这里，不用 403 暴露"对象确实存在"。 */
    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail onNotFound(NotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, String.valueOf(ex.getMessage()));
        problem.setTitle("对象不存在");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", currentRequestId());
        problem.setProperty("outcome", ErrorOutcome.REJECTED);
        return problem;
    }

    /**
     * 数据库约束冲突（唯一键、外键、CHECK）：对外只给通用结论，不返回 SQL 或表结构。
     *
     * <p>日志只记约束名一类信息：PostgreSQL 的唯一/外键错误消息包含约束名而不含行值，
     * 因此不会把业务数据写进日志；非预期约束错误仍需按 requestId 排查。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        String requestId = currentRequestId();
        Throwable root = ex.getMostSpecificCause();
        log.warn("data integrity violation requestId={} path={} cause={}",
                requestId, request.getRequestURI(), root == null ? ex.getClass().getSimpleName() : root.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "数据冲突：违反唯一性或引用约束，请检查编码与关联对象");
        problem.setTitle("数据约束冲突");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", requestId);
        problem.setProperty("outcome", ErrorOutcome.CONFLICT);
        return problem;
    }

    /**
     * 未预期异常：对外只给通用文案 + requestId，堆栈只进日志。
     *
     * <p>{@code outcome = UNKNOWN} 是这里唯一对外承诺的东西：客户端必须把这类响应当成
     * <b>"结果不确定"</b>，而不是"失败"——写操作可能已经提交，重试必须走幂等键或
     * 查询原交易结果（D-10）。
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex, HttpServletRequest request) {
        String requestId = currentRequestId();
        log.error("unhandled exception requestId={} path={}", requestId, request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "服务内部错误，请携带 requestId 反馈；写操作请先查询原交易结果再决定是否重试");
        problem.setTitle("服务内部错误");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", requestId);
        problem.setProperty("outcome", ErrorOutcome.UNKNOWN);
        return problem;
    }

    private static List<Map<String, String>> toFieldErrors(MethodArgumentNotValidException ex) {
        return ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
    }

    private static Map<String, String> toFieldError(FieldError fieldError) {
        return Map.of(
                "field", fieldError.getField(),
                "message", String.valueOf(fieldError.getDefaultMessage())
        );
    }

    private static String currentRequestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null ? "unknown" : requestId;
    }
}
