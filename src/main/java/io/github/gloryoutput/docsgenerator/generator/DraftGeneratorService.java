package io.github.gloryoutput.docsgenerator.generator;

import io.github.gloryoutput.docsgenerator.correlation.CorrelatedGroup;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 변경 이벤트 그룹으로부터 Markdown 초안을 생성하는 서비스
 *
 * <p>상관관계 그룹을 카테고리 우선순위(SCHEMA → API → CODE)에 따라 정렬한 뒤,
 * 각 그룹별 Markdown 섹션을 생성합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class DraftGeneratorService {
    /** 카테고리별 정렬 우선순위 */
    private static final Map<String, Integer> CATEGORY_ORDER = Map.of(
            "SCHEMA_CHANGE", 0,
            "API_CHANGE", 1,
            "CODE_CHANGE", 2,
            "DEPENDENCY_CHANGE", 3
    );

    /**
     * 상관관계 그룹 목록으로부터 Markdown 초안을 생성합니다.
     *
     * @param groups 상관관계 그룹 목록
     * @return Markdown 형식의 초안 텍스트
     */
    public String generateDraft(List<CorrelatedGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return "변경 사항이 없습니다.\n";
        }
        // 그룹을 카테고리 우선순위에 따라 정렬
        List<CorrelatedGroup> sorted = groups.stream()
                .sorted(Comparator.comparingInt(this::getGroupPriority))
                .collect(Collectors.toList());
        StringBuilder draft = new StringBuilder();
        for (CorrelatedGroup group : sorted) {
            draft.append("### ").append(group.getTitle()).append("\n\n");
            for (ChangeEvent event : group.getEvents()) {
                draft.append("- **").append(event.getTitle()).append("**: ");
                draft.append(event.getDescription() != null ? event.getDescription() : "상세 내용 없음");
                draft.append("\n");
            }
            draft.append("\n");
        }
        log.info("Markdown 초안 생성 완료 ({}개 그룹)", sorted.size());
        return draft.toString();
    }

    /**
     * 그룹 내 첫 번째 이벤트의 카테고리를 기준으로 정렬 우선순위를 반환합니다.
     */
    private int getGroupPriority(CorrelatedGroup group) {
        if (group.getEvents() == null || group.getEvents().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        String category = group.getEvents().get(0).getCategory();
        return CATEGORY_ORDER.getOrDefault(category, Integer.MAX_VALUE);
    }
}
