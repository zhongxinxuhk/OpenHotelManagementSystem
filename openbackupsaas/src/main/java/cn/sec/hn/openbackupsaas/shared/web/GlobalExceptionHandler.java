package cn.sec.hn.openbackupsaas.shared.web;

import cn.sec.hn.openbackupsaas.shared.error.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 统一错误响应：RFC 9457 ProblemDetail，不返回堆栈、内部类名或 SQL。
 *
 * <p>继承 {@link ResponseEntityExceptionHandler} 以保留 Spring MVC 对 404/405/415 等标准异常的处理，
 * 只覆写校验失败与补充兜底，避免把 404 误报成 500。
 *
 * <p>错误码体系与「失败/冲突/待核实」三分类在 S1-11 冻结，此处只保证：
 * 客户端能拿到 status、detail、requestId 与字段级错误。
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
        problem.setProperty("errors", toFieldErrors(ex));
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** 业务前置条件不满足（调用方输入问题），不记 error 级日志。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, String.valueOf(ex.getMessage()));
        problem.setTitle("请求无法处理");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", currentRequestId());
        return problem;
    }

    /** 业务冲突（编码重复、状态不允许）：409，客户端应提示处理而不是改输入。 */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail onConflict(ConflictException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, String.valueOf(ex.getMessage()));
        problem.setTitle("操作与当前状态冲突");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", currentRequestId());
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
        return problem;
    }

    /** 未预期异常：对外只给通用文案 + requestId，堆栈只进日志。 */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex, HttpServletRequest request) {
        String requestId = currentRequestId();
        log.error("unhandled exception requestId={} path={}", requestId, request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "服务内部错误，请携带 requestId 反馈");
        problem.setTitle("服务内部错误");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", requestId);
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
