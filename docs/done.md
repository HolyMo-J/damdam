# 완료한 것

완료된 작업 이력. 스테이지나 기능 단위로 정리한다. 진행중이거나 다음에 할 일은 docs/todo.md 참고.

## 1단계: 조회와 청산 봇

### 프로젝트 초기 설정
- 프로젝트 폴더 구조(bot, analysis, records, docs)와 보안 설정(.claude/settings.json, .gitignore, .env.example) 구성
- 깃허브 저장소(HolyMo-J/damdam) 연결, main 브랜치 push
- Java 21, Python, Git 설치 확인
- 토스증권 Open API 키 발급
- `bot/`에 Spring Boot 4.1.1 + Java 21 Gradle 프로젝트 초기화 (web, websocket 의존성)
- `.env` 파일을 Spring이 직접 읽도록 설정 (외부 라이브러리 없이 `spring.config.import` 사용)
- 조회 스크립트는 `query` 프로필일 때만 실행되게 분리해서, 기본 빌드나 테스트에서 실제 API가 자동 호출되지 않게 함
- `./gradlew build` 통과 확인 (실제 API 호출 없이)

### 토큰 관리
- 토큰 관리 서비스 (`TokenService`): `/oauth2/token` 호출로 액세스 토큰 발급, `bot/data/token.json`에 저장해 재사용, 소유자만 접근 가능하게 권한 제한 (처음에는 `File.setReadable`로 했으나 Windows에서 무동작임을 확인해 표준 ACL API로 교체, 아래 안전장치 보강 참고)

### 계좌·보유 종목 조회
- 계좌 조회 (`AccountService`, `AccountQueryRunner`): `/api/v1/accounts` 호출로 계좌 목록과 accountSeq 조회, 계좌번호는 뒷 4자리만 노출
- 허용 IP 등록, 계좌 조회 스크립트 실제 실행 확인 (토큰 발급, accountSeq 조회 성공)
- 보유 종목 조회 (`HoldingsService`, `HoldingsQueryRunner`): `/api/v1/holdings` 호출로 평가금액, 손익, 종목별 수량과 손익률 조회
- 계좌 목록을 프로세스 내에서 캐싱해서 중복 조회로 인한 429(호출 제한 초과) 방지

### 개발 환경 문제 해결
- 개발 PC 파워쉘 콘솔 한글 깨짐 수정 (콘솔 UTF-8 인코딩을 프로필에 등록, 실행 정책을 RemoteSigned로 변경)
- PowerShell 스크립트 파일을 BOM 있는 UTF-8로 저장 (BOM 없으면 옛 한글 코드페이지로 오인식), `Get-Content`로 로그 볼 때는 `-Encoding UTF8` 필요

### 체결 감지 웹소켓
- 체결 감지 웹소켓 클라이언트 (`orderevent` 패키지): `wss://openapi-ws.tossinvest.com/ws/v1` 연결, `personal:order` 구독, 60초 PING 유지, 끊기면 지수 백오프로 재연결 후 `GET /api/v1/orders?status=OPEN`으로 재동기화
- 진행중 주문 조회 (`orders` 패키지, `OrderService`): 웹소켓 재동기화용
- 가짜 체결 이벤트 JSON으로 웹소켓 메시지 처리 로직 단위 테스트 (`OrderEventWebSocketHandlerTest`, 실제 API 호출 없음)
- 웹소켓 주문 이벤트 수신을 백그라운드로 띄우고 끄는 스크립트 (`bot/scripts/start-listen.ps1`, `stop-listen.ps1`)
- 실제 소액 매수(해외 종목 1주)로 실제 주문 이벤트 수신 확인: PENDING → REPLACING/REPLACED(호가 재조정) → FILL까지 정상 수신

### 매매 기록 CSV
- 매매 기록 CSV 저장 (`records` 패키지, `TradeRecordWriter`): 웹소켓으로 감지된 실제 체결(FILL, PARTIAL_FILL)만 `records/manual_trades.csv`에 한 줄씩 기록. 계좌 식별값 없음
  - 항목: 시각, 주문ID, 종목, 매수매도구분, 이벤트, 체결수량, 평균체결가, 체결금액, 수수료, 세금, 통화, 주문유형, 상태, 체결시각(filled_at), 출처(source: stream 또는 resync). 뒤의 두 컬럼은 재동기화 도입 때 추가했고 옛 파일은 처음 기록할 때 자동으로 옮김
  - 전략 신호나 청산 사유 등 아직 없는 개념의 컬럼은 만들지 않음. docs/records.md의 나머지 기록 종류(신호, 가상계좌, 실행상태)는 해당 기능이 생기는 단계에서 추가
  - 가짜 체결 이벤트로 CSV 한 줄이 정확히 남는지 단위 테스트 확인

### 시간 기준 청산 (알림 + 자동 매도)
- 시간 기준 청산 알림 (`liquidation` 패키지, v0): 최대 보유 5거래일(docs/strategy.md) 도달 종목을 로그로 알림
  - 보유 시작 시점은 최근 30일 주문 기록을 FIFO로 리플레이해서, 포지션이 0이었다가 다시 쌓인 지점의 첫 매수 체결일로 판단 (동일 종목 반복 매매 대응)
  - 30일 조회 범위 안에서 매수 기록을 못 찾으면 "그보다 오래 보유 중이라 기준을 이미 넘었을 가능성이 높음"으로 알림
  - 거래일은 월~금으로만 계산 (v0: 공휴일 미반영)
  - 평일 장 시작 직후(09:05 KST) 자동 실행 (`TimeExitScheduler`, listen 프로필), `query` 프로필로 즉시 확인도 가능
  - FIFO 판단 로직과 거래일 계산은 가짜 데이터로 단위 테스트 (`PositionEntryResolverTest`, `TradingDayCalculatorTest`)
- 시간 청산 자동 매도 (`liquidation`, `orders` 패키지): 5거래일 기준 도달 시 실제로 시장가 매도 주문을 낸다
  - 기본값은 모의 실행(`damdam.orders.live-mode=false`). 실전 전환은 사용자가 `.env`에 `DAMDAM_LIVE_ORDERS=true`를 직접 추가해야만 켜짐
  - 관리 대상은 "이 기능을 처음 실행한 시각 이후에 새로 산 종목"만 (`ManagedScopeGate`). 그 전부터 갖고 있던 종목(사용자 요청으로 보유 종목 3개 포함)은 추적 여부와 상관없이 알림만 남기고 자동 매도하지 않음
  - 1회 주문 금액 한도: 국내 10만원, 해외 $71 (약 10만원, 환율 1400원 기준). 초과 시 거부하고 알림만
  - 하루 최대 자동 매도 10회 (버그 폭주 방지용 안전장치, 평소엔 걸리지 않을 여유), 연속 손실 3회 시 자동 매도 정지 (`AutoSellGuard`, `bot/data/auto_sell_guard.json`에 상태 저장)
  - 주문 생성 API(`POST /api/v1/orders`) 실패는 예외로 앱을 죽이지 않고 로그로 남기고 다음 주기에 재시도
  - 모의 주문 동작과 안전장치(하루 횟수, 연속 손실)는 가짜 데이터로 단위 테스트 (`OrderPlacementServiceTest`, `AutoSellGuardTest`)
  - 실제 계좌로 실행 확인: 알림은 보유 3종목 중 1종목은 정확한 경과일, 2종목은 30일 초과로 정상 동작. 자동 매도는 모의 실행으로 전체 플로우 확인(기존 보유 종목 전부 정상적으로 제외됨)

### ATR 기반 익절/손절 (조건주문 OCO)
- 과거 캔들 데이터 조회와 ATR 계산 (`market` 패키지)
  - `MarketDataService`: `GET /api/v1/candles`(일봉, `interval=1d`) 호출로 최신순 캔들 목록 조회. 계좌 구분 없는 공개 시세 데이터라 계좌 헤더 불필요
  - `AverageTrueRange`: 진짜 변동폭(True Range)의 단순 평균으로 14거래일 ATR 계산 (`AtrService`). 순수 계산 로직이라 API 호출 없음
  - `AtrQueryRunner`(`query` 프로필): 보유 종목별로 현재가, 14일 ATR, 익절/손절 기준가를 로그로 출력. 실제 계좌로 실행해 값 확인 완료
- 조건주문 OCO 등록/수정/취소/조회 (`conditionalorder` 패키지)
  - `AtrOcoPricing`: 체결가 ± ATR로 익절/손절 감시가 계산. KR은 정수로, US는 공식 문서에 명시된 $1 기준 소수 자리수로 맞춤. 손절 지정가는 확실한 체결을 위해 감시가보다 한 스텝(KR 1원, US 0.0001~0.01) 낮게 건다
    - 호가 단위(tick size) 전체 구간표는 토스 공식 문서에 없어서(예시만 있음) 추측으로 보정하지 않음. 실제 호가 단위와 안 맞으면 API가 400으로 거부하며 올바른 tickSize/nearestPrices를 알려주므로, 그 값으로 자동 보정해 재시도함 (아래 안전장치 보강 참고)
  - `ConditionalOrderService`: `POST /api/v1/conditional-orders`(등록), `POST .../modify`(수정, 종목당 1개 제한이라 추가 매수 시 새로 만들지 않고 기존 걸 수정), `DELETE .../{id}`(취소), `GET /api/v1/conditional-orders`(종목별 진행 중인 조건주문 조회)
  - 시간 청산 자동 매도와 동일하게 `damdam.orders.live-mode`로 실전/모의 전환. 기본값은 모의 실행
  - 개발 세션 중에는 CLAUDE.md 규칙에 따라 등록/수정/취소 API를 직접 호출하지 않음. 모의 실행(가짜 RestClient로 실제 API 미호출 확인) 단위 테스트와 가격 계산 단위 테스트로만 검증 (`ConditionalOrderServiceTest`, `AtrOcoPricingTest`)
- 매수 체결 시 자동 등록/수정 연결 (`AtrOcoManagementService`)
  - 기준가는 평단가(`averagePurchasePrice`). 처음 매수가만 쓰면 추가 매수를 못 반영하고, 최근 체결가만 쓰면 이전 매수분과 무관해져서 이상하다는 사용자 지적으로, 전체 보유분의 실제 손익분기점을 반영하는 평단가로 결정
  - 웹소켓에서 BUY 체결(FILL/PARTIAL_FILL) 감지 시: 현재 보유 수량 + 평단가 + 14일 ATR로 익절/손절가 계산 → 기존 조건주문 있으면 수정, 없으면 새로 등록
  - 조건주문 만료일은 시간 청산 기준(5거래일)을 넉넉히 덮도록 등록일+10일로 설정 (그 전에 시간 청산이 대신 정리함)
  - 매도 체결(FILL) 이벤트를 받은 뒤 남은 OCO를 정리 (`AtrOcoManagementService.syncAfterSellFill`): 보유 0이면 취소, 일부만 팔렸으면 기존 OCO 수량만 조정. 시간 청산 주문 접수 직후에는 취소하지 않음 (아래 안전장치 보강 참고)
  - OCO 갱신/정리 중 오류(네트워크, ATR 계산 실패 등)는 예외로 웹소켓 처리를 막지 않고 로그로만 남김
  - `OrderEventWebSocketHandlerTest`를 도달 불가능한 주소로 업데이트해서, BUY 체결 처리 경로에 OCO 동기화가 끼어도 여전히 예외 없이 통과하는지 확인
  - 실제 계좌 검증: 조회(GET)는 `query` 프로필로 실행 확인. `listen` 프로필로 실제 계좌에 연결한 상태에서 소액 실매수(해외 종목 1주)로 전체 플로우 끝까지 확인 — 웹소켓 FILL 감지(0.4초 내) → 평단가/ATR 계산 → `[모의 조건주문 등록] 1주 OCO 익절 15.00 / 손절 14.09` 로그까지 에러 없이 정상 동작

### 안전장치 보강 (외부 리뷰를 공식 문서와 코드로 검증한 뒤 반영)
- 모의 실행 결과는 안전장치(`AutoSellGuard`)에 기록하지 않음: 실제로 팔리지 않은 손실이 쌓여 실전 전환 때 이미 정지 상태가 되는 문제 수정
- `AutoSellGuard`를 실패 시 닫히는 방식으로 변경: 상태 파일이 있는데 못 읽으면 정지 상태, 저장에 실패하면 저장될 때까지 주문 차단, 임시 파일 후 원자적 교체. 하루 자동 매도 횟수도 파일에 저장해 재시작해도 유지. 단위 테스트 11개 (`AutoSellGuardTest`)
- 시간 청산 매도 접수 직후 OCO를 취소하던 것을 매도 체결 확인 후 취소로 변경. 웹소켓 SELL FILL에서만 정리하고 PARTIAL_FILL에는 반응하지 않음. Mockito 단위 테스트 6개 (`AtrOcoManagementServiceTest`)
- 국내 OCO 호가 단위 자동 보정 (`TickSizeCorrection`, `ConditionalOrderService`): 400 응답의 `data.field`로 어느 지정가인지 판별해 익절(`first.orderPrice`)은 `nearestPrices`의 위쪽, 손절(`second.orderPrice`)은 아래쪽으로 옮겨 다시 보냄. 등록과 수정 모두 적용. 안전 제한: 한 호가를 넘는 이동, 방향이 반대인 값, 모르는 필드, 파싱 불가 응답은 보정하지 않고 실패로 처리해 알림. 재시도는 최대 2회, 재시도마다 새 clientOrderId. 감시가(triggerPrice)는 건드리지 않음. 실제 API 없이 `MockRestServiceServer`로 요청 본문 검증 (`TickSizeCorrectionTest`, `ConditionalOrderServiceLiveTest`). 실제 국내 종목 응답으로는 아직 확인하지 못함
- 웹소켓 재동기화 (`OrderResyncService`): 봇 시작 직후와 모든 재연결 직후, 구독이 확정(ack)된 뒤에 REST로 상태를 다시 맞춤. (1) 최근 7일 진행중/종료 주문의 체결을 CSV에 기록 (2) 끊긴 사이 완전히 팔린 종목의 남은 OCO 정리 (3) 관리 범위 안의 보유 종목 중 OCO가 없거나 수량이 어긋난 것을 등록/수정, 관리 범위 밖의 기존 보유 종목은 건드리지 않음 (`ManagedPositions`). 무언가 반영했으면 알림
  - 중복 방지: `TradeRecordWriter`가 (주문ID, 누적 체결수량)을 키로 삼아 웹소켓과 재동기화에서 같은 체결이 두 번 기록되지 않게 함. 수량 표기 차이("10"과 "10.000")는 숫자로 정규화해 비교, 재시작 후에도 파일에서 키를 다시 읽음
  - 기존에는 재연결 전에 로그만 남기고 봇 시작 때는 아예 호출되지 않았으며, 재연결이 끝나기 전에 실행돼 그 사이 이벤트를 놓칠 수 있었음
  - 조건주문 조회는 토스 앱에서 직접 만든 단일 조건주문도 돌려주므로 OCO 타입만 봇의 관리 대상으로 삼도록 수정 (앱에서 건 조건주문을 봇이 덮어쓰지 않게)
  - 테스트: `TradeRecordWriterTest`, `OrderResyncServiceTest`, 구독 확정 시점 테스트, OCO 타입 필터 테스트
- 토큰 파일 권한을 실제로 동작하는 방식으로 교체 (`TokenService`): POSIX는 `rw-------`, Windows(NTFS)는 소유자만 허용하는 ACL(상속 항목 제거). 내용을 쓰기 전에 빈 파일을 만들어 권한부터 제한. 실패하면 조용히 넘기지 않고 경고. 실제 파일 권한을 검사하는 테스트 (`TokenServiceTest`, 가짜 서버로 발급 흉내, 실제 API 호출 없음)
- 실주문 경로 테스트와 CI: `OrderPlacementService`의 live-mode 경로를 `MockRestServiceServer`로 검증 (주소 `POST /api/v1/orders`, Authorization과 계정 헤더, 본문이 보유 수량 전체 시장가 매도이고 price 없음, 거부는 예외 없이 FAILED, 재시도 없음, 모의 모드는 요청 없음). push마다 `./gradlew test`를 돌리는 GitHub Actions 추가 (`.github/workflows/test.yml`, Java 21 Temurin, 시크릿 불필요, 실패 시 보고서 업로드, 액션 버전은 공식 최신 릴리스 확인). `bot/gradlew`의 git 실행 권한(100755) 설정
- 디스코드 웹훅 알림 (`notification` 패키지): 안전장치 발동(연속 손실 정지, 하루 한도, 상태 파일 문제), OCO 등록/수정/취소 실패, 웹소켓 재연결 연속 5회 실패, 토스 API 403(공용 RestClient 인터셉터 한 곳), 봇 시작과 종료. 웹훅 주소는 `.env`에서만 읽고 디스코드 웹훅 형식만 허용, 로그에는 예외 메시지 대신 상태 코드만 남김. 같은 사건은 10분에 한 번만 전송. `MockRestServiceServer`로 요청 본문, 스로틀, 실패 시 로그에 주소 미노출 검증 (`DiscordNotifierTest`)
- 정지 파일 (`control` 패키지, `TradingHaltSwitch`): `bot/data/STOP`이 있으면 자동 매도와 OCO 등록/수정 중단, 상태가 바뀔 때만 알림. OCO 취소는 막지 않음. 존재 여부를 판단할 수 없으면 정지로 취급. `ControlFileWatcher`(listen 프로필)가 5초마다 확인
- 정상 종료 요청 파일: Windows에서 `Stop-Process`는 종료 훅을 실행하지 못해 종료 알림이 안 나가므로, `stop-listen.ps1`이 `bot/data/shutdown.request`를 만들어 봇이 스스로 정상 종료하게 하고 15초 안에 안 끝나면 강제 종료
- 문서 정리: README(ATR OCO 문구, 구조 트리), CLAUDE.md(현재 단계, 커밋 메시지 한글 규칙), done.md의 실제 종목 코드 일반화, docs/strategy.md(생존 편향 한계, 판단 기준 자리), docs/troubleshooting.md 신설
- `.env` 없는 깨끗한 복제본에서 `./gradlew test` 전체 통과 확인

### 외부 리뷰 반영 (docs/review-tasks.md)
- CLAUDE.md 작업 규칙에 두 가지 추가: 새로 발견한 문제는 실전 전환을 막는지로 처리 여부를 가른다, 외부 수정 제안은 코드와 공식 문서로 검증 후 판단한다. 참고 문서 목록에 docs/review-tasks.md 추가
- 리뷰 항목 하나를 끝낼 때 쓰는 마무리 스킬 추가 (`.claude/skills/review-done`): 관련 테스트 실행, 커밋 승인, docs/review-tasks.md 완료 표시와 todo/done 갱신, 다음 항목 안내까지 절차대로 진행. 공식 문서 확인 결과 슬래시 커맨드와 스킬이 사실상 병합돼 있어 다단계 워크플로우 권장 방식인 스킬로 만들고 `disable-model-invocation: true`로 명시적 호출만 허용
- 해외 종목 시간 청산을 알림만 하도록 수정 (`HoldingTimeExitService`): 통화가 KRW가 아니면 관리 범위(`ManagedScopeGate`)와 무관하게 5거래일 경과 시 디스코드 알림만 보내고 자동 매도는 하지 않음 (`time-exit-overseas-{종목}` 알림 키). 기존에는 관리 범위 안의 해외 종목도 자동 매도 대상이었음. 단위 테스트로 해외/국내 분기 확인 (`HoldingTimeExitServiceTest`)
- 시간 청산 알림 경로를 디스코드로 연결 (`HoldingTimeExitService`): 매수 체결일 미확인, 관리 범위 밖, 주문 한도 초과, 자동 매도 실패, 자동 매도 접수 성공을 로그 대신 디스코드로 알림 (종목+종류별 알림 키로 분리). 매도 체결도 알리도록 `OrderEventWebSocketHandler`에 추가하되, 시간 청산으로 판 것만이 아니라 OCO 익절/손절 트리거를 포함한 모든 매도 체결을 알리는 방식으로 결정 (별도 상관관계 추적 코드 없이 이미 있는 SELL FILL 처리 지점 하나로 커버). 관련 단위 테스트 추가
- ATR 계산에서 오늘 날짜 봉 제외 (`AtrService`): 공식 문서(GET /api/v1/candles)에 첫 봉이 장중 진행 중인 캔들인지 명시돼 있지 않음을 확인. 문서로 확정할 수 없어 확인 여부와 무관하게, 조회한 캔들 중 타임스탬프가 오늘 날짜인 것을 걸러낸 뒤 계산하도록 방어 코드 추가. 여유분 1개를 더 조회해서 오늘 봉이 섞여도 기간을 채울 수 있게 하고, 그래도 부족하면 명확한 예외로 실패(기존 알림 경로가 처리). 단위 테스트 3개 추가 (`AtrServiceTest`)
- OCO 정기 점검 추가 (`OcoPeriodicCheckService`): 기존에는 `ensureOco`가 웹소켓 재연결/봇 시작 때(`OrderResyncService.resync`)만 호출돼, 그 사이 OCO 등록 실패나 매도 체결 직후 보유 조회 지연이 있으면 다음 재연결 전까지 손절 보호가 빌 수 있었음. 관리 종목이 소수(단타 위주)라 순차 호출로도 공식 문서 기준 초당 호출 제한(조건주문 조회 10회, 등록/수정 5회, 보유조회 5회)에 여유가 있어 1분 간격 상시 점검을 추가하고, `TradingHaltSwitch`가 정지 파일 해제 시 `TradingResumedEvent`를 발행해 즉시 한 번 더 점검하게 함. 실제로 보정이 있으면 디스코드로 알림. 단위 테스트 추가 (`OcoPeriodicCheckServiceTest`, `TradingHaltSwitchTest`)
- 연속 손실 판정을 매도 접수 시점에서 실제 체결 결과로 이동 (`AutoSellGuard`, `HoldingTimeExitService`): 기존에는 주문 접수 직전 lastPrice와 평단가만 비교해 손익을 정하고 접수 직후 바로 기록해서, 나중에 거부/미체결된 주문도 세어지고 수수료·세금(국내 왕복 약 0.23%)도 반영되지 않았음. `AutoSellGuard.recordAttempt`를 `recordDailyAttempt()`(접수 시점, 하루 횟수만)와 `recordSellResult(isLoss)`(체결 시점, 연속 손실만)로 분리 — 하루 횟수는 폭주 방지 목적이라 접수 시점 유지를 사용자가 선택. `GuardState` 파일 형식은 그대로라 기존 상태 파일과 호환됨. `HoldingTimeExitService`가 매도 접수 시 orderId→평단가를 메모리에 잠깐 들고 있다가, SELL FILL 체결의 실제 체결금액에서 수수료·세금을 뺀 실현 손익과 원가(평단가×체결수량)를 비교해 손실 여부를 판정(`onAutoSellFilled`). 매수측 수수료가 평단가에 포함되는지는 공식 문서에 없어 확인 못 했고, 매도측만 정확히 반영하기로 함(수수료율이 작아 판정 왜곡 미미). orderId가 등록되지 않은 체결(OCO 트리거, 수동 매도)은 영향 없음. 봇이 접수~체결 사이에 재시작되면 그 1건은 연속 손실 판정에서 빠질 수 있음(메모리 기반이라 감수하기로 함, docs/todo.md 기록). 단위 테스트 추가
- 백테스트 탐색 방향 안건에 세 가지 보강 (docs/todo.md, 코드 없음): 룩어헤드 차단(신호는 장 마감 후 확정, 진입은 다음 거래일 시가 이후로 고정하고 가격은 docs/records.md 가상 체결 규칙과 동일하게), 최종 검증 구간 봉인(가장 최근 구간은 튜닝이 끝날 때까지 열어보지 않음), 시도 횟수 기록(숫자 조합 시도 횟수를 남겨 결과 해석에 반영)

## 2단계: 과열 급락 반등 전략 백테스트

### 과거 캔들 데이터 수집
- `MarketDataService`에 페이지네이션 지원 추가 (`getDailyCandlesPage`, `before` 파라미터, 타임존 오프셋 `+` URL 인코딩 처리)
- `CandleHistoryExporter`(`backtest` 패키지): 한 종목의 일봉을 과거로 계속 페이지네이션해서 모으고, 오래된 것부터 오름차순으로 CSV 저장 (`analysis/data/<종목코드>_daily.csv`). 429 호출 제한 시 `Retry-After` 헤더만큼 대기 후 1회 재시도. 안전 상한 50페이지(1만 봉)
- `CandleExportRunner`(`export-candles` 프로필): `./gradlew bootRun --args='--spring.profiles.active=export-candles <종목코드>'`로 실행
- 실제 계좌로 삼성전자(005930) 수집 테스트: 안전 상한(1만 봉)에 걸릴 때까지도 1988년 데이터가 나와서, 캔들 히스토리 깊이는 백테스트에 문제없다는 것 확인 (docs/strategy.md 참고)
- CSV는 재생성 가능한 데이터라 `.gitignore`에 `analysis/data/` 추가

### Python 분석 환경
- `analysis/.venv`(가상환경) + pandas 3.0.6 (`analysis/requirements.txt`로 버전 고정)
- 실제 생성된 삼성전자 캔들 CSV를 pandas로 읽어 정상 동작 확인 (10,000행, 시각/가격/거래량 타입 정상 인식)

### 매매 수수료 조회
- 매매 수수료 조회 (`commission` 패키지, `CommissionService`, `CommissionQueryRunner`): `GET /api/v1/commissions` 호출로 계좌의 시장별 실제 수수료율 조회
- 실제 계좌로 확인: 국내(KR) 0.00015(2021-01-01부터 사실상 무기한), 해외(US) 0.001(종료일 2026-09-19, 프로모션 요율 가능성 있어 재확인 필요)

### 백테스트 대상 종목군 수집
- 종목 기본 정보 조회 (`stocks` 패키지, `StockInfoService`): `GET /api/v1/stocks`로 `securityType`, `isCommonShare`, `status` 등 조회
- 랭킹 조회 (`ranking` 패키지, `RankingService`): `GET /api/v1/rankings`로 국내 거래대금 상위 종목 조회 (계좌 무관, 토큰만으로 호출)
- `UniverseExportRunner`(`export-universe` 프로필): 거래대금 상위 풀(기본 100종목)을 받아 `StockInfoService`로 걸러서 ETF/ETN(레버리지·인버스 상품 등)을 제외한 일반 보통주만 남기고, 상위 N개(기본 30개)의 캔들 히스토리를 수집
  - ETF를 안 거르면 거래대금 상위에 KODEX 200, 레버리지/인버스 상품 등이 섞여 들어와서 "대형주/중형주" 취지와 안 맞음. 실제로 100종목 중 걸러내고 나니 44위까지 내려가야 보통주 30개가 채워짐
  - 실제 계좌로 실행 확인: SK하이닉스, 삼성전자, 현대차, NAVER, 삼성SDI 등 실제 대형주 30종목의 캔들 CSV를 `analysis/data/`에 저장 완료
