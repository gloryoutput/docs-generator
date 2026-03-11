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
     * <p>원본 보고서(고영 오류 수정 완료 보고서) 형식을 따라 다음 구조로 생성합니다:
     * 1) 메타 정보 테이블
     * 2) LLM이 다듬은 6개 섹션 (목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안)
     * 3) 프로젝트 코드 변경 상세 (원본 초안)</p>
     *
     * @param analysisRequest 분석 요청 엔티티
     * @param projectName 프로젝트명
     * @param groups 상관관계 그룹 목록
     * @param polishedDraft LLM이 다듬은 6개 섹션
     * @param rawDraft 원본 초안 (프로젝트 코드 변경 상세)
     * @return 완성된 Markdown 보고서 텍스트
     */
    public String generateReport(AnalysisRequest analysisRequest, String projectName,
                                  List<CorrelatedGroup> groups, String polishedDraft, String rawDraft) {
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
        // 6개 섹션 본문 (목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안)
        // LLM 성공 시 LLM 결과, 실패 시 이벤트 데이터 기반 fallback 생성
        String sections = (polishedDraft != null && !polishedDraft.isBlank())
                ? polishedDraft
                : buildFallbackSections(projectName, startDate, endDate, groups, categoryCount, severityCount);
        report.append(sections);
        if (!sections.endsWith("\n")) {
            report.append("\n");
        }
        report.append("\n");
        // 프로젝트 코드 변경 상세 (원본 초안)
        report.append("## 변경 상세\n\n");
        report.append(rawDraft);
        if (!rawDraft.endsWith("\n")) {
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
     * @param polishedDraft LLM이 다듬은 6개 섹션
     * @param rawDraft 원본 초안 (프로젝트 코드 변경 상세)
     * @return 저장된 Report 엔티티
     */
    @Transactional
    public Report generateAndSave(AnalysisRequest analysisRequest, String projectName,
                                   List<CorrelatedGroup> groups, String polishedDraft, String rawDraft) {
        String reportContent = generateReport(analysisRequest, projectName, groups, polishedDraft, rawDraft);
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
     * LLM 호출 실패 시 이벤트 데이터로부터 6개 섹션 fallback 콘텐츠를 생성합니다.
     *
     * <p>원본 보고서(고영 오류 수정 완료 보고서) 형식에 맞추어
     * 목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안
     * 6개 섹션을 ### 제목으로 구분하여 생성합니다.</p>
     */
    private String buildFallbackSections(String projectName, String startDate, String endDate,
                                          List<CorrelatedGroup> groups,
                                          Map<String, Integer> categoryCount,
                                          Map<String, Integer> severityCount) {
        log.info("LLM 미사용 - fallback 6개 섹션 생성 (프로젝트: {})", projectName);
        // 이벤트를 카테고리별로 분류
        Map<String, List<ChangeEvent>> eventsByCategory = new LinkedHashMap<>();
        List<String> highEvents = new ArrayList<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) continue;
            for (ChangeEvent event : group.getEvents()) {
                eventsByCategory.computeIfAbsent(event.getCategory(), k -> new ArrayList<>()).add(event);
                if ("HIGH".equals(event.getSeverity())) {
                    highEvents.add(event.getTitle());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        // ### 목적
        sb.append("### 목적\n\n");
        sb.append("본 보고서는 ").append(projectName).append(" 프로젝트의 ")
                .append(startDate).append(" ~ ").append(endDate)
                .append(" 기간 동안 수행된 소프트웨어 변경 작업의 과정을 정리하고, ")
                .append("작업 결과를 공유하기 위해 작성되었습니다.\n\n");
        // ### 발생한 문제
        sb.append("### 발생한 문제\n\n");
        if (eventsByCategory.containsKey("SCHEMA_CHANGE")) {
            sb.append("- **DB 스키마 변경**: ");
            appendEventTitles(sb, eventsByCategory.get("SCHEMA_CHANGE"));
        }
        if (eventsByCategory.containsKey("API_CHANGE")) {
            sb.append("- **API 변경**: ");
            appendEventTitles(sb, eventsByCategory.get("API_CHANGE"));
        }
        if (eventsByCategory.containsKey("CODE_CHANGE")) {
            sb.append("- **코드 변경**: ");
            appendEventTitles(sb, eventsByCategory.get("CODE_CHANGE"));
        }
        if (eventsByCategory.containsKey("DEPENDENCY_CHANGE")) {
            sb.append("- **의존성 변경**: ");
            appendEventTitles(sb, eventsByCategory.get("DEPENDENCY_CHANGE"));
        }
        sb.append("\n");
        // ### 문제 원인
        sb.append("### 문제 원인\n\n");
        if (eventsByCategory.containsKey("SCHEMA_CHANGE")) {
            sb.append("- **DB 스키마**: 데이터 모델 변경 요구사항이 발생하여 스키마 수정이 필요하였습니다.\n");
        }
        if (eventsByCategory.containsKey("API_CHANGE")) {
            sb.append("- **API**: API 인터페이스 변경 요구사항이 발생하여 엔드포인트 수정이 필요하였습니다.\n");
        }
        if (eventsByCategory.containsKey("CODE_CHANGE")) {
            sb.append("- **코드**: 비즈니스 로직 및 기능 변경 요구사항에 따라 코드 수정이 필요하였습니다.\n");
        }
        if (eventsByCategory.containsKey("DEPENDENCY_CHANGE")) {
            sb.append("- **의존성**: 라이브러리 또는 외부 의존성 변경이 필요하였습니다.\n");
        }
        sb.append("\n");
        // ### 문제 해결 과정
        sb.append("### 문제 해결 과정\n\n");
        int stepIndex = 1;
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null || group.getEvents().isEmpty()) continue;
            sb.append(stepIndex++).append(". **").append(group.getTitle()).append("**\n");
            for (ChangeEvent event : group.getEvents()) {
                if (event.getDescription() != null && !event.getDescription().isBlank()) {
                    // description의 첫 줄만 사용하여 간결하게 유지
                    String firstLine = event.getDescription().lines().findFirst().orElse("");
                    if (!firstLine.isBlank()) {
                        sb.append("   - ").append(firstLine).append("\n");
                    }
                }
            }
        }
        sb.append("\n");
        // ### 결과
        sb.append("### 결과\n\n");
        for (Map.Entry<String, Integer> entry : categoryCount.entrySet()) {
            sb.append("- ").append(formatCategoryName(entry.getKey()))
                    .append(" 관련 변경 ").append(entry.getValue()).append("건을 완료하였습니다.\n");
        }
        sb.append("\n");
        // ### 개선 및 예방 방안
        sb.append("### 개선 및 예방 방안\n\n");
        if (!highEvents.isEmpty()) {
            sb.append("- **고위험 변경 사항 모니터링**: 다음 항목에 대해 운영 환경 적용 후 모니터링이 필요합니다.\n");
            for (String title : highEvents) {
                sb.append("  - ").append(title).append("\n");
            }
        }
        if (severityCount.getOrDefault("HIGH", 0) == 0) {
            sb.append("- 별도의 후속 조치가 필요하지 않습니다.\n");
        }
        return sb.toString();
    }
    /**
     * 이벤트 목록의 제목을 한 줄로 이어 붙입니다.
     */
    private void appendEventTitles(StringBuilder sb, List<ChangeEvent> events) {
        if (events.size() == 1) {
            sb.append(events.get(0).getTitle()).append("\n");
        } else {
            sb.append(events.size()).append("건의 변경이 필요하였습니다.\n");
            for (ChangeEvent event : events) {
                sb.append("  - ").append(event.getTitle()).append("\n");
            }
        }
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
