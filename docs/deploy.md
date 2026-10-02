# 봇 서버 배포 (오라클 클라우드 기준, 업체와 무관하게 Ubuntu와 SSH만 있으면 같은 절차)

집 PC 대신 상시 켜져 있는 봇 서버(오라클 클라우드든 다른 업체든)에서 체결 감지 봇(`listen` 프로필)을 돌리기 위한 절차. 집 PC를 계속 쓰는 경우에는 이 절차가 필요 없다. 배경과 서버 선택 이유는 docs/todo.md "서버 이전" 참고.

가입, 인스턴스 생성, 공인 IP 확보는 본인 확인/결제 정보가 들어가는 웹 콘솔 작업이라 사용자가 직접 한다. 이 문서는 SSH로 접속 가능한 서버가 준비된 뒤부터의 절차다.

## 준비물
- 오라클 클라우드 인스턴스 (Always Free, Ubuntu 권장), 공인 IP(Reserved Public IP), SSH 접속 정보
- 로컬에서 서버로 파일을 옮길 방법 (git 저장소를 서버에서 clone하는 방식을 기본으로 한다)

## 1. 서버 초기 설정
```
# 패키지 갱신, JRE 21 설치 (Ubuntu 기준, 실제 패키지 이름은 배포판에 맞게 확인).
# jar는 GitHub Actions에서 빌드해 받으므로 서버에 JDK와 gradle은 필요 없다 (아래 "메모리" 참고)
sudo apt update && sudo apt install -y openjdk-21-jre-headless git
java -version   # 21인지 확인
which java      # systemd 서비스 파일에 쓸 절대 경로 (예: /usr/bin/java)

# 서버 시간대를 한국으로 맞춘다 (클라우드 이미지는 보통 UTC. 오라클 공식 문서에서는 기본값을 확인 못 함)
timedatectl                              # 먼저 Time zone 항목으로 현재 값 확인
sudo timedatectl set-timezone Asia/Seoul
timedatectl                              # Time zone: Asia/Seoul (KST, +0900)으로 바뀌었는지 확인
# 서비스가 이미 떠 있었다면 JVM이 시간대를 시작 시점에 읽으므로 재시작해야 반영된다

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

# jar는 서버에서 빌드하지 않고 GitHub Release에서 받는다 (서비스 파일이 이 경로를 그대로 쓴다)
mkdir -p bot/build/libs && cd bot/build/libs
curl -fLO https://github.com/HolyMo-J/damdam/releases/download/<태그>/bot-0.0.1-SNAPSHOT.jar
curl -fLO https://github.com/HolyMo-J/damdam/releases/download/<태그>/bot-0.0.1-SNAPSHOT.jar.sha256
sha256sum -c bot-0.0.1-SNAPSHOT.jar.sha256   # "bot-0.0.1-SNAPSHOT.jar: OK"가 나와야 한다
```
- 릴리스 만들기: GitHub 저장소의 Actions 탭에서 `release-jar` 워크플로를 수동 실행하고 태그(예: `v0.1.0`)를 입력한다. 테스트가 통과해야 jar가 올라간다 (`.github/workflows/release-jar.yml`). push마다 자동으로 돌지 않고, 서버로 직접 배포하지도 않는다. 서버 SSH 키 같은 시크릿을 GitHub에 두지 않으려는 선택이다
- sha256은 받는 도중 파일이 깨졌는지만 막는다. jar와 sha256이 같은 곳에서 오므로 GitHub 자체가 뚫린 경우까지 막지는 못한다
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

## 메모리 (AMD 무료 인스턴스 1GB 기준)
- 오라클 공식 문서(Always Free Resources)에서 AMD `VM.Standard.E2.1.Micro`는 메모리 1GB, CPU 1/8 OCPU다. ARM(A1) 무료 인스턴스는 합계 2 OCPU와 메모리 12GB라서 ARM을 만들면 이 절은 해당 없다 (서비스 파일의 `-Xmx256m`도 키워도 된다). 순수 Java jar라 집 PC나 Actions에서 빌드한 jar가 ARM에서도 돌 것으로 보지만 실제로는 확인 못 함
- 서버에서 빌드하지 않는 이유: 1GB 안에 OS와 Gradle 프로세스까지 올라간다. 로컬 시험에서 Gradle 힙을 256MB로 줄여도 빌드는 성공했고 64MB에서 실패했다. 다만 이는 힙만 잰 것이라 서버에서 실제로 부족한지는 확인 못 함
- 서버에서 직접 빌드해야 한다면(Actions를 못 쓸 때) 스왑을 먼저 만들고 `./gradlew bootJar --no-daemon -Dorg.gradle.jvmargs=-Xmx384m`로 빌드한다. 집 PC에서 `bootJar`를 만들어 `scp`로 올리는 방법도 있다
- 서비스 파일의 JVM 옵션: `-Xmx256m`은 힙 상한을 명시하고, `-XX:+ExitOnOutOfMemoryError`는 메모리가 부족하면 종료해서 `Restart=on-failure`로 다시 뜨게 한다. 256MB가 충분한지는 실측 전이다. 가동 후 며칠은 `systemctl status damdam-bot`의 `Memory:` 줄과 `journalctl -u damdam-bot | grep -i OutOfMemory`로 확인하고, 부족하면 값을 올린다
- 스왑 1GB(선택, 실행 중 메모리 부족으로 봇이 죽는 것을 막는 보험). 먼저 `swapon --show`로 이미 있는지 본다 (오라클 이미지 기본값은 확인 못 함). 비어 있을 때만 만든다. 디스크를 쓰는 비상용이라 느리다
```
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # 재부팅 뒤에도 유지
free -h   # Swap 줄 확인
```

## 봇 업데이트
- 새 릴리스를 만든 뒤 서버에서 jar를 다시 받고 `sha256sum -c`로 확인한 다음 재시작한다. 재시작하면 웹소켓이 끊기고 OCO 동기화가 한 번 다시 돈다. 장중이 아닌 시간에 한다
```
cd ~/damdam/bot/build/libs
curl -fLO https://github.com/HolyMo-J/damdam/releases/download/<새 태그>/bot-0.0.1-SNAPSHOT.jar
curl -fLO https://github.com/HolyMo-J/damdam/releases/download/<새 태그>/bot-0.0.1-SNAPSHOT.jar.sha256
sha256sum -c bot-0.0.1-SNAPSHOT.jar.sha256
sudo systemctl restart damdam-bot
```

## 서버가 바뀌면서 달라지는 것
- 집 PC에서 쌓은 가동 검증 기간(docs/live-checklist.md)은 새 환경 기준으로 다시 센다 (새 인프라에서의 가동 검증이 목적이라 환경이 바뀌면 처음부터 다시 봐야 함)
- 토큰이 새로 발급되므로 집 PC의 봇은 더 이상 API를 호출할 수 없다. 서버와 집 PC를 동시에 띄워두지 않는다

## 확인 못 함 (진행하면서 정할 것)
- `release-jar` 워크플로는 아직 한 번도 실행하지 않았다. 첫 실행에서 실패할 수 있고, 릴리스 파일을 토큰 없이 `curl`로 받을 수 있는지는 다른 공개 저장소로만 시험했다
- 서버에서 `-Xmx256m`이 충분한지, 오라클 이미지에 스왑이 기본으로 있는지, 1/8 OCPU에서 봇이 지연 없이 도는지
- 오라클 Always Free의 유휴 인스턴스 자동 회수 정책(7일간 CPU/네트워크 사용률 20% 미만이면 회수 가능)에 이 봇(대부분 조용히 대기)이 실제로 걸리는지, 더미 트래픽으로 피할 수 있는지, 회수 임박 알림이 오는지는 실제로 운영해봐야 안다 (docs/todo.md 참고)

## 서버 선택 기록 (2026-09-20~2026-09-29, 결정 근거와 조사 결과)

서버를 오라클 클라우드로 정한 이유, 후보 비교, 가입 실패 상황, 대안 순서를 todo.md에서 옮겨 온 기록이다 (2026-10-02, 내용은 그대로). 지금 해야 할 일과 상태는 todo.md "서버 이전"에 있다. 이 문서의 1~5절은 업체와 무관하게 쓴다.

- 계기: 실주문 전환 체크리스트에 따라 가동 검증 기간을 집 PC에서 시작했는데(2026-09-23 1일차), 사용자가 평일 낮에 집을 자주 비우고 컴퓨터는 보통 저녁에만 쓰는 생활 패턴이라 "장중에 컴퓨터를 계속 켜놔야 한다"는 조건 자체가 부담스럽다는 게 드러났다. 원래 "실주문 전환이 가까워지면 재검토"하기로 미뤄뒀던 서버 이전 고민을 지금 하기로 함
- 후보 비교: 클로드는 무료(오라클)의 유휴 인스턴스 회수 위험(7일간 CPU/네트워크 사용률 20% 미만이면 회수 가능, 이 봇은 대부분 조용히 대기하는 패턴이라 걸릴 가능성 있음)을 이유로 저비용 유료 VPS(Vultr $5~6/월, 카페24 월 9,000원)를 권장했으나, 사용자가 오라클 클라우드 방향으로 진행하기로 결정함
- 2026-09-20 조사 결과 (진행할 때 여기부터 이어간다)
  - 오라클 클라우드 Always Free: 공인 IP(Reserved Public IP)는 완전 무료라 고정 IP 조건은 맞음. 사양은 2026-06-15에 4 OCPU/24GB에서 2 OCPU/12GB로 축소됐고 신규 계정은 UI상 더 낮게 제한될 수 있음. 가장 큰 위험은 유휴 인스턴스 자동 회수 정책(7일간 CPU/네트워크 사용률 20% 미만이면 회수 가능)인데, 우리 봇은 대부분 조용히 대기하는 패턴이라 걸릴 가능성이 있음. 리전에 따라 신규 인스턴스 생성 자체가 재고 부족으로 실패하는 경우도 흔함. 실제로 되는지는 가입해서 만들어봐야 확인 가능. **회수 위험 대응은 진행하면서 구체적으로 정해야 함** (예: 더미 트래픽으로 사용률을 20% 이상 유지하는 방법이 있는지, 회수 임박 알림이 오는지 등을 가입 후 확인)
  - GCP e2-micro: Always Free지만 리전이 미국 3곳(us-west1/central1/east1)뿐, 사양이 더 작음(공유 vCPU 1개, RAM 1GB), 월 무료 트래픽 1GB로 부족할 수 있음. 2024년부터 공인 IP 자체에 시간당 과금이 붙기 시작해서 완전 무료가 아닐 가능성이 큼 (정확한 무료 한도는 확인 못 함)
  - AWS, Azure: 상시 무료 VM이 아님. AWS는 2025-07-15 이후 신규 가입 시 12개월 무료 대신 6개월 $200 크레딧으로 바뀜, Azure는 처음부터 12개월만 무료
  - 유료 최저가 비교(참고용, 채택 안 함): 카페24 리눅스 VPS 월 9,000원(+최초 설치비 22,000원, 1GB RAM/30GB/300GB 트래픽), 가비아는 공공기관 전용 페이지 기준 월 25,000원대(일반 상품 정확한 가격은 확인 못 함), Vultr 서울 $5~6/월, 네이버클라우드(NCP)는 서버비 1년 무료지만 공인 IP는 처음부터 별도 과금이고 1년 뒤 유료 전환
  - GCP/AWS 계정을 무료 체험 기간마다 새로 만들어 돌려쓰는 방안도 논의했으나, 약관 위반으로 계정이 예고 없이 정지될 위험과 계정을 바꿀 때마다 토스 허용 IP를 재등록해야 하는 운영 부담 때문에 보류하기로 함
  - 노트북을 상시 충전 상태로 돌리는 방안은 검토 결과 기각: 지금 집 PC와 같은 집, 같은 회선에 있어 정전/인터넷 장애 위험이 그대로고, 뚜껑 닫힘 절전 설정과 배터리 열화 문제까지 추가됨. 서버 이전의 대안이 아니라 지금 상태 유지에 가까움
- 2026-09-29 오라클 가입 실패 상황: 2회 이상 "Sorry, an error occurred while creating your account" 오류 (시크릿 창, VPN 미사용, 국내 해외결제 가능 신용카드, 한글/영어 주소 모두 재현). 2026-06-24에 같은 이메일로 이메일 인증 전 단계까지 진행했다 중단한 이력이 있어 "다중 계정 시도"로 걸렸을 가능성은 있으나 확인 못 함. 오라클 고객센터에 티켓 접수하고 답변 대기 중이고, 티켓 답이 오기 전에 다른 이메일로 새로 가입하지 않는다 (시도가 더 쌓일 위험)
  - 대기 기한은 영업일 3~5일. 기한 안에 답이 없으면 대안으로 넘어간다. 대안 순서(권장, 2026-09-29 사용자 결정으로 Lightsail에서 AWS로 변경): AWS 서울 리전 (사용자가 AWS 인프라 작업 경험이 있고 서울 리전 지원도 사용자가 확인함. 신규 가입 크레딧은 사용자 기억으로 6개월간 총 $200 (기본 $100 + 퀘스트 5개 x $20)이고, 위 "AWS는 2025-07-15 이후 신규 가입 시 6개월 $200 크레딧" 조사와 일치하나 세부 조건은 가입 화면에서 확인 필요. 확인 필요: 서비스 종류 선택, 크레딧 소진 뒤 요금, 고정 공인 IP의 과금 (AWS는 퍼블릭 IPv4 주소에 시간당 요금이 붙는 것으로 알고 있으나 확인 못 함)) -> Vultr 서울 (월 $5~6). GCP e2-micro는 고정 IP 과금 가능성과 미국 리전 지연 때문에 후순위
  - 토스 FAQ 확인 결과: IP가 바뀌는 환경은 차단되므로 고정 IP가 필수이고, 클라우드나 데이터센터 대역이라는 이유로 차단하지는 않는다. docs/deploy.md 1~5절는 Ubuntu와 SSH만 있으면 업체와 무관하게 그대로 쓴다
