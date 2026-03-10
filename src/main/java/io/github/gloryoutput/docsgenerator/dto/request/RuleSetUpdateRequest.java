package io.github.gloryoutput.docsgenerator.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 규칙셋 수정 요청 DTO
 *
 * <p>부분 수정(Partial Update)을 지원하며, null이 아닌 필드만 갱신됩니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
public class RuleSetUpdateRequest {
    private String ruleCode;
    private String ruleName;
    private String ruleType;
    private String conditionJson;
    private String templateText;
    private Integer priority;
}
