package io.github.gloryoutput.docsgenerator.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 규칙셋 생성 요청 DTO
 *
 * <p>변경 감지 규칙을 새로 등록할 때 사용합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
public class RuleSetCreateRequest {
    @NotBlank(message = "규칙 코드는 필수입니다")
    private String ruleCode;
    @NotBlank(message = "규칙 이름은 필수입니다")
    private String ruleName;
    @NotBlank(message = "규칙 유형은 필수입니다")
    private String ruleType;
    @NotBlank(message = "조건 JSON은 필수입니다")
    private String conditionJson;
    private String templateText;
    private Integer priority = 0;
}
