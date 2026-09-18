# start-listen.ps1로 띄운 백그라운드 봇을 종료한다
$botDir = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $botDir "data\listen.pid"
$requestFile = Join-Path $botDir "data\shutdown.request"

if (-not (Test-Path $pidFile)) {
    Write-Host "실행 중인 기록이 없습니다."
    exit
}

$existingPid = Get-Content $pidFile
$process = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
if ($process) {
    # 강제 종료(Stop-Process)는 JVM 종료 훅을 실행하지 못해 "봇 종료" 알림이 나가지 않는다.
    # 그래서 종료 요청 파일을 만들어 봇이 스스로 정상 종료하게 하고, 15초 안에 안 끝나면 강제 종료한다
    New-Item -ItemType File -Force -Path $requestFile | Out-Null
    if ($process.WaitForExit(15000)) {
        Write-Host "정상 종료했습니다 (PID $existingPid)."
    } else {
        Write-Host "15초 안에 종료되지 않아 강제 종료합니다 (PID $existingPid)."
        Stop-Process -Id $existingPid -Force
    }
    Remove-Item $requestFile -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "이미 종료되어 있습니다."
}
Remove-Item $pidFile -Force
