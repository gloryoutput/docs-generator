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
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
        // 중복 제거를 위한 Set
        Set<String> fileChangeKeys = new LinkedHashSet<>();
        List<FileChange> allFileChanges = new ArrayList<>();
        for (RevCommit commit : commits) {
            commitInfos.add(CommitInfo.builder()
                    .commitHash(commit.getName())
                    .authorName(commit.getAuthorIdent().getName())
                    .message(commit.getShortMessage())
                    .dateTime(commit.getAuthorIdent().getWhen().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDateTime().format(DT_FORMAT))
                    .build());
            // 각 커밋의 diff 추출
            List<DiffEntry> diffs = getDiffEntries(repository, commit);
            for (DiffEntry diff : diffs) {
                String changeType = diff.getChangeType().name();
                String filePath = diff.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? diff.getOldPath() : diff.getNewPath();
                String key = changeType + ":" + filePath;
                if (fileChangeKeys.add(key)) {
                    FileChange.FileChangeBuilder builder = FileChange.builder()
                            .changeType(changeType)
                            .filePath(filePath);
                    if (diff.getChangeType() == DiffEntry.ChangeType.RENAME) {
                        builder.oldPath(diff.getOldPath());
                    }
                    allFileChanges.add(builder.build());
                }
            }
        }
        return GitDiffResult.builder()
                .repositoryName(repoName)
                .repositoryUrl(repoUrl)
                .totalCommits(commitInfos.size())
                .commits(commitInfos)
                .fileChanges(allFileChanges)
                .build();
    }

    private List<DiffEntry> getDiffEntries(Repository repository, RevCommit commit) throws IOException {
        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
             ObjectReader reader = repository.newObjectReader()) {
            df.setRepository(repository);
            df.setDetectRenames(true);
            if (commit.getParentCount() == 0) {
                // 최초 커밋: 빈 트리와 비교
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, commit.getTree());
                return df.scan(new EmptyTreeIterator(), newTree);
            }
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            oldTree.reset(reader, commit.getParent(0).getTree());
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            newTree.reset(reader, commit.getTree());
            return df.scan(oldTree, newTree);
        }
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
