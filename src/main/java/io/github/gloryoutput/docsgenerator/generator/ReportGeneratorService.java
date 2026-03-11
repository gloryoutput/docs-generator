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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
     * 2) 6개 섹션 (목적, 발생한 문제, 문제 원인, 문제 해결 과정, 결과, 개선 및 예방 방안)
     * rawDraft(기능별 변경 상세)는 "문제 해결 과정" 섹션 내에 collapse 블록으로 포함됩니다.</p>
     *
     * @param analysisRequest 분석 요청 엔티티
     * @param projectName 프로젝트명
     * @param groups 상관관계 그룹 목록
     * @param polishedDraft LLM이 다듬은 6개 섹션
     * @param rawDraft 기능별 변경 상세 (collapse 블록)
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
        // rawDraft(기능별 변경 상세)를 "문제 해결 과정" 섹션 끝에 삽입
        sections = injectRawDraftIntoSections(sections, rawDraft);
        report.append(sections);
        if (!sections.endsWith("\n")) {
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
        List<ChangeEvent> highEvents = new ArrayList<>();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) continue;
            for (ChangeEvent event : group.getEvents()) {
                eventsByCategory.computeIfAbsent(event.getCategory(), k -> new ArrayList<>()).add(event);
                if ("HIGH".equals(event.getSeverity())) {
                    highEvents.add(event);
                }
            }
        }
        // 주요 기능 키워드 추출 (목적 섹션에서 사용)
        List<String> mainFeatures = extractMainFeatures(groups);
        StringBuilder sb = new StringBuilder();
        // ### 목적
        sb.append("### 목적\n\n");
        sb.append("본 보고서는 ").append(projectName).append(" 프로젝트의 ")
                .append(startDate).append(" ~ ").append(endDate).append(" 기간 동안 수행된 ");
        if (!mainFeatures.isEmpty()) {
            sb.append(String.join(", ", mainFeatures)).append(" 관련 ");
        }
        sb.append("변경 작업의 배경, 수행 과정 및 결과를 정리한 문서입니다.");
        // 카테고리별 요약을 목적에 포함
        List<String> scopeParts = new ArrayList<>();
        if (eventsByCategory.containsKey("SCHEMA_CHANGE")) {
            scopeParts.add("DB 스키마 변경 " + eventsByCategory.get("SCHEMA_CHANGE").size() + "건");
        }
        if (eventsByCategory.containsKey("API_CHANGE")) {
            scopeParts.add("API 변경 " + eventsByCategory.get("API_CHANGE").size() + "건");
        }
        if (eventsByCategory.containsKey("CODE_CHANGE")) {
            scopeParts.add("코드 변경 " + eventsByCategory.get("CODE_CHANGE").size() + "건");
        }
        if (!scopeParts.isEmpty()) {
            sb.append(" 이번 작업의 범위는 ").append(String.join(", ", scopeParts)).append("을 포함합니다.");
        }
        sb.append("\n\n");
        // ### 발생한 문제
        sb.append("### 발생한 문제\n\n");
        if (eventsByCategory.containsKey("SCHEMA_CHANGE")) {
            sb.append("- **DB 스키마**: ");
            appendEventDetails(sb, eventsByCategory.get("SCHEMA_CHANGE"));
        }
        if (eventsByCategory.containsKey("API_CHANGE")) {
            sb.append("- **API**: ");
            appendEventDetails(sb, eventsByCategory.get("API_CHANGE"));
        }
        if (eventsByCategory.containsKey("CODE_CHANGE")) {
            sb.append("- **코드**: ");
            appendEventDetails(sb, eventsByCategory.get("CODE_CHANGE"));
        }
        if (eventsByCategory.containsKey("DEPENDENCY_CHANGE")) {
            sb.append("- **의존성**: ");
            appendEventDetails(sb, eventsByCategory.get("DEPENDENCY_CHANGE"));
        }
        sb.append("\n");
        // ### 문제 원인
        sb.append("### 문제 원인\n\n");
        if (eventsByCategory.containsKey("SCHEMA_CHANGE")) {
            List<ChangeEvent> schemaEvents = eventsByCategory.get("SCHEMA_CHANGE");
            sb.append("- **DB 스키마**: ");
            appendCauseFromEvents(sb, schemaEvents, "데이터 모델");
        }
        if (eventsByCategory.containsKey("API_CHANGE")) {
            List<ChangeEvent> apiEvents = eventsByCategory.get("API_CHANGE");
            sb.append("- **API**: ");
            appendCauseFromEvents(sb, apiEvents, "API 인터페이스");
        }
        if (eventsByCategory.containsKey("CODE_CHANGE")) {
            List<ChangeEvent> codeEvents = eventsByCategory.get("CODE_CHANGE");
            sb.append("- **코드**: ");
            appendCauseFromEvents(sb, codeEvents, "비즈니스 로직");
        }
        if (eventsByCategory.containsKey("DEPENDENCY_CHANGE")) {
            List<ChangeEvent> depEvents = eventsByCategory.get("DEPENDENCY_CHANGE");
            sb.append("- **의존성**: ");
            appendCauseFromEvents(sb, depEvents, "외부 라이브러리");
        }
        sb.append("\n");
        // ### 문제 해결 과정 (그룹별 요약 - 기능별 상세는 rawDraft collapse로 자동 삽입)
        sb.append("### 문제 해결 과정\n\n");
        int stepIndex = 1;
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null || group.getEvents().isEmpty()) continue;
            sb.append(stepIndex++).append(". **").append(group.getTitle()).append("**\n");
            for (ChangeEvent event : group.getEvents()) {
                if (event.getDescription() != null && !event.getDescription().isBlank()) {
                    // description에서 의미 있는 첫 몇 줄을 추출
                    List<String> meaningfulLines = extractMeaningfulLines(event.getDescription(), 3);
                    for (String line : meaningfulLines) {
                        sb.append("   - ").append(line).append("\n");
                    }
                } else {
                    // description이 없는 경우 title 기반으로 서술
                    sb.append("   - ").append(event.getTitle()).append("이(가) 수행되었습니다.\n");
                }
            }
        }
        sb.append("\n");
        // ### 결과
        sb.append("### 결과\n\n");
        for (Map.Entry<String, List<ChangeEvent>> entry : eventsByCategory.entrySet()) {
            String categoryName = formatCategoryName(entry.getKey());
            List<ChangeEvent> events = entry.getValue();
            if (events.size() == 1) {
                sb.append("- ").append(events.get(0).getTitle()).append("을(를) 완료하였습니다.\n");
            } else {
                sb.append("- **").append(categoryName).append("**: ");
                List<String> titles = events.stream()
                        .map(ChangeEvent::getTitle)
                        .toList();
                if (titles.size() <= 3) {
                    sb.append(String.join(", ", titles)).append("을(를) 완료하였습니다.\n");
                } else {
                    sb.append(titles.get(0)).append(", ").append(titles.get(1))
                            .append(" 외 ").append(titles.size() - 2).append("건의 변경을 완료하였습니다.\n");
                    for (String title : titles.subList(2, titles.size())) {
                        sb.append("  - ").append(title).append("\n");
                    }
                }
            }
        }
        sb.append("\n");
        // ### 개선 및 예방 방안
        sb.append("### 개선 및 예방 방안\n\n");
        if (!highEvents.isEmpty()) {
            for (ChangeEvent event : highEvents) {
                sb.append("- **").append(event.getTitle()).append("**: ");
                if (event.getDescription() != null && !event.getDescription().isBlank()) {
                    String firstLine = event.getDescription().lines().findFirst().orElse("");
                    if (!firstLine.isBlank()) {
                        sb.append(firstLine).append(" 운영 환경 적용 후 정상 동작 여부를 확인해야 합니다.\n");
                    } else {
                        sb.append("해당 변경 항목에 대해 운영 환경 적용 후 모니터링이 필요합니다.\n");
                    }
                } else {
                    sb.append("고위험 변경으로 분류되었으므로, 운영 환경 적용 후 모니터링이 필요합니다.\n");
                }
            }
        }
        if (highEvents.isEmpty()) {
            sb.append("- 이번 변경은 모두 중·저위험으로 분류되어 별도의 후속 조치가 필요하지 않습니다.\n");
        }
        return sb.toString();
    }
    /**
     * 그룹 목록에서 주요 기능 키워드를 추출합니다.
     *
     * <p>그룹 제목 및 이벤트 제목에서 반복되는 핵심 기능명을 최대 3개까지 추출합니다.</p>
     */
    private List<String> extractMainFeatures(List<CorrelatedGroup> groups) {
        List<String> features = new ArrayList<>();
        for (CorrelatedGroup group : groups) {
            if (group.getTitle() != null && !group.getTitle().isBlank()) {
                // "프로젝트 코드 변경 (N건 커밋)" 같은 일반적 제목은 건너뜀
                String title = group.getTitle();
                if (!title.contains("코드 변경") && !title.contains("커밋") && title.length() <= 50) {
                    features.add(title);
                }
            }
        }
        // 최대 3개까지만
        if (features.size() > 3) {
            features = new ArrayList<>(features.subList(0, 3));
        }
        return features;
    }
    /**
     * 이벤트 목록을 제목 + description 기반으로 구체적으로 서술합니다.
     */
    private void appendEventDetails(StringBuilder sb, List<ChangeEvent> events) {
        if (events.size() == 1) {
            ChangeEvent event = events.get(0);
            sb.append(event.getTitle());
            if (event.getDescription() != null && !event.getDescription().isBlank()) {
                String firstLine = event.getDescription().lines().findFirst().orElse("");
                if (!firstLine.isBlank() && !firstLine.equals(event.getTitle())) {
                    sb.append(" — ").append(firstLine);
                }
            }
            sb.append("\n");
        } else {
            sb.append(events.size()).append("건의 변경이 필요하였습니다.\n");
            for (ChangeEvent event : events) {
                sb.append("  - ").append(event.getTitle());
                if (event.getDescription() != null && !event.getDescription().isBlank()) {
                    String firstLine = event.getDescription().lines().findFirst().orElse("");
                    if (!firstLine.isBlank() && !firstLine.equals(event.getTitle())) {
                        sb.append(": ").append(firstLine);
                    }
                }
                sb.append("\n");
            }
        }
    }
    /**
     * 이벤트 데이터에서 원인을 추출하여 서술합니다.
     *
     * <p>이벤트의 title/description에서 구체적인 변경 대상을 추출하여
     * '무엇 때문에 변경이 필요했는지' 맥락을 제공합니다.</p>
     */
    private void appendCauseFromEvents(StringBuilder sb, List<ChangeEvent> events, String domainLabel) {
        List<String> eventTitles = events.stream()
                .map(ChangeEvent::getTitle)
                .limit(3)
                .toList();
        sb.append(String.join(", ", eventTitles));
        if (events.size() > 3) {
            sb.append(" 등 ").append(events.size()).append("건의 ");
        } else {
            sb.append("에 대한 ");
        }
        sb.append(domainLabel).append(" 변경 요구사항이 발생하여 수정이 필요하였습니다.\n");
    }
    /**
     * description에서 의미 있는 줄만 추출합니다.
     *
     * <p>빈 줄, 메타 정보(대괄호 헤더), 너무 짧은 줄을 건너뛰고
     * 실질적 내용이 담긴 줄을 최대 maxLines개까지 반환합니다.</p>
     */
    private List<String> extractMeaningfulLines(String description, int maxLines) {
        List<String> result = new ArrayList<>();
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // [카테고리명] 형태의 헤더 라인은 건너뜀
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) continue;
            // "기능별 변경 내용:" 같은 메타 라인은 건너뜀
            if (trimmed.equals("기능별 변경 내용:")) continue;
            // "- " 접두사가 있으면 제거
            if (trimmed.startsWith("- ")) trimmed = trimmed.substring(2).trim();
            if (trimmed.length() < 3) continue;
            result.add(trimmed);
            if (result.size() >= maxLines) break;
        }
        return result;
    }
    /**
     * rawDraft(기능별 변경 상세 collapse 블록)를 "문제 해결 과정" 섹션 끝에 삽입합니다.
     *
     * <p>"### 결과" 섹션 시작 직전에 rawDraft를 삽입하여,
     * "문제 해결 과정" 섹션의 마지막 부분에 기능별 상세가 포함되도록 합니다.</p>
     */
    private String injectRawDraftIntoSections(String sections, String rawDraft) {
        if (rawDraft == null || rawDraft.isBlank()) {
            return sections;
        }
        // "### 결과" 앞에 rawDraft 삽입 → "문제 해결 과정" 섹션 내에 위치
        int resultIndex = sections.indexOf("### 결과");
        if (resultIndex > 0) {
            String before = sections.substring(0, resultIndex);
            String after = sections.substring(resultIndex);
            // "문제 해결 과정" 본문 뒤에 기능별 상세 추가
            if (!before.endsWith("\n\n")) {
                before = before.stripTrailing() + "\n\n";
            }
            return before + rawDraft.stripTrailing() + "\n\n" + after;
        }
        // "### 결과"를 찾지 못하면 섹션 끝에 추가
        return sections.stripTrailing() + "\n\n" + rawDraft;
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
