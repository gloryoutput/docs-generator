package io.github.gloryoutput.docsgenerator.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

/**
 * 분석 요청 DTO
 *
 * <p>id_project와 분석 기간(startDate, endDate)을 전달합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@NoArgsConstructor
public class AnalysisCreateRequest {
    private String idProject;
    private LocalDate startDate;
    private LocalDate endDate;
    private String requestedBy;
}
