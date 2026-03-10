package io.github.gloryoutput.docsgenerator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerResult;
import io.github.gloryoutput.docsgenerator.analyzer.database.DbSchemaResult;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult;
import io.github.gloryoutput.docsgenerator.domain.evidence.Evidence;
import io.github.gloryoutput.docsgenerator.domain.evidence.EvidenceRepository;
import io.github.gloryoutput.docsgenerator.dto.response.EvidenceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Evidence 저장 및 조회 서비스
 *
 * <p>각 분석기(Git, DB Schema, API)의 결과를 Evidence 엔티티로 변환하여 저장하고,
 * 분석 요청 ID 기반으로 조회하는 기능을 제공합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EvidenceService {
    private final EvidenceRepository evidenceRepository;

    /**
     * 모든 분석기 결과를 Evidence 엔티티로 변환하여 저장합니다.
     *
     * @param idAnalysisRequest 분석 요청 ID
     * @param idProject 프로젝트 ID
     * @param gitResults Git 분석 결과 목록
     * @param schemaResults DB 스키마 분석 결과 목록
     * @param apiResult API 분석 결과
     */
    @Transactional
    public void saveEvidencesFromAnalysis(UUID idAnalysisRequest, UUID idProject,
                                          List<GitDiffResult> gitResults,
                                          List<DbSchemaResult> schemaResults,
                                          ApiAnalyzerResult apiResult) {
        ObjectMapper objectMapper = new ObjectMapper();
        List<Evidence> evidences = new ArrayList<>();
        // Git FileChange -> Evidence
        for (GitDiffResult gitResult : gitResults) {
            if (gitResult.getError() != null || gitResult.getFileChanges() == null) continue;
            for (GitDiffResult.FileChange fileChange : gitResult.getFileChanges()) {
                String metadataJson = serializeMetadata(objectMapper, buildGitMetadata(gitResult, fileChange));
                Evidence evidence = Evidence.builder()
                        .idAnalysisRequest(idAnalysisRequest)
                        .idProject(idProject)
                        .sourceType("GIT")
                        .sourceName(gitResult.getRepositoryName())
                        .targetPath(fileChange.getFilePath())
                        .changeType(fileChange.getChangeType())
                        .metadataJson(metadataJson)
                        .build();
                evidences.add(evidence);
            }
        }
        // DbSchemaResult.SchemaChange -> Evidence
        for (DbSchemaResult schemaResult : schemaResults) {
            if (schemaResult.getError() != null || schemaResult.getChanges() == null) continue;
            for (DbSchemaResult.SchemaChange schemaChange : schemaResult.getChanges()) {
                String targetPath = buildSchemaTargetPath(schemaChange);
                String metadataJson = serializeMetadata(objectMapper, buildSchemaMetadata(schemaChange));
                Evidence evidence = Evidence.builder()
                        .idAnalysisRequest(idAnalysisRequest)
                        .idProject(idProject)
                        .sourceType("DB_SCHEMA")
                        .sourceName(schemaResult.getDatabaseName())
                        .targetPath(targetPath)
                        .changeType(schemaChange.getChangeType())
                        .metadataJson(metadataJson)
                        .build();
                evidences.add(evidence);
            }
        }
        // ApiAnalyzerResult.EndpointChange -> Evidence
        if (apiResult != null && apiResult.getChanges() != null) {
            for (ApiAnalyzerResult.EndpointChange endpointChange : apiResult.getChanges()) {
                String metadataJson = serializeMetadata(objectMapper, buildApiMetadata(endpointChange));
                Evidence evidence = Evidence.builder()
                        .idAnalysisRequest(idAnalysisRequest)
                        .idProject(idProject)
                        .sourceType("API_ENDPOINT")
                        .sourceName(endpointChange.getHandlerClass())
                        .targetPath(endpointChange.getHttpMethod() + " " + endpointChange.getPath())
                        .changeType(endpointChange.getChangeType())
                        .metadataJson(metadataJson)
                        .build();
                evidences.add(evidence);
            }
        }
        if (!evidences.isEmpty()) {
            evidenceRepository.saveAll(evidences);
            log.info("Evidence {} 건 저장 완료 (analysisRequest={})", evidences.size(), idAnalysisRequest);
        }
    }

    /**
     * 분석 요청 ID로 Evidence 목록을 조회합니다.
     *
     * @param idAnalysisRequest 분석 요청 ID (문자열)
     * @return EvidenceResponse 목록
     */
    @Transactional(readOnly = true)
    public List<EvidenceResponse> getEvidencesByAnalysisRequest(String idAnalysisRequest) {
        UUID uuid = UUID.fromString(idAnalysisRequest);
        return evidenceRepository.findByIdAnalysisRequestAndIsDeletedFalse(uuid)
                .stream()
                .map(EvidenceResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Git FileChange의 메타데이터를 빌드합니다.
     */
    private Map<String, Object> buildGitMetadata(GitDiffResult gitResult, GitDiffResult.FileChange fileChange) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("repositoryUrl", gitResult.getRepositoryUrl());
        metadata.put("totalCommits", gitResult.getTotalCommits());
        if (fileChange.getOldPath() != null) {
            metadata.put("oldPath", fileChange.getOldPath());
        }
        return metadata;
    }

    /**
     * DB 스키마 변경의 targetPath를 빌드합니다.
     *
     * <p>컬럼명이 있으면 "tableName.columnName", 없으면 "tableName"을 반환합니다.</p>
     */
    private String buildSchemaTargetPath(DbSchemaResult.SchemaChange schemaChange) {
        if (schemaChange.getColumnName() != null && !schemaChange.getColumnName().isEmpty()) {
            return schemaChange.getTableName() + "." + schemaChange.getColumnName();
        }
        return schemaChange.getTableName();
    }

    /**
     * DB 스키마 변경의 메타데이터를 빌드합니다.
     */
    private Map<String, Object> buildSchemaMetadata(DbSchemaResult.SchemaChange schemaChange) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (schemaChange.getOldDataType() != null) {
            metadata.put("oldDataType", schemaChange.getOldDataType());
        }
        if (schemaChange.getNewDataType() != null) {
            metadata.put("newDataType", schemaChange.getNewDataType());
        }
        if (schemaChange.getIndexName() != null) {
            metadata.put("indexName", schemaChange.getIndexName());
        }
        if (schemaChange.getIndexColumns() != null) {
            metadata.put("indexColumns", schemaChange.getIndexColumns());
        }
        return metadata;
    }

    /**
     * API endpoint 변경의 메타데이터를 빌드합니다.
     */
    private Map<String, Object> buildApiMetadata(ApiAnalyzerResult.EndpointChange endpointChange) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("httpMethod", endpointChange.getHttpMethod());
        metadata.put("path", endpointChange.getPath());
        metadata.put("handlerClass", endpointChange.getHandlerClass());
        metadata.put("handlerMethod", endpointChange.getHandlerMethod());
        return metadata;
    }

    /**
     * 메타데이터 맵을 JSON 문자열로 직렬화합니다.
     */
    private String serializeMetadata(ObjectMapper objectMapper, Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("메타데이터 JSON 직렬화 실패: {}", e.getMessage());
            return "{}";
        }
    }
}
