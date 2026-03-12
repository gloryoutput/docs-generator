package io.github.gloryoutput.docsgenerator.correlation;

import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 변경 이벤트 상관관계 분석 서비스
 *
 * <p>correlationKey를 기반으로 변경 이벤트를 그룹핑하고,
 * 각 그룹의 카테고리 조합에 따라 요약 제목을 생성합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class CorrelationService {

    /**
     * 변경 이벤트를 correlationKey 기준으로 그룹핑합니다.
     *
     * @param events 변경 이벤트 목록
     * @return 상관관계 그룹 목록
     */
    public List<CorrelatedGroup> correlateEvents(List<ChangeEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        // correlationKey가 있는 이벤트와 없는 이벤트 분리
        Map<String, List<ChangeEvent>> grouped = new LinkedHashMap<>();
        List<ChangeEvent> ungrouped = new ArrayList<>();
        for (ChangeEvent event : events) {
            if (event.getCorrelationKey() != null && !event.getCorrelationKey().isBlank()) {
                grouped.computeIfAbsent(event.getCorrelationKey(), k -> new ArrayList<>()).add(event);
            } else {
                ungrouped.add(event);
            }
        }
        List<CorrelatedGroup> result = new ArrayList<>();
        // correlationKey로 그룹핑된 이벤트 처리
        for (Map.Entry<String, List<ChangeEvent>> entry : grouped.entrySet()) {
            List<ChangeEvent> deduplicatedEvents = deduplicateEvents(entry.getValue());
            String title = generateGroupTitle(entry.getKey(), deduplicatedEvents);
            result.add(CorrelatedGroup.builder()
                    .correlationKey(entry.getKey())
                    .title(title)
                    .events(deduplicatedEvents)
                    .build());
        }
        // correlationKey가 없는 이벤트는 개별 그룹으로 처리
        for (ChangeEvent event : ungrouped) {
            result.add(CorrelatedGroup.builder()
                    .correlationKey(null)
                    .title(event.getTitle())
                    .events(List.of(event))
                    .build());
        }
        log.info("변경 이벤트 {}건을 {}개 그룹으로 상관관계 분석 완료", events.size(), result.size());
        return result;
    }

    /**
     * 그룹 내 이벤트 카테고리 조합에 따라 요약 제목을 생성합니다.
     */
    private String generateGroupTitle(String correlationKey, List<ChangeEvent> events) {
        Set<String> categories = events.stream()
                .map(ChangeEvent::getCategory)
                .collect(Collectors.toSet());
        boolean hasSchema = categories.contains("SCHEMA_CHANGE");
        boolean hasApi = categories.contains("API_CHANGE");
        boolean hasCode = categories.contains("CODE_CHANGE");
        boolean hasDependency = categories.contains("DEPENDENCY_CHANGE");
        if (hasSchema && hasApi) {
            return "기능 변경: " + correlationKey;
        }
        if (hasSchema && !hasApi && !hasCode && !hasDependency) {
            return "DB 변경: " + correlationKey;
        }
        if (hasApi && !hasSchema && !hasCode && !hasDependency) {
            return "API 변경: " + correlationKey;
        }
        if (hasCode && !hasSchema && !hasApi && !hasDependency) {
            if ("PROJECT".equals(correlationKey)) {
                return "소프트웨어 변경";
            }
            return "코드 변경: " + correlationKey;
        }
        return "복합 변경: " + correlationKey;
    }

    /**
     * 같은 category + title을 가진 이벤트를 중복 제거합니다.
     */
    private List<ChangeEvent> deduplicateEvents(List<ChangeEvent> events) {
        Set<String> seen = new HashSet<>();
        List<ChangeEvent> deduplicated = new ArrayList<>();
        for (ChangeEvent event : events) {
            String key = event.getCategory() + "::" + event.getTitle();
            if (seen.add(key)) {
                deduplicated.add(event);
            }
        }
        return deduplicated;
    }
}
