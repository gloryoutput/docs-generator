package io.github.gloryoutput.docsgenerator.controller;

import io.github.gloryoutput.docsgenerator.dto.request.CredentialCreateRequest;
import io.github.gloryoutput.docsgenerator.dto.response.ApiResponse;
import io.github.gloryoutput.docsgenerator.dto.response.CredentialResponse;
import io.github.gloryoutput.docsgenerator.service.CredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 레포지토리 인증 정보 관리 API 컨트롤러
 *
 * <p>레포지토리별 접근 인증 정보(토큰, SSH 키, 계정)를 설정/조회/삭제합니다.
 * 응답에서 민감 정보는 마스킹 처리됩니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/repositories/{idProjectRepositoryMap}/credentials")
@RequiredArgsConstructor
@Tag(name = "인증 정보 관리", description = "레포지토리 접근 인증 정보 API")
public class CredentialController {
    private final CredentialService credentialService;

    /**
     * 인증 정보를 설정합니다 (없으면 생성, 있으면 갱신).
     *
     * @param idProjectRepositoryMap 레포지토리 매핑 ID
     * @param request 인증 정보 요청
     * @return 설정된 인증 정보 (마스킹)
     */
    @PutMapping
    @Operation(summary = "인증 정보 설정", description = "레포지토리 접근 인증 정보를 설정합니다 (생성/갱신)")
    public ApiResponse<CredentialResponse> setCredential(
            @PathVariable String idProjectRepositoryMap,
            @RequestBody CredentialCreateRequest request) {
        return ApiResponse.ok(credentialService.setCredential(idProjectRepositoryMap, request));
    }

    /**
     * 인증 정보를 조회합니다 (민감 정보 마스킹).
     *
     * @param idProjectRepositoryMap 레포지토리 매핑 ID
     * @return 인증 정보 (마스킹)
     */
    @GetMapping
    @Operation(summary = "인증 정보 조회", description = "레포지토리 인증 정보를 조회합니다 (민감 정보 마스킹)")
    public ApiResponse<CredentialResponse> getCredential(@PathVariable String idProjectRepositoryMap) {
        return ApiResponse.ok(credentialService.getCredential(idProjectRepositoryMap));
    }

    /**
     * 인증 정보를 삭제합니다 (논리 삭제).
     *
     * @param idProjectRepositoryMap 레포지토리 매핑 ID
     * @return 성공 응답
     */
    @DeleteMapping
    @Operation(summary = "인증 정보 삭제", description = "레포지토리 인증 정보를 삭제합니다")
    public ApiResponse<Void> deleteCredential(@PathVariable String idProjectRepositoryMap) {
        credentialService.deleteCredential(idProjectRepositoryMap);
        return ApiResponse.ok();
    }
}
