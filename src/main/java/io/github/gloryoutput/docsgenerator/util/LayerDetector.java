package io.github.gloryoutput.docsgenerator.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git diff 결과의 파일 경로를 분석하여 아키텍처 레이어를 판별하는 유틸리티
 *
 * <p>Change Event Builder 알고리즘의 3단계(Layer 판별)를 담당합니다.
 * 파일 경로 패턴과 파일명 접미사를 기반으로 Controller, Service, Repository 등의
 * 레이어를 식별합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
public final class LayerDetector {
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final List<String> REMOVABLE_SUFFIXES = List.of(
            "Controller", "ServiceImpl", "Service", "Repository",
            "Request", "Response", "Entity", "Config", "Dto"
    );
    // ServiceImpl must precede Service to avoid partial removal
    private static final List<String> QUERY_KEYWORDS = List.of(
            "Query", "Search", "Sort", "Filter", "Specification", "Criteria"
    );
    private LayerDetector() {
    }
    /**
     * 파일 경로를 분석하여 해당 파일이 속하는 아키텍처 레이어를 반환합니다.
     *
     * @param filePath Git diff에서 추출한 파일 경로
     * @return 레이어명 (CONTROLLER, SERVICE, REPOSITORY, ENTITY, MIGRATION, CONFIG, DTO, UNKNOWN)
     */
    public static String detectLayer(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = filePath.replace('\\', '/');
        String fileName = extractFileName(normalized);
        // CONTROLLER
        if (normalized.contains("/controller/") || fileName.endsWith("Controller.java")) {
            return "CONTROLLER";
        }
        // SERVICE
        if (normalized.contains("/service/") || fileName.endsWith("Service.java") || fileName.endsWith("ServiceImpl.java")) {
            return "SERVICE";
        }
        // REPOSITORY
        if (normalized.contains("/repository/") || fileName.endsWith("Repository.java")) {
            return "REPOSITORY";
        }
        // ENTITY - /entity/ 또는 /domain/ 경로의 .java 파일 (Repository.java 제외)
        if ((normalized.contains("/entity/") || normalized.contains("/domain/"))
                && fileName.endsWith(".java") && !fileName.endsWith("Repository.java")) {
            return "ENTITY";
        }
        // MIGRATION
        if (normalized.contains("/migration/") || normalized.contains("/sql/") || fileName.endsWith(".sql")) {
            return "MIGRATION";
        }
        // CONFIG
        if (normalized.contains("/config/")
                || fileName.endsWith(".properties") || fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            return "CONFIG";
        }
        // DTO
        if (normalized.contains("/dto/") || fileName.endsWith("Request.java") || fileName.endsWith("Response.java")) {
            return "DTO";
        }
        return "UNKNOWN";
    }
    /**
     * 파일 경로 목록에서 발견된 모든 고유 레이어를 반환합니다.
     *
     * @param filePaths Git diff에서 추출한 파일 경로 목록
     * @return 식별된 레이어명 집합
     */
    public static Set<String> detectLayers(List<String> filePaths) {
        Set<String> layers = new LinkedHashSet<>();
        if (filePaths == null) {
            return layers;
        }
        for (String filePath : filePaths) {
            layers.add(detectLayer(filePath));
        }
        return layers;
    }
    /**
     * 파일 경로에서 의미 있는 키워드를 추출합니다.
     *
     * <p>파일명에서 확장자와 공통 접미사(Controller, Service 등)를 제거한 후
     * CamelCase를 기준으로 분리하여 소문자 키워드 목록으로 반환합니다.</p>
     *
     * @param filePath Git diff에서 추출한 파일 경로
     * @return 소문자 키워드 목록 (예: "BookSortController.java" → ["book", "sort"])
     */
    public static List<String> extractKeywords(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return List.of();
        }
        String fileName = extractFileName(filePath.replace('\\', '/'));
        // 확장자 제거
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
        // 공통 접미사 제거
        for (String suffix : REMOVABLE_SUFFIXES) {
            if (baseName.endsWith(suffix) && baseName.length() > suffix.length()) {
                baseName = baseName.substring(0, baseName.length() - suffix.length());
                break;
            }
        }
        // CamelCase 분리 후 소문자 변환
        String[] words = CAMEL_CASE_PATTERN.split(baseName);
        List<String> keywords = new ArrayList<>();
        for (String word : words) {
            if (!word.isBlank()) {
                keywords.add(word.toLowerCase());
            }
        }
        return keywords;
    }
    /**
     * 파일 경로 목록에 쿼리/검색 관련 기능이 포함되어 있는지 판별합니다.
     *
     * <p>파일명에 Query, Search, Sort, Filter, Specification, Criteria가 포함되어 있거나
     * 경로에 /querydsl/ 또는 /specification/이 포함된 경우 true를 반환합니다.</p>
     *
     * @param filePaths Git diff에서 추출한 파일 경로 목록
     * @return 쿼리/검색 패턴이 존재하면 true
     */
    public static boolean containsQueryPattern(List<String> filePaths) {
        if (filePaths == null) {
            return false;
        }
        for (String filePath : filePaths) {
            String normalized = filePath.replace('\\', '/');
            if (normalized.contains("/querydsl/") || normalized.contains("/specification/")) {
                return true;
            }
            String fileName = extractFileName(normalized);
            for (String keyword : QUERY_KEYWORDS) {
                if (fileName.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }
    /** 경로에서 파일명만 추출 */
    private static String extractFileName(String normalizedPath) {
        int lastSlash = normalizedPath.lastIndexOf('/');
        return (lastSlash >= 0) ? normalizedPath.substring(lastSlash + 1) : normalizedPath;
    }
}
