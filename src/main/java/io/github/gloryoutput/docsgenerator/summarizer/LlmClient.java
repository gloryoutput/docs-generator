package io.github.gloryoutput.docsgenerator.summarizer;

/**
 * LLM 호출 클라이언트 인터페이스
 *
 * <p>다양한 LLM 제공자(OpenAI, Anthropic, 로컬 LLM 등)를 지원하기 위한
 * 공통 인터페이스입니다. 새로운 LLM 제공자를 추가할 때 이 인터페이스를 구현합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
public interface LlmClient {
    /**
     * LLM에 시스템 프롬프트와 사용자 프롬프트를 전달하고 응답을 반환합니다.
     *
     * @param systemPrompt 시스템 프롬프트
     * @param userPrompt 사용자 프롬프트
     * @return LLM 응답 텍스트
     */
    String chat(String systemPrompt, String userPrompt);
}
