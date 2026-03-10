package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMap;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

/**
 * 레포지토리 매핑 응답 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class RepositoryMapResponse {
    private String idProjectRepositoryMap;
    private String idProject;
    private String repositoryName;
    private String repositoryUrl;
    private String defaultBranch;
    private Integer priorityOrder;
    private Boolean active;
    private LocalDateTime createdAt;

    public static RepositoryMapResponse from(ProjectRepositoryMap map) {
        return RepositoryMapResponse.builder()
                .idProjectRepositoryMap(map.getIdProjectRepositoryMap())
                .idProject(map.getIdProject())
                .repositoryName(map.getRepositoryName())
                .repositoryUrl(map.getRepositoryUrl())
                .defaultBranch(map.getDefaultBranch())
                .priorityOrder(map.getPriorityOrder())
                .active(map.getActive())
                .createdAt(map.getCreatedAt())
                .build();
    }
}
