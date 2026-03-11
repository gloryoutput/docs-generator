package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerResult;
import io.github.gloryoutput.docsgenerator.analyzer.api.ApiAnalyzerService;
import io.github.gloryoutput.docsgenerator.analyzer.database.DbSchemaAnalyzerService;
import io.github.gloryoutput.docsgenerator.analyzer.database.DbSchemaResult;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitAnalyzerService;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult;
import io.github.gloryoutput.docsgenerator.correlation.CorrelatedGroup;
import io.github.gloryoutput.docsgenerator.correlation.CorrelationService;
import io.github.gloryoutput.docsgenerator.domain.analysis.AnalysisRequest;
import io.github.gloryoutput.docsgenerator.domain.analysis.AnalysisRequestRepository;
import io.github.gloryoutput.docsgenerator.domain.api.ApiSnapshot;
import io.github.gloryoutput.docsgenerator.domain.api.ApiSnapshotRepository;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import io.github.gloryoutput.docsgenerator.domain.database.SchemaSnapshot;
import io.github.gloryoutput.docsgenerator.domain.database.SchemaSnapshotRepository;
import io.github.gloryoutput.docsgenerator.domain.project.Project;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectRepository;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMap;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMapRepository;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.RepositoryCredential;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.RepositoryCredentialRepository;
import io.github.gloryoutput.docsgenerator.dto.request.AnalysisCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.AnalysisResponse;
import io.github.gloryoutput.docsgenerator.filter.NoiseFilterService;
import io.github.gloryoutput.docsgenerator.generator.DraftGeneratorService;
import io.github.gloryoutput.docsgenerator.generator.ReportGeneratorService;
import io.github.gloryoutput.docsgenerator.summarizer.LlmSummarizerService;
import io.github.gloryoutput.docsgenerator.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 분석 요청을 처리하는 서비스
 *
 * <p>id_project로 프로젝트를 찾고, 등록된 모든 레포지토리에 대해
 * 지정 기간의 Git diff를 분석하고, 앱 DataSource의 DB 스키마를 분석합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalysisService {
    private final AnalysisRequestRepository analysisRequestRepository;
    private final ProjectRepository projectRepository;
    private final ProjectRepositoryMapRepository repositoryMapRepository;
    private final RepositoryCredentialRepository credentialRepository;
    private final SchemaSnapshotRepository schemaSnapshotRepository;
    private final ApiSnapshotRepository apiSnapshotRepository;
    private final GitAnalyzerService gitAnalyzerService;
    private final DbSchemaAnalyzerService dbSchemaAnalyzerService;
    private final ApiAnalyzerService apiAnalyzerService;
    private final EvidenceService evidenceService;
    private final NoiseFilterService noiseFilterService;
    private final ChangeEventService changeEventService;
    private final CorrelationService correlationService;
    private final DraftGeneratorService draftGeneratorService;
    private final ReportGeneratorService reportGeneratorService;
    private final LlmSummarizerService llmSummarizerService;
    private final DataSource dataSource;
    @Value("${app.encryption.key:docs-generator-default-key-32ch}")
    private String encryptionKey;

    /**
     * 분석 요청을 생성하고 Git diff 수집 및 DB 스키마 분석을 수행합니다.
     *
     * @param request 분석 요청 DTO
     * @return 분석 결과 응답
     */
    @Transactional
    public AnalysisResponse analyze(AnalysisCreateRequest request) {
        UUID projectId = UUID.fromString(request.getIdProject());
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "프로젝트를 찾을 수 없습니다: " + request.getIdProject()));
        // 분석 요청 생성
        boolean mergeRepos = request.getMergeRepositories() == null || request.getMergeRepositories();
        AnalysisRequest analysisRequest = AnalysisRequest.builder()
                .idProject(project.getIdProject())
                .requestedBy(request.getRequestedBy())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .mergeRepositories(mergeRepos)
                .build();
        analysisRequest.start();
        analysisRequestRepository.save(analysisRequest);
        // 프로젝트의 모든 레포지토리 조회
        List<ProjectRepositoryMap> repositories =
                repositoryMapRepository.findByIdProjectAndIsDeletedFalse(project.getIdProject());
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("등록된 레포지토리가 없습니다: " + request.getIdProject());
        }
        // 각 레포지토리에 대해 Git 분석 수행
        List<GitDiffResult> gitResults = new ArrayList<>();
        for (ProjectRepositoryMap repo : repositories) {
            String token = getDecryptedToken(repo.getIdProjectRepositoryMap());
            GitDiffResult result = gitAnalyzerService.analyze(
                    repo.getRepositoryName(),
                    repo.getRepositoryUrl(),
                    repo.getTargetBranch(),
                    request.getStartDate(),
                    request.getEndDate(),
                    token);
            gitResults.add(result);
        }
        // 앱 DataSource로 DB 스키마 분석 수행
        DbSchemaResult schemaResult = analyzeDbSchema(project, analysisRequest);
        List<DbSchemaResult> schemaResults = schemaResult != null ? List.of(schemaResult) : List.of();
        // API endpoint 분석 수행
        ApiAnalyzerResult apiResult = analyzeApi(project, analysisRequest);
        // Noise 필터링 적용
        List<GitDiffResult> filteredGitResults = noiseFilterService.filterGitChanges(gitResults);
        List<DbSchemaResult> filteredSchemaResults = noiseFilterService.filterSchemaChanges(schemaResults);
        ApiAnalyzerResult filteredApiResult = noiseFilterService.filterApiChanges(apiResult);
        // 필터링된 결과를 Evidence로 저장
        evidenceService.saveEvidencesFromAnalysis(
                analysisRequest.getIdAnalysisRequest(),
                project.getIdProject(),
                filteredGitResults,
                filteredSchemaResults,
                filteredApiResult);
        // 변경 이벤트 생성 (Step 8)
        List<ChangeEvent> changeEvents = changeEventService.buildChangeEvents(
                analysisRequest.getIdAnalysisRequest(),
                project.getIdProject(),
                filteredGitResults,
                filteredSchemaResults,
                filteredApiResult,
                mergeRepos);
        // 상관관계 분석 (Step 9)
        List<CorrelatedGroup> groups = correlationService.correlateEvents(changeEvents);
        // 초안 생성 (Step 10)
        String draft = draftGeneratorService.generateDraft(groups);
        // LLM 요약 - 초안 문장 다듬기 (Step 10.5)
        String polishedDraft = llmSummarizerService.summarize(groups, draft, mergeRepos);
        // 최종 보고서 생성 및 저장 (Step 11)
        reportGeneratorService.generateAndSave(analysisRequest, project.getProjectName(), groups, polishedDraft);
        analysisRequest.complete();
        analysisRequestRepository.save(analysisRequest);
        return AnalysisResponse.from(analysisRequest, filteredGitResults, filteredSchemaResults, filteredApiResult);
    }

    /**
     * 앱 DataSource를 사용하여 DB 스키마를 분석합니다.
     */
    private DbSchemaResult analyzeDbSchema(Project project, AnalysisRequest analysisRequest) {
        Optional<SchemaSnapshot> previousSnapshot =
                schemaSnapshotRepository.findTopByIdProjectOrderByCapturedAtDesc(project.getIdProject());
        String previousJson = previousSnapshot.map(SchemaSnapshot::getSnapshotJson).orElse(null);
        DbSchemaResult result = dbSchemaAnalyzerService.analyze(dataSource, previousJson);
        if (result != null && result.getError() == null) {
            String currentJson = dbSchemaAnalyzerService.captureSnapshotJson(dataSource);
            SchemaSnapshot snapshot = SchemaSnapshot.builder()
                    .idProject(project.getIdProject())
                    .idAnalysisRequest(analysisRequest.getIdAnalysisRequest())
                    .snapshotJson(currentJson)
                    .build();
            schemaSnapshotRepository.save(snapshot);
        }
        return result;
    }

    /**
     * API endpoint를 분석합니다.
     */
    private ApiAnalyzerResult analyzeApi(Project project, AnalysisRequest analysisRequest) {
        Optional<ApiSnapshot> previousSnapshot =
                apiSnapshotRepository.findTopByIdProjectOrderByCapturedAtDesc(project.getIdProject());
        String previousJson = previousSnapshot.map(ApiSnapshot::getSnapshotJson).orElse(null);
        ApiAnalyzerResult result = apiAnalyzerService.analyze(previousJson);
        if (result != null) {
            String currentJson = apiAnalyzerService.captureSnapshotJson();
            ApiSnapshot snapshot = ApiSnapshot.builder()
                    .idProject(project.getIdProject())
                    .idAnalysisRequest(analysisRequest.getIdAnalysisRequest())
                    .snapshotJson(currentJson)
                    .build();
            apiSnapshotRepository.save(snapshot);
        }
        return result;
    }

    /**
     * 레포지토리의 복호화된 인증 토큰을 반환합니다 (없으면 null).
     */
    private String getDecryptedToken(UUID idProjectRepositoryMap) {
        Optional<RepositoryCredential> credential =
                credentialRepository.findByIdProjectRepositoryMapAndIsDeletedFalse(idProjectRepositoryMap);
        if (credential.isEmpty()) return null;
        RepositoryCredential cred = credential.get();
        if (cred.getEncryptedToken() != null) {
            return EncryptionUtil.decrypt(cred.getEncryptedToken(), encryptionKey);
        }
        if (cred.getEncryptedPassword() != null) {
            return EncryptionUtil.decrypt(cred.getEncryptedPassword(), encryptionKey);
        }
        return null;
    }
}
