package io.github.gloryoutput.docsgenerator.summarizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM을 활용한 변경 설명 압축 서비스
 *
 * <p>여러 파일에 걸친 기능별 변경 내용을 카테고리 병합 → LLM 압축 순서로 처리합니다.
 * 같은 키워드를 공유하는 카테고리(예: "API (scout)", "비즈니스 로직 (scout)")를
 * 하나의 카테고리("scout")로 병합한 뒤, LLM으로 각 카테고리 내 항목을 압축합니다.</p>
 *
 * <p>LlmClient 빈이 없으면 카테고리 병합 + 중복 제거만 적용합니다(fallback).</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class LlmDescriptionCompressorService {
    private static final String SYSTEM_PROMPT =
            "당신은 IT 변경 보고서 작성 전문가입니다.\n" +
            "아래 '기능별 변경 내용'은 여러 파일을 수정하여 발생한 변경 사항을 기능별로 정리한 것입니다.\n" +
            "이것을 비개발자(경영진, 고객사)가 읽을 보고서에 들어갈 **구체적 변경 요약**으로 압축해 주세요.\n\n" +
            LlmPromptConstants.CONCRETE_SUBJECT_RULE + "\n" +
            "## 메뉴 기준 그룹핑\n" +
            "같은 메뉴에 속하는 하위 기능들은 반드시 하나의 카테고리로 통합하세요.\n" +
            "하위 기능은 상위 기능의 맥락에서 서술하고, 독립 카테고리로 분리하지 마세요.\n\n" +
            "## 압축 규칙\n" +
            "1. **카테고리당 최대 1~2개 문장**: 같은 키워드에 대한 항목이 여러 개면 반드시 하나로 통합\n" +
            "2. 기술 용어(Repository, Service, Controller, Entity, 필드, 메서드 등)를 사용하지 마세요\n" +
            "3. 파일명, 클래스명, 패키지 경로를 언급하지 마세요\n" +
            "4. 카테고리명은 비개발자가 이해할 수 있는 업무 관점의 한국어 이름으로 변환하세요\n" +
            "   영문 키워드를 해당 프로젝트의 업무 맥락에 맞는 한국어 메뉴/기능명으로 변환하세요\n" +
            "5. 항목이 모두 다른 카테고리에 병합되어 비게 된 카테고리는 제외하세요\n" +
            "6. 같은 문장 패턴에서 일부만 다른 항목은 쉼표로 병합하세요.\n" +
            "   예: '날씨 필드 추가' + '점수 필드 추가' → '날씨, 점수 필드 추가'\n" +
            "7. 같은 대상에 대한 세부 변경(필드 추가, 옵션 변경, 데이터 분리 등)은 의도 단위로 통합하세요.\n" +
            "   개별 필드나 옵션을 나열하지 말고, 해당 변경의 상위 의도로 한 문장에 압축하세요.\n" +
            "   개별 필드나 옵션을 나열하지 말고, 해당 변경의 상위 의도로 한 문장에 압축하세요.\n\n" +
            "## 응답 형식\n" +
            "반드시 아래 JSON 객체 형식으로만 응답하세요. 다른 텍스트를 포함하지 마세요.\n" +
            "{\n" +
            "  \"카테고리명\": [\"구체적 변경 요약 1\"],\n" +
            "  \"카테고리명\": [\"구체적 변경 요약 1\"]\n" +
            "}";
    /** 카테고리에서 괄호 안 키워드를 추출하는 패턴: "비즈니스 로직 (scout)" → "scout" */
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("\\(([^)]+)\\)");
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    /**
     * 키워드 정규화 맵: 약어/변형 → 정규 키워드
     *
     * <p>같은 기능을 가리키는 여러 키워드를 하나의 정규 키워드로 통합합니다.
     * 예: "gs", "sheets", "gsheet" → 모두 "google"로 정규화</p>
     */
    private static final Map<String, String> KEYWORD_ALIASES = Map.ofEntries(
            // Google Sheets 관련
            Map.entry("gs", "google"),
            Map.entry("sheets", "google"),
            Map.entry("gsheet", "google"),
            Map.entry("gsheets", "google"),
            Map.entry("google sheets", "google"),
            Map.entry("spreadsheet", "google"),
            Map.entry("spreadsheets", "google")
    );
    /**
     * 도메인에서 의미를 파악할 수 있는 알려진 키워드 목록
     *
     * <p>이 목록에 없는 영문 키워드(shape, afc 등)는 비개발자에게 무의미하므로
     * "기타 기능"으로 통합 시 키워드 접두사를 제거합니다.</p>
     */
    private static final Set<String> KNOWN_DOMAIN_KEYWORDS = Set.of(
            "schedule", "event", "note", "tag", "category", "priority",
            "report", "document", "template", "notification", "message",
            "comment", "user", "member", "admin", "role", "permission",
            "auth", "profile", "setting", "config", "dashboard",
            "statistics", "summary", "history", "log", "record",
            "status", "type", "level", "content", "block", "page",
            "image", "file", "attachment", "google",
            "project", "task", "issue", "customer", "client", "company",
            "order", "product", "inventory", "payment", "invoice",
            "board", "post", "reply", "menu", "form", "search",
            "approval", "workflow", "batch", "import", "export"
    );
    /**
     * 키워드 → 한국어 메뉴/기능명 번역 맵
     *
     * <p>카테고리명과 항목 텍스트에서 영문 키워드 대신 한국어 메뉴명을 사용합니다.
     * 이 맵에 없는 키워드는 원문 그대로 유지되며, KNOWN_DOMAIN_KEYWORDS에도 없으면
     * "기타 기능"으로 통합됩니다.</p>
     */
    private static final Map<String, String> KEYWORD_TRANSLATIONS = Map.ofEntries(
            Map.entry("schedule", "일정"), Map.entry("event", "이벤트"),
            Map.entry("note", "메모"), Map.entry("tag", "태그"),
            Map.entry("category", "카테고리"), Map.entry("priority", "우선순위"),
            Map.entry("report", "보고서"), Map.entry("document", "문서"),
            Map.entry("template", "템플릿"), Map.entry("notification", "알림"),
            Map.entry("message", "메시지"), Map.entry("comment", "댓글"),
            Map.entry("user", "사용자"), Map.entry("member", "회원"),
            Map.entry("admin", "관리자"), Map.entry("role", "역할"),
            Map.entry("permission", "권한"), Map.entry("auth", "인증"),
            Map.entry("profile", "프로필"), Map.entry("setting", "설정"),
            Map.entry("config", "설정"), Map.entry("dashboard", "대시보드"),
            Map.entry("statistics", "통계"), Map.entry("history", "이력"),
            Map.entry("content", "콘텐츠"), Map.entry("google", "Google 연동"),
            Map.entry("project", "프로젝트"), Map.entry("task", "작업"),
            Map.entry("customer", "고객"), Map.entry("client", "클라이언트"),
            Map.entry("order", "주문"), Map.entry("product", "상품"),
            Map.entry("inventory", "재고"), Map.entry("payment", "결제"),
            Map.entry("invoice", "청구서"), Map.entry("board", "게시판"),
            Map.entry("post", "게시글"), Map.entry("menu", "메뉴"),
            Map.entry("form", "양식"), Map.entry("search", "검색"),
            Map.entry("approval", "승인"), Map.entry("workflow", "업무흐름"),
            Map.entry("batch", "일괄처리"), Map.entry("import", "가져오기"),
            Map.entry("export", "내보내기")
    );
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${app.llm.prompt-output-dir:./llm-prompts}")
    private String promptOutputDir;

    public LlmDescriptionCompressorService(@Autowired(required = false) LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 기능별 변경 내용을 카테고리 병합 후 압축합니다.
     *
     * <p>처리 순서:
     * 1) 같은 키워드를 공유하는 카테고리 병합 (예: "API (scout)" + "비즈니스 로직 (scout)" → "scout")
     * 2) 키워드 기반 요약 압축 (같은 키워드의 유사 항목을 1~2문장으로 통합)
     * 3) 메뉴 기준 그룹핑 (계층적 키워드를 상위 메뉴로 병합, 예: "scout/weather" → "scout")
     * 3.5) 의도 기반 압축 (같은 의도의 세부 항목을 통합, 예: 날씨/포지션/소속 필드 추가 → "영입후보 필드 추가")
     * 3.6) 미번역 카테고리 통합 (영문 키워드 그대로 남은 카테고리를 "기타 기능"으로 병합, 예: shape+afc → "기타 기능")
     * 3.8) 목적 단위 재그룹핑 (기능 단위 → 목적 단위, 예: 스카우트/선수/팀 → 신규 기능 추가/데이터 모델 확장)
     * 4) LLM이 있으면 의도 기반으로 사전 압축된 데이터로 추가 압축, 없으면 3.8단계 결과 반환</p>
     *
     * @param changesByFeature 기능 영역 → 변경 설명 목록
     * @return 카테고리 → 압축된 변경 요약 목록
     */
    public Map<String, List<String>> compress(Map<String, Set<String>> changesByFeature) {
        if (changesByFeature == null || changesByFeature.isEmpty()) {
            return Map.of();
        }
        // 1단계: 같은 키워드의 카테고리 병합 + 중복 제거
        Map<String, Set<String>> merged = mergeCategories(changesByFeature);
        // 1.5단계: 계층 키워드에서 자식→부모 관계 맵 구축
        Map<String, String> childToParent = buildChildToParentMap(merged.keySet());
        // 2단계: 키워드 기반 요약 압축 (LLM 없어도 동작)
        Map<String, List<String>> summarized = summarizeByKeyword(merged);
        // 3단계: 메뉴 기준 그룹핑 (계층적 키워드를 상위 메뉴로 병합, 자식→부모 맥락 활용)
        Map<String, List<String>> menuGrouped = groupByMenu(summarized, childToParent);
        // 3.5단계: 의도 기반 압축 (같은 의도의 세부 항목을 하나의 의도 문장으로 통합)
        Map<String, List<String>> intentCompressed = compressToIntent(menuGrouped);
        // 3.6단계: 미분류 카테고리 재분배 (항목 내용 기반으로 가장 가까운 메뉴에 흡수)
        Map<String, List<String>> redistributed = redistributeOrphanCategories(intentCompressed);
        // 3.7단계: 주체 없는 제네릭 항목 제거 (메뉴명이 이미 맥락을 제공하므로 불필요)
        Map<String, List<String>> finalCompressed = removeGenericItems(redistributed);
        // 3.8단계: 목적 단위 재그룹핑 (기능 단위 → 목적 단위)
        Map<String, List<String>> purposeGrouped = regroupByPurpose(finalCompressed);
        if (llmClient == null) {
            int totalBefore = merged.values().stream().mapToInt(Set::size).sum();
            int totalAfter = purposeGrouped.values().stream().mapToInt(List::size).sum();
            log.debug("LLM 비활성화 - 목적 단위 압축 적용 ({}건 → {}건)", totalBefore, totalAfter);
            return purposeGrouped;
        }
        // 4단계: LLM 추가 압축 (의도 기반으로 사전 압축된 데이터 전달)
        Map<String, Set<String>> menuMerged = groupByMenuSet(merged, childToParent);
        Map<String, Set<String>> intentMergedForLlm = compressToIntentSet(menuMerged);
        Map<String, Set<String>> finalMergedForLlm = redistributeOrphanCategoriesSet(intentMergedForLlm);
        int totalItems = finalMergedForLlm.values().stream().mapToInt(Set::size).sum();
        try {
            String userPrompt = buildUserPrompt(finalMergedForLlm);
            String timestamp = LocalDateTime.now().format(FILE_FORMATTER);
            savePromptFile(timestamp, "compress_input", SYSTEM_PROMPT + "\n\n---\n\n" + userPrompt);
            log.info("LLM 변경 설명 압축 시작 - {}개 메뉴, {}건 항목 (의도 기반 사전 압축 적용)", finalMergedForLlm.size(), totalItems);
            String result = llmClient.chat(SYSTEM_PROMPT, userPrompt);
            savePromptFile(timestamp, "compress_output", result);
            Map<String, List<String>> compressed = parseResponse(result);
            if (compressed == null || compressed.isEmpty()) {
                log.warn("LLM 압축 결과가 비어있음 - 목적 단위 압축 반환");
                return purposeGrouped;
            }
            // LLM 결과도 목적 단위로 재그룹핑
            Map<String, List<String>> llmPurposeGrouped = regroupByPurpose(compressed);
            int compressedItems = llmPurposeGrouped.values().stream().mapToInt(List::size).sum();
            log.info("LLM 변경 설명 압축 완료 - {}개 메뉴 {}건 → {}개 목적 {}건",
                    finalMergedForLlm.size(), totalItems, llmPurposeGrouped.size(), compressedItems);
            return llmPurposeGrouped;
        } catch (Exception e) {
            log.warn("LLM 변경 설명 압축 실패 - 목적 단위 압축 반환. 원인: {}", e.getMessage());
            return purposeGrouped;
        }
    }
    /**
     * 카테고리별 항목을 키워드 기반으로 요약 압축합니다.
     *
     * <p>같은 키워드(예: "weather")에 대해 여러 레이어에서 생성된 유사 항목
     * ("weather 기능 추가", "weather 조회 기능 추가", "weather 정보 관리 추가" 등)을
     * 하나의 요약 문장으로 통합합니다.</p>
     *
     * <p>예: 12건의 weather 관련 항목 → "weather 관리 기능 신규 추가 (조회, 데이터 초기화 포함)"</p>
     */
    private Map<String, List<String>> summarizeByKeyword(Map<String, Set<String>> merged) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : merged.entrySet()) {
            String keyword = entry.getKey();
            Set<String> items = entry.getValue();
            if (items.isEmpty()) continue;
            // 항목이 2개 이하면 그대로 유지
            if (items.size() <= 2) {
                result.put(keyword, new ArrayList<>(items));
                continue;
            }
            result.put(keyword, buildKeywordSummary(keyword, items));
        }
        return result;
    }
    /**
     * 단일 키워드에 속하는 항목들을 1~2개의 요약 문장으로 압축합니다.
     *
     * <p>항목들에서 액션 패턴(추가, 조회, 수정, 삭제 등)을 감지하고,
     * 키워드와 무관한 고유 항목은 별도로 유지합니다.</p>
     *
     * <p>계층적 키워드(parent/child 형식)인 경우, 상위 기능의 맥락에서
     * 하위 기능을 서술합니다.
     * 예: "scout/weather" → "scout 기능에서 weather 입력 기능 추가"</p>
     */
    private List<String> buildKeywordSummary(String keyword, Set<String> items) {
        // 계층적 키워드 분리 (parent/child)
        String parentKeyword = null;
        String childKeyword = keyword;
        if (keyword.contains("/")) {
            String[] parts = keyword.split("/", 2);
            parentKeyword = parts[0];
            childKeyword = parts[1];
        }
        String keywordLower = childKeyword.toLowerCase();
        // 키워드 관련 항목과 무관한 항목을 분리
        List<String> keywordItems = new ArrayList<>();
        List<String> otherItems = new ArrayList<>();
        for (String item : items) {
            if (item.toLowerCase().contains(keywordLower)
                    || item.toLowerCase().contains(keywordLower.replaceAll("s$", ""))) {
                keywordItems.add(item);
            } else {
                otherItems.add(item);
            }
        }
        if (keywordItems.isEmpty()) {
            return new ArrayList<>(items);
        }
        // 접두사 생성 (한국어 메뉴명 사용, 계층적 키워드는 하위 기능만 표시)
        String translatedChild = translateKeyword(childKeyword);
        String prefix;
        if (parentKeyword != null) {
            // 계층 키워드: 하위 기능명만 접두사로 사용 (상위는 메뉴 그룹명에 반영됨)
            prefix = translatedChild;
        } else {
            // 단일 키워드: 메뉴명과 동일하므로 접두사 생략
            prefix = "";
        }
        // 액션 분류
        boolean hasNew = false, hasQuery = false, hasManage = false;
        boolean hasModify = false, hasDelete = false;
        Set<String> specialActions = new LinkedHashSet<>();
        for (String item : keywordItems) {
            String lower = item.toLowerCase();
            if (lower.contains("신규 추가") || lower.contains("기능 추가") || lower.contains("기능 연동")) hasNew = true;
            if (lower.contains("조회")) hasQuery = true;
            if (lower.contains("관리") || lower.contains("정보")) hasManage = true;
            if (lower.contains("수정") || lower.contains("변경") || lower.contains("개선")) hasModify = true;
            if (lower.contains("삭제") || lower.contains("제거")) hasDelete = true;
            if (lower.contains("initializer") || lower.contains("초기화")) specialActions.add("초기화");
            if (lower.contains("동기화")) specialActions.add("동기화");
            if (lower.contains("변환")) specialActions.add("변환");
            if (lower.contains("검증")) specialActions.add("검증");
            if (lower.contains("입력")) specialActions.add("입력");
        }
        // 액션별 개별 항목 생성 (하위 압축에서 패턴 매칭이 가능하도록 일관된 형식)
        List<String> summary = new ArrayList<>();
        if (hasNew || hasManage) {
            summary.add(withPrefix(prefix, "관리 기능 신규 추가"));
        } else if (hasQuery) {
            summary.add(withPrefix(prefix, "조회 기능 추가"));
        } else if (!specialActions.isEmpty()) {
            for (String action : specialActions) {
                summary.add(withPrefix(prefix, action + " 기능 추가"));
            }
            specialActions.clear();
        } else {
            summary.add(withPrefix(prefix, "관련 변경"));
        }
        if (hasModify) {
            summary.add(withPrefix(prefix, "관리 기능 개선"));
        }
        if (hasDelete) {
            summary.add(withPrefix(prefix, "삭제 기능 추가"));
        }
        // 키워드와 무관한 고유 항목은 그대로 추가
        summary.addAll(otherItems);
        return summary;
    }

    /**
     * 같은 키워드를 공유하는 카테고리를 병합합니다.
     *
     * <p>카테고리명에서 괄호 안 키워드를 추출하여 그룹핑합니다.
     * 예: "API (scout)" + "비즈니스 로직 (scout)" + "데이터 모델 (scout)" → "scout"
     * 키워드가 없는 카테고리(예: "설정", "공통 모듈")는 원래 이름을 유지합니다.</p>
     *
     * <p>계층적 키워드(parent/child)는 별도 키로 유지합니다.
     * 예: "비즈니스 로직 (scout/weather)" → "scout/weather"
     * 이렇게 하면 하위 기능의 항목이 상위 기능과 구분되어 LLM이 맥락을 파악할 수 있습니다.</p>
     */
    private Map<String, Set<String>> mergeCategories(Map<String, Set<String>> changesByFeature) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : changesByFeature.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            String categoryKey = extractCategoryKey(entry.getKey());
            merged.computeIfAbsent(categoryKey, k -> new LinkedHashSet<>()).addAll(entry.getValue());
        }
        log.debug("카테고리 병합: {}개 → {}개", changesByFeature.size(), merged.size());
        return merged;
    }

    /**
     * 카테고리명에서 병합 키를 추출하고 정규화합니다.
     *
     * <p>"비즈니스 로직 (scout)" → "scout", "비즈니스 로직 (gs)" → "google",
     * "설정" → "설정"</p>
     */
    private String extractCategoryKey(String categoryName) {
        Matcher matcher = KEYWORD_PATTERN.matcher(categoryName);
        if (matcher.find()) {
            return normalizeKeyword(matcher.group(1));
        }
        return normalizeKeyword(categoryName);
    }
    /**
     * 키워드를 정규화합니다.
     *
     * <p>약어/변형을 정규 키워드로 변환합니다.
     * 계층적 키워드(parent/child)는 각 부분을 개별 정규화하며,
     * 정규화 후 부모와 자식이 동일하면 단일 키워드로 축소합니다.</p>
     *
     * <p>예: "gs" → "google", "google/sheets" → "google", "scout/weather" → "scout/weather"</p>
     */
    private String normalizeKeyword(String keyword) {
        if (keyword.contains("/")) {
            String[] parts = keyword.split("/", 2);
            String parent = KEYWORD_ALIASES.getOrDefault(parts[0].toLowerCase(), parts[0]);
            String child = KEYWORD_ALIASES.getOrDefault(parts[1].toLowerCase(), parts[1]);
            if (parent.equalsIgnoreCase(child)) return parent;
            return parent + "/" + child;
        }
        return KEYWORD_ALIASES.getOrDefault(keyword.toLowerCase(), keyword);
    }

    /**
     * 요약 결과를 메뉴(상위 기능) 기준으로 그룹핑합니다.
     *
     * <p>계층적 키워드(parent/child)의 항목을 부모 키워드 그룹에 병합합니다.
     * 예: "scout" 항목과 "scout/weather" 항목 → "scout" 하나로 통합</p>
     *
     * @param summarized 키워드별 요약 결과
     * @return 메뉴 기준으로 그룹핑된 요약 결과
     */
    private Map<String, List<String>> groupByMenu(Map<String, List<String>> summarized,
                                                      Map<String, String> childToParent) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : summarized.entrySet()) {
            String menuKey = extractMenuKey(entry.getKey(), childToParent);
            grouped.computeIfAbsent(menuKey, k -> new ArrayList<>()).addAll(entry.getValue());
        }
        // 각 메뉴 내에서 압축: 중복 제거 → 유사 패턴 병합 → 액션 접미사 병합
        int totalBefore = grouped.values().stream().mapToInt(List::size).sum();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            List<String> items = deduplicateItems(entry.getValue());
            items = mergeSimilarItems(items);
            items = mergeByActionSuffix(items);
            entry.setValue(items);
        }
        int totalAfter = grouped.values().stream().mapToInt(List::size).sum();
        if (totalBefore != totalAfter) {
            log.debug("메뉴 내 압축: {}건 → {}건", totalBefore, totalAfter);
        }
        return grouped;
    }
    /**
     * 중복 항목을 제거합니다.
     *
     * <p>1단계: 완전 동일 문자열 제거
     * 2단계: 핵심 키워드 집합이 완전 일치하는 항목 중 짧은 것 제거
     * (키워드 부분집합 기반 제거는 다른 스코프의 항목을 잘못 제거할 수 있으므로 사용하지 않음)</p>
     */
    private List<String> deduplicateItems(List<String> items) {
        if (items.size() <= 1) return new ArrayList<>(items);
        // 1단계: 완전 동일 문자열 제거
        List<String> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String item : items) {
            if (seen.add(item.trim())) {
                unique.add(item);
            }
        }
        if (unique.size() <= 1) return unique;
        // 2단계: 키워드 집합 완전 일치인 경우 긴 항목만 유지
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        List<String> result = new ArrayList<>();
        for (String item : sorted) {
            Set<String> itemKeywords = extractContentKeywords(item);
            if (itemKeywords.isEmpty()) {
                result.add(item);
                continue;
            }
            boolean isDuplicate = false;
            for (String existing : result) {
                Set<String> existingKeywords = extractContentKeywords(existing);
                if (existingKeywords.equals(itemKeywords)) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                result.add(item);
            }
        }
        return result;
    }
    /** 항목에서 비교용 핵심 단어를 추출합니다 (순수 기능어만 제거). */
    private Set<String> extractContentKeywords(String item) {
        Set<String> keywords = new LinkedHashSet<>();
        String withoutParens = item.replaceAll("\\([^)]*\\)", "");
        String parenContent = "";
        int parenStart = item.indexOf('(');
        int parenEnd = item.indexOf(')');
        if (parenStart >= 0 && parenEnd > parenStart) {
            parenContent = item.substring(parenStart + 1, parenEnd);
        }
        for (String word : (withoutParens + " " + parenContent).split("[\\s,]+")) {
            String cleaned = word.trim().toLowerCase();
            // 순수 기능어만 제외 (관리/개선/조회 등 의미있는 단어는 유지)
            if (cleaned.length() >= 2
                    && !cleaned.equals("기능") && !cleaned.equals("추가")
                    && !cleaned.equals("포함") && !cleaned.equals("관련")
                    && !cleaned.equals("기능에서")) {
                keywords.add(cleaned);
            }
        }
        return keywords;
    }
    /**
     * 같은 패턴의 항목들을 병합합니다.
     *
     * <p>공통 접두사/접미사를 공유하고 중간 부분만 다른 항목들을
     * 쉼표로 연결하여 하나의 문장으로 합칩니다.</p>
     *
     * <p>예: "스카우팅 스케줄 날씨 필드 추가" + "스카우팅 스케줄 점수 필드 추가"
     * → "스카우팅 스케줄 날씨, 점수 필드 추가"</p>
     *
     * <p>접두사가 없고 접미사만 공유하는 경우도 병합합니다(접미사 2단어 이상).
     * 예: "날씨 정보 관리 추가" + "점수 정보 관리 추가"
     * → "날씨, 점수 정보 관리 추가"</p>
     */
    private List<String> mergeSimilarItems(List<String> items) {
        if (items.size() <= 1) return new ArrayList<>(items);
        List<String> result = new ArrayList<>();
        boolean[] used = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            if (used[i]) continue;
            String[] baseWords = items.get(i).split("\\s+");
            if (baseWords.length < 2) {
                result.add(items.get(i));
                continue;
            }
            List<String> diffParts = new ArrayList<>();
            int bestPrefixLen = -1;
            int bestSuffixLen = -1;
            for (int j = i + 1; j < items.size(); j++) {
                if (used[j]) continue;
                String[] otherWords = items.get(j).split("\\s+");
                int prefixLen = commonPrefixLen(baseWords, otherWords);
                int suffixLen = commonSuffixLen(baseWords, otherWords, prefixLen);
                int baseMidLen = baseWords.length - prefixLen - suffixLen;
                int otherMidLen = otherWords.length - prefixLen - suffixLen;
                // 병합 조건: 차이 부분이 동일 길이(1~3단어)이고
                // (접두사 ≥ 1 AND 접미사 ≥ 1) 또는 (접미사 ≥ 2)
                boolean canMerge = baseMidLen >= 1 && baseMidLen <= 3
                        && baseMidLen == otherMidLen
                        && ((prefixLen >= 1 && suffixLen >= 1) || suffixLen >= 2);
                if (canMerge) {
                    if (bestPrefixLen == -1) {
                        bestPrefixLen = prefixLen;
                        bestSuffixLen = suffixLen;
                        diffParts.add(joinWords(baseWords, prefixLen, baseWords.length - suffixLen));
                    }
                    if (prefixLen == bestPrefixLen && suffixLen == bestSuffixLen) {
                        diffParts.add(joinWords(otherWords, prefixLen, otherWords.length - suffixLen));
                        used[j] = true;
                    }
                }
            }
            if (diffParts.isEmpty()) {
                result.add(items.get(i));
            } else {
                used[i] = true;
                StringBuilder sb = new StringBuilder();
                if (bestPrefixLen > 0) {
                    sb.append(joinWords(baseWords, 0, bestPrefixLen)).append(" ");
                }
                sb.append(joinSubjects(diffParts));
                sb.append(" ").append(joinWords(baseWords, baseWords.length - bestSuffixLen, baseWords.length));
                result.add(sb.toString());
            }
        }
        return result;
    }
    /** 같은 액션 접미사를 공유하는 항목들의 주어부를 쉼표로 병합합니다. */
    private static final String[] ACTION_SUFFIXES = {
            // 사용자 친화적 접미사 (정규화 후 생성됨) - 긴 패턴 우선
            "정보 관리 기능 추가", "정보 조회 기능 추가", "관리 항목 추가",
            // 원본 접미사
            "관리 기능 신규 추가", "관리 기능 개선", "관리 기능 추가",
            "조회/생성 기능 추가", "조회 기능 추가", "삭제 기능 추가",
            "초기화 기능 추가", "동기화 기능 추가", "변환 기능 추가",
            "검증 기능 추가", "입력 기능 추가",
            "기능 신규 추가", "기능 추가", "기능 개선", "시스템 연동", "기능 연동",
            "정보 관리 추가", "처리 기능 연동", "필드 추가", "관련 변경",
    };
    /**
     * 같은 액션 접미사를 공유하는 항목들을 병합합니다.
     *
     * <p>{@code mergeSimilarItems}에서 처리하지 못한 나머지 항목 중,
     * 같은 액션 접미사(예: "기능 추가", "정보 관리 추가")를 공유하는 항목의
     * 주어부를 쉼표로 연결합니다.</p>
     *
     * <p>예: "평가 기능 추가" + "관찰 기능 추가" → "평가, 관찰 기능 추가"</p>
     */
    private List<String> mergeByActionSuffix(List<String> items) {
        if (items.size() <= 1) return new ArrayList<>(items);
        // 액션 접미사별로 그룹핑 (긴 접미사 우선 매칭)
        Map<String, List<String>> bySuffix = new LinkedHashMap<>();
        List<String> unmatched = new ArrayList<>();
        for (String item : items) {
            String matchedSuffix = null;
            for (String suffix : ACTION_SUFFIXES) {
                if (item.endsWith(suffix) && item.length() > suffix.length() + 1) {
                    matchedSuffix = suffix;
                    break;
                }
            }
            if (matchedSuffix != null) {
                String subject = item.substring(0, item.length() - matchedSuffix.length()).trim();
                bySuffix.computeIfAbsent(matchedSuffix, k -> new ArrayList<>()).add(subject);
            } else {
                unmatched.add(item);
            }
        }
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : bySuffix.entrySet()) {
            List<String> subjects = entry.getValue();
            result.add(joinSubjects(subjects) + " " + entry.getKey());
        }
        result.addAll(unmatched);
        return result;
    }
    private int commonPrefixLen(String[] a, String[] b) {
        int len = 0;
        int min = Math.min(a.length, b.length);
        while (len < min && a[len].equals(b[len])) len++;
        return len;
    }
    private int commonSuffixLen(String[] a, String[] b, int prefixLen) {
        int len = 0;
        int maxSuffix = Math.min(a.length, b.length) - prefixLen;
        while (len < maxSuffix && a[a.length - 1 - len].equals(b[b.length - 1 - len])) len++;
        return len;
    }
    private String joinWords(String[] words, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append(" ");
            sb.append(words[i]);
        }
        return sb.toString();
    }
    /**
     * 주어부 목록을 자연스러운 한국어로 연결합니다.
     *
     * <p>1개: "스카우트", 2개: "스카우트 및 선수", 3개 이상: "스카우트, 선수, 팀"</p>
     */
    private String joinSubjects(List<String> subjects) {
        if (subjects.size() == 1) return subjects.get(0);
        if (subjects.size() == 2) return subjects.get(0) + " 및 " + subjects.get(1);
        return String.join(", ", subjects);
    }
    /**
     * 주체 없는 제네릭 액션 패턴 목록
     *
     * <p>메뉴명이 이미 맥락을 제공하므로, 주체 없이 액션만 있는 항목은
     * 정보를 추가하지 않습니다. 이런 항목은 제거 대상입니다.
     * 예: "스카우트" 메뉴 아래 "관리 기능 신규 추가"는 의미 없음</p>
     */
    private static final Set<String> GENERIC_ACTION_PATTERNS = Set.of(
            "관리 기능 신규 추가", "관리 기능 추가", "관리 기능 개선",
            "조회 기능 추가", "삭제 기능 추가",
            "기능 추가", "기능 개선", "기능 신규 추가",
            "기능 연동", "필드 추가", "관련 변경",
            "초기화 기능 추가", "동기화 기능 추가", "변환 기능 추가",
            "검증 기능 추가", "입력 기능 추가", "정보 관리 추가",
            "처리 기능 연동"
    );
    /**
     * 메뉴별 항목에서 주체 없는 제네릭 항목을 제거합니다.
     *
     * <p>메뉴명이 맥락을 제공하므로 "관리 기능 신규 추가" 같은 주체 없는 액션만으로는
     * 의미가 없습니다. 구체적 주체가 있는 항목(예: "날씨 조회 기능 추가")만 유지합니다.</p>
     *
     * <p>메뉴의 모든 항목이 제네릭이면, 해당 메뉴에서 가장 대표적인 항목 하나만
     * "{메뉴명} 기능 추가 및 개선" 형태로 남깁니다.</p>
     */
    private Map<String, List<String>> removeGenericItems(Map<String, List<String>> compressed) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        int removedCount = 0;
        for (Map.Entry<String, List<String>> entry : compressed.entrySet()) {
            String menu = entry.getKey();
            List<String> items = entry.getValue();
            List<String> specific = new ArrayList<>();
            for (String item : items) {
                if (!isGenericItem(item, menu)) {
                    specific.add(item);
                }
            }
            if (specific.isEmpty()) {
                // 모든 항목이 제네릭 → 대표 항목 하나로 축약
                specific.add(menu + " 기능 추가 및 개선");
            }
            removedCount += items.size() - specific.size();
            result.put(menu, specific);
        }
        if (removedCount > 0) {
            log.debug("제네릭 항목 제거: {}건", removedCount);
        }
        return result;
    }
    /**
     * 항목이 주체 없는 제네릭 액션인지 판별합니다.
     *
     * <p>항목이 GENERIC_ACTION_PATTERNS에 정확히 일치하거나,
     * "{메뉴명} {제네릭 패턴}" 형태(메뉴명 반복)인 경우 제네릭으로 판별합니다.</p>
     */
    private boolean isGenericItem(String item, String menuName) {
        String trimmed = item.trim();
        // 정확히 제네릭 패턴과 일치
        if (GENERIC_ACTION_PATTERNS.contains(trimmed)) return true;
        // "{메뉴명} {제네릭 패턴}" 형태 (메뉴명이 반복되므로 의미 없음)
        if (trimmed.startsWith(menuName + " ")) {
            String afterMenu = trimmed.substring(menuName.length() + 1).trim();
            if (GENERIC_ACTION_PATTERNS.contains(afterMenu)) return true;
        }
        return false;
    }
    /**
     * 의도 기반 압축을 위한 항목 분류 패턴 (긴 패턴 우선 매칭)
     *
     * <p>항목에 포함된 키워드를 기반으로 의도 유형을 분류합니다.
     * 같은 의도로 분류된 2개 이상의 항목은 하나의 의도 문장으로 통합됩니다.</p>
     */
    private static final String[][] INTENT_CLASSIFIERS = {
            // {매칭 키워드, 의도 라벨}
            {"필드 추가", "필드 추가"},
            {"정보 관리 추가", "필드 추가"},
            {"관리 기능 신규 추가", "관리 기능 신규 추가"},
            {"관리 기능 개선", "관리 기능 개선"},
            {"관리 기능 추가", "관리 기능 추가"},
            {"조회 기능 추가", "조회 기능 추가"},
            {"삭제 기능 추가", "기능 추가"},
            {"초기화 기능 추가", "기능 추가"},
            {"동기화 기능 추가", "기능 추가"},
            {"변환 기능 추가", "기능 추가"},
            {"검증 기능 추가", "기능 추가"},
            {"입력 기능 추가", "기능 추가"},
            {"기능 신규 추가", "기능 추가"},
            {"기능 추가", "기능 추가"},
            {"기능 개선", "기능 개선"},
            {"기능 연동", "기능 연동"},
            {"처리 기능 연동", "기능 연동"},
            {"관련 변경", "관련 변경"},
    };
    /**
     * 목적 단위 분류 패턴 (긴 패턴 우선 매칭)
     *
     * <p>기능 단위로 정리된 항목을 목적 단위로 재분류합니다.
     * 예: "스카우트 필드 추가" → "데이터 모델 확장",
     * "날씨 조회 기능 추가" → "신규 기능 추가"</p>
     */
    private static final String[][] PURPOSE_CLASSIFIERS = {
            // {매칭 키워드, 목적 카테고리} - 긴 패턴 우선
            {"관리 항목 추가", "데이터 모델 확장"},
            {"정보 관리 추가", "데이터 모델 확장"},
            {"필드 추가", "데이터 모델 확장"},
            {"정보 관리 기능 추가", "신규 기능 추가"},
            {"정보 조회 기능 추가", "신규 기능 추가"},
            {"관리 기능 신규 추가", "신규 기능 추가"},
            {"관리 기능 추가", "신규 기능 추가"},
            {"조회 기능 추가", "신규 기능 추가"},
            {"삭제 기능 추가", "신규 기능 추가"},
            {"입력 기능 추가", "신규 기능 추가"},
            // 엔드유저가 몰라도 되는 시스템 내부 작업 → 기타 변경
            {"초기화 기능 추가", "기타 변경"},
            {"동기화 기능 추가", "기타 변경"},
            {"변환 기능 추가", "기타 변경"},
            {"검증 기능 추가", "기타 변경"},
            {"기능 신규 추가", "신규 기능 추가"},
            {"기능 추가 및 개선", "신규 기능 추가"},
            {"기능 추가", "신규 기능 추가"},
            {"관리 기능 개선", "기능 개선"},
            {"기능 개선", "기능 개선"},
            {"시스템 연동", "외부 연동"},
            {"처리 기능 연동", "외부 연동"},
            {"기능 연동", "외부 연동"},
            {"관련 변경", "기타 변경"},
    };
    /**
     * 목적 그룹 내 액션 접미사 정규화 맵 (사용자 친화적 어휘로 변환)
     *
     * <p>기술 용어를 비개발자가 이해할 수 있는 표현으로 변환하고,
     * 미세하게 다른 액션 접미사를 통일하여 후속 병합이 가능하도록 합니다.
     * 예: "스카우트 필드 추가" → "스카우트 관리 항목 추가",
     * "스카우트 관리 기능 신규 추가" → "스카우트 정보 관리 기능 추가"</p>
     */
    private static final String[][] ACTION_SUFFIX_NORMALIZATIONS = {
            // {원본 접미사, 정규화된 접미사} - 긴 패턴 우선
            {"관리 기능 신규 추가", "정보 관리 기능 추가"},
            {"관리 기능 추가", "정보 관리 기능 추가"},
            {"조회 기능 추가", "정보 조회 기능 추가"},
            {"기능 신규 추가", "기능 추가"},
            {"기능 추가 및 개선", "기능 추가"},
            {"정보 관리 추가", "관리 항목 추가"},
            {"필드 추가", "관리 항목 추가"},
    };
    /**
     * 기능 단위 카테고리를 목적 단위로 재그룹핑합니다.
     *
     * <p>기능별로 분류된 항목(스카우트, 선수, 팀 등)을
     * 목적별(신규 기능 추가, 데이터 모델 확장, 기능 개선 등)로 재분류합니다.
     * 각 항목에는 이미 기능 맥락(스카우트 필드 추가 등)이 포함되어 있으므로
     * 목적 카테고리로 이동해도 의미가 유지됩니다.</p>
     *
     * <p>예:
     * Before: {스카우트: [스카우트 필드 추가, 날씨 조회 기능 추가], 선수: [선수 필드 추가]}
     * After: {데이터 모델 확장: [스카우트 필드 추가, 선수 필드 추가], 신규 기능 추가: [날씨 조회 기능 추가]}</p>
     */
    private Map<String, List<String>> regroupByPurpose(Map<String, List<String>> featureGrouped) {
        Map<String, List<String>> purposeGroups = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : featureGrouped.entrySet()) {
            for (String item : entry.getValue()) {
                String purpose = classifyPurpose(item);
                purposeGroups.computeIfAbsent(purpose, k -> new ArrayList<>()).add(item);
            }
        }
        int totalBefore = purposeGroups.values().stream().mapToInt(List::size).sum();
        // 각 목적 그룹 내: 액션 접미사 정규화 → 중복 제거 → 유사 항목 병합 → 접미사 병합
        for (Map.Entry<String, List<String>> entry : purposeGroups.entrySet()) {
            List<String> items = normalizeActionSuffixes(entry.getValue());
            items = deduplicateItems(items);
            items = mergeSimilarItems(items);
            items = mergeByActionSuffix(items);
            entry.setValue(items);
        }
        // "신규 기능 추가"에서 엔드유저가 몰라도 되는 시스템 내부 항목 걸러내기
        List<String> newFeatures = purposeGroups.get("신규 기능 추가");
        if (newFeatures != null) {
            List<String> internal = new ArrayList<>();
            newFeatures.removeIf(item -> {
                if (isInternalItem(item)) {
                    internal.add(item);
                    return true;
                }
                return false;
            });
            if (!internal.isEmpty()) {
                purposeGroups.computeIfAbsent("기타 변경", k -> new ArrayList<>()).addAll(internal);
                log.debug("신규 기능 추가에서 시스템 내부 항목 {}건 → 기타 변경으로 이동", internal.size());
            }
        }
        // 빈 카테고리 제거
        purposeGroups.entrySet().removeIf(e -> e.getValue().isEmpty());
        int totalAfter = purposeGroups.values().stream().mapToInt(List::size).sum();
        if (totalBefore != totalAfter) {
            log.debug("목적 단위 재그룹핑: {}건 → {}건 ({}개 목적 카테고리)",
                    totalBefore, totalAfter, purposeGroups.size());
        }
        return purposeGroups;
    }
    /**
     * 엔드유저가 몰라도 되는 시스템 내부 작업 키워드
     *
     * <p>이 키워드가 포함된 항목은 "신규 기능 추가"에서 제외됩니다.
     * 초기화, 동기화, 변환, 검증 등은 시스템 내부에서 이루어지는 작업으로
     * 사용자가 직접 사용하는 기능이 아닙니다.</p>
     */
    private static final Set<String> INTERNAL_KEYWORDS = Set.of(
            "초기화", "동기화", "변환", "검증", "배치", "마이그레이션", "캐시", "인덱스", "로깅"
    );
    /**
     * 항목이 시스템 내부 작업인지 판별합니다.
     */
    private boolean isInternalItem(String item) {
        for (String keyword : INTERNAL_KEYWORDS) {
            if (item.contains(keyword)) return true;
        }
        return false;
    }
    /**
     * 항목들의 액션 접미사를 정규화합니다.
     *
     * <p>같은 목적 그룹 내에서 미세하게 다른 액션 접미사를 통일하여
     * 후속 mergeByActionSuffix에서 주어부 병합이 가능하도록 합니다.
     * 예: "스카우트 관리 기능 신규 추가" → "스카우트 관리 기능 추가"</p>
     */
    private List<String> normalizeActionSuffixes(List<String> items) {
        List<String> normalized = new ArrayList<>(items.size());
        for (String item : items) {
            String result = item;
            for (String[] norm : ACTION_SUFFIX_NORMALIZATIONS) {
                // 이중 정규화 방지: 이미 목표 접미사로 끝나면 건너뜀
                if (item.endsWith(norm[0]) && !item.endsWith(norm[1])) {
                    result = item.substring(0, item.length() - norm[0].length()) + norm[1];
                    break;
                }
            }
            normalized.add(result);
        }
        return normalized;
    }
    /**
     * 항목의 목적을 분류합니다.
     *
     * @param item 분류 대상 항목
     * @return 목적 카테고리명
     */
    private String classifyPurpose(String item) {
        for (String[] classifier : PURPOSE_CLASSIFIERS) {
            if (item.contains(classifier[0])) {
                return classifier[1];
            }
        }
        return "기타 변경";
    }
    /**
     * 메뉴 그룹 내 항목들을 의도 기반으로 압축합니다.
     *
     * <p>같은 메뉴 그룹 내에서 동일한 의도(필드 추가, 기능 추가 등)로 분류되는
     * 여러 항목을 하나의 의도 문장으로 통합합니다.
     * 예: "날씨 필드 추가", "보조 포지션 필드 추가", "소속 필드 추가"
     * → "scout 필드 추가"</p>
     */
    private Map<String, List<String>> compressToIntent(Map<String, List<String>> menuGrouped) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        int totalBefore = menuGrouped.values().stream().mapToInt(List::size).sum();
        for (Map.Entry<String, List<String>> entry : menuGrouped.entrySet()) {
            String menu = entry.getKey();
            List<String> items = entry.getValue();
            if (items.size() <= 2) {
                result.put(menu, items);
                continue;
            }
            result.put(menu, buildIntentSummary(menu, items));
        }
        int totalAfter = result.values().stream().mapToInt(List::size).sum();
        if (totalBefore != totalAfter) {
            log.debug("의도 기반 압축: {}건 → {}건", totalBefore, totalAfter);
        }
        return result;
    }
    /**
     * 원본 항목(Set)에 의도 기반 압축을 적용합니다.
     *
     * <p>LLM에 전달할 데이터를 의도 기반으로 사전 압축하여
     * 토큰 사용량을 최소화합니다.</p>
     */
    private Map<String, Set<String>> compressToIntentSet(Map<String, Set<String>> menuMerged) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : menuMerged.entrySet()) {
            String menu = entry.getKey();
            Set<String> items = entry.getValue();
            if (items.size() <= 2) {
                result.put(menu, items);
                continue;
            }
            List<String> compressed = buildIntentSummary(menu, new ArrayList<>(items));
            result.put(menu, new LinkedHashSet<>(compressed));
        }
        return result;
    }
    /**
     * 의도 라벨 → 사용자 친화적 라벨 변환 맵
     *
     * <p>의도 기반 압축 시 기술적 의도 라벨을 비개발자가 이해할 수 있는
     * 표현으로 변환합니다.
     * 예: "필드 추가" → "관리 항목 추가", "관리 기능 추가" → "정보 관리 기능 추가"</p>
     */
    private static final Map<String, String> FRIENDLY_INTENT_LABELS = Map.of(
            "필드 추가", "관리 항목 추가",
            "관리 기능 신규 추가", "정보 관리 기능 추가",
            "관리 기능 추가", "정보 관리 기능 추가",
            "조회 기능 추가", "정보 조회 기능 추가",
            "기능 추가", "관련 기능 추가",
            "기능 연동", "시스템 연동"
    );
    /**
     * 단일 메뉴 그룹의 항목들을 의도별로 분류하여 압축합니다.
     *
     * <p>각 항목을 의도 유형(필드 추가, 기능 추가, 기능 개선 등)으로 분류합니다.
     * 같은 의도로 분류된 2개 이상의 항목은 "{menu} {사용자 친화적 의도}" 형태의
     * 단일 문장으로 통합됩니다. 단일 항목은 원본을 유지합니다.</p>
     *
     * <p>예: "날씨 정보 관리 추가", "보조 포지션 정보 관리 추가", "소속 필드 추가"
     * → "scout 관리 항목 추가" (3개 모두 "필드 추가" 의도 → "관리 항목 추가"로 변환)</p>
     */
    private List<String> buildIntentSummary(String menu, List<String> items) {
        Map<String, List<String>> intentGroups = new LinkedHashMap<>();
        List<String> unclassified = new ArrayList<>();
        for (String item : items) {
            String intent = classifyIntent(item);
            if (intent != null) {
                intentGroups.computeIfAbsent(intent, k -> new ArrayList<>()).add(item);
            } else {
                unclassified.add(item);
            }
        }
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : intentGroups.entrySet()) {
            if (group.getValue().size() >= 2) {
                // 2개 이상이면 사용자 친화적 의도 문장으로 통합
                String friendlyLabel = FRIENDLY_INTENT_LABELS.getOrDefault(
                        group.getKey(), group.getKey());
                result.add(menu + " " + friendlyLabel);
            } else {
                result.addAll(group.getValue());
            }
        }
        result.addAll(unclassified);
        return result;
    }
    /**
     * 항목을 의도 유형으로 분류합니다.
     *
     * @param item 분류 대상 항목
     * @return 의도 라벨 (매칭되지 않으면 null)
     */
    private String classifyIntent(String item) {
        for (String[] classifier : INTENT_CLASSIFIERS) {
            if (item.contains(classifier[0])) {
                return classifier[1];
            }
        }
        return null;
    }
    /**
     * 도메인 매핑이 없는 카테고리(영문 키워드 그대로 남은 것)를 하나의 카테고리로 통합합니다.
     *
     * <p>미분류 카테고리(한국어 번역이 안 된 영문 키워드)의 항목 내용을 분석하여
     * 가장 관련 있는 기존 한국어 메뉴에 흡수합니다. 항목 텍스트에서 한국어 메뉴명이
     * 발견되면 해당 메뉴로 이동하고, 매칭 안 되면 가장 항목이 많은 메뉴에 흡수합니다.
     * 기존 한국어 메뉴가 하나도 없는 경우에만 "기타 기능"을 생성합니다.</p>
     *
     * <p>예: shape → 항목에 "선수" 관련 내용 → "선수" 메뉴로 흡수
     * afc → 매칭 없음, 가장 큰 메뉴 "스카우트"로 흡수</p>
     */
    private Map<String, List<String>> redistributeOrphanCategories(Map<String, List<String>> compressed) {
        Map<String, List<String>> known = new LinkedHashMap<>();
        Map<String, List<String>> orphans = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : compressed.entrySet()) {
            if (isUnknownKeyword(entry.getKey())) {
                orphans.put(entry.getKey(), entry.getValue());
            } else {
                known.put(entry.getKey(), entry.getValue());
            }
        }
        if (orphans.isEmpty()) return compressed;
        if (known.isEmpty()) {
            // 한국어 메뉴가 없으면 모두 기타로 통합
            List<String> allItems = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : orphans.entrySet()) {
                for (String item : e.getValue()) {
                    allItems.add(stripUnknownPrefix(item, e.getKey()));
                }
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            result.put("기타 기능", mergeByActionSuffix(mergeSimilarItems(deduplicateItems(allItems))));
            return result;
        }
        Map<String, List<String>> result = new LinkedHashMap<>(known);
        int redistributed = 0;
        for (Map.Entry<String, List<String>> orphan : orphans.entrySet()) {
            String keyword = orphan.getKey();
            List<String> items = orphan.getValue();
            // 항목 내용에서 가장 관련 있는 메뉴 찾기
            String bestMenu = findBestMenuForItems(items, known.keySet());
            if (bestMenu == null) {
                // 매칭 실패 → 가장 항목이 많은 메뉴에 흡수
                bestMenu = findLargestMenu(result);
            }
            for (String item : items) {
                result.computeIfAbsent(bestMenu, k -> new ArrayList<>())
                        .add(stripUnknownPrefix(item, keyword));
            }
            redistributed += items.size();
        }
        // 재분배 후 각 메뉴 내 중복 제거
        for (Map.Entry<String, List<String>> entry : result.entrySet()) {
            entry.setValue(deduplicateItems(entry.getValue()));
        }
        log.debug("고아 카테고리 재분배: {}개 카테고리 {}건 → 기존 메뉴로 흡수",
                orphans.size(), redistributed);
        return result;
    }
    /**
     * 원본 항목(Set)에 고아 카테고리 재분배를 적용합니다.
     */
    private Map<String, Set<String>> redistributeOrphanCategoriesSet(Map<String, Set<String>> compressed) {
        Map<String, Set<String>> known = new LinkedHashMap<>();
        Map<String, Set<String>> orphans = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : compressed.entrySet()) {
            if (isUnknownKeyword(entry.getKey())) {
                orphans.put(entry.getKey(), entry.getValue());
            } else {
                known.put(entry.getKey(), entry.getValue());
            }
        }
        if (orphans.isEmpty()) return compressed;
        if (known.isEmpty()) {
            Set<String> allItems = new LinkedHashSet<>();
            for (Map.Entry<String, Set<String>> e : orphans.entrySet()) {
                for (String item : e.getValue()) {
                    allItems.add(stripUnknownPrefix(item, e.getKey()));
                }
            }
            Map<String, Set<String>> result = new LinkedHashMap<>();
            result.put("기타 기능", allItems);
            return result;
        }
        Map<String, Set<String>> result = new LinkedHashMap<>(known);
        for (Map.Entry<String, Set<String>> orphan : orphans.entrySet()) {
            String keyword = orphan.getKey();
            Set<String> items = orphan.getValue();
            String bestMenu = findBestMenuForItems(new ArrayList<>(items), known.keySet());
            if (bestMenu == null) {
                bestMenu = findLargestMenuSet(result);
            }
            for (String item : items) {
                result.computeIfAbsent(bestMenu, k -> new LinkedHashSet<>())
                        .add(stripUnknownPrefix(item, keyword));
            }
        }
        return result;
    }
    /**
     * 항목 내용에서 가장 관련 있는 메뉴를 찾습니다.
     *
     * <p>항목 텍스트에 기존 메뉴명(한국어)이 포함되어 있으면 해당 메뉴를 반환합니다.
     * 여러 메뉴에 매칭되면 가장 많이 매칭된 메뉴를 반환합니다.</p>
     */
    private String findBestMenuForItems(List<String> items, Set<String> knownMenus) {
        Map<String, Integer> menuScores = new LinkedHashMap<>();
        for (String item : items) {
            for (String menu : knownMenus) {
                if (item.contains(menu)) {
                    menuScores.merge(menu, 1, Integer::sum);
                }
            }
        }
        if (menuScores.isEmpty()) return null;
        return menuScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
    /**
     * 가장 항목이 많은 메뉴를 반환합니다.
     */
    private String findLargestMenu(Map<String, List<String>> menus) {
        return menus.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey)
                .orElse("기타 기능");
    }
    private String findLargestMenuSet(Map<String, Set<String>> menus) {
        return menus.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey)
                .orElse("기타 기능");
    }
    /**
     * 키워드가 도메인에서 의미를 파악할 수 없는 미지 키워드인지 확인합니다.
     *
     * <p>한국어가 포함되어 있거나, KNOWN_DOMAIN_KEYWORDS에 등록된 키워드는
     * 의미가 파악 가능하므로 false를 반환합니다.
     * "shape", "afc" 등 도메인 매핑이 없는 영문 키워드만 true입니다.</p>
     */
    private boolean isUnknownKeyword(String keyword) {
        if (keyword.matches(".*[가-힣].*")) return false;
        return !KNOWN_DOMAIN_KEYWORDS.contains(keyword.toLowerCase());
    }
    /**
     * 항목 텍스트에서 미지 키워드 접두사를 제거합니다.
     *
     * <p>비개발자에게 무의미한 키워드(shape, afc 등)를 항목에서 제거하고
     * 액션 부분만 유지합니다.
     * 예: "shape 관리 기능 신규 추가" → "관리 기능 신규 추가"</p>
     */
    private String stripUnknownPrefix(String item, String keyword) {
        if (item.toLowerCase().startsWith(keyword.toLowerCase() + " ")) {
            String stripped = item.substring(keyword.length() + 1).trim();
            if (!stripped.isEmpty()) return stripped;
        }
        return item;
    }
    /**
     * 원본 항목(Set)을 메뉴(상위 기능) 기준으로 그룹핑합니다.
     *
     * <p>LLM에 전달할 데이터를 메뉴 단위로 묶어 LLM이 관련 항목을 함께 보고
     * 상위 기능 맥락에서 압축할 수 있도록 합니다.</p>
     *
     * @param merged 키워드별 원본 항목
     * @return 메뉴 기준으로 그룹핑된 원본 항목
     */
    private Map<String, Set<String>> groupByMenuSet(Map<String, Set<String>> merged,
                                                        Map<String, String> childToParent) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : merged.entrySet()) {
            String menuKey = extractMenuKey(entry.getKey(), childToParent);
            grouped.computeIfAbsent(menuKey, k -> new LinkedHashSet<>()).addAll(entry.getValue());
        }
        return grouped;
    }
    /**
     * 키워드에서 메뉴 키를 추출합니다.
     *
     * <p>계층적 키워드(parent/child)는 부모 키워드를 반환합니다.
     * 단일 키워드는 그대로 반환합니다.</p>
     *
     * @param keyword 원본 키워드 (예: "scout/weather", "player")
     * @return 메뉴 키 (예: "scout", "player")
     */
    private String extractMenuKey(String keyword, Map<String, String> childToParent) {
        String normalized = normalizeKeyword(keyword);
        if (normalized.contains("/")) {
            return translateKeyword(normalized.split("/")[0]);
        }
        // 직접 번역 가능하면 사용
        String translated = KEYWORD_TRANSLATIONS.get(normalized.toLowerCase());
        if (translated != null) return translated;
        // 다른 곳에서 부모가 있는 자식 키워드면 부모 메뉴로 흡수
        String parent = childToParent.get(normalized.toLowerCase());
        if (parent != null) return translateKeyword(parent);
        // 번역 불가, 부모도 없음 - 원본 유지 (나중에 재분배 대상)
        return normalized;
    }
    /**
     * 키워드를 한국어 메뉴명으로 번역합니다.
     *
     * <p>KEYWORD_TRANSLATIONS에 등록된 키워드는 한국어 메뉴명으로 변환됩니다.
     * 등록되지 않은 키워드는 원본을 그대로 반환합니다.</p>
     */
    private String translateKeyword(String keyword) {
        String translated = KEYWORD_TRANSLATIONS.get(keyword.toLowerCase());
        return translated != null ? translated : keyword;
    }
    /**
     * 접두사와 액션을 결합합니다. 접두사가 비어있으면 액션만 반환합니다.
     */
    private String withPrefix(String prefix, String action) {
        return prefix.isEmpty() ? action : prefix + " " + action;
    }
    /**
     * 계층 키워드에서 자식→부모 관계 맵을 구축합니다.
     *
     * <p>입력 키워드 셋에서 "parent/child" 형태의 계층 키워드를 찾아
     * child → parent 매핑을 생성합니다. 이를 통해 단독으로 등장하는 키워드도
     * 다른 곳에서 부모가 확인되면 해당 부모 메뉴로 흡수할 수 있습니다.</p>
     *
     * <p>예: keywords에 "scout/shape", "scout/weather" 존재
     * → {"shape" → "scout", "weather" → "scout"}</p>
     */
    private Map<String, String> buildChildToParentMap(Set<String> keywords) {
        Map<String, String> childToParent = new LinkedHashMap<>();
        for (String keyword : keywords) {
            String normalized = normalizeKeyword(keyword);
            if (normalized.contains("/")) {
                String[] parts = normalized.split("/", 2);
                childToParent.putIfAbsent(parts[1].toLowerCase(), parts[0]);
            }
        }
        if (!childToParent.isEmpty()) {
            log.debug("자식→부모 맵 구축: {}", childToParent);
        }
        return childToParent;
    }
    private Map<String, List<String>> toListMap(Map<String, Set<String>> setMap) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : setMap.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return result;
    }

    private String buildUserPrompt(Map<String, Set<String>> mergedCategories) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 기능별 변경 내용\n\n");
        for (Map.Entry<String, Set<String>> entry : mergedCategories.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            sb.append("### ").append(entry.getKey()).append("\n");
            for (String desc : entry.getValue()) {
                sb.append("- ").append(desc).append("\n");
            }
            sb.append("\n");
        }
        sb.append("---\n위 내용을 카테고리별 핵심 변경 요약 JSON 객체로 압축해 주세요.");
        return sb.toString();
    }

    private Map<String, List<String>> parseResponse(String response) {
        try {
            String json = extractJson(response);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("LLM 압축 응답 JSON 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int objStart = trimmed.indexOf('{');
        int objEnd = trimmed.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return trimmed.substring(objStart, objEnd + 1);
        }
        return trimmed;
    }

    private void savePromptFile(String timestamp, String suffix, String content) {
        try {
            Path dir = Paths.get(promptOutputDir);
            Files.createDirectories(dir);
            Path filePath = dir.resolve("llm_" + suffix + "_" + timestamp + ".md");
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.debug("LLM 파일 저장: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("LLM 파일 저장 실패: {}", e.getMessage());
        }
    }
}
