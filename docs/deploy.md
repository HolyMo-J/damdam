# 서버 배포 (오라클 클라우드)

집 PC 대신 상시 켜져 있는 서버에서 체결 감지 봇(`listen` 프로필)을 돌리기 위한 절차. 배경과 서버 선택 이유는 docs/todo.md "서버 이전" 참고.

가입, 인스턴스 생성, 공인 IP 확보는 본인 확인/결제 정보가 들어가는 웹 콘솔 작업이라 사용자가 직접 한다. 이 문서는 SSH로 접속 가능한 서버가 준비된 뒤부터의 절차다.

## 준비물
- 오라클 클라우드 인스턴스 (Always Free, Ubuntu 권장), 공인 IP(Reserved Public IP), SSH 접속 정보
- 로컬에서 서버로 파일을 옮길 방법 (git 저장소를 서버에서 clone하는 방식을 기본으로 한다)

## 1. 서버 초기 설정
```
# 패키지 갱신, JDK 21 설치 (Ubuntu 기준, 실제 버전 이름은 배포판에 맞게 확인)
sudo apt update && sudo apt install -y openjdk-21-jdk git
java -version   # 21인지 확인
which java      # systemd 서비스 파일에 쓸 절대 경로 (예: /usr/bin/java)

# 봇 전용 사용자 생성 (root로 돌리지 않는다)
sudo useradd -m -s /bin/bash damdam
sudo su - damdam
```

## 2. 코드 배포
`damdam` 사용자로 진행한다.
```
git clone <저장소 주소> ~/damdam
cd ~/damdam
cp .env.example .env
nano .env   # TOSS_API_KEY, TOSS_API_SECRET, DAMDAM_DISCORD_WEBHOOK_URL 채움 (DAMDAM_LIVE_ORDERS는 아직 추가하지 않음, 기본값 모의 실행 유지)

cd bot
chmod +x gradlew
./gradlew bootJar   # build/libs/bot-0.0.1-SNAPSHOT.jar 생성 확인
```
- `.env`는 git에 올라가지 않으므로 서버에서 직접 값을 채워야 한다 (집 PC의 `.env`를 그대로 복사해도 되지만, 키/시크릿을 다른 경로로 옮기는 것이므로 신중하게 다룬다)
- `bot/data/`(토큰 파일, 안전장치 상태)는 git에서 제외되므로 서버에서 새로 생성된다. 첫 실행 시 토큰이 새로 발급되며 **집 PC에서 쓰던 토큰은 무효화된다** (토큰은 클라이언트당 1개만 유효, CLAUDE.md 참고)

## 3. systemd 서비스 등록
`bot/scripts/linux/damdam-bot.service`를 서버로 복사한 뒤, `<damdam-linux-user>`, `<install-path>`, `<java-path>`를 실제 값으로 바꾼다.
```
sudo cp ~/damdam/bot/scripts/linux/damdam-bot.service /etc/systemd/system/damdam-bot.service
sudo nano /etc/systemd/system/damdam-bot.service   # 위 세 값 수정
sudo systemctl daemon-reload
sudo systemctl enable --now damdam-bot
sudo systemctl status damdam-bot
```
Windows의 `start-listen.ps1`/`stop-listen.ps1`과 달리 별도 스크립트가 필요 없다. `systemctl stop`이 보내는 SIGTERM은 JVM 종료 훅(`OrderStreamRunner`)을 정상적으로 거치므로, 종료 요청 파일(`shutdown.request`) 트릭 없이도 종료 알림과 정리가 이뤄진다.

## 4. 토스 WTS 허용 IP 재등록
서버의 공인 IP를 토스 WTS 설정의 허용 IP 목록에 등록한다. 집 PC의 기존 IP는 더 이상 쓰지 않으면 지워도 되지만, 당장은 남겨둬도 무방하다(허용 목록에 여러 개를 둘 수 있다면). 등록하지 않으면 403이 발생한다.

## 5. 동작 확인
```
journalctl -u damdam-bot -f          # 실시간 로그
journalctl -u damdam-bot --since "10 min ago"
```
- 확인할 것: 토큰 발급 성공, 웹소켓 연결(`구독 확정`) 로그, 403/에러 없음, 디스코드로 "봇 시작" 알림 수신
- `sudo systemctl stop damdam-bot` 실행 후 디스코드로 "봇 종료" 알림이 오는지 확인 (안 오면 `TimeoutStopSec`이 너무 짧거나 종료 훅에 문제가 있다는 뜻)
- 정지 파일(`bot/data/STOP`)과 재개도 서버에서 동일하게 동작하는지 확인 (README "운영" 참고)

## 서버가 바뀌면서 달라지는 것
- 집 PC에서 쌓은 모의 관찰 기간(docs/live-checklist.md)은 새 환경 기준으로 다시 센다 (새 인프라에서의 가동 검증이 목적이라 환경이 바뀌면 처음부터 다시 봐야 함)
- 토큰이 새로 발급되므로 집 PC의 봇은 더 이상 API를 호출할 수 없다. 서버와 집 PC를 동시에 띄워두지 않는다

## 확인 못 함 (진행하면서 정할 것)
- 오라클 Always Free의 유휴 인스턴스 자동 회수 정책(7일간 CPU/네트워크 사용률 20% 미만이면 회수 가능)에 이 봇(대부분 조용히 대기)이 실제로 걸리는지, 더미 트래픽으로 피할 수 있는지, 회수 임박 알림이 오는지는 실제로 운영해봐야 안다 (docs/todo.md 참고)
