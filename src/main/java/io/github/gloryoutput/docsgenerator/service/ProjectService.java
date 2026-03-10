package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.domain.project.Project;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectCode;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectCodeRepository;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectRepository;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMap;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMapRepository;
import io.github.gloryoutput.docsgenerator.dto.request.ProjectCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.request.RepositoryMapCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.ProjectResponse;
import io.github.gloryoutput.docsgenerator.dto.response.RepositoryMapResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * 프로젝트 및 레포지토리 매핑 관련 비즈니스 로직을 처리하는 서비스
 *
 * <p>프로젝트 생성, 레포지토리 등록/조회 기능을 담당합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectRepository projectRepository;
    private final ProjectCodeRepository projectCodeRepository;
    private final ProjectRepositoryMapRepository repositoryMapRepository;

    /**
     * 프로젝트를 생성합니다.
     *
     * <p>프로젝트 코드가 없으면 project_code 테이블에 먼저 등록한 후,
     * 해당 코드를 참조하는 프로젝트를 생성합니다.</p>
     *
     * @param request 프로젝트 생성 요청 DTO
     * @return 생성된 프로젝트 정보
     */
    @Transactional
    public ProjectResponse createProject(ProjectCreateRequest request) {
        if (projectCodeRepository.findByProjectCode(request.getProjectCode()).isPresent()) {
            throw new IllegalArgumentException("이미 존재하는 프로젝트 코드입니다: " + request.getProjectCode());
        }
        ProjectCode projectCode = projectCodeRepository.save(
                ProjectCode.builder()
                        .projectCode(request.getProjectCode())
                        .build());
        Project project = Project.builder()
                .idProjectCode(projectCode.getIdProjectCode())
                .projectName(request.getProjectName())
                .build();
        return ProjectResponse.from(projectRepository.save(project), projectCode);
    }

    /**
     * 전체 프로젝트 목록을 조회합니다.
     *
     * @return 프로젝트 목록
     */
    public List<ProjectResponse> getProjects() {
        return projectRepository.findAll().stream()
                .filter(p -> !p.getIsDeleted())
                .map(p -> {
                    ProjectCode code = projectCodeRepository.findById(p.getIdProjectCode())
                            .orElseThrow(() -> new IllegalStateException("프로젝트 코드를 찾을 수 없습니다: " + p.getIdProjectCode()));
                    return ProjectResponse.from(p, code);
                })
                .toList();
    }

    /**
     * 프로젝트에 레포지토리를 등록합니다 (1개 또는 여러 개).
     *
     * @param idProject 프로젝트 ID
     * @param requests 레포지토리 매핑 생성 요청 목록
     * @return 등록된 레포지토리 매핑 정보 목록
     */
    @Transactional
    public List<RepositoryMapResponse> addRepositories(String idProject, List<RepositoryMapCreateRequest> requests) {
        UUID projectId = UUID.fromString(idProject);
        projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + idProject));
        List<ProjectRepositoryMap> maps = requests.stream()
                .map(request -> ProjectRepositoryMap.builder()
                        .idProject(projectId)
                        .repositoryName(request.getRepositoryName())
                        .repositoryUrl(request.getRepositoryUrl())
                        .targetBranch(request.getTargetBranch())
                        .priorityOrder(request.getPriorityOrder())
                        .build())
                .toList();
        return repositoryMapRepository.saveAll(maps).stream()
                .map(RepositoryMapResponse::from)
                .toList();
    }

    /**
     * 프로젝트에 등록된 레포지토리 목록을 조회합니다.
     *
     * @param idProject 프로젝트 ID
     * @return 레포지토리 매핑 목록
     */
    /**
     * 레포지토리 정보를 수정합니다.
     *
     * @param idProjectRepositoryMap 레포지토리 매핑 ID
     * @param request 수정 요청 DTO
     * @return 수정된 레포지토리 정보
     */
    @Transactional
    public RepositoryMapResponse updateRepository(String idProjectRepositoryMap, RepositoryMapCreateRequest request) {
        ProjectRepositoryMap map = repositoryMapRepository.findById(UUID.fromString(idProjectRepositoryMap))
                .orElseThrow(() -> new IllegalArgumentException("레포지토리를 찾을 수 없습니다: " + idProjectRepositoryMap));
        map.update(request.getRepositoryName(), request.getRepositoryUrl(),
                request.getTargetBranch(), request.getPriorityOrder());
        return RepositoryMapResponse.from(repositoryMapRepository.save(map));
    }

    public List<RepositoryMapResponse> getRepositories(String idProject) {
        return repositoryMapRepository.findByIdProjectAndIsDeletedFalse(UUID.fromString(idProject)).stream()
                .map(RepositoryMapResponse::from)
                .toList();
    }
}
