package io.github.gloryoutput.docsgenerator.util;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 보고서 출력 시 클라이언트에게 노출하지 않을 changeSummary 패턴과 인프라 파일을 통합 관리합니다.
 *
 * <p>분석 로직(레이어 판별, 키워드 추출)에는 영향을 주지 않으며,
 * 보고서의 '기능별 변경 내용' 영역에서만 필터링됩니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
public final class ReportNoiseFilter {
    private ReportNoiseFilter() {}

    // ── changeSummary 정규식 필터 ──
    /** 매칭되면 해당 changeSummary 항목을 보고서에서 제거합니다. */
    private static final List<Pattern> SUMMARY_REGEX_FILTERS = List.of(
            Pattern.compile("^MODIFY\\s*\\([+\\-\\d/\\s]+lines?\\)$"),
            Pattern.compile("^[+\\-\\d/\\s]+lines?$"),
            Pattern.compile("^(ADD|DELETE|RENAME|COPY)(\\s.*)?$"),
            Pattern.compile("^추가 어노테이션:.*")
    );

    // ── changeSummary 접두사 필터 ──
    /** startsWith로 매칭하여 해당 항목을 보고서에서 제거합니다. */
    private static final List<String> SUMMARY_PREFIX_FILTERS = List.of(
            // 파일 변경 유형
            "MODIFY",
            // 삭제 정보
            "삭제 클래스:",
            "삭제 메서드:",
            // SQL / DB
            "SQL:",
            "SQL 변경",
            // 빌드 / 의존성
            "의존성 변경:",
            "의존성 추가:",
            "빌드 설정 변경",
            "pom.xml 변경",
            // 환경설정
            "추가 설정:",
            "제거 설정:",
            "설정 변경"
    );

    // ── 인프라 파일 확장자 ──
    private static final Set<String> INFRA_EXTENSIONS = Set.of(
            ".sql", ".yml", ".yaml", ".properties", ".env"
    );

    // ── 인프라 파일명 (정확 일치, 소문자 비교) ──
    private static final Set<String> INFRA_FILE_NAMES = Set.of(
            "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts",
            "pom.xml", "gradlew", "gradlew.bat",
            "docker-compose.yml", "docker-compose.yaml",
            ".dockerignore", ".gitignore", ".editorconfig", "lombok.config"
    );

    // ── 인프라 경로 키워드 (contains 검사, 소문자 비교) ──
    private static final List<String> INFRA_PATH_KEYWORDS = List.of(
            "/gradle/", "/.github/", "/.gitlab-ci"
    );

    /**
     * changeSummary 항목이 보고서에서 제외 대상인지 판별합니다.
     *
     * @param trimmedPart 세미콜론으로 분리된 changeSummary의 개별 항목 (trim 완료)
     * @return true이면 보고서에서 제외
     */
    public static boolean shouldFilterSummary(String trimmedPart) {
        for (String prefix : SUMMARY_PREFIX_FILTERS) {
            if (trimmedPart.startsWith(prefix)) return true;
        }
        for (Pattern pattern : SUMMARY_REGEX_FILTERS) {
            if (pattern.matcher(trimmedPart).matches()) return true;
        }
        return false;
    }

    /**
     * 클라이언트가 몰라도 되는 인프라/설정/빌드/DB 파일인지 판별합니다.
     *
     * <p>SQL, 빌드 설정(Gradle/Maven), 환경설정(yml/yaml/properties),
     * Docker, CI/CD 파일 등 비즈니스 로직과 무관한 파일을 필터링합니다.</p>
     *
     * @param filePath 파일 경로
     * @return true이면 인프라 파일
     */
    public static boolean isInfraFile(String filePath) {
        String normalized = filePath.replace('\\', '/').toLowerCase();
        // 파일명 추출
        int lastSlash = normalized.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        // Dockerfile (접두사 매칭)
        if (fileName.startsWith("dockerfile")) return true;
        // 정확 일치 파일명
        if (INFRA_FILE_NAMES.contains(fileName)) return true;
        // 확장자 검사
        for (String ext : INFRA_EXTENSIONS) {
            if (fileName.endsWith(ext)) return true;
        }
        // 경로 키워드 검사
        for (String keyword : INFRA_PATH_KEYWORDS) {
            if (normalized.contains(keyword)) return true;
        }
        return false;
    }
}
