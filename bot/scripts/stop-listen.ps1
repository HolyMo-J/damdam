# start-listen.ps1로 띄운 백그라운드 봇을 종료한다
$botDir = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $botDir "data\listen.pid"

if (-not (Test-Path $pidFile)) {
    Write-Host "실행 중인 기록이 없습니다."
    exit
}

$existingPid = Get-Content $pidFile
$process = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
if ($process) {
    Stop-Process -Id $existingPid
    Write-Host "종료했습니다 (PID $existingPid)."
} else {
    Write-Host "이미 종료되어 있습니다."
}
Remove-Item $pidFile -Force
