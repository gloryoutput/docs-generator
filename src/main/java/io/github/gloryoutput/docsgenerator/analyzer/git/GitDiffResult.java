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
        /** 이 커밋에서 변경된 파일 목록 */
        private List<FileChange> fileChanges;
    }

    @Getter
    @Builder
    public static class FileChange {
        /** ADD, MODIFY, DELETE, RENAME, COPY */
        private String changeType;
        private String filePath;
        private String oldPath;
        /** 추가된 라인 수 */
        private int addedLines;
        /** 삭제된 라인 수 */
        private int deletedLines;
        /** 서비스/클래스 단위 변경 요약 (새 클래스, 필드 추가, 메서드 추가 등) */
        private String changeSummary;
    }
}
