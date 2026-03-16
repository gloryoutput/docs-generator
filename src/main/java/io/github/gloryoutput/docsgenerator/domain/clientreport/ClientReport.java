package io.github.gloryoutput.docsgenerator.domain.clientreport;

import io.github.gloryoutput.docsgenerator.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 클라이언트 보고서 엔티티
 *
 * <p>시스템 문서와 카카오톡 대화 내용을 기반으로 LLM이 생성한 보고서를 저장합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Entity
@Table(name = "client_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientReport extends BaseEntity {
    @Id
    @Column(name = "id_client_report", nullable = false, updatable = false, columnDefinition = "binary(16)")
    private UUID idClientReport;
    @Column(name = "id_project", nullable = false, columnDefinition = "binary(16)")
    private UUID idProject;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "requested_by")
    private String requestedBy;
    @Column(name = "document_names", columnDefinition = "TEXT")
    private String documentNames;
    @Column(name = "chat_file_name")
    private String chatFileName;
    @Column(name = "report_content", nullable = false, columnDefinition = "LONGTEXT")
    private String reportContent;
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Builder
    public ClientReport(UUID idProject, LocalDate startDate, LocalDate endDate,
                        String requestedBy, String documentNames,
                        String chatFileName, String reportContent) {
        this.idClientReport = UUID.randomUUID();
        this.idProject = idProject;
        this.startDate = startDate;
        this.endDate = endDate;
        this.requestedBy = requestedBy;
        this.documentNames = documentNames;
        this.chatFileName = chatFileName;
        this.reportContent = reportContent;
        this.generatedAt = LocalDateTime.now();
    }
}
