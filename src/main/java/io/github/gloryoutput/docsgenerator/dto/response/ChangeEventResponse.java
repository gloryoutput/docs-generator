package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import lombok.Builder;
import lombok.Getter;

/**
 * 변경 이벤트 응답 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class ChangeEventResponse {
    private String idChangeEvent;
    private String category;
    private String title;
    private String description;
    private String severity;
    private Double confidenceScore;
    private String sourceType;
    private String correlationKey;

    /**
     * ChangeEvent 엔티티로부터 응답 DTO를 생성합니다.
     *
     * @param event 변경 이벤트 엔티티
     * @return 변경 이벤트 응답 DTO
     */
    public static ChangeEventResponse from(ChangeEvent event) {
        return ChangeEventResponse.builder()
                .idChangeEvent(event.getIdChangeEvent().toString())
                .category(event.getCategory())
                .title(event.getTitle())
                .description(event.getDescription())
                .severity(event.getSeverity())
                .confidenceScore(event.getConfidenceScore())
                .sourceType(event.getSourceType())
                .correlationKey(event.getCorrelationKey())
                .build();
    }
}
