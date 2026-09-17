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
