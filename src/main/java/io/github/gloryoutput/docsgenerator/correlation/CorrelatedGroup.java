package io.github.gloryoutput.docsgenerator.correlation;

import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 상관관계로 그룹핑된 변경 이벤트 묶음
 *
 * <p>correlationKey가 동일한 변경 이벤트들을 하나의 그룹으로 묶고,
 * 포함된 이벤트 카테고리에 따라 요약 제목을 부여합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CorrelatedGroup {
    private String correlationKey;
    private String title;
    private List<ChangeEvent> events;
}
