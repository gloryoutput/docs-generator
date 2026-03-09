package io.github.gloryoutput.docsgenerator.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * API 표준 응답 래퍼
 *
 * <p>모든 API 응답은 이 형식을 따릅니다.</p>
 * <ul>
 *   <li>성공: {@code success=true, data=T}</li>
 *   <li>실패: {@code success=false, error=ErrorInfo}</li>
 * </ul>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final ErrorInfo error;
    private final Object pagination;

    /** 데이터가 있는 성공 응답 */
    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    /** 데이터 없는 성공 응답 */
    public static ApiResponse<Void> ok() {
        return ApiResponse.<Void>builder()
                .success(true)
                .build();
    }

    /** 실패 응답 */
    public static ApiResponse<Void> fail(ErrorInfo error) {
        return ApiResponse.<Void>builder()
                .success(false)
                .error(error)
                .build();
    }
}
