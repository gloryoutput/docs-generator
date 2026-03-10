package io.github.gloryoutput.docsgenerator.analyzer.git;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

/**
 * Git diff 분석 결과
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class GitDiffResult {
    private String repositoryName;
    private String repositoryUrl;
    private int totalCommits;
    private List<CommitInfo> commits;
    private List<FileChange> fileChanges;
    /** 분석 실패 시 오류 메시지 */
    private String error;

    @Getter
    @Builder
    public static class CommitInfo {
        private String commitHash;
        private String authorName;
        private String message;
        private String dateTime;
    }

    @Getter
    @Builder
    public static class FileChange {
        /** ADD, MODIFY, DELETE, RENAME, COPY */
        private String changeType;
        private String filePath;
        private String oldPath;
    }
}
