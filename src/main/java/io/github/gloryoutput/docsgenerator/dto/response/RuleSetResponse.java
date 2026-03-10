package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.domain.ruleset.RuleSet;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

/**
 * 규칙셋 응답 DTO
 *
 * <p>등록된 변경 감지 규칙 정보를 반환합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class RuleSetResponse {
    private String idRuleSet;
    private String ruleCode;
    private String ruleName;
    private String ruleType;
    private String conditionJson;
    private String templateText;
    private Integer priority;
    private Boolean active;
    private LocalDateTime createdAt;

    /**
     * RuleSet 엔티티를 응답 DTO로 변환합니다.
     *
     * @param ruleSet 규칙셋 엔티티
     * @return 규칙셋 응답 DTO
     */
    public static RuleSetResponse from(RuleSet ruleSet) {
        return RuleSetResponse.builder()
                .idRuleSet(ruleSet.getIdRuleSet().toString())
                .ruleCode(ruleSet.getRuleCode())
                .ruleName(ruleSet.getRuleName())
                .ruleType(ruleSet.getRuleType())
                .conditionJson(ruleSet.getConditionJson())
                .templateText(ruleSet.getTemplateText())
                .priority(ruleSet.getPriority())
                .active(ruleSet.getActive())
                .createdAt(ruleSet.getCreatedAt())
                .build();
    }
}
