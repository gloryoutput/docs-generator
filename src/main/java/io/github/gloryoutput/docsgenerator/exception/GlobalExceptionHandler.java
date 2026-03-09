package io.github.gloryoutput.docsgenerator.exception;

import io.github.gloryoutput.docsgenerator.dto.response.ApiResponse;
import io.github.gloryoutput.docsgenerator.dto.response.ErrorInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 전역 예외 처리 핸들러
 *
 * <p>API_RESPONSE_FORMAT.md에 정의된 표준 에러 응답 형식을 따릅니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    /** @Valid 유효성 검증 실패 (400) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorInfo.FieldErrorInfo> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> ErrorInfo.FieldErrorInfo.builder()
                        .field(fe.getField())
                        .value(fe.getRejectedValue())
                        .reason(fe.getDefaultMessage())
                        .build())
                .collect(Collectors.toList());
        String message = "입력값 검증 실패: " + ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + "=" + fe.getDefaultMessage())
                .collect(Collectors.joining(", ", "{", "}"));
        ErrorInfo error = ErrorInfo.builder()
                .code("COMMON_VALIDATION")
                .message(message)
                .path(request.getRequestURI())
                .fields(fieldErrors)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(error));
    }

    /** 타입 변환 오류 (400) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = String.format("파라미터 '%s'의 값 '%s'이(가) 올바르지 않습니다.", ex.getName(), ex.getValue());
        ErrorInfo error = ErrorInfo.builder()
                .code("COMMON_VALIDATION")
                .message(message)
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(error));
    }

    /** IllegalArgumentException (400) */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        ErrorInfo error = ErrorInfo.builder()
                .code("COMMON_BAD_REQUEST")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(error));
    }

    /** 서버 내부 오류 (500) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex, HttpServletRequest request) {
        ErrorInfo error = ErrorInfo.builder()
                .code("COMMON_INTERNAL")
                .message("서버 내부 오류가 발생했습니다")
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail(error));
    }
}
