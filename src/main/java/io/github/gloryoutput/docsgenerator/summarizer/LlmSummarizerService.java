package io.github.gloryoutput.docsgenerator.summarizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gloryoutput.docsgenerator.correlation.CorrelatedGroup;
import io.github.gloryoutput.docsgenerator.domain.changeevent.ChangeEvent;
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
import java.util.List;

/**
 * LLM을 활용한 보고서 초안 문장 다듬기 서비스 (오케스트레이터)
 *
 * <p>변경 이벤트 목록과 보고서 초안을 입력받아, LlmClient를 통해 문장을 자연스럽고
 * 명확하게 다듬어 반환합니다. LlmClient 빈이 없으면 원본 초안을 그대로 반환합니다.</p>
 *
 * <p>설계 원칙(Section 16): LLM에는 raw diff, 전체 소스코드, config 원문을 절대
 * 전달하지 않으며, change event의 title/description과 구조화된 초안만 입력합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Service
@Slf4j
public class LlmSummarizerService {
    private static final String SYSTEM_PROMPT =
            "당신은 소프트웨어 변경 보고서 작성을 돕는 전문가입니다.\n" +
            "아래 변경 이벤트 목록을 기반으로 보고서 초안의 문장을 자연스럽고 명확하게 다듬어 주세요.\n" +
            "- 기술적 정확성을 유지하세요.\n" +
            "- 간결하고 명확한 한국어 문장을 사용하세요.\n" +
            "- Markdown 형식을 유지하세요.\n" +
            "- 새로운 정보를 추가하지 마세요.";
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmClient llmClient;
    @Value("${app.llm.prompt-output-dir:./llm-prompts}")
    private String promptOutputDir;

    public LlmSummarizerService(@Autowired(required = false) LlmClient llmClient) {
        this.llmClient = llmClient;
        if (llmClient != null) {
            log.info("LLM Summarizer 활성화 - client: {}", llmClient.getClass().getSimpleName());
        } else {
            log.info("LLM Summarizer 비활성화 (LlmClient 빈 없음)");
        }
    }

    /**
     * 변경 이벤트 그룹과 보고서 초안을 기반으로 문장을 다듬어 반환합니다.
     *
     * <p>LlmClient 빈이 없으면 원본 초안을 그대로 반환합니다.
     * LLM 호출 중 오류 발생 시에도 원본 초안을 반환합니다(fallback).</p>
     *
     * @param groups        상관관계로 그룹핑된 변경 이벤트 목록
     * @param originalDraft 보고서 초안 (Markdown)
     * @return 다듬어진 보고서 텍스트 또는 원본 초안
     */
    public String summarize(List<CorrelatedGroup> groups, String originalDraft) {
        try {
            String eventsJson = buildEventsJson(groups);
            String userPrompt = buildUserPrompt(eventsJson, originalDraft);
            String timestamp = LocalDateTime.now().format(FILE_FORMATTER);
            saveInputFile(timestamp, SYSTEM_PROMPT, userPrompt);
            if (llmClient == null) {
                log.debug("LLM 비활성화 - 원본 초안 반환");
                return originalDraft;
            }
            log.info("LLM 입력 - System Prompt:\n{}", SYSTEM_PROMPT);
            log.info("LLM 입력 - User Prompt:\n{}", userPrompt);
            String result = llmClient.chat(SYSTEM_PROMPT, userPrompt);
            log.info("LLM 출력:\n{}", result);
            saveOutputFile(timestamp, result);
            return result;
        } catch (Exception e) {
            log.warn("LLM 호출 실패 - 원본 초안 반환. 원인: {}", e.getMessage());
            return originalDraft;
        }
    }

    /**
     * LLM 입력(System Prompt + User Prompt)을 파일로 저장합니다.
     */
    private void saveInputFile(String timestamp, String systemPrompt, String userPrompt) {
        try {
            Path dir = Paths.get(promptOutputDir);
            Files.createDirectories(dir);
            String content = "# LLM Input\n\n" +
                    "## System Prompt\n\n" + systemPrompt +
                    "\n\n## User Prompt\n\n" + userPrompt;
            Path filePath = dir.resolve("llm_input_" + timestamp + ".md");
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.info("LLM 입력 파일 저장 완료: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("LLM 입력 파일 저장 실패: {}", e.getMessage());
        }
    }
    /**
     * LLM 출력 결과를 파일로 저장합니다.
     */
    private void saveOutputFile(String timestamp, String output) {
        try {
            Path dir = Paths.get(promptOutputDir);
            Files.createDirectories(dir);
            String content = "# LLM Output\n\n" + output;
            Path filePath = dir.resolve("llm_output_" + timestamp + ".md");
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.info("LLM 출력 파일 저장 완료: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("LLM 출력 파일 저장 실패: {}", e.getMessage());
        }
    }

    /**
     * 변경 이벤트 그룹에서 title/description만 추출하여 JSON 배열 문자열로 변환합니다.
     */
    private String buildEventsJson(List<CorrelatedGroup> groups) throws Exception {
        ArrayNode array = objectMapper.createArrayNode();
        for (CorrelatedGroup group : groups) {
            if (group.getEvents() == null) {
                continue;
            }
            for (ChangeEvent event : group.getEvents()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("title", event.getTitle());
                node.put("description", event.getDescription());
                array.add(node);
            }
        }
        return objectMapper.writeValueAsString(array);
    }

    /**
     * 시스템 프롬프트에 전달할 사용자 프롬프트를 구성합니다.
     */
    private String buildUserPrompt(String eventsJson, String originalDraft) {
        return "## 변경 이벤트 목록\n" + eventsJson +
                "\n\n## 보고서 초안\n" + originalDraft +
                "\n\n위 변경 이벤트를 참고하여 아래 보고서 초안의 문장을 다듬어 주세요.";
    }
}
