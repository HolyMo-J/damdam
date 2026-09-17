# 완료한 것

완료된 작업 이력. 스테이지나 기능 단위로 정리한다. 진행중이거나 다음에 할 일은 docs/todo.md 참고.

## 1단계: 조회와 청산 봇
- 프로젝트 폴더 구조(bot, analysis, records, docs)와 보안 설정(.claude/settings.json, .gitignore, .env.example) 구성
- 깃허브 저장소(HolyMo-J/damdam) 연결, main 브랜치 push
- Java 21, Python, Git 설치 확인
- 토스증권 Open API 키 발급
- `bot/`에 Spring Boot 4.1.1 + Java 21 Gradle 프로젝트 초기화 (web, websocket 의존성)
- 토큰 관리 서비스 (`TokenService`): `/oauth2/token` 호출로 액세스 토큰 발급, `bot/data/token.json`에 저장해 재사용, 소유자만 읽기/쓰기 가능하게 권한 제한
- 계좌 조회 (`AccountService`, `AccountQueryRunner`): `/api/v1/accounts` 호출로 계좌 목록과 accountSeq 조회, 계좌번호는 뒷 4자리만 노출
- `.env` 파일을 Spring이 직접 읽도록 설정 (외부 라이브러리 없이 `spring.config.import` 사용)
- 조회 스크립트는 `query` 프로필일 때만 실행되게 분리해서, 기본 빌드나 테스트에서 실제 API가 자동 호출되지 않게 함
- `./gradlew build` 통과 확인 (실제 API 호출 없이)
- 허용 IP 등록, 계좌 조회 스크립트 실제 실행 확인 (토큰 발급, accountSeq 조회 성공)
- 보유 종목 조회 (`HoldingsService`, `HoldingsQueryRunner`): `/api/v1/holdings` 호출로 평가금액, 손익, 종목별 수량과 손익률 조회
- 계좌 목록을 프로세스 내에서 캐싱해서 중복 조회로 인한 429(호출 제한 초과) 방지
- 개발 PC 파워쉘 콘솔 한글 깨짐 수정 (콘솔 UTF-8 인코딩을 프로필에 등록, 실행 정책을 RemoteSigned로 변경)
- 체결 감지 웹소켓 클라이언트 (`orderevent` 패키지): `wss://openapi-ws.tossinvest.com/ws/v1` 연결, `personal:order` 구독, 60초 PING 유지, 끊기면 지수 백오프로 재연결 후 `GET /api/v1/orders?status=OPEN`으로 재동기화
- 진행중 주문 조회 (`orders` 패키지, `OrderService`): 웹소켓 재동기화용
- 가짜 체결 이벤트 JSON으로 웹소켓 메시지 처리 로직 단위 테스트 (`OrderEventWebSocketHandlerTest`, 실제 API 호출 없음)
- 웹소켓 주문 이벤트 수신을 백그라운드로 띄우고 끄는 스크립트 (`bot/scripts/start-listen.ps1`, `stop-listen.ps1`), 실제 실행 확인 완료
- 개발 PC 한글 깨짐 추가 수정: PowerShell 스크립트 파일을 BOM 있는 UTF-8로 저장 (BOM 없으면 옛 한글 코드페이지로 오인식), `Get-Content`로 로그 볼 때는 `-Encoding UTF8` 필요
