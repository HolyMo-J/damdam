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

## 6. 서버 보안 기본값 (2026-10-03 초안, 서버에서 아직 시험하지 못함)
서버에 토스 API 키와 디스코드 웹훅 주소가 올라가므로 배포 직후에 아래를 확인한다 (docs/review-tasks.md E묶음 3번).
```
# .env는 봇 사용자만 읽게 하고, 토큰과 안전장치 상태가 있는 폴더도 같은 사용자만 열게 한다
chmod 600 ~/damdam/.env
chmod 700 ~/damdam/bot/data
ls -l ~/damdam/.env           # -rw------- damdam damdam 이어야 한다

# SSH가 비밀번호 로그인을 막고 키로만 접속하는지, root 로그인이 막혔는지 확인한다
sudo sshd -T | grep -Ei 'passwordauthentication|permitrootlogin|pubkeyauthentication'
# 기대값: passwordauthentication no, pubkeyauthentication yes, permitrootlogin은 no 또는 prohibit-password
```
- SSH 키 접속: 오라클 공식 문서(Managing Key Pairs on Linux Instances)에 Oracle Linux, Ubuntu 등 오라클 Linux 이미지는 비밀번호 대신 SSH 키 쌍으로 접속하고, 기본 SSH 설정이 개인 키로만 로그인을 허용한다고 되어 있다. 그래서 따로 끌 필요는 없지만 위 `sshd -T`로 실제 값을 확인한다 (`sshd_config`를 고쳤다면 `sudo systemctl reload ssh`). 개인 키 파일은 서버가 아니라 접속하는 PC에만 둔다
- 인바운드 포트: 오라클 공식 문서(Security Lists)에 따르면 VCN의 기본 보안 목록은 SSH(TCP 22) 허용 규칙과 ICMP 규칙 두 개를 기본으로 둔다. 이 봇은 서버 쪽에서 포트를 여는 일이 없다 (체결 감지 웹소켓과 REST 호출은 모두 바깥으로 나가는 연결이고, 1단계에서는 외부에 여는 API를 만들지 않는다). 그래서 22번 외에는 보안 목록에 인바운드 규칙을 **추가하지 않는다**. 서버를 만든 뒤 콘솔의 보안 목록에서 실제 인바운드 규칙 목록을 눈으로 확인한다
- 22번의 허용 범위: 기본 규칙은 모든 주소(0.0.0.0/0)에서 22번을 받는 형태가 일반적이다(확인 못 함, 콘솔에서 확인). 집 PC 공인 IP가 고정이면 그 주소로 좁힐 수 있지만, IP가 바뀌면 SSH로 못 들어가므로 키 접속과 `fail2ban` 같은 방어와 비교해 정한다 (정해지지 않음)
- 확인 못 함: 오라클 Ubuntu 이미지에 호스트 방화벽(iptables 규칙)이 기본으로 켜져 있는지, 자동 보안 업데이트(`unattended-upgrades`)가 기본으로 켜져 있는지는 서버를 만든 뒤 `sudo iptables -L -n`, `systemctl status unattended-upgrades`로 확인한다
- 토스 WTS 허용 IP에는 서버의 공인 IP만 둔다 (4절). 집 PC IP를 오래 남겨 두지 않는다

## 7. 거래 기록 백업 (2026-10-03 초안, 서버에서 아직 시험하지 못함)
서버로 옮기면 `records/`의 CSV와 `bot/data/`의 안전장치 상태가 서버에만 남는다. 저장소가 공개라 git에 올릴 수도 없고, 오라클이 유휴 인스턴스를 회수하거나 서버를 다시 만들면 1단계 판단과 3단계 가상매매 판단에 쓸 기록이 사라진다 (docs/review-tasks.md E묶음 4번).

백업 대상:
- 넣는다: `records/` 전체(직접 매매 기록, 가상매매 원장 `records/paper/`, 신호 관찰 기록, 실측 로그), `bot/data/auto_sell_guard.json`, `bot/data/auto_sell_scope_start.txt`, 가상매매 상태 `bot/data/paper/`
- 뺀다: `bot/data/token.json`(토큰, 새로 발급받으면 되고 유출되면 안 된다), `bot/data/STOP`, `bot/data/shutdown.request`, `.env`(키, 시크릿, 웹훅 주소). 기록 파일에는 계좌 식별값이 없지만(docs/records.md) 백업 위치가 공개되면 안 된다는 점은 같다

방법 비교:

| 방법 | 장점 | 단점 |
| --- | --- | --- |
| 가. 집 PC가 서버에서 하루 한 번 당겨 온다 (SSH 키와 `scp`, Windows 작업 스케줄러) | 서버에 외부 저장소 자격증명이 남지 않는다. 무료다. 서버가 압축본을 며칠치 보관하므로 집 PC가 꺼져 있던 날도 다음에 켜질 때 받으면 빠짐이 없다 | 집 PC 한 곳에 의존한다 (집 PC가 고장 나면 복사본이 없다). 집 PC에서 서버로 SSH가 되어야 한다 |
| 나. 서버가 외부 저장소(오라클 Object Storage, 다른 클라우드 등)로 올린다 | 집 PC가 필요 없다 | 서버에 저장소 자격증명을 두어야 하고(서버가 뚫리면 같이 노출), 같은 오라클 계정이면 계정 문제가 생길 때 서버와 함께 잃는다 |
| 다. 서버가 비공개 git 저장소로 push한다 | 이력이 남는다 | 쓰기 권한이 있는 키가 서버에 남고, CSV가 쌓이면 저장소가 커진다 |

**권장은 가**: 서버 쪽에 새 자격증명을 늘리지 않는 것이 가장 중요하다. 집 PC 한 곳 의존은 프로젝트 폴더가 이미 OneDrive 아래에 있어서(경로 `...\OneDrive\...\damdam`) 받는 폴더를 그 안에 두면 두 번째 사본이 생길 수 있다. 다만 OneDrive가 `records/`를 실제로 동기화하는지는 확인 못 함이다.

서버 쪽 초안 (damdam 사용자의 `crontab -e`, 가상매매 저녁 실행과 겹치지 않게 아침 시간):
```
# 매일 06:30(서버 시간대 Asia/Seoul)에 압축본을 만들고 7일 지난 것을 지운다
30 6 * * * mkdir -p ~/backup && tar czf ~/backup/damdam-$(date +\%F).tar.gz -C ~/damdam --exclude='token.json' --exclude='STOP' --exclude='shutdown.request' records bot/data && chmod 600 ~/backup/damdam-*.tar.gz && find ~/backup -name 'damdam-*.tar.gz' -mtime +7 -delete
```
집 PC 쪽 초안 (PowerShell, Windows 작업 스케줄러로 하루 한 번. OpenSSH 클라이언트가 설치돼 있어야 하고 설치 여부는 확인 못 함):
```
scp -i <개인 키 경로> damdam@<서버 공인 IP>:backup/damdam-*.tar.gz <받을 폴더>
```
- 가상매매 원장은 CSV를 먼저 쓰고 상태 JSON을 마지막에 교체하는 구조라서(docs/strategy.md "구현 현황"), 쓰는 도중에 압축해도 다음 정산에서 다시 계산하면 맞춰진다. 그래도 저녁 실행 시간대는 피한다
- 복원 시험: 한 달에 한 번 압축본을 다른 폴더에 풀어 CSV가 열리는지, 상태 JSON이 읽히는지 본다. 풀기만 하고 `bot/data`에 덮어쓰지 않는다 (덮어쓰면 서버의 상태가 과거로 돌아간다)
- 확인 못 함: 위 명령은 서버에서 실행해 보지 않았다. 7일 보관이 충분한지, 집 PC에서 서버로의 SSH가 서버 보안 규칙(6절)과 충돌하지 않는지도 서버가 생긴 뒤에 본다

## 8. 가상매매 러너 가동 (집 PC, 서버 이전 전, 2026-10-03 초안)
구현 (3-a)로 만든 `paper-run` 프로필 러너를 저녁에 한 번 돌리는 절차와 첫 가동 전 확인 사항이다. 아직 한 번도 가동하지 않았다. 구현은 docs/strategy.md "구현 현황"과 `PaperRunner` 클래스 주석에 있다.
- (3-c): 작업 스케줄러 등록 절차 문서. 평일만 실행, 작업 폴더는 `bot/`(상대 경로 설정이 작업 폴더 기준이라 틀리면 `ledger_config.json`이 없어 시작을 거부한다), 시각은 21:00(2026-10-03 사용자 승인), 처음 한 번만 `--init-ledger`를 붙인다. 시스템 설정이라 등록은 사용자가 직접 한다
- **첫 가동 전에 확인할 것**: (a) 기록 폴더(`records/paper`)의 CSV를 엑셀로 열어 둔 채 돌리지 않는다. 다른 프로세스가 `FileShare.Read`로 열어 둔 파일 위로 `ATOMIC_MOVE` 교체가 `AccessDeniedException`으로 실패하는 것을 스크래치 실험으로 확인했다 (엑셀이 정확히 그 공유 모드인지는 확인 못 함). 원장의 모든 CSV에 해당하고 복사본으로 열면 피한다 (b) 신호 시각 기준(순위 20:15, 매매동향 20:20)은 관찰 3일치로 정한 값이라 정상인 날이 공백으로 막히면 값을 다시 본다 (c) 전략 버전 이름 `v1`과 슬리피지 0.2%는 첫 실행 뒤에 같은 폴더에서 바꿀 수 없다 (d) 디스코드 웹훅(`DAMDAM_DISCORD_WEBHOOK_URL`)이 비면 알림이 아예 안 나간다
- 알려진 한계: 조회 실패가 있어도 신호 판정 종목이 절반 이상이면 신호는 정상 저장되고 [주의] 알림만 간다 (종료 코드는 0, 작업 스케줄러 결과는 성공으로 보임). 일봉 확정 시각(전략 B)은 여전히 모름이고 실행 시각 20:30 이후가 유일한 근거다

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
