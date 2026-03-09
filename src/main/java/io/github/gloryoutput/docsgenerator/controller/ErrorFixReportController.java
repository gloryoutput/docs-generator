package io.github.gloryoutput.docsgenerator.controller;

import io.github.gloryoutput.docsgenerator.dto.request.ErrorFixReportRequest;
import io.github.gloryoutput.docsgenerator.service.ErrorFixReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 오류 수정 완료 보고서 생성 API 컨트롤러
 *
 * <p>JSON 요청을 받아 docx 형식의 보고서 파일을 생성하여 반환합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "보고서 생성", description = "문서(docx) 보고서 생성 API")
public class ErrorFixReportController {
    private static final MediaType DOCX_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private final ErrorFixReportService errorFixReportService;

    /**
     * 오류 수정 완료 보고서를 docx 파일로 생성하여 다운로드합니다.
     *
     * @param request 보고서 생성 요청 데이터
     * @return 생성된 docx 파일
     */
    @PostMapping("/error-fix")
    @Operation(
            summary = "오류 수정 완료 보고서 생성",
            description = "JSON 데이터를 입력받아 오류 수정 완료 보고서를 docx 파일로 생성합니다. "
                    + "sections 배열로 테이블 행을 동적으로 구성합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "오류 수정 완료 보고서 예시",
                                    value = EXAMPLE_JSON
                            )
                    )
            )
    )
    public ResponseEntity<byte[]> generateErrorFixReport(@Valid @RequestBody ErrorFixReportRequest request) throws IOException {
        byte[] reportBytes = errorFixReportService.generateReport(request);
        String encodedFileName = URLEncoder.encode(request.getTitle() + ".docx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(DOCX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .body(reportBytes);
    }

    private static final String EXAMPLE_JSON = """
            {
              "title": "오류 수정 완료 보고서",
              "createdDate": "2025년 1월 23일",
              "recipient": "㈜고영 김철수",
              "manager": "조민정",
              "sections": [
                {
                  "type": "simple",
                  "label": "회사명",
                  "contentType": "text",
                  "textValue": "㈜로동"
                },
                {
                  "type": "simple",
                  "label": "제출자 성명",
                  "contentType": "text",
                  "textValue": "조민정"
                },
                {
                  "type": "simple",
                  "label": "목적",
                  "contentType": "text",
                  "textValue": "본 보고서는 고객사 웹사이트에서 발생한 오류 문제를 해결한 과정을 정리하고, 작업 결과를 공유하기 위해 작성되었습니다"
                },
                {
                  "type": "group",
                  "label": "문제 정의",
                  "subRows": [
                    {
                      "label": "발생한 문제",
                      "contentType": "titledList",
                      "titledItems": [
                        {
                          "title": "상품 디테일 페이지 접속 불가",
                          "descriptions": [
                            "EN, JP, CH, GE 각 언어로 번역된 WordPress 기반의 웹사이트에서 상품 디테일 정보에 접속되지 않는 오류가 발생."
                          ]
                        },
                        {
                          "title": "메인화면 동영상 및 UI 오류",
                          "descriptions": [
                            "관리자 계정으로 로그인 시 JP, GE, CH 언어 페이지의 메인화면 동영상이 비정상적으로 출력되지 않음.",
                            "관리자 계정 로그인 상태에서 상품 카드 UI가 깨지는 현상이 발견됨."
                          ]
                        }
                      ]
                    },
                    {
                      "label": "문제 원인",
                      "contentType": "titledList",
                      "titledItems": [
                        {
                          "title": "호환성 문제",
                          "descriptions": [
                            "Elementor와 The Plus Addons for Elementor 플러그인 간의 버전 호환성 문제로 인해 상품 디테일 페이지 접속 오류 발생."
                          ]
                        },
                        {
                          "title": "CSS 캐싱 및 라이센스 문제",
                          "descriptions": [
                            "Elementor CSS 관련 데이터 초기화 필요 및 라이센스 동기화 문제로 인해 메인화면 동영상 및 UI 관련 오류 발생."
                          ]
                        }
                      ]
                    }
                  ]
                },
                {
                  "type": "simple",
                  "label": "문제 해결 과정",
                  "contentType": "steps",
                  "steps": [
                    {
                      "stepNumber": 1,
                      "title": "상품 디테일 페이지 접속 불가 해결",
                      "subSteps": [
                        {
                          "title": "초기 조사",
                          "items": [
                            "클라이언트로부터 전달받은 오류 정보를 바탕으로, 웹 FTP에 접속하여 .htaccess 파일에 디버그 로그 코드를 추가.",
                            "디버그 로그를 통해 서버 에러 로그를 확인하고, 문제 원인을 분석."
                          ]
                        },
                        {
                          "title": "원인 파악",
                          "items": [
                            "Elementor와 The Plus Addons for Elementor 플러그인의 버전 충돌로 인해 상품 디테일 페이지가 접속되지 않는 것으로 판단."
                          ]
                        },
                        {
                          "title": "문제 해결",
                          "items": [
                            "Elementor와 The Plus Addons for Elementor의 각 버전 간 호환성을 철저히 검토하고, 변경 내역(Changelog)을 바탕으로 플러그인 간의 연동 문제 파악",
                            "Elementor 플러그인을 Version 3.25.0으로 다운그레이드.",
                            "The Plus Addons for Elementor 플러그인을 Version 6.1.2로 다운그레이드하여 문제를 해결."
                          ]
                        }
                      ]
                    },
                    {
                      "stepNumber": 2,
                      "title": "메인화면 동영상 및 UI 오류 해결",
                      "descriptions": [
                        "관리자 계정 로그인 시 발생하는 오류를 확인.",
                        "Elementor CSS 초기화 및 라이센스 동기화 작업 수행.",
                        "W3 Total Cache 플러그인을 사용하여 캐시 삭제 작업 진행."
                      ]
                    }
                  ]
                },
                {
                  "type": "simple",
                  "label": "결과",
                  "contentType": "numberedList",
                  "listItems": [
                    "상품 디테일 페이지 접속 오류 해결 완료.",
                    "관리자 계정으로 로그인 시 발생하던 JP, GE, CH 언어 페이지의 메인화면 동영상 미출력 및 상품 카드 UI 깨짐 현상 해결 완료.",
                    "모든 작업 후 최종 확인 결과, 현재 웹사이트가 정상적으로 동작하고 있음을 확인."
                  ]
                },
                {
                  "type": "simple",
                  "label": "개선 및 예방 방안",
                  "contentType": "titledList",
                  "titledItems": [
                    {
                      "title": "플러그인 관리 강화",
                      "descriptions": [
                        "현재 사용 중인 플러그인과 테마의 버전을 기록하여, 업데이트 시 기존 버전으로 롤백해야 할 경우를 대비합니다.",
                        "업데이트 적용 전후, 플러그인을 단계적으로 비활성화 및 활성화하며 문제를 점검합니다."
                      ]
                    },
                    {
                      "title": "정기 점검 및 테스트",
                      "descriptions": [
                        "주요 업데이트 전후에 테스트 환경에서 문제를 사전에 확인하고 대응 방안을 마련."
                      ]
                    },
                    {
                      "title": "캐시 관리 체계화",
                      "descriptions": [
                        "W3 Total Cache 플러그인 등 캐시 관리 도구를 주기적으로 점검하고 최적화."
                      ]
                    }
                  ]
                }
              ]
            }
            """;
}
