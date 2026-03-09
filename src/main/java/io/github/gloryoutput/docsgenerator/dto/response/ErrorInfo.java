package io.github.gloryoutput.docsgenerator.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

/**
 * 에러 상세 정보
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class ErrorInfo {
    private final String code;
    private final String message;
    private final String path;
    private final List<FieldErrorInfo> fields;

    @Getter
    @Builder
    public static class FieldErrorInfo {
        private final String field;
        private final Object value;
        private final String reason;
    }
}
