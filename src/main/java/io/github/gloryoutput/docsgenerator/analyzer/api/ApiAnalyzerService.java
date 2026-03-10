package io.github.gloryoutput.docsgenerator.analyzer.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerResult.EndpointChange;
import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerResult.EndpointInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.*;

/**
 * Spring Boot endpoint 분석 서비스
 *
 * <p>RequestMappingHandlerMapping에서 등록된 모든 endpoint를 수집하고,
 * 이전 스냅샷과 비교하여 추가/삭제된 endpoint를 감지합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class ApiAnalyzerService {
    private static final String SOURCE_TYPE = "API_ENDPOINT";
    private final RequestMappingHandlerMapping handlerMapping;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiAnalyzerService(RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    /**
     * 현재 등록된 endpoint 목록을 수집하고 이전 스냅샷과 비교합니다.
     *
     * @param previousSnapshotJson 이전 스냅샷 JSON (null이면 최초 분석)
     * @return API 분석 결과
     */
    public ApiAnalyzerResult analyze(String previousSnapshotJson) {
        List<EndpointInfo> currentEndpoints = collectEndpoints();
        List<EndpointChange> changes;
        if (previousSnapshotJson == null) {
            changes = buildInitialChanges(currentEndpoints);
        } else {
            try {
                List<EndpointInfo> previousEndpoints = objectMapper.readValue(
                        previousSnapshotJson, new TypeReference<>() {});
                changes = compareSnapshots(previousEndpoints, currentEndpoints);
            } catch (Exception e) {
                log.error("API 스냅샷 비교 실패: {}", e.getMessage(), e);
                changes = buildInitialChanges(currentEndpoints);
            }
        }
        return ApiAnalyzerResult.builder()
                .totalEndpoints(currentEndpoints.size())
                .totalChanges(changes.size())
                .endpoints(currentEndpoints)
                .changes(changes)
                .build();
    }

    /**
     * 현재 endpoint 스냅샷 JSON을 생성합니다 (저장용).
     */
    public String captureSnapshotJson() {
        try {
            return objectMapper.writeValueAsString(collectEndpoints());
        } catch (Exception e) {
            log.error("API 스냅샷 캡처 실패: {}", e.getMessage(), e);
            throw new RuntimeException("API 스냅샷 캡처 실패: " + e.getMessage(), e);
        }
    }

    /**
     * RequestMappingHandlerMapping에서 endpoint 목록을 수집합니다.
     */
    private List<EndpointInfo> collectEndpoints() {
        List<EndpointInfo> endpoints = new ArrayList<>();
        Map<RequestMappingInfo, HandlerMethod> methods = handlerMapping.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : methods.entrySet()) {
            RequestMappingInfo mappingInfo = entry.getKey();
            HandlerMethod handlerMethod = entry.getValue();
            // 앱 패키지의 컨트롤러만 수집 (Spring 내부 제외)
            String className = handlerMethod.getBeanType().getName();
            if (!className.startsWith("io.github.gloryoutput.docsgenerator")) continue;
            Set<String> patterns = new LinkedHashSet<>();
            if (mappingInfo.getPathPatternsCondition() != null) {
                mappingInfo.getPathPatternsCondition().getPatterns()
                        .forEach(p -> patterns.add(p.getPatternString()));
            }
            Set<String> httpMethods = new LinkedHashSet<>();
            mappingInfo.getMethodsCondition().getMethods()
                    .forEach(m -> httpMethods.add(m.name()));
            // method가 비어있으면 모든 HTTP method 허용
            if (httpMethods.isEmpty()) httpMethods.add("ALL");
            for (String path : patterns) {
                for (String method : httpMethods) {
                    endpoints.add(EndpointInfo.builder()
                            .httpMethod(method)
                            .path(path)
                            .handlerClass(handlerMethod.getBeanType().getSimpleName())
                            .handlerMethod(handlerMethod.getMethod().getName())
                            .build());
                }
            }
        }
        endpoints.sort(Comparator.comparing(EndpointInfo::getPath)
                .thenComparing(EndpointInfo::getHttpMethod));
        return endpoints;
    }

    /**
     * 최초 분석 시 모든 endpoint를 ENDPOINT_ADDED로 반환합니다.
     */
    private List<EndpointChange> buildInitialChanges(List<EndpointInfo> endpoints) {
        return endpoints.stream()
                .map(ep -> EndpointChange.builder()
                        .sourceType(SOURCE_TYPE)
                        .changeType("ENDPOINT_ADDED")
                        .httpMethod(ep.getHttpMethod())
                        .path(ep.getPath())
                        .handlerClass(ep.getHandlerClass())
                        .handlerMethod(ep.getHandlerMethod())
                        .build())
                .toList();
    }

    /**
     * 이전 스냅샷과 비교하여 추가/삭제된 endpoint를 반환합니다.
     */
    private List<EndpointChange> compareSnapshots(List<EndpointInfo> previous, List<EndpointInfo> current) {
        List<EndpointChange> changes = new ArrayList<>();
        Set<String> prevKeys = new HashSet<>();
        Map<String, EndpointInfo> prevMap = new HashMap<>();
        for (EndpointInfo ep : previous) {
            String key = ep.getHttpMethod() + " " + ep.getPath();
            prevKeys.add(key);
            prevMap.put(key, ep);
        }
        Set<String> currKeys = new HashSet<>();
        Map<String, EndpointInfo> currMap = new HashMap<>();
        for (EndpointInfo ep : current) {
            String key = ep.getHttpMethod() + " " + ep.getPath();
            currKeys.add(key);
            currMap.put(key, ep);
        }
        // endpoint 추가 감지
        for (String key : currKeys) {
            if (!prevKeys.contains(key)) {
                EndpointInfo ep = currMap.get(key);
                changes.add(EndpointChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("ENDPOINT_ADDED")
                        .httpMethod(ep.getHttpMethod()).path(ep.getPath())
                        .handlerClass(ep.getHandlerClass()).handlerMethod(ep.getHandlerMethod())
                        .build());
            }
        }
        // endpoint 삭제 감지
        for (String key : prevKeys) {
            if (!currKeys.contains(key)) {
                EndpointInfo ep = prevMap.get(key);
                changes.add(EndpointChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("ENDPOINT_REMOVED")
                        .httpMethod(ep.getHttpMethod()).path(ep.getPath())
                        .handlerClass(ep.getHandlerClass()).handlerMethod(ep.getHandlerMethod())
                        .build());
            }
        }
        return changes;
    }
}
