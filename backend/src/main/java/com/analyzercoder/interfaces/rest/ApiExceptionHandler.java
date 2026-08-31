package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.ci.CiCheckException;
import com.analyzercoder.application.intelligence.CodeGraphException;
import com.analyzercoder.application.knowledge.KnowledgeDriftException;
import com.analyzercoder.application.memory.TaskContextException;
import com.analyzercoder.application.outcome.TaskReviewOutcomeException;
import com.analyzercoder.application.pullrequest.PullRequestIntegrationException;
import com.analyzercoder.application.project.EngineeringProjectException;
import com.analyzercoder.application.review.TaskReviewException;
import com.analyzercoder.security.ApiSecurityException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将参数、权限、业务和基础设施异常转换为稳定的 HTTP 错误响应，避免泄漏内部细节。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiSecurityException.class)
    public ResponseEntity<ApiErrorResponse> security(ApiSecurityException e) {
        return ResponseEntity.status(e.status())
                .body(ApiErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> argument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException e) {
        String m =
                e.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(x -> x.getField() + " " + x.getDefaultMessage())
                        .orElse("Validation failed");
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("VALIDATION_FAILED", m));
    }

    @ExceptionHandler(TaskReviewException.class)
    public ResponseEntity<ApiErrorResponse> taskReview(TaskReviewException e) {
        HttpStatus status =
                switch (e.code()) {
                    case "TASK_REVIEW_NOT_FOUND", "REPOSITORY_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "IDEMPOTENCY_KEY_CONFLICT",
                                    "CURRENT_SNAPSHOT_REQUIRED",
                                    "SNAPSHOT_CHANGED_DURING_REVIEW",
                                    "CHANGE_HEAD_NOT_CURRENT_SNAPSHOT" ->
                            HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(PullRequestIntegrationException.class)
    public ResponseEntity<ApiErrorResponse> pullRequest(PullRequestIntegrationException e) {
        HttpStatus status =
                switch (e.code()) {
                    case "REPOSITORY_NOT_FOUND", "WEBHOOK_REPOSITORY_NOT_FOUND" ->
                            HttpStatus.NOT_FOUND;
                    case "CURRENT_SNAPSHOT_REQUIRED", "PR_HEAD_NOT_CURRENT_SNAPSHOT" ->
                            HttpStatus.CONFLICT;
                    case "PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "WEBHOOK_NOT_CONFIGURED" ->
                            HttpStatus.SERVICE_UNAVAILABLE;
                    case "PROVIDER_HTTP_ERROR" -> HttpStatus.BAD_GATEWAY;
                    case "WEBHOOK_SIGNATURE_INVALID" -> HttpStatus.UNAUTHORIZED;
                    case "WEBHOOK_REPOSITORY_AMBIGUOUS" -> HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(CiCheckException.class)
    public ResponseEntity<ApiErrorResponse> ciCheck(CiCheckException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(TaskReviewOutcomeException.class)
    public ResponseEntity<ApiErrorResponse> taskOutcome(TaskReviewOutcomeException e) {
        HttpStatus status =
                switch (e.code()) {
                    case "TASK_OUTCOME_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "TASK_OUTCOME_REVIEW_NOT_COMPLETED",
                                    "TASK_OUTCOME_IDEMPOTENCY_CONFLICT" ->
                            HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(EngineeringProjectException.class)
    public ResponseEntity<ApiErrorResponse> engineeringProject(EngineeringProjectException e) {
        HttpStatus status =
                switch (e.code()) {
                    case "ENGINEERING_PROJECT_NOT_FOUND", "REPOSITORY_NOT_FOUND" ->
                            HttpStatus.NOT_FOUND;
                    case "ENGINEERING_PROJECT_VERSION_CONFLICT", "ENGINEERING_PROJECT_IN_USE" ->
                            HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(TaskContextException.class)
    public ResponseEntity<ApiErrorResponse> taskContext(TaskContextException e) {
        HttpStatus status =
                switch (e.code()) {
                    case "CURRENT_SNAPSHOT_REQUIRED",
                                    "TASK_REVIEW_NOT_COMPLETED",
                                    "TASK_REVIEW_SNAPSHOT_MISMATCH" ->
                            HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(KnowledgeDriftException.class)
    public ResponseEntity<ApiErrorResponse> knowledgeDrift(KnowledgeDriftException e) {
        HttpStatus status =
                switch (e.code()) {
                    case "KNOWLEDGE_CARD_NOT_FOUND", "REPOSITORY_NOT_FOUND" ->
                            HttpStatus.NOT_FOUND;
                    case "KNOWLEDGE_REVISION_CONFLICT", "CURRENT_SNAPSHOT_REQUIRED" ->
                            HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(CodeGraphException.class)
    public ResponseEntity<ApiErrorResponse> codeGraph(CodeGraphException e) {
        HttpStatus status =
                switch (e.code()) {
                    case "CODEGRAPH_SYMBOL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "CODEGRAPH_CLI_OUTPUT_INVALID",
                                    "CODEGRAPH_IMPACT_SCHEMA_UNSUPPORTED",
                                    "CODEGRAPH_EXPORT_SCHEMA_UNSUPPORTED" ->
                            HttpStatus.UNPROCESSABLE_ENTITY;
                    case "CODEGRAPH_IMPACT_QUERY_FAILED", "CODEGRAPH_EXPORT_NOT_AVAILABLE" ->
                            HttpStatus.SERVICE_UNAVAILABLE;
                    default -> HttpStatus.CONFLICT;
                };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ApiErrorResponse> conflict(Exception e) {
        LOG.warn("Request conflict", e);
        String m = e instanceof DataIntegrityViolationException ? "数据约束校验失败" : e.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of("CONFLICT", m));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception e) {
        LOG.error("Unexpected request failure", e);
        return ResponseEntity.status(500).body(ApiErrorResponse.of("INTERNAL_ERROR", "服务器内部错误"));
    }

    public record ApiErrorResponse(String code, String message, Instant timestamp) {
        static ApiErrorResponse of(String c, String m) {
            return new ApiErrorResponse(c, m, Instant.now());
        }
    }
}
