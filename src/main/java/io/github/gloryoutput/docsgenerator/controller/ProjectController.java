package io.github.gloryoutput.docsgenerator.controller;

import io.github.gloryoutput.docsgenerator.dto.request.ProjectCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.request.RepositoryMapCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.ApiResponse;
import io.github.gloryoutput.docsgenerator.dto.response.ProjectResponse;
import io.github.gloryoutput.docsgenerator.dto.response.RepositoryMapResponse;
import io.github.gloryoutput.docsgenerator.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 프로젝트 및 레포지토리 매핑 API 컨트롤러
 *
 * <p>프로젝트 생성, 레포지토리 등록/조회 기능을 제공합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "프로젝트 관리", description = "프로젝트 및 레포지토리 매핑 API")
public class ProjectController {
    private final ProjectService projectService;

    /**
     * 프로젝트를 생성합니다.
     *
     * @param request 프로젝트 생성 요청
     * @return 생성된 프로젝트 정보
     */
    @PostMapping
    @Operation(summary = "프로젝트 생성", description = "새 프로젝트를 생성합니다")
    public ApiResponse<ProjectResponse> createProject(@RequestBody ProjectCreateRequest request) {
        return ApiResponse.ok(projectService.createProject(request));
    }

    /**
     * 전체 프로젝트 목록을 조회합니다.
     *
     * @return 프로젝트 목록
     */
    @GetMapping
    @Operation(summary = "프로젝트 목록 조회", description = "전체 프로젝트 목록을 조회합니다")
    public ApiResponse<List<ProjectResponse>> getProjects() {
        return ApiResponse.ok(projectService.getProjects());
    }

    /**
     * 프로젝트에 레포지토리를 등록합니다 (1개 또는 여러 개).
     *
     * @param idProject 프로젝트 ID
     * @param requests 레포지토리 등록 요청 목록
     * @return 등록된 레포지토리 정보 목록
     */
    @PostMapping("/{idProject}/repositories")
    @Operation(summary = "레포지토리 등록", description = "프로젝트에 레포지토리를 1개 또는 여러 개 등록합니다")
    public ApiResponse<List<RepositoryMapResponse>> addRepositories(
            @PathVariable String idProject,
            @RequestBody List<RepositoryMapCreateRequest> requests) {
        return ApiResponse.ok(projectService.addRepositories(idProject, requests));
    }

    /**
     * 프로젝트에 등록된 레포지토리 목록을 조회합니다.
     *
     * @param idProject 프로젝트 ID
     * @return 레포지토리 목록
     */
    @GetMapping("/{idProject}/repositories")
    @Operation(summary = "레포지토리 목록 조회", description = "프로젝트에 등록된 레포지토리 목록을 조회합니다")
    public ApiResponse<List<RepositoryMapResponse>> getRepositories(@PathVariable String idProject) {
        return ApiResponse.ok(projectService.getRepositories(idProject));
    }
}
