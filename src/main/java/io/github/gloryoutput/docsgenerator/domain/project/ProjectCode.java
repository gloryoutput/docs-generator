package io.github.gloryoutput.docsgenerator.domain.project;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

/**
 * 프로젝트 코드 엔티티
 *
 * <p>프로젝트 식별용 고유 코드를 관리합니다.
 * 하나의 프로젝트 코드는 하나의 프로젝트에 매핑됩니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "project_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectCode extends BaseEntity {
    @Id
    @Column(name = "id_project_code", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idProjectCode;
    @Column(name = "project_code", nullable = false, unique = true)
    private String projectCode;

    @Builder
    public ProjectCode(String projectCode) {
        this.idProjectCode = UUID.randomUUID();
        this.projectCode = projectCode;
    }
}
