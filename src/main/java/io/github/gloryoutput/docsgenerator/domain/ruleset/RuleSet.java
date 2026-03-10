package io.github.gloryoutput.docsgenerator.domain.ruleset;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

/**
 * 규칙 세트 엔티티
 *
 * <p>변경 이벤트를 문서 항목으로 변환하기 위한 매칭 규칙과 출력 템플릿을 정의합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "rule_set")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleSet extends BaseEntity {
    @Id
    @Column(name = "id_rule_set", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idRuleSet;
    @Column(name = "rule_code", nullable = false, unique = true)
    private String ruleCode;
    @Column(name = "rule_name", nullable = false)
    private String ruleName;
    @Column(name = "rule_type", nullable = false)
    private String ruleType;
    @Column(name = "condition_json", nullable = false, columnDefinition = "TEXT")
    @Lob
    private String conditionJson;
    @Column(name = "template_text", columnDefinition = "TEXT")
    @Lob
    private String templateText;
    @Column(name = "priority", nullable = false)
    private Integer priority;
    @Column(name = "active", nullable = false)
    private Boolean active;

    @Builder
    public RuleSet(String ruleCode, String ruleName, String ruleType,
                   String conditionJson, String templateText, Integer priority, Boolean active) {
        this.idRuleSet = UUID.randomUUID();
        this.ruleCode = ruleCode;
        this.ruleName = ruleName;
        this.ruleType = ruleType;
        this.conditionJson = conditionJson;
        this.templateText = templateText;
        this.priority = (priority != null) ? priority : 0;
        this.active = (active != null) ? active : true;
    }

    /**
     * null이 아닌 필드만 선택적으로 갱신합니다.
     *
     * @param ruleName      규칙 이름
     * @param ruleType      규칙 유형
     * @param conditionJson 조건 JSON
     * @param templateText  출력 템플릿
     * @param priority      우선순위
     * @param active        활성 여부
     */
    public void update(String ruleName, String ruleType, String conditionJson,
                       String templateText, Integer priority, Boolean active) {
        if (ruleName != null) this.ruleName = ruleName;
        if (ruleType != null) this.ruleType = ruleType;
        if (conditionJson != null) this.conditionJson = conditionJson;
        if (templateText != null) this.templateText = templateText;
        if (priority != null) this.priority = priority;
        if (active != null) this.active = active;
    }
}
