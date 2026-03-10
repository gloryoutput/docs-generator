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
import java.util.stream.Collectors;

/**
 * 최종 Markdown 보고서를 생성하고 저장하는 서비스
 *
 * <p>분석 요청 정보, 상관관계 그룹, 초안 내용을 종합하여
 * 템플릿 기반의 완성된 Markdown 보고서를 만듭니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportGeneratorService {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ReportRepository reportRepository;

    /**
     * 최종 Markdown 보고서를 생성합니다.
     *
     * @param analysisRequest 분석 요청 엔티티
     * @param projectName 프로젝트명
     * @param groups 상관관계 그룹 목록
     * @param draft 초안 Markdown 텍스트
     * @return 완성된 Markdown 보고서 텍스트
     */
    public String generateReport(AnalysisRequest analysisRequest, String projectName,
                                  List<CorrelatedGroup> groups, String draft) {
        int totalEvents = groups.stream()
                .mapToInt(g -> g.getEvents() != null ? g.getEvents().size() : 0)
                .sum();
        String generatedAt = LocalDateTime.now().format(DATETIME_FORMATTER);
        String startDate = analysisRequest.getStartDate().format(DATE_FORMATTER);
        String endDate = analysisRequest.getEndDate().format(DATE_FORMATTER);
        StringBuilder report = new StringBuilder();
        // 헤더
        report.append("# 자동 보고서\n\n");
        // 작업 개요
        report.append("## 작업 개요\n\n");
        report.append("| 항목 | 내용 |\n");
        report.append("|------|------|\n");
        report.append("| 프로젝트 | ").append(projectName).append(" |\n");
        report.append("| 분석 기간 | ").append(startDate).append(" ~ ").append(endDate).append(" |\n");
        report.append("| 생성 일시 | ").append(generatedAt).append(" |\n");
        report.append("| 총 변경 이벤트 | ").append(totalEvents).append("건 |\n\n");
        // 주요 변경 사항
        report.append("## 주요 변경 사항\n\n");
        report.append(draft);
        // 영향 범위
        report.append("## 영향 범위\n\n");
        report.append(buildImpactScope(groups));
        // 확인 필요 사항
        report.append("## 확인 필요 사항\n\n");
        report.append(buildHighSeveritySection(groups));
        log.info("최종 보고서 생성 완료 (프로젝트: {}, 이벤트: {}건)", projectName, totalEvents);
        return report.toString();
    }

    /**
     * 보고서를 생성하고 DB에 저장합니다.
     *
     * @param analysisRequest 분석 요청 엔티티
     * @param projectName 프로젝트명
     * @param groups 상관관계 그룹 목록
     * @param draft 초안 Markdown 텍스트
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
     * 영향 범위 섹션을 생성합니다 (테이블, endpoint, 코드 모듈).
     */
    private String buildImpactScope(List<CorrelatedGroup> groups) {
        Set<String> tables = new LinkedHashSet<>();
        Set<String> endpoints = new LinkedHashSet<>();
        Set<String> repositories = new LinkedHashSet<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) continue;
            for (ChangeEvent event : group.getEvents()) {
                switch (event.getCategory()) {
                    case "SCHEMA_CHANGE":
                        if (event.getCorrelationKey() != null) {
                            tables.add(event.getCorrelationKey());
                        }
                        break;
                    case "API_CHANGE":
                        if (event.getCorrelationKey() != null) {
                            endpoints.add(event.getCorrelationKey());
                        }
                        break;
                    case "CODE_CHANGE":
                        if (event.getCorrelationKey() != null) {
                            repositories.add(event.getCorrelationKey());
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        StringBuilder scope = new StringBuilder();
        if (!tables.isEmpty()) {
            scope.append("**영향받는 테이블:**\n");
            tables.forEach(t -> scope.append("- ").append(t).append("\n"));
            scope.append("\n");
        }
        if (!endpoints.isEmpty()) {
            scope.append("**영향받는 API:**\n");
            endpoints.forEach(e -> scope.append("- ").append(e).append("\n"));
            scope.append("\n");
        }
        if (!repositories.isEmpty()) {
            scope.append("**영향받는 레포지토리:**\n");
            repositories.forEach(r -> scope.append("- ").append(r).append("\n"));
            scope.append("\n");
        }
        if (scope.length() == 0) {
            scope.append("영향 범위 없음\n\n");
        }
        return scope.toString();
    }

    /**
     * HIGH severity 이벤트를 모아 확인 필요 사항 섹션을 생성합니다.
     */
    private String buildHighSeveritySection(List<CorrelatedGroup> groups) {
        List<ChangeEvent> highEvents = groups.stream()
                .filter(g -> g.getEvents() != null)
                .flatMap(g -> g.getEvents().stream())
                .filter(e -> "HIGH".equals(e.getSeverity()))
                .collect(Collectors.toList());
        if (highEvents.isEmpty()) {
            return "확인이 필요한 고위험 변경 사항이 없습니다.\n";
        }
        StringBuilder section = new StringBuilder();
        for (ChangeEvent event : highEvents) {
            section.append("- **[").append(event.getSeverity()).append("]** ")
                    .append(event.getTitle());
            if (event.getDescription() != null) {
                section.append(" - ").append(event.getDescription());
            }
            section.append("\n");
        }
        return section.toString();
    }
}
