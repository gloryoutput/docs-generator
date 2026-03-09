# 오류 수정 완료 보고서 - 문서 생성 명세

> 원본 파일: `고영 오류 수정 완료 보고서_원본.docx` 기반 분석

---

## 1. 입력 데이터 모델

### 1.1 최상위 구조

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `title` | `String` | O | 보고서 제목 (예: "오류 수정 완료 보고서") |
| `created_date` | `String` | O | 작성일자 (예: "2025년 1월 23일") |
| `company_name` | `String` | O | 회사명 (예: "㈜로동") |
| `author_name` | `String` | O | 제출자 성명 (예: "조민정") |
| `purpose` | `String` | O | 보고서 목적 |
| `problem_definition` | `ProblemDefinition` | O | 문제 정의 (발생한 문제 + 원인) |
| `resolution_process` | `List<ResolutionStep>` | O | 문제 해결 과정 (번호별 단계) |
| `result` | `List<String>` | O | 결과 항목 목록 |
| `prevention` | `List<PreventionItem>` | O | 개선 및 예방 방안 목록 |

### 1.2 ProblemDefinition (문제 정의)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `issues` | `List<Issue>` | O | 발생한 문제 목록 |
| `causes` | `List<Cause>` | O | 문제 원인 목록 |

### 1.3 Issue (발생한 문제)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `title` | `String` | O | 문제 제목 (볼드 표시됨) |
| `descriptions` | `List<String>` | O | 문제 상세 설명 (줄 단위) |

### 1.4 Cause (문제 원인)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `title` | `String` | O | 원인 제목 (볼드 표시됨) |
| `description` | `String` | O | 원인 상세 설명 |

### 1.5 ResolutionStep (문제 해결 과정)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `step_number` | `int` | O | 단계 번호 (1, 2, ...) |
| `title` | `String` | O | 단계 제목 (볼드 표시됨) |
| `sub_steps` | `List<SubStep>` | X | 하위 단계 목록 (소제목 + 항목) |
| `descriptions` | `List<String>` | X | 하위 단계 없이 단순 설명만 있는 경우 |

### 1.6 SubStep (하위 단계)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `title` | `String` | O | 소제목 (볼드 표시됨, 예: "초기 조사", "원인 파악") |
| `items` | `List<String>` | O | "- " 접두사로 표시되는 항목 목록 |

### 1.7 PreventionItem (개선 및 예방 방안)

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `title` | `String` | O | 방안 제목 (볼드 표시됨) |
| `descriptions` | `List<String>` | O | 방안 상세 설명 (줄 단위) |

---

## 2. 입력 데이터 예시 (JSON)

```json
{
  "title": "오류 수정 완료 보고서",
  "created_date": "2025년 1월 23일",
  "company_name": "㈜로동",
  "author_name": "조민정",
  "purpose": "본 보고서는 고객사 웹사이트에서 발생한 오류 문제를 해결한 과정을 정리하고, 작업 결과를 공유하기 위해 작성되었습니다",
  "problem_definition": {
    "issues": [
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
    ],
    "causes": [
      {
        "title": "호환성 문제",
        "description": "Elementor와 The Plus Addons for Elementor 플러그인 간의 버전 호환성 문제로 인해 상품 디테일 페이지 접속 오류 발생."
      },
      {
        "title": "CSS 캐싱 및 라이센스 문제",
        "description": "Elementor CSS 관련 데이터 초기화 필요 및 라이센스 동기화 문제로 인해 메인화면 동영상 및 UI 관련 오류 발생."
      }
    ]
  },
  "resolution_process": [
    {
      "step_number": 1,
      "title": "상품 디테일 페이지 접속 불가 해결",
      "sub_steps": [
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
      "step_number": 2,
      "title": "메인화면 동영상 및 UI 오류 해결",
      "descriptions": [
        "관리자 계정 로그인 시 발생하는 오류를 확인.",
        "Elementor CSS 초기화 및 라이센스 동기화 작업 수행.",
        "W3 Total Cache 플러그인을 사용하여 캐시 삭제 작업 진행."
      ]
    }
  ],
  "result": [
    "상품 디테일 페이지 접속 오류 해결 완료.",
    "관리자 계정으로 로그인 시 발생하던 JP, GE, CH 언어 페이지의 메인화면 동영상 미출력 및 상품 카드 UI 깨짐 현상 해결 완료.",
    "모든 작업 후 최종 확인 결과, 현재 웹사이트가 정상적으로 동작하고 있음을 확인."
  ],
  "prevention": [
    {
      "title": "플러그인 관리 강화",
      "descriptions": [
        "현재 사용 중인 플러그인과 테마의 버전을 기록하여, 업데이트 시 기존 버전으로 롤백해야 할 경우를 대비합니다. 이를 통해 예상치 못한 문제 발생 시 신속한 복구가 가능합니다.",
        "업데이트 적용 전후, 플러그인을 단계적으로 비활성화 및 활성화하며 문제를 점검합니다. 이는 업데이트 시 발생할 수 있는 충돌 문제를 보다 체계적으로 식별할 수 있습니다."
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
| 작성일자 | 우측 정렬, "작성일자" 부분만 **볼드** |

#### 3.2.2 테이블 구조

테이블 스타일: **Table Grid** (격자형 테두리)

테이블은 3개 열로 구성되며, 행에 따라 열 병합이 다르게 적용됩니다.

```
┌──────────────────┬──────────────────┬──────────────────────────────┐
│    열 0 (섹션)    │   열 1 (소섹션)   │       열 2 (내용)             │
├──────────────────┴──────────────────┼──────────────────────────────┤
│ 회사명 (열 0+1 병합)                 │ {company_name}               │
├─────────────────────────────────────┼──────────────────────────────┤
│ 제출자 성명 (열 0+1 병합)            │ {author_name}                │
├─────────────────────────────────────┼──────────────────────────────┤
│ 목적 (열 0+1 병합)                   │ {purpose}                    │
├──────────────────┬──────────────────┼──────────────────────────────┤
│ 문제 정의        │ 발생한 문제       │ {issues 내용}                 │
│ (세로 병합)       ├──────────────────┼──────────────────────────────┤
│                  │ 문제 원인         │ {causes 내용}                 │
├──────────────────┴──────────────────┼──────────────────────────────┤
│ 문제 해결 과정 (열 0+1 병합)          │ {resolution_process 내용}     │
├─────────────────────────────────────┼──────────────────────────────┤
│ 결과 (열 0+1 병합)                   │ {result 내용}                 │
├─────────────────────────────────────┼──────────────────────────────┤
│ 개선 및 예방 방안 (열 0+1 병합)       │ {prevention 내용}             │
└─────────────────────────────────────┴──────────────────────────────┘
```

### 3.3 셀 병합 규칙

| 행 | 열 0~1 병합 | 열 0 세로 병합 | 설명 |
|----|-------------|---------------|------|
| 회사명 | 가로 병합 (gridSpan=2) | - | 열 0+1 하나로 합침 |
| 제출자 성명 | 가로 병합 (gridSpan=2) | - | 열 0+1 하나로 합침 |
| 목적 | 가로 병합 (gridSpan=2) | - | 열 0+1 하나로 합침 |
| 문제 정의 - 발생한 문제 | 병합 안 함 | 세로 병합 시작 (vMerge=restart) | 열 0이 아래 행과 세로 병합 |
| 문제 정의 - 문제 원인 | 병합 안 함 | 세로 병합 계속 (vMerge=restart) | 열 0이 위 행과 세로 병합 |
| 문제 해결 과정 | 가로 병합 (gridSpan=2) | - | 열 0+1 하나로 합침 |
| 결과 | 가로 병합 (gridSpan=2) | - | 열 0+1 하나로 합침 |
| 개선 및 예방 방안 | 가로 병합 (gridSpan=2) | - | 열 0+1 하나로 합침 |

### 3.4 셀 내부 텍스트 서식 규칙

#### 열 0~1 (헤더 셀)

| 항목 | 서식 |
|------|------|
| 글씨 크기 | 12pt (회사명/제출자/목적), 기본 크기 (나머지) |
| 굵기 | **볼드** |

#### 열 2 (내용 셀)

| 항목 | 서식 |
|------|------|
| 글씨 크기 | 10pt (회사명/제출자/목적), 기본 크기 (나머지) |
| 굵기 | 일반 (본문), **볼드** (소제목/강조 부분) |

#### 내용 셀 (열 2) 텍스트 패턴

**발생한 문제 / 문제 원인 셀:**
```
{issue.title}              ← 볼드, 줄바꿈 후 설명 시작
{issue.descriptions[0]}    ← 일반
{issue.descriptions[1]}    ← 일반
```

**문제 해결 과정 셀:**
```
{step_number}. {step.title}     ← 볼드
{sub_step.title}                ← 볼드
- {sub_step.items[0]}           ← "- " 접두사, 일반
- {sub_step.items[1]}           ← "- " 접두사, 일반
{next_sub_step.title}           ← 볼드
- {next_sub_step.items[0]}      ← "- " 접두사, 일반
```

하위 단계(sub_steps)가 없는 경우:
```
{step_number}. {step.title}     ← 볼드
{descriptions[0]}               ← 일반
{descriptions[1]}               ← 일반
```

**결과 셀:**
```
{result[0]}     ← 일반, 각 항목이 별도 줄(paragraph)
{result[1]}     ← 일반
{result[2]}     ← 일반
```

**개선 및 예방 방안 셀:**
```
{prevention.title}              ← 볼드
{prevention.descriptions[0]}    ← 일반
{prevention.descriptions[1]}    ← 일반
{next_prevention.title}         ← 볼드
{next_prevention.descriptions[0]} ← 일반
```

---

## 4. 데이터 모델 클래스 구조 (Java)

```
ErrorFixReportRequest
├── title: String
├── created_date: String
├── company_name: String
├── author_name: String
├── purpose: String
├── problem_definition: ProblemDefinition
│   ├── issues: List<Issue>
│   │   ├── title: String
│   │   └── descriptions: List<String>
│   └── causes: List<Cause>
│       ├── title: String
│       └── description: String
├── resolution_process: List<ResolutionStep>
│   ├── step_number: int
│   ├── title: String
│   ├── sub_steps: List<SubStep>  (nullable)
│   │   ├── title: String
│   │   └── items: List<String>
│   └── descriptions: List<String>  (nullable)
├── result: List<String>
└── prevention: List<PreventionItem>
    ├── title: String
    └── descriptions: List<String>
```

---

## 5. API 엔드포인트 (예상)

```
POST /api/reports/error-fix
Content-Type: application/json

Request Body: ErrorFixReportRequest (섹션 2의 JSON 형식)
Response: 생성된 .docx 파일 다운로드 (application/vnd.openxmlformats-officedocument.wordprocessingml.document)
```
