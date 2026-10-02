# PreToolUse hook draft: blocks Toss order-related calls in a dev session (a helper that backs up the CLAUDE.md security rule).
# Rule: Claude Code must never call Toss order, modify, cancel, or conditional-order endpoints by any means; the user runs such commands.
# It inspects the command text (as the official permissions docs describe), so it is NOT a security boundary.
# It cannot stop: calls hidden inside a script file (only the file name is visible), URLs built from pieces, or code written with Write and then run.
# If the input cannot be read it does not block, so a hook failure never freezes every command.
$ErrorActionPreference = 'Stop'
try {
    $raw = [Console]::In.ReadToEnd()
    $data = $raw | ConvertFrom-Json
    $command = [string]$data.tool_input.command
} catch {
    exit 0
}
if ([string]::IsNullOrWhiteSpace($command)) { exit 0 }

# Rule 1: order or conditional-order endpoint text together with an HTTP client (plain code searches have no client, so they pass)
$endpoint = '/api/v1/(orders|conditional-orders)'
$client = 'curl|wget|Invoke-RestMethod|Invoke-WebRequest|\biwr\b|\birm\b|requests\.|urllib|httpx|http\.client|fetch\(|axios|WebClient|HttpClient|RestClient|Net\.Http|openssl\s+s_client'
# Rule 2: a write request (POST, PUT, PATCH, DELETE) aimed at the Toss API host. Token issuance (POST /oauth2/token) is blocked here too
$tossHost = 'tossinvest\.com'
$write = '-X\s*(POST|PUT|PATCH|DELETE)|--request\s+(POST|PUT|PATCH|DELETE)|-Method\s+(Post|Put|Patch|Delete)|--data|\s-d\s|-Body|\.post\(|\.put\(|\.patch\(|\.delete\('

$blocked = $null
if (($command -match $endpoint) -and ($command -match $client)) {
    $blocked = 'order or conditional-order endpoint with an HTTP client'
} elseif (($command -match $tossHost) -and ($command -match $write)) {
    $blocked = 'write request to the Toss API host'
}

if ($blocked) {
    $reason = "Blocked ($blocked). CLAUDE.md security rule: Claude Code must not call Toss order, modify, cancel, or conditional-order endpoints. Show the command to the user and let the user run it."
    @{
        hookSpecificOutput = @{
            hookEventName            = 'PreToolUse'
            permissionDecision       = 'deny'
            permissionDecisionReason = $reason
        }
    } | ConvertTo-Json -Depth 5 -Compress
    exit 0
}
exit 0
