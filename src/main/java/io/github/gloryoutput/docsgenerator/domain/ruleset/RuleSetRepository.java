package io.github.gloryoutput.docsgenerator.domain.ruleset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RuleSet JPA Repository
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface RuleSetRepository extends JpaRepository<RuleSet, UUID> {
    /**
     * 삭제되지 않고 활성화된 규칙을 우선순위 내림차순으로 조회합니다.
     *
     * @return 활성 규칙 목록
     */
    List<RuleSet> findByIsDeletedFalseAndActiveTrueOrderByPriorityDesc();

    /**
     * 규칙 코드로 삭제되지 않은 규칙을 조회합니다.
     *
     * @param ruleCode 규칙 코드
     * @return 규칙 Optional
     */
    Optional<RuleSet> findByRuleCodeAndIsDeletedFalse(String ruleCode);

    /**
     * 규칙 유형별로 삭제되지 않고 활성화된 규칙을 우선순위 내림차순으로 조회합니다.
     *
     * @param ruleType 규칙 유형
     * @return 해당 유형의 활성 규칙 목록
     */
    List<RuleSet> findByRuleTypeAndIsDeletedFalseAndActiveTrueOrderByPriorityDesc(String ruleType);
}
