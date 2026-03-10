package io.github.gloryoutput.docsgenerator.analyzer.git;

import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult.CommitInfo;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult.FileChange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JGit 기반 Git 리포지토리 diff 분석 서비스
 *
 * <p>리포지토리를 클론하여 지정 기간 내 커밋과 변경 파일 목록을 수집합니다.
 * commit message 품질에 의존하지 않고, 파일/패턴 중심으로 구조화합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GitAnalyzerService {
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 리포지토리를 클론하고 기간 내 커밋/파일 변경을 분석합니다.
     *
     * @param repositoryName 레포지토리 이름
     * @param repositoryUrl 레포지토리 URL
     * @param branch 분석 대상 브랜치
     * @param startDate 시작일
     * @param endDate 종료일
     * @param token 인증 토큰 (null이면 인증 없이 클론)
     * @return Git diff 분석 결과
     */
    public GitDiffResult analyze(String repositoryName, String repositoryUrl,
                                  String branch, LocalDate startDate, LocalDate endDate, String token) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("docs-gen-");
            CredentialsProvider credentialsProvider = null;
            if (token != null && !token.isBlank()) {
                credentialsProvider = new UsernamePasswordCredentialsProvider(token, "");
            }
            // 클론
            var cloneCommand = Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setDirectory(tempDir.toFile())
                    .setBranch(branch);
            if (credentialsProvider != null) {
                cloneCommand.setCredentialsProvider(credentialsProvider);
            }
            try (Git git = cloneCommand.call()) {
                return analyzeRepository(git, repositoryName, repositoryUrl, startDate, endDate);
            }
        } catch (Exception e) {
            log.error("Git 분석 실패: {} - {}", repositoryName, e.getMessage(), e);
            return GitDiffResult.builder()
                    .repositoryName(repositoryName)
                    .repositoryUrl(repositoryUrl)
                    .totalCommits(0)
                    .commits(List.of())
                    .fileChanges(List.of())
                    .error(e.getMessage())
                    .build();
        } finally {
            deleteTempDir(tempDir);
        }
    }

    private GitDiffResult analyzeRepository(Git git, String repoName, String repoUrl,
                                             LocalDate startDate, LocalDate endDate) throws Exception {
        Repository repository = git.getRepository();
        Date since = Date.from(startDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date until = Date.from(endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        // 기간 내 커밋 수집
        Iterable<RevCommit> commits = git.log()
                .setRevFilter(CommitTimeRevFilter.between(since, until))
                .call();
        List<CommitInfo> commitInfos = new ArrayList<>();
        // 전체 파일 변경 중복 제거를 위한 Set
        Set<String> fileChangeKeys = new LinkedHashSet<>();
        List<FileChange> allFileChanges = new ArrayList<>();
        for (RevCommit commit : commits) {
            // 각 커밋의 diff 추출 (diff 내용 포함)
            List<DiffEntryWithPatch> diffsWithPatch = getDiffEntriesWithPatch(repository, commit);
            List<FileChange> commitFileChanges = new ArrayList<>();
            for (DiffEntryWithPatch dwp : diffsWithPatch) {
                DiffEntry diff = dwp.entry;
                String changeType = diff.getChangeType().name();
                String filePath = diff.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? diff.getOldPath() : diff.getNewPath();
                // diff 내용에서 추가/삭제 라인 수와 서비스 단위 변경 요약 추출
                int[] lineCounts = countAddedDeletedLines(dwp.patch);
                String changeSummary = extractChangeSummary(filePath, changeType, dwp.patch);
                FileChange.FileChangeBuilder builder = FileChange.builder()
                        .changeType(changeType)
                        .filePath(filePath)
                        .addedLines(lineCounts[0])
                        .deletedLines(lineCounts[1])
                        .changeSummary(changeSummary);
                if (diff.getChangeType() == DiffEntry.ChangeType.RENAME) {
                    builder.oldPath(diff.getOldPath());
                }
                FileChange fileChange = builder.build();
                commitFileChanges.add(fileChange);
                // 전체 목록에는 중복 제거하여 추가
                String key = changeType + ":" + filePath;
                if (fileChangeKeys.add(key)) {
                    allFileChanges.add(fileChange);
                }
            }
            commitInfos.add(CommitInfo.builder()
                    .commitHash(commit.getName())
                    .authorName(commit.getAuthorIdent().getName())
                    .message(commit.getShortMessage())
                    .dateTime(commit.getAuthorIdent().getWhen().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDateTime().format(DT_FORMAT))
                    .fileChanges(commitFileChanges)
                    .build());
        }
        return GitDiffResult.builder()
                .repositoryName(repoName)
                .repositoryUrl(repoUrl)
                .totalCommits(commitInfos.size())
                .commits(commitInfos)
                .fileChanges(allFileChanges)
                .build();
    }

    /**
     * DiffEntry와 해당 patch 텍스트를 함께 담는 내부 클래스
     */
    private record DiffEntryWithPatch(DiffEntry entry, String patch) {}

    /**
     * 커밋의 diff entry 목록과 각 entry의 patch 텍스트를 추출합니다.
     */
    private List<DiffEntryWithPatch> getDiffEntriesWithPatch(Repository repository, RevCommit commit) throws IOException {
        try (ObjectReader reader = repository.newObjectReader()) {
            CanonicalTreeParser oldTree;
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            newTree.reset(reader, commit.getTree());
            if (commit.getParentCount() == 0) {
                oldTree = null;
            } else {
                oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, commit.getParent(0).getTree());
            }
            // scan으로 diff entry 목록 획득
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DiffFormatter df = new DiffFormatter(baos)) {
                df.setRepository(repository);
                df.setDetectRenames(true);
                List<DiffEntry> entries = (oldTree == null)
                        ? df.scan(new EmptyTreeIterator(), newTree)
                        : df.scan(oldTree, newTree);
                List<DiffEntryWithPatch> result = new ArrayList<>();
                for (DiffEntry entry : entries) {
                    baos.reset();
                    try {
                        df.format(entry);
                        df.flush();
                        String patch = baos.toString("UTF-8");
                        result.add(new DiffEntryWithPatch(entry, patch));
                    } catch (Exception e) {
                        // 바이너리 파일 등 포맷 불가한 경우
                        result.add(new DiffEntryWithPatch(entry, ""));
                    }
                }
                return result;
            }
        }
    }

    /**
     * diff patch에서 추가/삭제된 라인 수를 집계합니다.
     *
     * @return [추가 라인 수, 삭제 라인 수]
     */
    private int[] countAddedDeletedLines(String patch) {
        if (patch == null || patch.isEmpty()) return new int[]{0, 0};
        int added = 0, deleted = 0;
        for (String line : patch.split("\n")) {
            if (line.startsWith("+++") || line.startsWith("---")) continue;
            if (line.startsWith("+")) added++;
            else if (line.startsWith("-")) deleted++;
        }
        return new int[]{added, deleted};
    }

    // --- 서비스 단위 변경 요약 추출 ---

    private static final Pattern CLASS_DECL = Pattern.compile("^[+].*(?:public|protected|private)?\\s*(?:abstract\\s+)?(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern FIELD_DECL = Pattern.compile("^[+]\\s+(?:private|protected|public)\\s+[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*[;=]");
    private static final Pattern METHOD_DECL = Pattern.compile("^[+]\\s+(?:public|protected|private)?\\s*(?:static\\s+)?[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\(");
    private static final Pattern ANNOTATION_DECL = Pattern.compile("^[+]\\s*@(\\w+)");
    private static final Pattern REMOVED_CLASS_DECL = Pattern.compile("^[-].*(?:public|protected|private)?\\s*(?:abstract\\s+)?(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern REMOVED_METHOD_DECL = Pattern.compile("^[-]\\s+(?:public|protected|private)?\\s*(?:static\\s+)?[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\(");

    /**
     * 파일의 diff patch를 분석하여 서비스/클래스 단위 변경 요약을 생성합니다.
     *
     * <p>Java 파일: 클래스/인터페이스 선언, 필드, 메서드, 어노테이션 변경 감지
     * SQL 파일: CREATE/ALTER/DROP 문 감지
     * 설정 파일: 주요 설정 항목 변경 감지</p>
     */
    private String extractChangeSummary(String filePath, String changeType, String patch) {
        if (patch == null || patch.isEmpty()) {
            return changeType + " 파일";
        }
        String lowerPath = filePath.toLowerCase();
        if (lowerPath.endsWith(".java")) {
            return extractJavaChangeSummary(changeType, patch);
        } else if (lowerPath.endsWith(".sql")) {
            return extractSqlChangeSummary(patch);
        } else if (lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml") || lowerPath.endsWith(".properties")) {
            return extractConfigChangeSummary(patch);
        } else if (lowerPath.endsWith(".xml") && lowerPath.contains("pom")) {
            return extractPomChangeSummary(patch);
        } else if (lowerPath.endsWith(".gradle") || lowerPath.endsWith(".gradle.kts")) {
            return extractGradleChangeSummary(patch);
        }
        int[] counts = countAddedDeletedLines(patch);
        return changeType + " 파일 (+" + counts[0] + "/-" + counts[1] + " lines)";
    }

    /**
     * Java 파일의 diff에서 서비스 단위 변경 요약을 추출합니다.
     *
     * <p>새 클래스/인터페이스 선언, 추가된 필드, 추가/제거된 메서드,
     * 추가된 어노테이션을 감지하여 요약 문자열을 반환합니다.</p>
     */
    private String extractJavaChangeSummary(String changeType, String patch) {
        Set<String> newClasses = new LinkedHashSet<>();
        Set<String> removedClasses = new LinkedHashSet<>();
        Set<String> newFields = new LinkedHashSet<>();
        Set<String> newMethods = new LinkedHashSet<>();
        Set<String> removedMethods = new LinkedHashSet<>();
        Set<String> newAnnotations = new LinkedHashSet<>();
        for (String line : patch.split("\n")) {
            Matcher m;
            m = CLASS_DECL.matcher(line);
            if (m.find()) { newClasses.add(m.group(1)); continue; }
            m = REMOVED_CLASS_DECL.matcher(line);
            if (m.find()) { removedClasses.add(m.group(1)); continue; }
            m = METHOD_DECL.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                // 제어문/키워드 제외
                if (!Set.of("if", "for", "while", "switch", "return", "throw", "catch", "try").contains(name)) {
                    newMethods.add(name);
                }
                continue;
            }
            m = REMOVED_METHOD_DECL.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                if (!Set.of("if", "for", "while", "switch", "return", "throw", "catch", "try").contains(name)) {
                    removedMethods.add(name);
                }
                continue;
            }
            m = FIELD_DECL.matcher(line);
            if (m.find()) { newFields.add(m.group(1)); continue; }
            m = ANNOTATION_DECL.matcher(line);
            if (m.find()) {
                String ann = m.group(1);
                // 일반 코드 어노테이션만 (Override 제외)
                if (!ann.equals("Override") && !ann.equals("SuppressWarnings")) {
                    newAnnotations.add("@" + ann);
                }
            }
        }
        List<String> parts = new ArrayList<>();
        if (!newClasses.isEmpty()) parts.add("새 클래스: " + String.join(", ", newClasses));
        if (!removedClasses.isEmpty()) parts.add("삭제 클래스: " + String.join(", ", removedClasses));
        if (!newFields.isEmpty()) parts.add("추가 필드: " + String.join(", ", limitSet(newFields, 5)));
        if (!newMethods.isEmpty()) parts.add("추가 메서드: " + String.join(", ", limitSet(newMethods, 5)));
        if (!removedMethods.isEmpty()) parts.add("삭제 메서드: " + String.join(", ", limitSet(removedMethods, 5)));
        if (!newAnnotations.isEmpty()) parts.add("추가 어노테이션: " + String.join(", ", limitSet(newAnnotations, 5)));
        if (parts.isEmpty()) {
            int[] counts = countAddedDeletedLines(patch);
            return changeType + " (+" + counts[0] + "/-" + counts[1] + " lines)";
        }
        return String.join("; ", parts);
    }

    /**
     * SQL 파일의 diff에서 DDL 변경 요약을 추출합니다.
     */
    private String extractSqlChangeSummary(String patch) {
        Set<String> operations = new LinkedHashSet<>();
        Pattern sqlPattern = Pattern.compile("^[+]\\s*(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE)\\s+(TABLE|INDEX|VIEW|COLUMN)?\\s*(\\w+)?", Pattern.CASE_INSENSITIVE);
        for (String line : patch.split("\n")) {
            Matcher m = sqlPattern.matcher(line);
            if (m.find()) {
                String op = m.group(1).toUpperCase();
                String target = m.group(2) != null ? m.group(2).toUpperCase() : "";
                String name = m.group(3) != null ? m.group(3) : "";
                operations.add(op + " " + target + " " + name);
            }
        }
        if (operations.isEmpty()) {
            int[] counts = countAddedDeletedLines(patch);
            return "SQL 변경 (+" + counts[0] + "/-" + counts[1] + " lines)";
        }
        return "SQL: " + String.join("; ", operations);
    }

    /**
     * YAML/Properties 설정 파일의 diff에서 변경된 설정 항목을 추출합니다.
     */
    private String extractConfigChangeSummary(String patch) {
        Set<String> addedKeys = new LinkedHashSet<>();
        Set<String> removedKeys = new LinkedHashSet<>();
        for (String line : patch.split("\n")) {
            if (line.startsWith("+++") || line.startsWith("---")) continue;
            if (line.startsWith("+") && line.contains(":")) {
                String key = line.substring(1).split(":")[0].trim();
                if (!key.isEmpty() && !key.startsWith("#")) addedKeys.add(key);
            } else if (line.startsWith("-") && line.contains(":")) {
                String key = line.substring(1).split(":")[0].trim();
                if (!key.isEmpty() && !key.startsWith("#")) removedKeys.add(key);
            }
        }
        List<String> parts = new ArrayList<>();
        if (!addedKeys.isEmpty()) parts.add("추가 설정: " + String.join(", ", limitSet(addedKeys, 5)));
        if (!removedKeys.isEmpty()) parts.add("제거 설정: " + String.join(", ", limitSet(removedKeys, 5)));
        if (parts.isEmpty()) {
            int[] counts = countAddedDeletedLines(patch);
            return "설정 변경 (+" + counts[0] + "/-" + counts[1] + " lines)";
        }
        return String.join("; ", parts);
    }

    /**
     * pom.xml의 diff에서 의존성 변경을 추출합니다.
     */
    private String extractPomChangeSummary(String patch) {
        Set<String> addedDeps = new LinkedHashSet<>();
        Pattern depPattern = Pattern.compile("<artifactId>(.*?)</artifactId>");
        boolean inAddedBlock = false;
        for (String line : patch.split("\n")) {
            if (line.startsWith("+")) {
                inAddedBlock = true;
                Matcher m = depPattern.matcher(line);
                if (m.find()) addedDeps.add(m.group(1));
            } else {
                inAddedBlock = false;
            }
        }
        if (addedDeps.isEmpty()) {
            int[] counts = countAddedDeletedLines(patch);
            return "pom.xml 변경 (+" + counts[0] + "/-" + counts[1] + " lines)";
        }
        return "의존성 변경: " + String.join(", ", limitSet(addedDeps, 5));
    }

    /**
     * Gradle 빌드 파일의 diff에서 의존성 변경을 추출합니다.
     */
    private String extractGradleChangeSummary(String patch) {
        Set<String> changes = new LinkedHashSet<>();
        Pattern depPattern = Pattern.compile("^[+]\\s*(?:implementation|api|compileOnly|runtimeOnly|testImplementation)\\s*[('\"]([^'\"]+)['\")]");
        for (String line : patch.split("\n")) {
            Matcher m = depPattern.matcher(line);
            if (m.find()) {
                String dep = m.group(1);
                // 짧게 표시: group:artifact만
                String[] parts = dep.split(":");
                if (parts.length >= 2) {
                    changes.add(parts[1]);
                } else {
                    changes.add(dep);
                }
            }
        }
        if (changes.isEmpty()) {
            int[] counts = countAddedDeletedLines(patch);
            return "빌드 설정 변경 (+" + counts[0] + "/-" + counts[1] + " lines)";
        }
        return "의존성 추가: " + String.join(", ", limitSet(changes, 5));
    }

    /**
     * Set에서 최대 limit개의 항목만 반환합니다.
     */
    private Set<String> limitSet(Set<String> set, int limit) {
        if (set.size() <= limit) return set;
        Set<String> limited = new LinkedHashSet<>();
        int count = 0;
        for (String s : set) {
            limited.add(s);
            if (++count >= limit) break;
        }
        limited.add("외 " + (set.size() - limit) + "개");
        return limited;
    }

    private void deleteTempDir(Path dir) {
        if (dir == null) return;
        try {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
            }
        } catch (IOException ignored) {}
    }
}
