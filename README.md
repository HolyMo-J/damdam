# 담담 (damdam)

토스증권 Open API를 이용한 주식 자동매매 봇 프로젝트입니다.

이름 "담담"은 급락에도 급등에도 흔들리지 않고, 미리 정한 규칙대로 매매한다는 뜻입니다.

## 프로젝트 목표

- 감정적인 매매 대신, 정해둔 규칙대로 손절과 청산을 실행하는 봇을 만든다
- 부수입 수단이면서, 동시에 백엔드 개발 포트폴리오로 쓴다
- 아래 4단계로 나눠서 점진적으로 완성한다

| 단계 | 내용 |
| --- | --- |
| 1 | 조회와 청산 봇: 매수는 사용자가 직접, 청산(매도)은 봇이 규칙대로 자동 실행 |
| 2 | 과열 급락 반등 전략 백테스트 |
| 3 | 가상매매로 매매 기록 수집 |
| 4 | 소액 실전 투입 (전환 기준 충족 시) |

지금은 **1단계**를 진행 중입니다. 자세한 진행 상황은 [docs/todo.md](docs/todo.md), [docs/done.md](docs/done.md)를 참고하세요.

## 현재 할 수 있는 것

1단계는 아직 조회 기능만 있고, 매수/매도 등 실제 주문 기능은 없습니다.

- 토스증권 Open API 인증 토큰 발급, 저장, 재사용 (`TokenService`)
- 보유 계좌 목록 조회 (`AccountService`)

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| 봇 본체 | Java 21, Spring Boot 4.1.1, Gradle |
| API 호출 | Spring RestClient |
| 체결 감지 | 웹소켓 클라이언트 (예정) |
| 주기 작업, 시간 청산 | Spring 스케줄러 (예정) |
| 매매 기록 | CSV 파일 |
| 백테스트, 기록 분석 | Python, pandas |

봇과 분석 코드는 CSV 파일로만 연결되며, 서로의 코드에 의존하지 않습니다.

## 프로젝트 구조

```
damdam/
├── bot/                     # Java + Spring Boot 봇 본체
│   └── src/main/java/com/damdam/bot/
│       ├── config/          # 설정값 바인딩, 공용 RestClient 빈
│       ├── token/           # 토큰 발급, 저장, 재사용
│       └── account/         # 계좌 조회
├── analysis/                # Python + pandas 백테스트, 기록 분석 (예정)
├── records/                 # 매매 기록 CSV 저장 위치 (git 제외)
├── docs/
│   ├── todo.md              # 확인 필요한 것, 다음 할 일
│   ├── done.md              # 완료한 작업 이력
│   ├── strategy.md          # 청산 규칙, 매매 전략
│   └── records.md           # 매매 기록 설계
├── .env.example             # 환경변수 이름만 정의 (실제 값은 .env에만 작성)
└── CLAUDE.md                # 프로젝트 규칙과 현재 상태
```

## 시작하기

### 준비물

- Java 21
- 토스증권 Open API 키 (WTS 설정에서 발급)
- 허용 IP 등록 (봇을 실행할 PC의 공인 IP를 토스 WTS 설정에 등록해야 함, 안 하면 403 에러 발생)

### 환경변수 설정

프로젝트 루트에 `.env` 파일을 만들고 `.env.example`을 참고해 값을 채웁니다. 이 파일은 git에 올라가지 않습니다.

```
TOSS_API_KEY=발급받은 키
TOSS_API_SECRET=발급받은 시크릿
```

### 계좌 조회 스크립트 실행

```
cd bot
./gradlew bootRun --args='--spring.profiles.active=query'
```

`query` 프로필로 실행할 때만 실제 계좌 조회 API를 호출합니다. 기본 실행이나 테스트에서는 호출하지 않습니다.

## 보안

- API 키, 시크릿, 토큰, 계좌 정보는 코드나 문서에 직접 쓰지 않고 `.env` 파일에서만 읽습니다
- 발급받은 토큰은 `bot/data/token.json`에 저장되며, `.env`와 동일한 수준으로 git과 접근 권한에서 제외됩니다
- 자세한 보안 규칙은 [CLAUDE.md](CLAUDE.md)를 참고하세요
