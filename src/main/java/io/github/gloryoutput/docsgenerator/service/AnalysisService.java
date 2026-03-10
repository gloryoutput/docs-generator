package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.analyzer.git.GitAnalyzerService;
import io.github.gloryoutput.docsgenerator.analyzer.git.GitDiffResult;
import io.github.gloryoutput.docsgenerator.domain.analysis.AnalysisRequest;
import io.github.gloryoutput.docsgenerator.domain.analysis.AnalysisRequestRepository;
import io.github.gloryoutput.docsgenerator.domain.project.Project;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectCode;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectCodeRepository;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectRepository;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMap;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMapRepository;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.RepositoryCredential;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.RepositoryCredentialRepository;
import io.github.gloryoutput.docsgenerator.dto.request.AnalysisCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.AnalysisResponse;
import io.github.gloryoutput.docsgenerator.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 분석 요청을 처리하는 서비스
 *
 * <p>project_code로 프로젝트를 찾고, 등록된 모든 레포지토리에 대해
 * 지정 기간의 Git diff를 분석합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalysisService {
    private final AnalysisRequestRepository analysisRequestRepository;
    private final ProjectCodeRepository projectCodeRepository;
    private final ProjectRepository projectRepository;
    private final ProjectRepositoryMapRepository repositoryMapRepository;
    private final RepositoryCredentialRepository credentialRepository;
    private final GitAnalyzerService gitAnalyzerService;
    @Value("${app.encryption.key:docs-generator-default-key-32ch}")
    private String encryptionKey;

    /**
     * 분석 요청을 생성하고 모든 레포지토리의 Git diff를 수집합니다.
     *
     * @param request 분석 요청 DTO
     * @return 분석 결과 응답
     */
    @Transactional
    public AnalysisResponse analyze(AnalysisCreateRequest request) {
        // project_code로 프로젝트 조회
        ProjectCode projectCode = projectCodeRepository.findByProjectCode(request.getProjectCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "프로젝트 코드를 찾을 수 없습니다: " + request.getProjectCode()));
        Project project = projectRepository.findByIdProjectCode(projectCode.getIdProjectCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "프로젝트를 찾을 수 없습니다: " + request.getProjectCode()));
        // 분석 요청 생성
        AnalysisRequest analysisRequest = AnalysisRequest.builder()
                .idProject(project.getIdProject())
                .requestedBy(request.getRequestedBy())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .build();
        analysisRequest.start();
        analysisRequestRepository.save(analysisRequest);
        // 프로젝트의 모든 레포지토리 조회
        List<ProjectRepositoryMap> repositories =
                repositoryMapRepository.findByIdProjectAndIsDeletedFalse(project.getIdProject());
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("등록된 레포지토리가 없습니다: " + request.getProjectCode());
        }
        // 각 레포지토리에 대해 Git 분석 수행
        List<GitDiffResult> results = new ArrayList<>();
        for (ProjectRepositoryMap repo : repositories) {
            String token = getDecryptedToken(repo.getIdProjectRepositoryMap());
            GitDiffResult result = gitAnalyzerService.analyze(
                    repo.getRepositoryName(),
                    repo.getRepositoryUrl(),
                    repo.getTargetBranch(),
                    request.getStartDate(),
                    request.getEndDate(),
                    token);
            results.add(result);
        }
        analysisRequest.complete();
        analysisRequestRepository.save(analysisRequest);
        return AnalysisResponse.from(analysisRequest, request.getProjectCode(), results);
    }

    /**
     * 레포지토리의 복호화된 인증 토큰을 반환합니다 (없으면 null).
     */
    private String getDecryptedToken(java.util.UUID idProjectRepositoryMap) {
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
