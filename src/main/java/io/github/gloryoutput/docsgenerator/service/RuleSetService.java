package io.github.gloryoutput.docsgenerator.service;

import io.github.gloryoutput.docsgenerator.domain.ruleset.RuleSet;
import io.github.gloryoutput.docsgenerator.domain.ruleset.RuleSetRepository;
import io.github.gloryoutput.docsgenerator.dto.request.RuleSetCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.request.RuleSetUpdateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.RuleSetResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * 규칙 세트 관련 비즈니스 로직을 처리하는 서비스
 *
 * <p>규칙 세트 CRUD 및 유형별 조회 기능을 담당합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RuleSetService {
    private final RuleSetRepository ruleSetRepository;

    /**
     * 규칙 세트를 생성합니다.
     *
     * <p>규칙 코드 중복을 검증한 후 새 규칙을 등록합니다.</p>
     *
     * @param request 규칙 세트 생성 요청 DTO
     * @return 생성된 규칙 세트 정보
     */
    @Transactional
    public RuleSetResponse createRuleSet(RuleSetCreateRequest request) {
        ruleSetRepository.findByRuleCodeAndIsDeletedFalse(request.getRuleCode())
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("이미 존재하는 규칙 코드입니다: " + request.getRuleCode());
                });
        RuleSet ruleSet = RuleSet.builder()
                .ruleCode(request.getRuleCode())
                .ruleName(request.getRuleName())
                .ruleType(request.getRuleType())
                .conditionJson(request.getConditionJson())
                .templateText(request.getTemplateText())
                .priority(request.getPriority())
                .build();
        return RuleSetResponse.from(ruleSetRepository.save(ruleSet));
    }

    /**
     * 규칙 세트를 수정합니다.
     *
     * <p>null이 아닌 필드만 선택적으로 갱신합니다.</p>
     *
     * @param idRuleSet 규칙 세트 ID
     * @param request   규칙 세트 수정 요청 DTO
     * @return 수정된 규칙 세트 정보
     */
    @Transactional
    public RuleSetResponse updateRuleSet(String idRuleSet, RuleSetUpdateRequest request) {
        RuleSet ruleSet = findActiveRuleSet(idRuleSet);
        // ruleCode 변경 시 중복 검증
        if (request.getRuleCode() != null && !request.getRuleCode().equals(ruleSet.getRuleCode())) {
            ruleSetRepository.findByRuleCodeAndIsDeletedFalse(request.getRuleCode())
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("이미 존재하는 규칙 코드입니다: " + request.getRuleCode());
                    });
        }
        ruleSet.update(request.getRuleName(), request.getRuleType(), request.getConditionJson(),
                request.getTemplateText(), request.getPriority(), null);
        return RuleSetResponse.from(ruleSetRepository.save(ruleSet));
    }

    /**
     * 규칙 세트를 논리 삭제합니다.
     *
     * @param idRuleSet 규칙 세트 ID
     */
    @Transactional
    public void deleteRuleSet(String idRuleSet) {
        RuleSet ruleSet = findActiveRuleSet(idRuleSet);
        ruleSet.softDelete();
        ruleSetRepository.save(ruleSet);
    }

    /**
     * 규칙 세트를 단건 조회합니다.
     *
     * @param idRuleSet 규칙 세트 ID
     * @return 규칙 세트 정보
     */
    public RuleSetResponse getRuleSet(String idRuleSet) {
        RuleSet ruleSet = findActiveRuleSet(idRuleSet);
        return RuleSetResponse.from(ruleSet);
    }

    /**
     * 삭제되지 않은 활성 규칙 세트 전체를 조회합니다.
     *
     * @return 활성 규칙 세트 목록
     */
    public List<RuleSetResponse> getAllActiveRuleSets() {
        return ruleSetRepository.findByIsDeletedFalseAndActiveTrueOrderByPriorityDesc().stream()
                .map(RuleSetResponse::from)
                .toList();
    }

    /**
     * 규칙 유형별로 활성 규칙 세트를 조회합니다.
     *
     * @param ruleType 규칙 유형
     * @return 해당 유형의 활성 규칙 세트 목록
     */
    public List<RuleSetResponse> getRuleSetsByType(String ruleType) {
        return ruleSetRepository.findByRuleTypeAndIsDeletedFalseAndActiveTrueOrderByPriorityDesc(ruleType).stream()
                .map(RuleSetResponse::from)
                .toList();
    }

    /**
     * ID로 삭제되지 않은 규칙 세트를 조회합니다.
     *
     * @param idRuleSet 규칙 세트 ID
     * @return 규칙 세트 엔티티
     * @throws IllegalArgumentException 규칙 세트가 없거나 삭제된 경우
     */
    private RuleSet findActiveRuleSet(String idRuleSet) {
        RuleSet ruleSet = ruleSetRepository.findById(UUID.fromString(idRuleSet))
                .orElseThrow(() -> new IllegalArgumentException("규칙 세트를 찾을 수 없습니다: " + idRuleSet));
        if (ruleSet.getIsDeleted()) {
            throw new IllegalArgumentException("삭제된 규칙 세트입니다: " + idRuleSet);
        }
        return ruleSet;
    }
}
