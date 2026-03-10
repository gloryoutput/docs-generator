package io.github.gloryoutput.docsgenerator.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프로젝트 생성 요청 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
public class ProjectCreateRequest {
    private String projectCode;
    private String projectName;
}
