package io.github.gloryoutput.docsgenerator.generator;

import io.github.gloryoutput.docsgenerator.correlation.CorrelatedGroup;
import io.github.gloryoutput.docsgenerator.domain.analysis.AnalysisRequest;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
import io.github.gloryoutput.docsgenerator.domain.report.Report;
import io.github.gloryoutput.docsgenerator.domain.report.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 최종 Markdown 보고서를 생성하고 저장하는 서비스
 *
 * <p>분석 요청 정보, 상관관계 그룹, LLM이 다듬은 초안을 종합하여
 * 원본 보고서 형식(목적, 문제 정의, 해결 과정, 결과, 개선 방안)에 맞는
 * Markdown 보고서를 만듭니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportGeneratorService {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_KR_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");
    private final ReportRepository reportRepository;

    /**
     * 최종 Markdown 보고서를 생성합니다.
     *
     * <p>원본 보고서(고영 오류 수정 완료 보고서) 형식을 따라
     * 목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안
     * 6개 섹션을 포함하는 보고서를 생성합니다.</p>
     *
     * @param analysisRequest 분석 요청 엔티티
     * @param projectName 프로젝트명
     * @param groups 상관관계 그룹 목록
     * @param draft LLM이 다듬은 초안 (6개 섹션 포함)
     * @return 완성된 Markdown 보고서 텍스트
     */
    public String generateReport(AnalysisRequest analysisRequest, String projectName,
                                  List<CorrelatedGroup> groups, String draft) {
        int totalEvents = groups.stream()
                .mapToInt(g -> g.getEvents() != null ? g.getEvents().size() : 0)
                .sum();
        String startDate = analysisRequest.getStartDate().format(DATE_FORMATTER);
        String endDate = analysisRequest.getEndDate().format(DATE_FORMATTER);
        String createdDate = LocalDateTime.now().format(DATE_KR_FORMATTER);
        // 카테고리별·심각도별 통계
        Map<String, Integer> categoryCount = new LinkedHashMap<>();
        Map<String, Integer> severityCount = new LinkedHashMap<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) continue;
            for (ChangeEvent event : group.getEvents()) {
                categoryCount.merge(event.getCategory(), 1, Integer::sum);
                severityCount.merge(event.getSeverity(), 1, Integer::sum);
            }
        }
        StringBuilder report = new StringBuilder();
        // 보고서 제목
        report.append("# 소프트웨어 변경 보고서\n\n");
        report.append("작성일자: ").append(createdDate).append("\n\n");
        // 메타 정보 테이블
        report.append("| 항목 | 내용 |\n");
        report.append("|------|------|\n");
        report.append("| 프로젝트 | ").append(projectName).append(" |\n");
        report.append("| 분석 기간 | ").append(startDate).append(" ~ ").append(endDate).append(" |\n");
        report.append("| 총 변경 이벤트 | ").append(totalEvents).append("건 |\n");
        if (!categoryCount.isEmpty()) {
            StringBuilder catSummary = new StringBuilder();
            categoryCount.forEach((k, v) -> {
                if (!catSummary.isEmpty()) catSummary.append(", ");
                catSummary.append(formatCategoryName(k)).append(" ").append(v).append("건");
            });
            report.append("| 변경 유형 | ").append(catSummary).append(" |\n");
        }
        if (!severityCount.isEmpty()) {
            StringBuilder sevSummary = new StringBuilder();
            severityCount.forEach((k, v) -> {
                if (!sevSummary.isEmpty()) sevSummary.append(", ");
                sevSummary.append(formatSeverityName(k)).append(" ").append(v).append("건");
            });
            report.append("| 심각도 분포 | ").append(sevSummary).append(" |\n");
        }
        report.append("\n");
        // LLM이 다듬은 6개 섹션 본문 (목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안)
        report.append(draft);
        if (!draft.endsWith("\n")) {
            report.append("\n");
        }
        log.info("최종 보고서 생성 완료 (프로젝트: {}, 이벤트: {}건)", projectName, totalEvents);
        return report.toString();
    }

    /**
     * 보고서를 생성하고 DB에 저장합니다.
     *
     * @param analysisRequest 분석 요청 엔티티
     * @param projectName 프로젝트명
     * @param groups 상관관계 그룹 목록
     * @param draft LLM이 다듬은 초안 (6개 섹션 포함)
     * @return 저장된 Report 엔티티
     */
    @Transactional
    public Report generateAndSave(AnalysisRequest analysisRequest, String projectName,
                                   List<CorrelatedGroup> groups, String draft) {
        String reportContent = generateReport(analysisRequest, projectName, groups, draft);
        Report report = Report.builder()
                .idAnalysisRequest(analysisRequest.getIdAnalysisRequest())
                .idProject(analysisRequest.getIdProject())
                .reportContent(reportContent)
                .build();
        reportRepository.save(report);
        log.info("보고서 저장 완료 (ID: {})", report.getIdReport());
        return report;
    }

    /**
     * 카테고리 코드를 한국어 표시명으로 변환합니다.
     */
    private String formatCategoryName(String category) {
        return switch (category) {
            case "SCHEMA_CHANGE" -> "DB 스키마";
            case "API_CHANGE" -> "API";
            case "CODE_CHANGE" -> "코드";
            case "DEPENDENCY_CHANGE" -> "의존성";
            default -> category;
        };
    }

    /**
     * 심각도 코드를 한국어 표시명으로 변환합니다.
     */
    private String formatSeverityName(String severity) {
        return switch (severity) {
            case "HIGH" -> "높음(HIGH)";
            case "MEDIUM" -> "보통(MEDIUM)";
            case "LOW" -> "낮음(LOW)";
            default -> severity;
        };
    }
}
