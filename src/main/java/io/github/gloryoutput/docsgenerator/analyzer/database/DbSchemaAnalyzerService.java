package io.github.gloryoutput.docsgenerator.analyzer.database;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gloryoutput.docsgenerator.analyzer.database.DbSchemaResult.SchemaChange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * information_schema 기반 DB 스키마 분석 서비스
 *
 * <p>앱 DataSource를 사용하여 테이블, 컬럼, 인덱스 정보를 수집합니다.
 * 이전 스냅샷과 비교하여 추가/삭제/변경된 항목을 감지합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class DbSchemaAnalyzerService {
    private static final String SOURCE_TYPE = "DB_SCHEMA";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * DataSource를 사용하여 DB 스키마를 분석합니다.
     *
     * @param dataSource 앱 DataSource
     * @param previousSnapshotJson 이전 스냅샷 JSON (null이면 최초 분석)
     * @return 스키마 분석 결과
     */
    public DbSchemaResult analyze(DataSource dataSource, String previousSnapshotJson) {
        try (Connection conn = dataSource.getConnection()) {
            String databaseName = conn.getCatalog();
            Map<String, Object> currentSnapshot = collectSchema(conn, databaseName);
            List<SchemaChange> changes;
            if (previousSnapshotJson == null) {
                changes = buildInitialChanges(currentSnapshot);
            } else {
                Map<String, Object> previousSnapshot = objectMapper.readValue(
                        previousSnapshotJson, new TypeReference<>() {});
                changes = compareSnapshots(previousSnapshot, currentSnapshot);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tables = (Map<String, Object>) currentSnapshot.get("tables");
            return DbSchemaResult.builder()
                    .databaseName(databaseName)
                    .totalTables(tables.size())
                    .totalChanges(changes.size())
                    .changes(changes)
                    .build();
        } catch (Exception e) {
            log.error("DB 스키마 분석 실패: {}", e.getMessage(), e);
            return DbSchemaResult.builder()
                    .totalTables(0)
                    .totalChanges(0)
                    .changes(List.of())
                    .error(e.getMessage())
                    .build();
        }
    }

    /**
     * 현재 스냅샷 JSON을 생성합니다 (저장용).
     */
    public String captureSnapshotJson(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String databaseName = conn.getCatalog();
            Map<String, Object> snapshot = collectSchema(conn, databaseName);
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("스냅샷 캡처 실패: {}", e.getMessage(), e);
            throw new RuntimeException("스냅샷 캡처 실패: " + e.getMessage(), e);
        }
    }

    /**
     * information_schema에서 테이블, 컬럼, 인덱스 정보를 수집합니다.
     */
    private Map<String, Object> collectSchema(Connection conn, String databaseName) throws SQLException {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> tables = new LinkedHashMap<>();
        List<String> tableNames = collectTableNames(conn, databaseName);
        for (String tableName : tableNames) {
            Map<String, Object> tableInfo = new LinkedHashMap<>();
            tableInfo.put("columns", collectColumns(conn, databaseName, tableName));
            tableInfo.put("indexes", collectIndexes(conn, databaseName, tableName));
            tables.put(tableName, tableInfo);
        }
        snapshot.put("tables", tables);
        return snapshot;
    }

    private List<String> collectTableNames(Connection conn, String databaseName) throws SQLException {
        List<String> names = new ArrayList<>();
        String sql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        return names;
    }

    private Map<String, Map<String, String>> collectColumns(Connection conn, String databaseName,
                                                             String tableName) throws SQLException {
        Map<String, Map<String, String>> columns = new LinkedHashMap<>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY " +
                "FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> colInfo = new LinkedHashMap<>();
                    colInfo.put("dataType", rs.getString("DATA_TYPE"));
                    colInfo.put("columnType", rs.getString("COLUMN_TYPE"));
                    colInfo.put("nullable", rs.getString("IS_NULLABLE"));
                    colInfo.put("columnKey", rs.getString("COLUMN_KEY"));
                    columns.put(rs.getString("COLUMN_NAME"), colInfo);
                }
            }
        }
        return columns;
    }

    private Map<String, Map<String, String>> collectIndexes(Connection conn, String databaseName,
                                                             String tableName) throws SQLException {
        Map<String, Map<String, String>> indexes = new LinkedHashMap<>();
        String sql = "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS COLUMNS, " +
                "NON_UNIQUE FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? GROUP BY INDEX_NAME, NON_UNIQUE ORDER BY INDEX_NAME";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, databaseName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> idxInfo = new LinkedHashMap<>();
                    idxInfo.put("columns", rs.getString("COLUMNS"));
                    idxInfo.put("unique", rs.getInt("NON_UNIQUE") == 0 ? "YES" : "NO");
                    indexes.put(rs.getString("INDEX_NAME"), idxInfo);
                }
            }
        }
        return indexes;
    }

    @SuppressWarnings("unchecked")
    private List<SchemaChange> buildInitialChanges(Map<String, Object> snapshot) {
        List<SchemaChange> changes = new ArrayList<>();
        Map<String, Object> tables = (Map<String, Object>) snapshot.get("tables");
        for (Map.Entry<String, Object> tableEntry : tables.entrySet()) {
            String tableName = tableEntry.getKey();
            changes.add(SchemaChange.builder()
                    .sourceType(SOURCE_TYPE).changeType("TABLE_ADDED").tableName(tableName).build());
            Map<String, Object> tableInfo = (Map<String, Object>) tableEntry.getValue();
            Map<String, Map<String, String>> columns = (Map<String, Map<String, String>>) tableInfo.get("columns");
            for (Map.Entry<String, Map<String, String>> col : columns.entrySet()) {
                changes.add(SchemaChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("COLUMN_ADDED")
                        .tableName(tableName).columnName(col.getKey())
                        .newDataType(col.getValue().get("columnType")).build());
            }
            Map<String, Map<String, String>> indexes = (Map<String, Map<String, String>>) tableInfo.get("indexes");
            for (Map.Entry<String, Map<String, String>> idx : indexes.entrySet()) {
                changes.add(SchemaChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("INDEX_ADDED")
                        .tableName(tableName).indexName(idx.getKey())
                        .indexColumns(idx.getValue().get("columns")).build());
            }
        }
        return changes;
    }

    @SuppressWarnings("unchecked")
    private List<SchemaChange> compareSnapshots(Map<String, Object> previous, Map<String, Object> current) {
        List<SchemaChange> changes = new ArrayList<>();
        Map<String, Object> prevTables = (Map<String, Object>) previous.get("tables");
        Map<String, Object> currTables = (Map<String, Object>) current.get("tables");
        // 테이블 추가 감지
        for (String tableName : currTables.keySet()) {
            if (!prevTables.containsKey(tableName)) {
                changes.add(SchemaChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("TABLE_ADDED").tableName(tableName).build());
                Map<String, Object> tableInfo = (Map<String, Object>) currTables.get(tableName);
                addAllColumnsAndIndexes(changes, tableName, tableInfo);
            }
        }
        // 테이블 삭제 감지
        for (String tableName : prevTables.keySet()) {
            if (!currTables.containsKey(tableName)) {
                changes.add(SchemaChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("TABLE_REMOVED").tableName(tableName).build());
            }
        }
        // 기존 테이블의 컬럼/인덱스 변경 감지
        for (String tableName : currTables.keySet()) {
            if (!prevTables.containsKey(tableName)) continue;
            Map<String, Object> prevTable = (Map<String, Object>) prevTables.get(tableName);
            Map<String, Object> currTable = (Map<String, Object>) currTables.get(tableName);
            compareColumns(changes, tableName,
                    (Map<String, Map<String, String>>) prevTable.get("columns"),
                    (Map<String, Map<String, String>>) currTable.get("columns"));
            compareIndexes(changes, tableName,
                    (Map<String, Map<String, String>>) prevTable.get("indexes"),
                    (Map<String, Map<String, String>>) currTable.get("indexes"));
        }
        return changes;
    }

    @SuppressWarnings("unchecked")
    private void addAllColumnsAndIndexes(List<SchemaChange> changes, String tableName, Map<String, Object> tableInfo) {
        Map<String, Map<String, String>> columns = (Map<String, Map<String, String>>) tableInfo.get("columns");
        for (Map.Entry<String, Map<String, String>> col : columns.entrySet()) {
            changes.add(SchemaChange.builder()
                    .sourceType(SOURCE_TYPE).changeType("COLUMN_ADDED")
                    .tableName(tableName).columnName(col.getKey())
                    .newDataType(col.getValue().get("columnType")).build());
        }
        Map<String, Map<String, String>> indexes = (Map<String, Map<String, String>>) tableInfo.get("indexes");
        for (Map.Entry<String, Map<String, String>> idx : indexes.entrySet()) {
            changes.add(SchemaChange.builder()
                    .sourceType(SOURCE_TYPE).changeType("INDEX_ADDED")
                    .tableName(tableName).indexName(idx.getKey())
                    .indexColumns(idx.getValue().get("columns")).build());
        }
    }

    private void compareColumns(List<SchemaChange> changes, String tableName,
                                 Map<String, Map<String, String>> prevColumns,
                                 Map<String, Map<String, String>> currColumns) {
        for (Map.Entry<String, Map<String, String>> entry : currColumns.entrySet()) {
            if (!prevColumns.containsKey(entry.getKey())) {
                changes.add(SchemaChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("COLUMN_ADDED")
                        .tableName(tableName).columnName(entry.getKey())
                        .newDataType(entry.getValue().get("columnType")).build());
            }
        }
        for (String colName : prevColumns.keySet()) {
            if (!currColumns.containsKey(colName)) {
                changes.add(SchemaChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("COLUMN_REMOVED")
                        .tableName(tableName).columnName(colName).build());
            }
        }
        for (Map.Entry<String, Map<String, String>> entry : currColumns.entrySet()) {
            if (!prevColumns.containsKey(entry.getKey())) continue;
            String prevType = prevColumns.get(entry.getKey()).get("columnType");
            String currType = entry.getValue().get("columnType");
            if (prevType != null && !prevType.equals(currType)) {
                changes.add(SchemaChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("COLUMN_TYPE_CHANGED")
                        .tableName(tableName).columnName(entry.getKey())
                        .oldDataType(prevType).newDataType(currType).build());
            }
        }
    }

    private void compareIndexes(List<SchemaChange> changes, String tableName,
                                 Map<String, Map<String, String>> prevIndexes,
                                 Map<String, Map<String, String>> currIndexes) {
        for (Map.Entry<String, Map<String, String>> entry : currIndexes.entrySet()) {
            if (!prevIndexes.containsKey(entry.getKey())) {
                changes.add(SchemaChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("INDEX_ADDED")
                        .tableName(tableName).indexName(entry.getKey())
                        .indexColumns(entry.getValue().get("columns")).build());
            }
        }
        for (String idxName : prevIndexes.keySet()) {
            if (!currIndexes.containsKey(idxName)) {
                changes.add(SchemaChange.builder()
                        .sourceType(SOURCE_TYPE).changeType("INDEX_REMOVED")
                        .tableName(tableName).indexName(idxName).build());
            }
        }
    }
}
