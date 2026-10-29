# ZLMediaKit local start + self-check (Plan A: http 8082 / mediaServerId=zlm-dev / secret=zlm / hook -> backend 8181)
# Layout:
#   D:\2026_git\shipin\ZLMediaKit      runtime (MediaServer.exe + config.ini + www + default.pem + log)
#   D:\2026_git\shipin\ZLMediaKit-src  official source mirror
#   D:\2026_git\shipin\zlm-bin-src     prebuilt packages (multiple windows versions)
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\scripts\zlm-start.ps1
#   ... -Root E:\zlm -BackendPort 8181 -HttpPort 8082
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 needs a UTF-8 BOM to parse
#       non-ASCII .ps1 content; an ASCII script avoids that whole class of failure.

param(
    [string]$Root = 'D:\2026_git\shipin\ZLMediaKit',
    [int]$BackendPort = 8181,
    [string]$Secret = 'zlm',
    [string]$MediaServerId = 'zlm-dev',
    [int]$HttpPort = 8082
)

$ErrorActionPreference = 'Stop'
$repoConfig = Join-Path $PSScriptRoot '..\config.ini'
$cfg = Join-Path $Root 'config.ini'
$exe = Join-Path $Root 'MediaServer.exe'
$consoleLog = Join-Path $Root 'log\console.log'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Write-Step($text) { Write-Host "`n== $text ==" -ForegroundColor Cyan }

Write-Step "1. Align config ($cfg)"
if (-not (Test-Path $Root)) { New-Item -ItemType Directory -Path $Root -Force | Out-Null }
foreach ($d in @('www', 'www\snap', 'ffmpeg', 'log')) {
    $p = Join-Path $Root $d
    if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
}
if (-not (Test-Path $cfg)) {
    if (-not (Test-Path $repoConfig)) { throw "template missing: $repoConfig" }
    Copy-Item $repoConfig $cfg -Force
    Write-Host "[ok] config.ini generated from repo template"
}
$text = [System.IO.File]::ReadAllText($cfg, [System.Text.Encoding]::UTF8)
$text = $text -replace '(?m)^mediaServerId=.*\r?$', "mediaServerId=$MediaServerId"
$text = $text -replace '(?m)^port=80\r?$', "port=$HttpPort"
$text = $text -replace '(?m)^port=9092\r?$', "port=$HttpPort"
$text = $text -replace '127\.0\.0\.1:8081/index/hook', "127.0.0.1:$BackendPort/index/hook"
$text = $text -replace '(?m)^bin=.*ffmpeg.*\r?$', 'bin=C:/soft/ffmpeg-7.0.2/bin/ffmpeg.exe'
[System.IO.File]::WriteAllText($cfg, $text, $utf8)
Write-Host "[ok] mediaServerId=$MediaServerId  http.port=$HttpPort  hook->127.0.0.1:$BackendPort"

Write-Step "2. Preflight"
if (-not (Test-Path $exe)) {
    Write-Host "[x] missing $exe" -ForegroundColor Red
    Write-Host "    prebuilt: git clone --depth 1 https://gitee.com/beixiaocai/zlm.git then copy V*.windows\MediaServer.exe" -ForegroundColor Yellow
    exit 2
}
Write-Host "[ok] MediaServer.exe"
if (Test-Path (Join-Path $Root 'default.pem')) { Write-Host "[ok] default.pem" }
else { Write-Host "[!] default.pem missing: HTTPS(443)/WebRTC unavailable" -ForegroundColor Yellow }
$backendUp = Test-NetConnection -ComputerName 127.0.0.1 -Port $BackendPort -InformationLevel Quiet -WarningAction SilentlyContinue
if ($backendUp) { Write-Host "[ok] backend $BackendPort is listening (hook target reachable)" }
else { Write-Host "[!] backend $BackendPort not listening: node will NOT be registered" -ForegroundColor Yellow }

Write-Step "3. Start MediaServer (detached, log -> log\console.log)"
$old = Get-Process MediaServer -ErrorAction SilentlyContinue
if ($old) {
    Stop-Process -Name MediaServer -Force
    Start-Sleep -Seconds 2
    Write-Host "[!] stopped old process $($old.Id -join ',')" -ForegroundColor Yellow
}
if (Test-Path $consoleLog) { Move-Item $consoleLog "$consoleLog.bak" -Force -ErrorAction SilentlyContinue }
$cmdLine = 'cmd.exe /c "cd /d ' + $Root + ' && MediaServer.exe > log\console.log 2>&1"'
$res = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmdLine; CurrentDirectory = $Root }
Start-Sleep -Seconds 5
$proc = Get-Process MediaServer -ErrorAction SilentlyContinue
if (-not $proc) {
    Write-Host "[x] process not alive, log tail:" -ForegroundColor Red
    if (Test-Path $consoleLog) { Get-Content $consoleLog -Tail 15 }
    exit 3
}
Write-Host "[ok] MediaServer PID=$($proc.Id -join ',')"

Write-Step "4. REST check (http://127.0.0.1:$HttpPort, secret=$Secret)"
try {
    $r = Invoke-RestMethod "http://127.0.0.1:$HttpPort/index/api/getServerConfig?secret=$Secret" -TimeoutSec 8
    $d = $r.data[0]
    "  general.mediaServerId  = " + $d.'general.mediaServerId'
    "  http.port              = " + $d.'http.port'
    "  hook.enable            = " + $d.'hook.enable'
    "  hook.on_server_started = " + $d.'hook.on_server_started'
} catch {
    Write-Host "[x] REST failed: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Step "5. DB check (tb_media_node)"
if (Get-Command python -ErrorAction SilentlyContinue) {
    $code = @"
import pymysql
c = pymysql.connect(host='192.168.118.20', port=3306, user='root', password='123456', database='voglander', charset='utf8mb4')
cur = c.cursor()
cur.execute('select server_id, host, secret, status, enabled, weight, keepalive from tb_media_node')
rows = cur.fetchall()
print('  rows =', len(rows))
for r in rows:
    print('  ', r)
c.close()
"@
    $tmp = Join-Path $env:TEMP 'zlm_node_check.py'
    Set-Content -Path $tmp -Value $code -Encoding UTF8
    python $tmp
} else {
    Write-Host "[!] python not found, run manually: select server_id,host,secret,status,enabled from tb_media_node;" -ForegroundColor Yellow
}

Write-Step "6. Expected"
Write-Host "  tb_media_node: server_id=$MediaServerId  host=http://127.0.0.1:$HttpPort  secret=$Secret  status=1  enabled=1"
Write-Host "  backend log  : $env:USERPROFILE\logs\voglander\voglander-info.log  (onServerStarted -> node upsert ok)"
