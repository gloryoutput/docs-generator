package io.github.gloryoutput.docsgenerator.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 섹션 콘텐츠 유형
 *
 * @author Lodong
 * @since 1.0.0
 */
public enum ContentType {
    @JsonProperty("text") TEXT,
    @JsonProperty("numberedList") NUMBERED_LIST,
    @JsonProperty("titledList") TITLED_LIST,
    @JsonProperty("steps") STEPS
}
