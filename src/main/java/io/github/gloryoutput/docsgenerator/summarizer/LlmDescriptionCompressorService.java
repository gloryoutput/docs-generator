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
            "이것을 비개발자(경영진, 고객사)가 읽을 보고서에 들어갈 **핵심 변경 요약**으로 압축해 주세요.\n\n" +
            "## 핵심 원칙: 메뉴(상위 기능) 기준으로 비슷한 것들끼리 묶어서 압축하세요\n" +
            "같은 메뉴에 속하는 하위 기능들은 반드시 하나의 카테고리로 통합하세요.\n" +
            "예시: 'scout 관리 기능', 'weather 기능 추가', 'weather 조회', 'observation 관리' 등이\n" +
            "모두 같은 카테고리에 있으면 → '스카우트 관리' 카테고리 아래에\n" +
            "'스카우트 관리 기능 신규 추가', '스카우팅 스케줄에서 날씨 입력 기능 추가' 등으로 정리\n\n" +
            "## 압축 규칙\n" +
            "1. **카테고리당 최대 1~2개 문장**: 같은 키워드에 대한 항목이 여러 개면 반드시 하나로 통합\n" +
            "2. 기술 용어(Repository, Service, Controller, Entity, 필드, 메서드 등)를 사용하지 마세요\n" +
            "3. 파일명, 클래스명, 패키지 경로를 언급하지 마세요\n" +
            "4. '~기능 추가', '~처리 방식 개선', '~관리 기능 확장' 형태의 간결한 문장으로 작성\n" +
            "5. 카테고리명은 비개발자가 이해할 수 있는 업무 관점의 한국어 이름으로 변환하세요\n" +
            "   예: 'scout' → '스카우트 관리', 'evaluation' → '선수 평가', 'weather' → '날씨 정보'\n" +
            "6. 항목이 모두 다른 카테고리에 병합되어 비게 된 카테고리는 제외하세요\n" +
            "7. '조회 기능 추가', '관리 기능 추가', '데이터 관리 추가'가 모두 있으면 → '관리 기능 신규 추가'로 통합\n" +
            "   같은 문장 패턴에서 일부만 다른 항목은 쉼표로 병합하세요.\n" +
            "   예: '날씨 필드 추가' + '점수 필드 추가' → '날씨, 점수 필드 추가'\n" +
            "   예: '선수 조회 기능 추가' + '팀 조회 기능 추가' → '선수, 팀 조회 기능 추가'\n" +
            "8. 하나의 카테고리 안에 여러 하위 기능(예: scout 관련 항목 + weather 관련 항목)이 섞여 있으면,\n" +
            "   하위 기능은 상위 기능의 맥락에서 서술하세요. 하위 기능을 독립 카테고리로 분리하지 마세요.\n" +
            "   나쁜 예: '날씨 관리 기능 신규 추가' (하위 기능을 독립적으로 서술)\n" +
            "   좋은 예: '스카우팅 스케줄에서 날씨 입력 기능 추가' (상위 기능 맥락에서 서술)\n\n" +
            "## 응답 형식\n" +
            "반드시 아래 JSON 객체 형식으로만 응답하세요. 다른 텍스트를 포함하지 마세요.\n" +
            "{\n" +
            "  \"카테고리명\": [\"변경 요약 1\"],\n" +
            "  \"카테고리명\": [\"변경 요약 1\"]\n" +
            "}";
    /** 카테고리에서 괄호 안 키워드를 추출하는 패턴: "비즈니스 로직 (scout)" → "scout" */
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("\\(([^)]+)\\)");
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
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
     * 4) LLM이 있으면 메뉴 기준 그룹핑된 데이터로 추가 압축, 없으면 3단계 결과 반환</p>
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
        if (llmClient == null) {
            int totalBefore = merged.values().stream().mapToInt(Set::size).sum();
            int totalAfter = menuGrouped.values().stream().mapToInt(List::size).sum();
            log.debug("LLM 비활성화 - 키워드 기반 요약 적용 ({}건 → {}건)", totalBefore, totalAfter);
            return menuGrouped;
        }
        // 4단계: LLM 추가 압축 (메뉴 기준으로 그룹핑된 원본 데이터 전달)
        Map<String, Set<String>> menuMerged = groupByMenuSet(merged);
        int totalItems = menuMerged.values().stream().mapToInt(Set::size).sum();
        try {
            String userPrompt = buildUserPrompt(menuMerged);
            String timestamp = LocalDateTime.now().format(FILE_FORMATTER);
            savePromptFile(timestamp, "compress_input", SYSTEM_PROMPT + "\n\n---\n\n" + userPrompt);
            log.info("LLM 변경 설명 압축 시작 - {}개 메뉴, {}건 항목", menuMerged.size(), totalItems);
            String result = llmClient.chat(SYSTEM_PROMPT, userPrompt);
            savePromptFile(timestamp, "compress_output", result);
            Map<String, List<String>> compressed = parseResponse(result);
            if (compressed == null || compressed.isEmpty()) {
                log.warn("LLM 압축 결과가 비어있음 - 메뉴 기준 요약 반환");
                return menuGrouped;
            }
            int compressedItems = compressed.values().stream().mapToInt(List::size).sum();
            log.info("LLM 변경 설명 압축 완료 - {}개 메뉴 {}건 → {}개 카테고리 {}건",
                    menuMerged.size(), totalItems, compressed.size(), compressedItems);
            return compressed;
        } catch (Exception e) {
            log.warn("LLM 변경 설명 압축 실패 - 메뉴 기준 요약 반환. 원인: {}", e.getMessage());
            return menuGrouped;
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
        List<String> summary = new ArrayList<>();
        if (!keywordItems.isEmpty()) {
            // 액션 패턴 감지
            boolean hasNew = false;
            boolean hasQuery = false;
            boolean hasManage = false;
            boolean hasModify = false;
            boolean hasDelete = false;
            Set<String> extraActions = new LinkedHashSet<>();
            for (String item : keywordItems) {
                if (item.contains("신규 추가") || item.contains("기능 추가") || item.contains("기능 연동")) hasNew = true;
                if (item.contains("조회")) hasQuery = true;
                if (item.contains("관리") || item.contains("정보")) hasManage = true;
                if (item.contains("수정") || item.contains("변경") || item.contains("개선")) hasModify = true;
                if (item.contains("삭제") || item.contains("제거")) hasDelete = true;
                if (item.contains("initializer") || item.contains("초기화")) extraActions.add("초기화");
                if (item.contains("동기화")) extraActions.add("동기화");
                if (item.contains("변환")) extraActions.add("변환");
                if (item.contains("검증")) extraActions.add("검증");
            }
            // 요약 문장 생성
            StringBuilder sb = new StringBuilder();
            if (parentKeyword != null) {
                // 계층적 키워드: 상위 기능 맥락에서 하위 기능 서술
                sb.append(parentKeyword).append(" 기능에서 ").append(childKeyword);
            } else {
                sb.append(childKeyword);
            }
            if (hasNew) {
                sb.append(" 관리 기능 신규 추가");
            } else if (hasModify) {
                sb.append(" 관리 기능 개선");
            } else if (hasManage) {
                sb.append(" 관리 기능 추가");
            } else if (hasQuery) {
                sb.append(" 조회 기능 추가");
            } else {
                sb.append(" 관련 변경");
            }
            // 부가 기능 괄호 표기
            List<String> subFeatures = new ArrayList<>();
            if (hasQuery && hasNew) subFeatures.add("조회");
            if (hasManage && hasNew) subFeatures.add("데이터 관리");
            if (hasDelete) subFeatures.add("삭제");
            subFeatures.addAll(extraActions);
            if (!subFeatures.isEmpty()) {
                sb.append(" (").append(String.join(", ", subFeatures)).append(" 포함)");
            }
            summary.add(sb.toString());
        }
        // 키워드와 무관한 고유 항목은 그대로 추가 (groupByMenu에서 유사 패턴 병합 처리)
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
     * 카테고리명에서 병합 키를 추출합니다.
     *
     * <p>"비즈니스 로직 (scout)" → "scout", "설정" → "설정"</p>
     */
    private String extractCategoryKey(String categoryName) {
        Matcher matcher = KEYWORD_PATTERN.matcher(categoryName);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return categoryName;
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
     * 의미적으로 중복되거나 다른 항목에 포함되는 항목을 제거합니다.
     *
     * <p>한 항목의 핵심 단어가 다른 항목에 모두 포함되어 있으면 중복으로 판단합니다.
     * 예: "scout 조회 기능 추가"는 "scout 관리 기능 신규 추가 (조회, 데이터 관리 포함)"에 포함</p>
     */
    private List<String> deduplicateItems(List<String> items) {
        if (items.size() <= 1) return new ArrayList<>(items);
        // 긴 항목 우선 (더 상세한 항목이 남도록)
        List<String> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        List<String> result = new ArrayList<>();
        for (String item : sorted) {
            Set<String> itemKeywords = extractContentKeywords(item);
            boolean isDuplicate = false;
            for (String existing : result) {
                Set<String> existingKeywords = extractContentKeywords(existing);
                // item의 핵심 단어가 existing에 모두 포함되면 중복
                if (existingKeywords.containsAll(itemKeywords)) {
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
    /** 항목에서 비교용 핵심 단어를 추출합니다 (조사/접미사 등 제거). */
    private Set<String> extractContentKeywords(String item) {
        Set<String> keywords = new LinkedHashSet<>();
        // 괄호 내용 분리하여 포함
        String withoutParens = item.replaceAll("\\([^)]*\\)", "");
        String parenContent = "";
        int parenStart = item.indexOf('(');
        int parenEnd = item.indexOf(')');
        if (parenStart >= 0 && parenEnd > parenStart) {
            parenContent = item.substring(parenStart + 1, parenEnd);
        }
        for (String word : (withoutParens + " " + parenContent).split("[\\s,]+")) {
            String cleaned = word.trim().toLowerCase();
            // 의미 없는 단어 제외
            if (cleaned.length() >= 2
                    && !cleaned.equals("기능") && !cleaned.equals("추가") && !cleaned.equals("신규")
                    && !cleaned.equals("관리") && !cleaned.equals("포함") && !cleaned.equals("관련")
                    && !cleaned.equals("변경") && !cleaned.equals("개선") && !cleaned.equals("기능에서")) {
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
                // 병합 조건: 차이 부분이 1~3단어이고
                // (접두사 ≥ 1 AND 접미사 ≥ 1) 또는 (접미사 ≥ 2)
                boolean canMerge = baseMidLen >= 1 && baseMidLen <= 3
                        && otherMidLen >= 1 && otherMidLen <= 3
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
            "조회/생성 기능 추가", "조회 기능 추가", "기능 신규 추가",
            "기능 추가", "기능 개선", "기능 연동",
            "정보 관리 추가", "처리 기능 연동", "필드 추가",
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
        if (keyword.contains("/")) {
            return keyword.split("/")[0];
        }
        return keyword;
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
