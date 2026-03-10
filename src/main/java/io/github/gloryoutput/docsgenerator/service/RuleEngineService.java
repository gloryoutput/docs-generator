package io.github.gloryoutput.docsgenerator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gloryoutput.docsgenerator.domain.ruleset.RuleSet;
import io.github.gloryoutput.docsgenerator.domain.ruleset.RuleSetRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 규칙 엔진 서비스
 *
 * <p>DB에서 활성 규칙을 로드하고, 분석 컨텍스트의 조건과 매칭하여
 * 일치하는 규칙 목록을 반환합니다. 매칭된 규칙의 템플릿을 기반으로
 * 변경 이벤트 템플릿을 생성할 수 있습니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RuleEngineService {
    private final RuleSetRepository ruleSetRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 분석 컨텍스트에 대한 분석 상태 정보를 담는 클래스
     *
     * <p>소스 유형, 레이어, 변경 유형 등 현재 분석 상태를 보관하며,
     * 규칙 매칭 시 조건 비교의 기준이 됩니다.</p>
     */
    @Getter
    @Builder
    public static class AnalysisContext {
        private final Set<String> presentSourceTypes;
        private final Set<String> presentLayers;
        private final Set<String> presentChangeTypes;
        private final boolean hasApiChange;
        private final boolean hasSchemaChange;
        private final boolean hasCodeChange;
        private final boolean hasQueryPattern;
        private final String primaryKeyword;
    }

    /**
     * 규칙 매칭 결과를 담는 클래스
     *
     * <p>매칭된 규칙과 템플릿으로부터 resolve된 카테고리, 제목, 설명 등을 포함합니다.</p>
     */
    @Getter
    @Builder
    public static class MatchedRule {
        private final RuleSet rule;
        private final String resolvedCategory;
        private final String resolvedTitle;
        private final String resolvedDescription;
        private final String severity;
        private final Double confidenceScore;
    }

    /**
     * 변경 이벤트 템플릿 클래스
     *
     * <p>매칭된 규칙의 templateText를 파싱하여 키워드가 치환된 최종 결과를 담습니다.</p>
     */
    @Getter
    @Builder
    public static class ChangeEventTemplate {
        private final String category;
        private final String title;
        private final String description;
        private final String severity;
        private final Double confidenceScore;
    }

    /**
     * 분석 컨텍스트에 매칭되는 모든 활성 규칙을 조회합니다.
     *
     * <p>DB에서 우선순위 내림차순으로 활성 규칙을 로드한 뒤,
     * 각 규칙의 conditionJson을 분석 컨텍스트와 비교하여 매칭 여부를 판단합니다.
     * 매칭된 규칙은 templateText로부터 카테고리, 제목, 설명 등을 resolve하여 반환합니다.</p>
     *
     * @param context 현재 분석 상태 정보
     * @return 매칭된 규칙 목록 (우선순위 내림차순)
     */
    public List<MatchedRule> matchRules(AnalysisContext context) {
        List<RuleSet> activeRules = ruleSetRepository.findByIsDeletedFalseAndActiveTrueOrderByPriorityDesc();
        log.info("활성 규칙 {}건 로드 완료, 매칭 시작", activeRules.size());
        List<MatchedRule> matchedRules = new ArrayList<>();
        for (RuleSet rule : activeRules) {
            if (matchCondition(rule, context)) {
                MatchedRule matched = buildMatchedRule(rule, context.getPrimaryKeyword());
                if (matched != null) {
                    matchedRules.add(matched);
                }
            }
        }
        log.info("규칙 매칭 완료: {}건 중 {}건 매칭", activeRules.size(), matchedRules.size());
        return matchedRules;
    }

    /**
     * 단일 규칙의 conditionJson을 분석 컨텍스트와 비교하여 매칭 여부를 판단합니다.
     *
     * <p>conditionJson의 모든 조건 필드가 AND 로직으로 평가됩니다.
     * 조건 필드가 존재하지 않거나 null이면 해당 조건은 무시됩니다.</p>
     *
     * @param rule 매칭 대상 규칙
     * @param context 현재 분석 상태 정보
     * @return 모든 조건이 만족하면 true
     */
    public boolean matchCondition(RuleSet rule, AnalysisContext context) {
        try {
            JsonNode condition = objectMapper.readTree(rule.getConditionJson());
            // requiredSources 검사: 필요한 소스 유형이 모두 존재해야 함
            if (!checkRequiredSet(condition, "requiredSources", context.getPresentSourceTypes())) {
                return false;
            }
            // requiredLayers 검사: 필요한 레이어가 모두 존재해야 함
            if (!checkRequiredSet(condition, "requiredLayers", context.getPresentLayers())) {
                return false;
            }
            // requiredChangeTypes 검사: 필요한 변경 유형이 모두 존재해야 함
            if (!checkRequiredSet(condition, "requiredChangeTypes", context.getPresentChangeTypes())) {
                return false;
            }
            // excludedSources 검사: 제외 소스가 하나라도 존재하면 매칭 실패
            if (condition.has("excludedSources") && condition.get("excludedSources").isArray()) {
                for (JsonNode excluded : condition.get("excludedSources")) {
                    String excludedSource = excluded.asText();
                    if (context.getPresentSourceTypes() != null
                            && context.getPresentSourceTypes().contains(excludedSource)) {
                        return false;
                    }
                }
            }
            // boolean 조건 검사
            if (!checkBooleanCondition(condition, "hasApiChange", context.isHasApiChange())) {
                return false;
            }
            if (!checkBooleanCondition(condition, "hasSchemaChange", context.isHasSchemaChange())) {
                return false;
            }
            if (!checkBooleanCondition(condition, "hasCodeChange", context.isHasCodeChange())) {
                return false;
            }
            if (!checkBooleanCondition(condition, "queryPatternRequired", context.isHasQueryPattern())) {
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("규칙 '{}' conditionJson 파싱 실패: {}", rule.getRuleCode(), e.getMessage());
            return false;
        }
    }

    /**
     * 매칭된 규칙으로부터 키워드를 치환한 ChangeEventTemplate을 생성합니다.
     *
     * @param matchedRule 매칭된 규칙 정보
     * @param keyword 치환할 키워드
     * @return 키워드가 반영된 변경 이벤트 템플릿
     */
    public ChangeEventTemplate buildEventFromRule(MatchedRule matchedRule, String keyword) {
        String safeKeyword = (keyword != null) ? keyword : "";
        return ChangeEventTemplate.builder()
                .category(matchedRule.getResolvedCategory())
                .title(replacePlaceholder(matchedRule.getResolvedTitle(), safeKeyword))
                .description(replacePlaceholder(matchedRule.getResolvedDescription(), safeKeyword))
                .severity(matchedRule.getSeverity())
                .confidenceScore(matchedRule.getConfidenceScore())
                .build();
    }

    /**
     * 규칙의 templateText를 파싱하여 MatchedRule을 생성합니다.
     */
    private MatchedRule buildMatchedRule(RuleSet rule, String keyword) {
        try {
            JsonNode template = objectMapper.readTree(rule.getTemplateText());
            String safeKeyword = (keyword != null) ? keyword : "";
            return MatchedRule.builder()
                    .rule(rule)
                    .resolvedCategory(getTextOrDefault(template, "category", "UNKNOWN"))
                    .resolvedTitle(replacePlaceholder(
                            getTextOrDefault(template, "titleTemplate", ""), safeKeyword))
                    .resolvedDescription(replacePlaceholder(
                            getTextOrDefault(template, "descriptionTemplate", ""), safeKeyword))
                    .severity(getTextOrDefault(template, "severity", "MEDIUM"))
                    .confidenceScore(template.has("confidenceScore")
                            ? template.get("confidenceScore").asDouble() : 0.5)
                    .build();
        } catch (Exception e) {
            log.warn("규칙 '{}' templateText 파싱 실패: {}", rule.getRuleCode(), e.getMessage());
            return null;
        }
    }

    /**
     * conditionJson 내 배열 조건을 검사합니다.
     * 조건에 명시된 모든 값이 context의 Set에 포함되어야 true를 반환합니다.
     */
    private boolean checkRequiredSet(JsonNode condition, String fieldName, Set<String> contextSet) {
        if (!condition.has(fieldName) || !condition.get(fieldName).isArray()) {
            return true;
        }
        JsonNode requiredArray = condition.get(fieldName);
        if (requiredArray.isEmpty()) {
            return true;
        }
        if (contextSet == null || contextSet.isEmpty()) {
            return false;
        }
        for (JsonNode required : requiredArray) {
            if (!contextSet.contains(required.asText())) {
                return false;
            }
        }
        return true;
    }

    /**
     * conditionJson 내 boolean 조건을 검사합니다.
     * 조건이 true를 요구하면 context 값도 true여야 매칭됩니다.
     */
    private boolean checkBooleanCondition(JsonNode condition, String fieldName, boolean contextValue) {
        if (!condition.has(fieldName) || condition.get(fieldName).isNull()) {
            return true;
        }
        boolean required = condition.get(fieldName).asBoolean();
        // 조건이 true를 요구하면 context도 true여야 함
        if (required && !contextValue) {
            return false;
        }
        return true;
    }

    /**
     * 문자열 내 {keyword} 플레이스홀더를 실제 키워드로 치환합니다.
     */
    private String replacePlaceholder(String text, String keyword) {
        if (text == null) return "";
        return text.replace("{keyword}", keyword);
    }

    /**
     * JsonNode에서 문자열 값을 추출하되, 없으면 기본값을 반환합니다.
     */
    private String getTextOrDefault(JsonNode node, String fieldName, String defaultValue) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            return node.get(fieldName).asText();
        }
        return defaultValue;
    }
}
