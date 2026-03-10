package io.github.gloryoutput.docsgenerator.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 레포지토리 매핑 생성 요청 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
public class RepositoryMapCreateRequest {
    private String repositoryName;
    private String repositoryUrl;
    private String defaultBranch;
    private Integer priorityOrder;
}
