package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.domain.project.Project;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectCode;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

/**
 * 프로젝트 응답 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class ProjectResponse {
    private String idProject;
    private String idProjectCode;
    private String projectCode;
    private String projectName;
    private Boolean active;
    private LocalDateTime createdAt;

    public static ProjectResponse from(Project project, ProjectCode projectCode) {
        return ProjectResponse.builder()
                .idProject(project.getIdProject().toString())
                .idProjectCode(project.getIdProjectCode().toString())
                .projectCode(projectCode.getProjectCode())
                .projectName(project.getProjectName())
                .active(project.getActive())
                .createdAt(project.getCreatedAt())
                .build();
    }
}
