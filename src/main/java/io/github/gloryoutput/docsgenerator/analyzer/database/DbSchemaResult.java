package io.github.gloryoutput.docsgenerator.analyzer.database;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

/**
 * DB 스키마 분석 결과
 *
 * <p>대상 데이터베이스의 스키마를 분석하여 이전 스냅샷 대비 변경 사항을 담습니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class DbSchemaResult {
    private String databaseName;
    private int totalTables;
    private int totalChanges;
    private List<SchemaChange> changes;
    /** 분석 실패 시 오류 메시지 */
    private String error;

    /**
     * 스키마 변경 항목
     */
    @Getter
    @Builder
    public static class SchemaChange {
        /** DB_SCHEMA */
        private String sourceType;
        /** TABLE_ADDED, TABLE_REMOVED, COLUMN_ADDED, COLUMN_REMOVED, COLUMN_TYPE_CHANGED, INDEX_ADDED, INDEX_REMOVED */
        private String changeType;
        private String tableName;
        /** 컬럼명 (컬럼 변경 시) */
        private String columnName;
        /** 변경 전 타입 (타입 변경 시) */
        private String oldDataType;
        /** 변경 후 타입 (타입 변경 시) */
        private String newDataType;
        /** 인덱스명 (인덱스 변경 시) */
        private String indexName;
        /** 인덱스 컬럼 목록 (인덱스 변경 시) */
        private String indexColumns;
    }
}
