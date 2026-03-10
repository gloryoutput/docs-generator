package io.github.gloryoutput.docsgenerator.domain.repositorymap;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

/**
 * 프로젝트-레포지토리 매핑 엔티티
 *
 * <p>하나의 프로젝트가 여러 repository를 가지는 구조를 지원합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "project_repository_map")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectRepositoryMap extends BaseEntity {
    @Id
    @Column(name = "id_project_repository_map", nullable = false, updatable = false, length = 36)
    private String idProjectRepositoryMap;
    @Column(name = "id_project", nullable = false, length = 36)
    private String idProject;
    @Column(name = "repository_name", nullable = false)
    private String repositoryName;
    @Column(name = "repository_url", nullable = false)
    private String repositoryUrl;
    @Column(name = "default_branch", nullable = false)
    private String defaultBranch = "main";
    @Column(name = "priority_order")
    private Integer priorityOrder = 0;
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Builder
    public ProjectRepositoryMap(String idProject, String repositoryName,
                                 String repositoryUrl, String defaultBranch, Integer priorityOrder) {
        this.idProjectRepositoryMap = UUID.randomUUID().toString();
        this.idProject = idProject;
        this.repositoryName = repositoryName;
        this.repositoryUrl = repositoryUrl;
        this.defaultBranch = defaultBranch != null ? defaultBranch : "main";
        this.priorityOrder = priorityOrder != null ? priorityOrder : 0;
        this.active = true;
    }
}
