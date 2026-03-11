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
            "이것을 비개발자(경영진, 고객사)가 읽을 보고서에 들어갈 **변경 목적 중심 요약**으로 압축해 주세요.\n\n" +
            "## 핵심 원칙: '어떤 목적으로 코드를 수정했는지'를 메인으로 작성하세요\n" +
            "단순히 '무엇을 추가/수정했다'가 아니라, '왜 이 변경이 필요했는지'를 중심으로 서술하세요.\n" +
            "나쁜 예: '스카우트 관리 기능 신규 추가' (무엇을 했는지만 서술)\n" +
            "좋은 예: '스카우트 후보 선수 정보를 체계적으로 관리하기 위한 기능 신규 추가' (목적 + 행위)\n" +
            "나쁜 예: '날씨 조회 기능 추가' (행위만 서술)\n" +
            "좋은 예: '스카우팅 스케줄에서 날씨 조건을 사전에 확인할 수 있도록 날씨 조회 기능 추가' (목적 + 행위)\n\n" +
            "## 메뉴 기준 그룹핑\n" +
            "같은 메뉴에 속하는 하위 기능들은 반드시 하나의 카테고리로 통합하세요.\n" +
            "하위 기능은 상위 기능의 맥락에서 서술하고, 독립 카테고리로 분리하지 마세요.\n\n" +
            "## 압축 규칙\n" +
            "1. **카테고리당 최대 1~2개 문장**: 같은 키워드에 대한 항목이 여러 개면 반드시 하나로 통합\n" +
            "2. 기술 용어(Repository, Service, Controller, Entity, 필드, 메서드 등)를 사용하지 마세요\n" +
            "3. 파일명, 클래스명, 패키지 경로를 언급하지 마세요\n" +
            "4. '~하기 위한 ~기능 추가', '~할 수 있도록 ~기능 개선' 형태의 **목적 포함** 문장으로 작성\n" +
            "5. 카테고리명은 비개발자가 이해할 수 있는 업무 관점의 한국어 이름으로 변환하세요\n" +
            "   예: 'scout' → '스카우트 관리', 'evaluation' → '선수 평가', 'weather' → '날씨 정보'\n" +
            "6. 항목이 모두 다른 카테고리에 병합되어 비게 된 카테고리는 제외하세요\n" +
            "7. 같은 문장 패턴에서 일부만 다른 항목은 쉼표로 병합하세요.\n" +
            "   예: '날씨 필드 추가' + '점수 필드 추가' → '날씨, 점수 필드 추가'\n" +
            "8. 같은 대상에 대한 세부 변경(필드 추가, 옵션 변경, 데이터 분리 등)은 의도 단위로 통합하세요.\n" +
            "   예: '날씨 필드 추가' + '보조 포지션 선택 기능' + '소속 분리' → '영입후보 관리 항목 추가'\n" +
            "   개별 필드나 옵션을 나열하지 말고, 해당 변경의 상위 의도로 한 문장에 압축하세요.\n\n" +
            "## 응답 형식\n" +
            "반드시 아래 JSON 객체 형식으로만 응답하세요. 다른 텍스트를 포함하지 마세요.\n" +
            "{\n" +
            "  \"카테고리명\": [\"변경 목적 + 요약 1\"],\n" +
            "  \"카테고리명\": [\"변경 목적 + 요약 1\"]\n" +
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
            "scout", "player", "team", "match", "league", "season",
            "evaluation", "observation", "assessment", "candidate",
            "position", "transfer", "contract", "salary", "agent",
            "schedule", "event", "note", "tag", "category", "priority",
            "report", "document", "template", "notification", "message",
            "comment", "user", "member", "admin", "role", "permission",
            "auth", "profile", "setting", "config", "dashboard",
            "statistics", "summary", "history", "log", "record",
            "status", "type", "level", "content", "block", "page",
            "image", "file", "attachment", "weather", "google",
            "project", "task", "issue", "customer", "client", "company"
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
     * 4) LLM이 있으면 의도 기반으로 사전 압축된 데이터로 추가 압축, 없으면 3.6단계 결과 반환</p>
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
        // 2단계: 키워드 기반 요약 압축 (LLM 없어도 동작)
        Map<String, List<String>> summarized = summarizeByKeyword(merged);
        // 3단계: 메뉴 기준 그룹핑 (계층적 키워드를 상위 메뉴로 병합)
        Map<String, List<String>> menuGrouped = groupByMenu(summarized);
        // 3.5단계: 의도 기반 압축 (같은 의도의 세부 항목을 하나의 의도 문장으로 통합)
        Map<String, List<String>> intentCompressed = compressToIntent(menuGrouped);
        // 3.6단계: 미번역 카테고리 통합 (영문 키워드 그대로 남은 카테고리를 하나로 병합)
        Map<String, List<String>> finalCompressed = mergeUntranslatedCategories(intentCompressed);
        if (llmClient == null) {
            int totalBefore = merged.values().stream().mapToInt(Set::size).sum();
            int totalAfter = finalCompressed.values().stream().mapToInt(List::size).sum();
            log.debug("LLM 비활성화 - 의도 기반 압축 적용 ({}건 → {}건)", totalBefore, totalAfter);
            return wrapWithPurpose(finalCompressed);
        }
        // 4단계: LLM 추가 압축 (의도 기반으로 사전 압축된 데이터 전달)
        Map<String, Set<String>> menuMerged = groupByMenuSet(merged);
        Map<String, Set<String>> intentMergedForLlm = compressToIntentSet(menuMerged);
        Map<String, Set<String>> finalMergedForLlm = mergeUntranslatedCategoriesSet(intentMergedForLlm);
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
                log.warn("LLM 압축 결과가 비어있음 - 의도 기반 압축 반환");
                return finalCompressed;
            }
            int compressedItems = compressed.values().stream().mapToInt(List::size).sum();
            log.info("LLM 변경 설명 압축 완료 - {}개 메뉴 {}건 → {}개 카테고리 {}건",
                    finalMergedForLlm.size(), totalItems, compressed.size(), compressedItems);
            return compressed;
        } catch (Exception e) {
            log.warn("LLM 변경 설명 압축 실패 - 의도 기반 압축 반환. 원인: {}", e.getMessage());
            return finalCompressed;
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
        // 접두사 생성 (계층적 키워드는 상위 맥락 포함)
        String prefix = parentKeyword != null
                ? parentKeyword + " 기능에서 " + childKeyword
                : childKeyword;
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
            summary.add(prefix + " 관리 기능 신규 추가");
        } else if (hasQuery) {
            summary.add(prefix + " 조회 기능 추가");
        } else if (!specialActions.isEmpty()) {
            for (String action : specialActions) {
                summary.add(prefix + " " + action + " 기능 추가");
            }
            specialActions.clear();
        } else {
            summary.add(prefix + " 관련 변경");
        }
        if (hasModify) {
            summary.add(prefix + " 관리 기능 개선");
        }
        if (hasDelete) {
            summary.add(prefix + " 삭제 기능 추가");
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
    private Map<String, List<String>> groupByMenu(Map<String, List<String>> summarized) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : summarized.entrySet()) {
            String menuKey = extractMenuKey(entry.getKey());
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
                sb.append(String.join(", ", diffParts));
                sb.append(" ").append(joinWords(baseWords, baseWords.length - bestSuffixLen, baseWords.length));
                result.add(sb.toString());
            }
        }
        return result;
    }
    /** 같은 액션 접미사를 공유하는 항목들의 주어부를 쉼표로 병합합니다. */
    private static final String[] ACTION_SUFFIXES = {
            "관리 기능 신규 추가", "관리 기능 개선", "관리 기능 추가",
            "조회/생성 기능 추가", "조회 기능 추가", "삭제 기능 추가",
            "초기화 기능 추가", "동기화 기능 추가", "변환 기능 추가",
            "검증 기능 추가", "입력 기능 추가",
            "기능 신규 추가", "기능 추가", "기능 개선", "기능 연동",
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
            // 단일 항목이면 그대로, 2개 이상이면 쉼표 병합
            if (subjects.size() == 1) {
                result.add(subjects.get(0) + " " + entry.getKey());
            } else {
                result.add(String.join(", ", subjects) + " " + entry.getKey());
            }
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
    /** 행위 접미사 → 목적 중심 접미사 변환 패턴 (긴 패턴 우선) */
    private static final String[][] PURPOSE_PATTERNS = {
            {"관리 기능 신규 추가", "정보를 체계적으로 관리하기 위한 기능 신규 추가"},
            {"관리 기능 개선", "관리 프로세스를 개선하기 위한 기능 수정"},
            {"관리 기능 추가", "정보를 효율적으로 관리하기 위한 기능 추가"},
            {"조회 기능 추가", "정보를 효율적으로 조회하기 위한 기능 추가"},
            {"삭제 기능 추가", "불필요한 데이터를 정리하기 위한 삭제 기능 추가"},
            {"초기화 기능 추가", "데이터 초기 설정을 위한 초기화 기능 추가"},
            {"동기화 기능 추가", "데이터 일관성 유지를 위한 동기화 기능 추가"},
            {"변환 기능 추가", "데이터 형식 변환을 위한 기능 추가"},
            {"검증 기능 추가", "데이터 정합성 확보를 위한 검증 기능 추가"},
            {"입력 기능 추가", "정보 입력을 지원하기 위한 기능 추가"},
            {"기능 신규 추가", "업무 효율화를 위한 기능 신규 추가"},
            {"기능 추가", "업무 지원을 위한 기능 추가"},
            {"기능 개선", "사용성 향상을 위한 기능 개선"},
            {"기능 연동", "외부 시스템 연동을 위한 기능 추가"},
            {"정보 관리 추가", "정보를 관리하기 위한 기능 추가"},
            {"처리 기능 연동", "처리 자동화를 위한 기능 연동"},
            {"필드 추가", "관리 항목 확장을 위한 데이터 항목 추가"},
            {"관련 변경", "안정성 향상을 위한 관련 기능 수정"},
    };
    /**
     * 압축 결과의 각 항목에 목적 프레이밍을 적용합니다.
     *
     * <p>행위 중심 서술("~기능 추가")을 목적 중심 서술("~하기 위한 기능 추가")로 변환합니다.
     * 예: "scout 관리 기능 신규 추가" → "scout 정보를 체계적으로 관리하기 위한 기능 신규 추가"</p>
     */
    private Map<String, List<String>> wrapWithPurpose(Map<String, List<String>> compressed) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : compressed.entrySet()) {
            List<String> purposeItems = new ArrayList<>();
            for (String item : entry.getValue()) {
                purposeItems.add(addPurposeContext(item));
            }
            result.put(entry.getKey(), purposeItems);
        }
        return result;
    }
    /**
     * 단일 항목에 목적 접미사를 적용합니다.
     *
     * <p>항목이 알려진 행위 접미사로 끝나면, 해당 접미사를 목적 포함 접미사로 교체합니다.
     * 매칭되지 않는 항목은 원본을 그대로 반환합니다.</p>
     */
    private String addPurposeContext(String item) {
        for (String[] pattern : PURPOSE_PATTERNS) {
            if (item.endsWith(pattern[0])) {
                String subject = item.substring(0, item.length() - pattern[0].length()).trim();
                if (!subject.isEmpty()) {
                    return subject + " " + pattern[1];
                }
                return pattern[1];
            }
        }
        return item;
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
     * 단일 메뉴 그룹의 항목들을 의도별로 분류하여 압축합니다.
     *
     * <p>각 항목을 의도 유형(필드 추가, 기능 추가, 기능 개선 등)으로 분류합니다.
     * 같은 의도로 분류된 2개 이상의 항목은 "{menu} {의도}" 형태의
     * 단일 문장으로 통합됩니다. 단일 항목은 원본을 유지합니다.</p>
     *
     * <p>예: "날씨 정보 관리 추가", "보조 포지션 정보 관리 추가", "소속 필드 추가"
     * → "scout 필드 추가" (3개 모두 "필드 추가" 의도로 분류)</p>
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
                // 2개 이상이면 의도 문장으로 통합
                result.add(menu + " " + group.getKey());
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
     * <p>비개발자에게 무의미한 영문 키워드(shape, afc 등)를 카테고리명과 항목 텍스트에서
     * 모두 제거하고, 액션(관리 기능 추가 등)만 남겨 "기타 기능"으로 병합합니다.
     * 키워드 자체가 아니라 '어디에 종속되는지'가 중요하므로, 맥락을 알 수 없는
     * 키워드는 출력하지 않습니다.</p>
     *
     * <p>예: shape → "shape 관리 기능 신규 추가", afc → "afc 관리 기능 신규 추가"
     * → "기타 기능" → "관리 기능 신규 추가" (키워드 제거, 액션만 유지)</p>
     */
    private Map<String, List<String>> mergeUntranslatedCategories(Map<String, List<String>> compressed) {
        Map<String, List<String>> known = new LinkedHashMap<>();
        List<String> unknownItems = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : compressed.entrySet()) {
            if (isUnknownKeyword(entry.getKey())) {
                String keyword = entry.getKey();
                for (String item : entry.getValue()) {
                    unknownItems.add(stripUnknownPrefix(item, keyword));
                }
            } else {
                known.put(entry.getKey(), entry.getValue());
            }
        }
        if (unknownItems.isEmpty()) {
            return compressed;
        }
        List<String> merged = mergeByActionSuffix(mergeSimilarItems(deduplicateItems(unknownItems)));
        Map<String, List<String>> result = new LinkedHashMap<>(known);
        result.put("기타 기능", merged);
        log.debug("미지 카테고리 통합: {}개 → '기타 기능' ({}건)", compressed.size() - known.size(), merged.size());
        return result;
    }
    /**
     * 원본 항목(Set)에 미지 카테고리 통합을 적용합니다.
     *
     * <p>LLM에 전달할 데이터에서 미지 키워드 카테고리의 키워드 접두사를 제거하고
     * "기타 기능"으로 병합합니다.</p>
     */
    private Map<String, Set<String>> mergeUntranslatedCategoriesSet(Map<String, Set<String>> compressed) {
        Map<String, Set<String>> known = new LinkedHashMap<>();
        Set<String> unknownItems = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : compressed.entrySet()) {
            if (isUnknownKeyword(entry.getKey())) {
                String keyword = entry.getKey();
                for (String item : entry.getValue()) {
                    unknownItems.add(stripUnknownPrefix(item, keyword));
                }
            } else {
                known.put(entry.getKey(), entry.getValue());
            }
        }
        if (unknownItems.isEmpty()) {
            return compressed;
        }
        Map<String, Set<String>> result = new LinkedHashMap<>(known);
        result.put("기타 기능", unknownItems);
        return result;
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
    private Map<String, Set<String>> groupByMenuSet(Map<String, Set<String>> merged) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : merged.entrySet()) {
            String menuKey = extractMenuKey(entry.getKey());
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
    private String extractMenuKey(String keyword) {
        String normalized = normalizeKeyword(keyword);
        if (normalized.contains("/")) {
            return normalized.split("/")[0];
        }
        return normalized;
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
