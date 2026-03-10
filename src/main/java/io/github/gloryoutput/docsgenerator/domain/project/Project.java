package io.github.gloryoutput.docsgenerator.domain.project;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

/**
 * 프로젝트 엔티티
 *
 * <p>하나의 프로젝트는 여러 repository를 가질 수 있으며,
 * 보고서 생성의 최상위 단위입니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends BaseEntity {
    @Id
    @Column(name = "id_project", nullable = false, updatable = false, length = 36)
    private String idProject;
    @Column(name = "project_code", nullable = false, unique = true)
    private String projectCode;
    @Column(name = "project_name", nullable = false)
    private String projectName;
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Builder
    public Project(String projectCode, String projectName) {
        this.idProject = UUID.randomUUID().toString();
        this.projectCode = projectCode;
        this.projectName = projectName;
        this.active = true;
    }
}
