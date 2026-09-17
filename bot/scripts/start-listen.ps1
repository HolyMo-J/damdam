# 체결 감지 봇을 창 없이 백그라운드로 시작한다 (listen 프로필)
$ErrorActionPreference = "Stop"

$botDir = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $botDir "build\libs\bot-0.0.1-SNAPSHOT.jar"
$dataDir = Join-Path $botDir "data"
if (-not (Test-Path $dataDir)) { New-Item -ItemType Directory -Force -Path $dataDir | Out-Null }

$pidFile = Join-Path $dataDir "listen.pid"
$outLog = Join-Path $dataDir "listen.out.log"
$errLog = Join-Path $dataDir "listen.err.log"

if (Test-Path $pidFile) {
    $existingPid = Get-Content $pidFile
    if ($existingPid -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
        Write-Host "이미 실행 중입니다 (PID $existingPid)."
        exit
    }
}

if (-not (Test-Path $jar)) {
    Write-Host "jar 파일이 없습니다. 먼저 bot 폴더에서 './gradlew bootJar'를 실행하세요."
    exit 1
}

$argumentString = "-jar `"$jar`" --spring.profiles.active=listen"

$process = Start-Process -FilePath "javaw" `
    -ArgumentList $argumentString `
    -WorkingDirectory $botDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput $outLog `
    -RedirectStandardError $errLog `
    -PassThru

$process.Id | Out-File -FilePath $pidFile -Encoding ascii
Write-Host "백그라운드로 시작했습니다 (PID $($process.Id))."
Write-Host "로그 확인: Get-Content `"$outLog`" -Tail 20 -Encoding UTF8"
