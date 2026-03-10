package io.github.gloryoutput.docsgenerator.analyzer.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * API endpoint 분석 결과
 *
 * <p>Spring Boot의 RequestMappingHandlerMapping에서 수집한 endpoint 목록과
 * 이전 스냅샷 대비 변경 사항을 담습니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class ApiAnalyzerResult {
    private int totalEndpoints;
    private int totalChanges;
    private List<EndpointInfo> endpoints;
    private List<EndpointChange> changes;

    /**
     * endpoint 정보
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointInfo {
        private String httpMethod;
        private String path;
        private String handlerClass;
        private String handlerMethod;
    }

    /**
     * endpoint 변경 항목
     */
    @Getter
    @Builder
    public static class EndpointChange {
        /** API_ENDPOINT */
        private String sourceType;
        /** ENDPOINT_ADDED, ENDPOINT_REMOVED */
        private String changeType;
        private String httpMethod;
        private String path;
        private String handlerClass;
        private String handlerMethod;
    }
}
