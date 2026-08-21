package com.elma.gohan.infrastructure.web;

import com.elma.gohan.application.NoRecommendationAvailableException;
import com.elma.gohan.application.RecommendationNotFoundException;
import com.elma.gohan.application.ValidationFailedException;
import com.elma.gohan.application.FeedbackAlreadyRecordedException;
import com.elma.gohan.controller.api.ErrorResponse;
import com.elma.gohan.provider.poi.PoiProviderException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 业务错误统一转 ErrorResponse,前端按稳定 code 分支处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), defaultMessage(fe)))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法", fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerValidation(HandlerMethodValidationException e) {
        List<ErrorResponse.FieldError> fieldErrors = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> new ErrorResponse.FieldError(
                                r.getMethodParameter().getParameterName(), err.getDefaultMessage())))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法", fieldErrors);
    }

    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<ErrorResponse> handleValidationFailed(ValidationFailedException e) {
        List<ErrorResponse.FieldError> fieldErrors = e.getFieldErrors().entrySet().stream()
                .map(entry -> new ErrorResponse.FieldError(entry.getKey(), entry.getValue()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法", fieldErrors);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法",
                List.of(new ErrorResponse.FieldError(e.getHeaderName(), "必填")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求体不是合法的 JSON", null);
    }

    @ExceptionHandler(NoRecommendationAvailableException.class)
    public ResponseEntity<ErrorResponse> handleNoRecommendation(NoRecommendationAvailableException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "NO_RECOMMENDATION_AVAILABLE",
                e.getMessage(), null);
    }

    @ExceptionHandler(RecommendationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(RecommendationNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "RECOMMENDATION_NOT_FOUND", e.getMessage(), null);
    }

    @ExceptionHandler(FeedbackAlreadyRecordedException.class)
    public ResponseEntity<ErrorResponse> handleFeedbackConflict(FeedbackAlreadyRecordedException e) {
        return build(HttpStatus.CONFLICT, "FEEDBACK_ALREADY_RECORDED", e.getMessage(), null);
    }

    @ExceptionHandler(PoiProviderException.class)
    public ResponseEntity<ErrorResponse> handlePoiProvider(PoiProviderException e) {
        log.warn("POI 上游不可用: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, "POI_PROVIDER_UNAVAILABLE",
                "附近餐厅服务暂时不可用,请稍后再试", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "RECOMMENDATION_NOT_FOUND", "接口不存在", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("未处理异常", e);
        // 契约未定义 500 的错误码,使用稳定内部码;不属于四个业务错误码场景。
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误,请稍后再试",
                null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                List<ErrorResponse.FieldError> fieldErrors) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, fieldErrors, MDC.get(TraceIdFilter.MDC_KEY)));
    }

    private String defaultMessage(org.springframework.context.MessageSourceResolvable fe) {
        String message = fe.getDefaultMessage();
        return message == null ? "不合法" : message;
    }
}
