package io.github.gloryoutput.docsgenerator.controller;

import io.github.gloryoutput.docsgenerator.dto.request.RuleSetCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.request.RuleSetUpdateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.ApiResponse;
import io.github.gloryoutput.docsgenerator.dto.response.RuleSetResponse;
import io.github.gloryoutput.docsgenerator.service.RuleSetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 규칙 세트 관리 API 컨트롤러
 *
 * <p>규칙 세트 CRUD 및 유형별 조회 엔드포인트를 제공합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/rule-sets")
@RequiredArgsConstructor
@Tag(name = "규칙 세트", description = "규칙 세트 관리 API")
public class RuleSetController {
    private final RuleSetService ruleSetService;

    /**
     * 규칙 세트를 생성합니다.
     *
     * @param request 규칙 세트 생성 요청
     * @return 생성된 규칙 세트 정보
     */
    @PostMapping
    @Operation(summary = "규칙 세트 생성", description = "새 규칙 세트를 등록합니다")
    public ApiResponse<RuleSetResponse> createRuleSet(@Valid @RequestBody RuleSetCreateRequest request) {
        return ApiResponse.ok(ruleSetService.createRuleSet(request));
    }

    /**
     * 규칙 세트를 수정합니다.
     *
     * @param idRuleSet 규칙 세트 ID
     * @param request   규칙 세트 수정 요청
     * @return 수정된 규칙 세트 정보
     */
    @PutMapping("/{idRuleSet}")
    @Operation(summary = "규칙 세트 수정", description = "규칙 세트를 부분 수정합니다")
    public ApiResponse<RuleSetResponse> updateRuleSet(
            @PathVariable String idRuleSet,
            @RequestBody RuleSetUpdateRequest request) {
        return ApiResponse.ok(ruleSetService.updateRuleSet(idRuleSet, request));
    }

    /**
     * 규칙 세트를 삭제합니다 (논리 삭제).
     *
     * @param idRuleSet 규칙 세트 ID
     * @return 빈 성공 응답
     */
    @DeleteMapping("/{idRuleSet}")
    @Operation(summary = "규칙 세트 삭제", description = "규칙 세트를 논리 삭제합니다")
    public ApiResponse<Void> deleteRuleSet(@PathVariable String idRuleSet) {
        ruleSetService.deleteRuleSet(idRuleSet);
        return ApiResponse.ok();
    }

    /**
     * 규칙 세트를 단건 조회합니다.
     *
     * @param idRuleSet 규칙 세트 ID
     * @return 규칙 세트 정보
     */
    @GetMapping("/{idRuleSet}")
    @Operation(summary = "규칙 세트 단건 조회", description = "ID로 규칙 세트를 조회합니다")
    public ApiResponse<RuleSetResponse> getRuleSet(@PathVariable String idRuleSet) {
        return ApiResponse.ok(ruleSetService.getRuleSet(idRuleSet));
    }

    /**
     * 활성 규칙 세트 전체를 조회합니다.
     *
     * @return 활성 규칙 세트 목록
     */
    @GetMapping
    @Operation(summary = "활성 규칙 세트 목록 조회", description = "삭제되지 않은 활성 규칙 세트를 우선순위순으로 조회합니다")
    public ApiResponse<List<RuleSetResponse>> getAllActiveRuleSets() {
        return ApiResponse.ok(ruleSetService.getAllActiveRuleSets());
    }

    /**
     * 규칙 유형별로 활성 규칙 세트를 조회합니다.
     *
     * @param ruleType 규칙 유형
     * @return 해당 유형의 활성 규칙 세트 목록
     */
    @GetMapping("/type/{ruleType}")
    @Operation(summary = "유형별 규칙 세트 조회", description = "규칙 유형별로 활성 규칙 세트를 조회합니다")
    public ApiResponse<List<RuleSetResponse>> getRuleSetsByType(@PathVariable String ruleType) {
        return ApiResponse.ok(ruleSetService.getRuleSetsByType(ruleType));
    }
}
