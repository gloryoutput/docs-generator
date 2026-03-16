package io.github.gloryoutput.docsgenerator.dto.response;

import io.github.gloryoutput.docsgenerator.domain.clientreport.ClientReport;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 클라이언트 보고서 응답 DTO
 *
 * @author Lodong
 * @since 1.0.0
 */
@Getter
@Builder
public class ClientReportResponse {
    private String idClientReport;
    private String idProject;
    private LocalDate startDate;
    private LocalDate endDate;
    private String requestedBy;
    private String documentNames;
    private String chatFileName;
    private String sourceUrls;
    private String reportContent;
    private LocalDateTime generatedAt;

    /**
     * ClientReport 엔티티로부터 응답 DTO를 생성합니다.
     *
     * @param entity 클라이언트 보고서 엔티티
     * @return 클라이언트 보고서 응답 DTO
     */
    public static ClientReportResponse from(ClientReport entity) {
        return ClientReportResponse.builder()
                .idClientReport(entity.getIdClientReport().toString())
                .idProject(entity.getIdProject().toString())
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .requestedBy(entity.getRequestedBy())
                .documentNames(entity.getDocumentNames())
                .chatFileName(entity.getChatFileName())
                .sourceUrls(entity.getSourceUrls())
                .reportContent(entity.getReportContent())
                .generatedAt(entity.getGeneratedAt())
                .build();
    }
}
