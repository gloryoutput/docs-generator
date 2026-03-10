package io.github.gloryoutput.docsgenerator.filter;

import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerResult;
import io.github.gloryoutput.docsgenerator.analyzer.database.DbSchemaResult;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 의미 없는 변경(noise)을 필터링하는 서비스
 *
 * <p>Git 파일 변경, DB 스키마 변경, API 변경에서 실질적이지 않은 변경을 제거합니다.
 * 테스트 코드, 설정 파일, 문서 파일, 내부 시스템 테이블, 인프라 엔드포인트 등을
 * noise로 판단하여 필터링합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class NoiseFilterService {
    /** 포맷팅/설정 파일로 간주하여 제거할 파일명 패턴 */
    private static final Set<String> NOISE_FILE_NAMES = Set.of(
            ".gitignore", ".editorconfig", ".prettierrc", ".eslintrc",
            "package-lock.json", "yarn.lock"
    );
    /** noise로 간주할 파일 확장자 */
    private static final Set<String> NOISE_LOCK_EXTENSIONS = Set.of(".lock");
    /** noise로 간주할 디렉토리 접두사 */
    private static final Set<String> NOISE_DIRECTORY_PREFIXES = Set.of(".idea/", ".vscode/");
    /** 문서 전용 변경으로 간주할 파일명 */
    private static final Set<String> DOCUMENTATION_FILE_NAMES = Set.of(
            "README.md", "CHANGELOG.md", "LICENSE"
    );
    /** Hibernate 내부 테이블 접두사 */
    private static final Set<String> HIBERNATE_TABLE_PREFIXES = Set.of("hibernate_");
    /** Spring 내부 테이블 접두사 */
    private static final Set<String> SPRING_TABLE_PREFIXES = Set.of("SPRING_SESSION");
    /** noise로 간주할 API 경로 패턴 */
    private static final Set<String> NOISE_API_PATH_PREFIXES = Set.of(
            "/actuator", "/v3/api-docs", "/swagger-ui"
    );
    /** noise로 간주할 정확한 API 경로 */
    private static final Set<String> NOISE_API_EXACT_PATHS = Set.of("/error");

    /**
     * Git 파일 변경 목록에서 noise를 제거합니다.
     *
     * <p>테스트 코드, 포맷팅/설정 파일, 문서 전용 파일 변경을 필터링합니다.
     * 원본 객체를 수정하지 않고 새로운 필터링된 복사본을 반환합니다.</p>
     *
     * @param gitResults Git diff 분석 결과 목록
     * @return noise가 제거된 새로운 GitDiffResult 목록
     */
    public List<GitDiffResult> filterGitChanges(List<GitDiffResult> gitResults) {
        if (gitResults == null || gitResults.isEmpty()) {
            return Collections.emptyList();
        }
        log.debug("Git 변경 noise 필터링 시작: {} 건", gitResults.size());
        List<GitDiffResult> filtered = gitResults.stream()
                .map(this::filterSingleGitResult)
                .collect(Collectors.toList());
        int totalBefore = gitResults.stream()
                .mapToInt(r -> r.getFileChanges() != null ? r.getFileChanges().size() : 0)
                .sum();
        int totalAfter = filtered.stream()
                .mapToInt(r -> r.getFileChanges() != null ? r.getFileChanges().size() : 0)
                .sum();
        log.info("Git 변경 noise 필터링 완료: 파일 변경 {} -> {} 건 ({}건 제거)",
                totalBefore, totalAfter, totalBefore - totalAfter);
        return filtered;
    }

    /**
     * DB 스키마 변경 목록에서 noise를 제거합니다.
     *
     * <p>Hibernate 내부 테이블, Spring 내부 테이블 변경을 필터링합니다.
     * 원본 객체를 수정하지 않고 새로운 필터링된 복사본을 반환합니다.</p>
     *
     * @param schemaResults DB 스키마 분석 결과 목록
     * @return noise가 제거된 새로운 DbSchemaResult 목록
     */
    public List<DbSchemaResult> filterSchemaChanges(List<DbSchemaResult> schemaResults) {
        if (schemaResults == null || schemaResults.isEmpty()) {
            return Collections.emptyList();
        }
        log.debug("DB 스키마 변경 noise 필터링 시작: {} 건", schemaResults.size());
        List<DbSchemaResult> filtered = schemaResults.stream()
                .map(this::filterSingleSchemaResult)
                .collect(Collectors.toList());
        int totalBefore = schemaResults.stream()
                .mapToInt(r -> r.getChanges() != null ? r.getChanges().size() : 0)
                .sum();
        int totalAfter = filtered.stream()
                .mapToInt(r -> r.getChanges() != null ? r.getChanges().size() : 0)
                .sum();
        log.info("DB 스키마 변경 noise 필터링 완료: {} -> {} 건 ({}건 제거)",
                totalBefore, totalAfter, totalBefore - totalAfter);
        return filtered;
    }

    /**
     * API 변경 결과에서 noise를 제거합니다.
     *
     * <p>Spring Actuator, Swagger/OpenAPI, error 엔드포인트를 필터링합니다.
     * 원본 객체를 수정하지 않고 새로운 필터링된 복사본을 반환합니다.</p>
     *
     * @param apiResult API 분석 결과
     * @return noise가 제거된 새로운 ApiAnalyzerResult
     */
    public ApiAnalyzerResult filterApiChanges(ApiAnalyzerResult apiResult) {
        if (apiResult == null) {
            return null;
        }
        log.debug("API 변경 noise 필터링 시작");
        List<ApiAnalyzerResult.EndpointInfo> filteredEndpoints = filterEndpoints(apiResult.getEndpoints());
        List<ApiAnalyzerResult.EndpointChange> filteredChanges = filterEndpointChanges(apiResult.getChanges());
        int endpointsBefore = apiResult.getEndpoints() != null ? apiResult.getEndpoints().size() : 0;
        int changesBefore = apiResult.getChanges() != null ? apiResult.getChanges().size() : 0;
        ApiAnalyzerResult filtered = ApiAnalyzerResult.builder()
                .totalEndpoints(filteredEndpoints.size())
                .totalChanges(filteredChanges.size())
                .endpoints(filteredEndpoints)
                .changes(filteredChanges)
                .build();
        log.info("API 변경 noise 필터링 완료: 엔드포인트 {} -> {} 건, 변경 {} -> {} 건",
                endpointsBefore, filteredEndpoints.size(),
                changesBefore, filteredChanges.size());
        return filtered;
    }

    /**
     * 단일 GitDiffResult에서 noise 파일 변경을 제거한 복사본을 생성합니다.
     * 커밋별 파일 변경과 전체 파일 변경 모두 필터링합니다.
     */
    private GitDiffResult filterSingleGitResult(GitDiffResult result) {
        List<GitDiffResult.FileChange> filteredFileChanges = result.getFileChanges() != null
                ? result.getFileChanges().stream()
                        .filter(fc -> !isNoiseFileChange(fc))
                        .collect(Collectors.toList())
                : Collections.emptyList();
        // 커밋별 파일 변경도 필터링
        List<GitDiffResult.CommitInfo> filteredCommits = result.getCommits() != null
                ? result.getCommits().stream()
                        .map(this::filterCommitFileChanges)
                        .collect(Collectors.toList())
                : Collections.emptyList();
        return GitDiffResult.builder()
                .repositoryName(result.getRepositoryName())
                .repositoryUrl(result.getRepositoryUrl())
                .totalCommits(result.getTotalCommits())
                .commits(filteredCommits)
                .fileChanges(filteredFileChanges)
                .error(result.getError())
                .build();
    }

    /**
     * 커밋의 파일 변경 목록에서 noise를 제거한 복사본을 생성합니다.
     */
    private GitDiffResult.CommitInfo filterCommitFileChanges(GitDiffResult.CommitInfo commit) {
        List<GitDiffResult.FileChange> filtered = commit.getFileChanges() != null
                ? commit.getFileChanges().stream()
                        .filter(fc -> !isNoiseFileChange(fc))
                        .collect(Collectors.toList())
                : Collections.emptyList();
        return GitDiffResult.CommitInfo.builder()
                .commitHash(commit.getCommitHash())
                .authorName(commit.getAuthorName())
                .message(commit.getMessage())
                .dateTime(commit.getDateTime())
                .fileChanges(filtered)
                .build();
    }

    /**
     * 파일 변경이 noise인지 판별합니다.
     *
     * @param fileChange 파일 변경 정보
     * @return noise이면 true
     */
    private boolean isNoiseFileChange(GitDiffResult.FileChange fileChange) {
        String filePath = fileChange.getFilePath();
        if (filePath == null) {
            return false;
        }
        // 테스트 코드 필터링
        if (isTestFile(filePath)) {
            log.trace("테스트 코드 noise 제거: {}", filePath);
            return true;
        }
        // 포맷팅/설정 파일 필터링
        if (isFormattingOrConfigFile(filePath)) {
            log.trace("설정 파일 noise 제거: {}", filePath);
            return true;
        }
        // 문서 전용 파일 필터링
        if (isDocumentationFile(filePath)) {
            log.trace("문서 파일 noise 제거: {}", filePath);
            return true;
        }
        return false;
    }

    /**
     * 테스트 코드 파일인지 확인합니다.
     */
    private boolean isTestFile(String filePath) {
        String normalized = filePath.replace('\\', '/');
        if (normalized.contains("/test/") || normalized.contains("/tests/")) {
            return true;
        }
        return normalized.endsWith("Test.java")
                || normalized.endsWith("Tests.java")
                || normalized.endsWith("Spec.java");
    }

    /**
     * 포맷팅/설정 파일인지 확인합니다.
     */
    private boolean isFormattingOrConfigFile(String filePath) {
        String normalized = filePath.replace('\\', '/');
        String fileName = extractFileName(normalized);
        if (NOISE_FILE_NAMES.contains(fileName)) {
            return true;
        }
        // .lock 확장자 파일
        for (String ext : NOISE_LOCK_EXTENSIONS) {
            if (normalized.endsWith(ext)) {
                return true;
            }
        }
        // .idea/, .vscode/ 디렉토리 하위 파일
        for (String prefix : NOISE_DIRECTORY_PREFIXES) {
            if (normalized.contains("/" + prefix) || normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 문서 전용 파일인지 확인합니다.
     */
    private boolean isDocumentationFile(String filePath) {
        String normalized = filePath.replace('\\', '/');
        String fileName = extractFileName(normalized);
        if (DOCUMENTATION_FILE_NAMES.contains(fileName)) {
            return true;
        }
        // *.md 파일은 문서 전용 변경으로 간주
        return normalized.endsWith(".md");
    }

    /**
     * 경로에서 파일명만 추출합니다.
     */
    private String extractFileName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }

    /**
     * 단일 DbSchemaResult에서 noise 테이블 변경을 제거한 복사본을 생성합니다.
     */
    private DbSchemaResult filterSingleSchemaResult(DbSchemaResult result) {
        List<DbSchemaResult.SchemaChange> filteredChanges = result.getChanges() != null
                ? result.getChanges().stream()
                        .filter(sc -> !isNoiseSchemaChange(sc))
                        .collect(Collectors.toList())
                : Collections.emptyList();
        return DbSchemaResult.builder()
                .databaseName(result.getDatabaseName())
                .totalTables(result.getTotalTables())
                .totalChanges(filteredChanges.size())
                .changes(filteredChanges)
                .error(result.getError())
                .build();
    }

    /**
     * 스키마 변경이 noise인지 판별합니다.
     *
     * @param schemaChange 스키마 변경 항목
     * @return noise이면 true
     */
    private boolean isNoiseSchemaChange(DbSchemaResult.SchemaChange schemaChange) {
        String tableName = schemaChange.getTableName();
        if (tableName == null) {
            return false;
        }
        String lowerTableName = tableName.toLowerCase();
        // Hibernate 내부 테이블
        for (String prefix : HIBERNATE_TABLE_PREFIXES) {
            if (lowerTableName.startsWith(prefix)) {
                log.trace("Hibernate 내부 테이블 noise 제거: {}", tableName);
                return true;
            }
        }
        // Spring 내부 테이블 (대소문자 무시)
        for (String prefix : SPRING_TABLE_PREFIXES) {
            if (lowerTableName.startsWith(prefix.toLowerCase())) {
                log.trace("Spring 내부 테이블 noise 제거: {}", tableName);
                return true;
            }
        }
        return false;
    }

    /**
     * API 엔드포인트 목록에서 noise를 제거합니다.
     */
    private List<ApiAnalyzerResult.EndpointInfo> filterEndpoints(List<ApiAnalyzerResult.EndpointInfo> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return Collections.emptyList();
        }
        return endpoints.stream()
                .filter(ep -> !isNoiseApiPath(ep.getPath()))
                .collect(Collectors.toList());
    }

    /**
     * API 엔드포인트 변경 목록에서 noise를 제거합니다.
     */
    private List<ApiAnalyzerResult.EndpointChange> filterEndpointChanges(List<ApiAnalyzerResult.EndpointChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return Collections.emptyList();
        }
        return changes.stream()
                .filter(ec -> !isNoiseApiPath(ec.getPath()))
                .collect(Collectors.toList());
    }

    /**
     * API 경로가 noise인지 판별합니다.
     *
     * @param path API 경로
     * @return noise이면 true
     */
    private boolean isNoiseApiPath(String path) {
        if (path == null) {
            return false;
        }
        // 정확한 경로 매칭
        if (NOISE_API_EXACT_PATHS.contains(path)) {
            log.trace("noise API 경로 제거: {}", path);
            return true;
        }
        // 접두사 매칭 (예: /actuator, /actuator/health 등)
        for (String prefix : NOISE_API_PATH_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                log.trace("noise API 경로 제거: {}", path);
                return true;
            }
        }
        return false;
    }
}
