package io.github.gloryoutput.docsgenerator.generator;

import io.github.gloryoutput.docsgenerator.correlation.CorrelatedGroup;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 변경 이벤트 그룹으로부터 Markdown 초안을 생성하는 서비스
 *
 * <p>상관관계 그룹을 카테고리 우선순위(SCHEMA → API → CODE)에 따라 정렬한 뒤,
 * 각 그룹별 Markdown 섹션을 생성합니다.
 * description 내 "기능별 변경 내용:" 구조가 있으면 기능 단위로 collapse하여 출력합니다.</p>
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
    /** description 내 기능 카테고리 헤더 패턴: [카테고리명] */
    private static final Pattern FEATURE_CATEGORY_PATTERN = Pattern.compile("^\\[(.+?)]$");
    /** 카테고리에서 키워드를 추출하는 패턴: "레이어명 (키워드)" */
    private static final Pattern KEYWORD_EXTRACT_PATTERN = Pattern.compile("^.+?\\((.+?)\\)$");

    /**
     * 상관관계 그룹 목록으로부터 Markdown 초안을 생성합니다.
     *
     * <p>이벤트 description에 "기능별 변경 내용:" 구조가 포함된 경우,
     * 각 기능 카테고리를 별도의 {@code <details>} 블록으로 분리하여 출력합니다.</p>
     *
     * @param groups 상관관계 그룹 목록
     * @return Markdown 형식의 초안 텍스트
     */
    public String generateDraft(List<CorrelatedGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return "변경 사항이 없습니다.\n";
        }
        List<CorrelatedGroup> sorted = groups.stream()
                .sorted(Comparator.comparingInt(this::getGroupPriority))
                .collect(Collectors.toList());
        StringBuilder draft = new StringBuilder();
        for (CorrelatedGroup group : sorted) {
            for (ChangeEvent event : group.getEvents()) {
                String description = event.getDescription();
                if (description != null && description.contains("기능별 변경 내용:")) {
                    // 기능 카테고리별로 분리하여 각각 collapse 블록 생성
                    appendFeatureCollapseSections(draft, description);
                } else {
                    // 기능별 구조가 없는 이벤트는 그룹 단위로 collapse
                    draft.append("<details>\n");
                    draft.append("<summary>").append(group.getTitle()).append("</summary>\n\n");
                    draft.append("- **").append(event.getTitle()).append("**: ");
                    draft.append(description != null ? description : "상세 내용 없음");
                    draft.append("\n\n</details>\n\n");
                }
            }
        }
        log.info("Markdown 초안 생성 완료 ({}개 그룹)", sorted.size());
        return draft.toString();
    }

    /**
     * description에서 "기능별 변경 내용:" 이후의 카테고리 구조를 파싱하여
     * 기능 키워드 단위로 통합한 {@code <details>} 블록으로 출력합니다.
     *
     * <p>"비즈니스 로직 (session)", "데이터 모델 (session)", "API (session)" 등
     * 같은 키워드를 가진 카테고리를 하나의 collapse 블록으로 병합합니다.
     * 키워드가 없는 카테고리(예: "설정", "공통 모듈")는 그대로 유지합니다.</p>
     */
    private void appendFeatureCollapseSections(StringBuilder draft, String description) {
        String[] lines = description.split("\n");
        boolean inFeatureSection = false;
        String currentCategory = null;
        // 키워드 → 항목 목록 (순서 유지)
        Map<String, List<String>> itemsByKeyword = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!inFeatureSection) {
                if (trimmed.equals("기능별 변경 내용:")) {
                    inFeatureSection = true;
                }
                continue;
            }
            Matcher categoryMatcher = FEATURE_CATEGORY_PATTERN.matcher(trimmed);
            if (categoryMatcher.matches()) {
                currentCategory = categoryMatcher.group(1);
            } else if (trimmed.startsWith("- ") && currentCategory != null) {
                String keyword = extractKeyword(currentCategory);
                itemsByKeyword.computeIfAbsent(keyword, k -> new ArrayList<>())
                        .add(trimmed.substring(2).trim());
            } else if (!trimmed.isEmpty() && currentCategory != null) {
                String keyword = extractKeyword(currentCategory);
                itemsByKeyword.computeIfAbsent(keyword, k -> new ArrayList<>())
                        .add(trimmed);
            }
        }
        // 키워드별 collapse 블록 생성
        for (Map.Entry<String, List<String>> entry : itemsByKeyword.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                appendCollapseBlock(draft, entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 카테고리명에서 기능 키워드를 추출하고 표시 형식으로 변환합니다.
     *
     * <p>"비즈니스 로직 (session)" → "session", "설정" → "설정"
     * 계층적 키워드의 경우 "비즈니스 로직 (scout/weather)" → "scout > weather"로 변환하여
     * 하위 기능이 상위 기능에 종속됨을 표현합니다.</p>
     *
     * @param category 카테고리명
     * @return 추출된 기능 키워드 (계층 구조 포함)
     */
    private String extractKeyword(String category) {
        Matcher matcher = KEYWORD_EXTRACT_PATTERN.matcher(category);
        if (matcher.matches()) {
            String keyword = matcher.group(1);
            // 계층적 키워드 변환: "scout/weather" → "scout > weather"
            if (keyword.contains("/")) {
                return keyword.replace("/", " > ");
            }
            return keyword;
        }
        // 괄호 없는 카테고리도 계층적 키워드일 수 있음 (LLM 미사용 시)
        if (category.contains("/")) {
            return category.replace("/", " > ");
        }
        return category;
    }

    /**
     * 단일 기능 카테고리에 대한 {@code <details>} collapse 블록을 생성합니다.
     */
    private void appendCollapseBlock(StringBuilder draft, String categoryTitle, List<String> items) {
        draft.append("<details>\n");
        draft.append("<summary>").append(categoryTitle).append("</summary>\n\n");
        for (String item : items) {
            draft.append("- ").append(item).append("\n");
        }
        draft.append("\n</details>\n\n");
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
