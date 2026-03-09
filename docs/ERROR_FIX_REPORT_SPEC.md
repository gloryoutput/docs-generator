# 오류 수정 완료 보고서 - 문서 생성 명세

> 원본 파일: `고영 오류 수정 완료 보고서_원본.docx` 기반 분석

---

## 1. 입력 데이터 모델

### 1.1 최상위 구조

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `title` | `String` | O | 보고서 제목 (예: "오류 수정 완료 보고서") |
| `createdDate` | `String` | O | 작성일자 (예: "2025년 1월 23일") |
| `sections` | `List<ReportSection>` | O | 테이블 섹션(행) 목록 - 순서대로 행 생성 |

### 1.2 ReportSection (섹션)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `type` | `SectionType` | O | `simple` (2셀 병합 행) 또는 `group` (3셀 세로병합 행) |
| `label` | `String` | O | 헤더 라벨 텍스트 |
| `contentType` | `ContentType` | simple일 때 O | `text`, `numberedList`, `titledList`, `steps` |
| `textValue` | `String` | contentType=text일 때 | 텍스트 값 |
| `listItems` | `List<String>` | contentType=numberedList일 때 | 번호 목록 항목 |
| `titledItems` | `List<TitledItem>` | contentType=titledList일 때 | 제목+설명 목록 |
| `steps` | `List<ResolutionStep>` | contentType=steps일 때 | 단계별 항목 |
| `subRows` | `List<SectionSubRow>` | group일 때 O | 서브행 목록 |

### 1.3 SectionSubRow (그룹 서브행)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `label` | `String` | O | 서브행 라벨 (두 번째 열) |
| `contentType` | `ContentType` | O | `text`, `numberedList`, `titledList`, `steps` |
| `textValue` | `String` | contentType=text일 때 | 텍스트 값 |
| `listItems` | `List<String>` | contentType=numberedList일 때 | 번호 목록 항목 |
| `titledItems` | `List<TitledItem>` | contentType=titledList일 때 | 제목+설명 목록 |
| `steps` | `List<ResolutionStep>` | contentType=steps일 때 | 단계별 항목 |

### 1.4 TitledItem (제목+설명 항목)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `title` | `String` | O | 항목 제목 (볼드 표시됨) |
| `descriptions` | `List<String>` | O | 설명 목록 (들여쓰기 표시) |

### 1.5 ResolutionStep (해결 과정 단계)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `stepNumber` | `int` | O | 단계 번호 (1, 2, ...) |
| `title` | `String` | O | 단계 제목 (볼드 표시됨) |
| `subSteps` | `List<SubStep>` | X | 하위 단계 목록 (소제목 + 항목) |
| `descriptions` | `List<String>` | X | 하위 단계 없이 단순 설명만 있는 경우 |

### 1.6 SubStep (하위 단계)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `title` | `String` | O | 소제목 (볼드 표시됨, 예: "초기 조사", "원인 파악") |
| `items` | `List<String>` | O | "- " 접두사로 표시되는 항목 목록 |

### 1.7 ContentType (콘텐츠 유형)

| 값 | 설명 | 사용 필드 |
|----|------|-----------|
| `text` | 단일 텍스트 | `textValue` |
| `numberedList` | 번호 목록 (1. xxx, 2. xxx) | `listItems` |
| `titledList` | 제목+설명 목록 | `titledItems` |
| `steps` | 단계별 해결 과정 | `steps` |

### 1.8 SectionType (섹션 유형)

| 값 | 설명 | 테이블 레이아웃 |
|----|------|----------------|
| `simple` | 2셀 행 | 헤더(열0+1 병합) + 내용(열2) |
| `group` | 3셀 행 (세로병합) | 부모 헤더(열0, vMerge) + 서브 라벨(열1) + 내용(열2) |

---

## 2. 입력 데이터 예시 (JSON)

```json
{
  "title": "오류 수정 완료 보고서",
  "createdDate": "2025년 1월 23일",
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
              "descriptions": ["WordPress 기반 웹사이트에서 상품 디테일 정보에 접속되지 않는 오류 발생."]
            }
          ]
        },
        {
          "label": "문제 원인",
          "contentType": "titledList",
          "titledItems": [
            {
              "title": "호환성 문제",
              "descriptions": ["플러그인 간 버전 호환성 문제로 인해 오류 발생."]
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
              "items": ["디버그 로그를 통해 서버 에러 로그를 확인."]
            }
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
        "현재 웹사이트가 정상적으로 동작하고 있음을 확인."
      ]
    },
    {
      "type": "simple",
      "label": "개선 및 예방 방안",
      "contentType": "titledList",
      "titledItems": [
        {
          "title": "플러그인 관리 강화",
          "descriptions": ["플러그인과 테마의 버전을 기록하여 롤백에 대비."]
        }
      ]
    }
  ]
}
```

---

## 3. 출력 형태 (docx 문서 레이아웃)

### 3.1 페이지 설정

| 항목 | 값 |
|------|-----|
| 용지 크기 | A4 세로 (약 210mm x 297mm) |
| 상단 여백 | 약 30mm |
| 하단 여백 | 약 25mm |
| 좌우 여백 | 약 25mm |

### 3.2 문서 구성

문서는 **제목 영역** + **1개의 테이블**로 구성됩니다.

#### 3.2.1 제목 영역 (테이블 상단)

| 요소 | 서식 |
|------|------|
| 보고서 제목 | 중앙 정렬, **볼드**, 24pt |
| 구분선 | 진한 파랑(#4472C4) 가로선 |
| 작성일자 | 우측 정렬, "작성일자" 부분만 **볼드** |

#### 3.2.2 테이블 구조

테이블은 **3개 열** 기반이며, `sections` 배열에 따라 행이 동적으로 생성됩니다.

**simple 타입 행:**
```
┌─────────────────────────────────────┬──────────────────────────────┐
│ {label} (열 0+1 병합, 배경색)        │ {content} (열 2)              │
└─────────────────────────────────────┴──────────────────────────────┘
```

**group 타입 행:**
```
┌──────────────────┬──────────────────┬──────────────────────────────┐
│ {label}          │ {subRow[0].label}│ {subRow[0] content}          │
│ (세로 병합)       ├──────────────────┼──────────────────────────────┤
│                  │ {subRow[1].label}│ {subRow[1] content}          │
└──────────────────┴──────────────────┴──────────────────────────────┘
```

### 3.3 디자인

| 항목 | 값 |
|------|-----|
| 헤더 셀 배경색 | #D9E2F3 (연한 파랑) |
| 외곽 테두리 | 12pt, #4472C4 (진한 파랑) |
| 내부 테두리 | 4pt, #BFBFBF (회색) |
| 셀 내부 여백 | 상하 60twip, 좌우 113twip |
| 줄 간격 | 1.15배 |

### 3.4 콘텐츠 타입별 렌더링

**text:** 가로 중앙 정렬, 10pt

**numberedList:**
```
1. {items[0]}
2. {items[1]}
```

**titledList:**
```
1. {title}              ← 볼드
   {descriptions[0]}    ← 들여쓰기 (567twip)
   {descriptions[1]}    ← 들여쓰기 (567twip)
2. {title}              ← 볼드
   {descriptions[0]}    ← 들여쓰기 (567twip)
```

**steps:**
```
{stepNumber}. {title}        ← 볼드
   1. {subStep.title}        ← 볼드, 들여쓰기 (567twip)
      - {subStep.items[0]}   ← 들여쓰기 (1134twip)
      - {subStep.items[1]}   ← 들여쓰기 (1134twip)
```

---

## 4. 데이터 모델 클래스 구조 (Java)

```
ErrorFixReportRequest
├── title: String
├── createdDate: String
└── sections: List<ReportSection>
    ├── type: SectionType (simple | group)
    ├── label: String
    ├── contentType: ContentType (text | numberedList | titledList | steps)
    ├── textValue: String
    ├── listItems: List<String>
    ├── titledItems: List<TitledItem>
    │   ├── title: String
    │   └── descriptions: List<String>
    ├── steps: List<ResolutionStep>
    │   ├── stepNumber: int
    │   ├── title: String
    │   ├── subSteps: List<SubStep>  (nullable)
    │   │   ├── title: String
    │   │   └── items: List<String>
    │   └── descriptions: List<String>  (nullable)
    └── subRows: List<SectionSubRow>  (group 타입 전용)
        ├── label: String
        ├── contentType: ContentType
        ├── textValue: String
        ├── listItems: List<String>
        ├── titledItems: List<TitledItem>
        └── steps: List<ResolutionStep>
```

---

## 5. API 엔드포인트

```
POST /api/reports/error-fix
Content-Type: application/json

Request Body: ErrorFixReportRequest (섹션 2의 JSON 형식)
Response: 생성된 .docx 파일 다운로드 (application/vnd.openxmlformats-officedocument.wordprocessingml.document)
```
