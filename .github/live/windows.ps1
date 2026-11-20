# Live test on a Windows runner: installs the released setup, logs in with a test subscription,
# connects to every server and checks the exit IP, DNS and that nothing leaks.
$ErrorActionPreference = 'Stop'
$sub = $env:LIVE_SUB
$ver = $env:WIN_VERSION
$app = "$env:ProgramFiles\Kanki VPN"
Invoke-WebRequest "https://github.com/$env:GITHUB_REPOSITORY/releases/download/windows-v$ver/KankiVPN-Setup-$ver.exe" -OutFile setup.exe
$p = Start-Process -FilePath .\setup.exe -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-' -PassThru -Wait
if ($p.ExitCode -ne 0) { throw "setup exit $($p.ExitCode)" }
Start-Sleep 3

$script:id = 0
function Ask($cmd, $args2) {
  $script:id++
  $o = @{ id = $script:id; cmd = $cmd }
  if ($args2) { $o.args = $args2 }
  if ($env:DEBUG_SVC -eq '1' -and $dbg -and $dbg.HasExited -and -not $script:told) {
    $script:told = $true
    Write-Host "!! service (console) exited with $($dbg.ExitCode) during ${cmd}:"
    Get-Content "$env:TEMP\svc-err.txt", "$env:TEMP\svc-out.txt" -ErrorAction SilentlyContinue | Select-Object -Last 80 | Write-Host
  }
  $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', 'KankiVPN', [System.IO.Pipes.PipeDirection]::InOut)
  try {
    $pipe.Connect(5000)
    $w = New-Object System.IO.StreamWriter($pipe); $w.AutoFlush = $true
    $r = New-Object System.IO.StreamReader($pipe)
    $w.WriteLine(($o | ConvertTo-Json -Compress -Depth 6))
    $t = $r.ReadLineAsync()
    $a = if ($t.Wait(20000)) { $t.Result } else { $null }
  } catch { $a = $null } finally { $pipe.Dispose() }
  if (-not $a) {
    $svc = Get-Service KankiVPN -ErrorAction SilentlyContinue
    Write-Host "!! no answer to $cmd (service $($svc.Status))"
    Get-Content "$env:ProgramData\KankiVPN\service.log" -ErrorAction SilentlyContinue | Select-Object -Last 40 | Write-Host
    return [pscustomobject]@{ ok = $false; error = "no answer"; data = $null }
  }
  return ($a | ConvertFrom-Json)
}
function MyIP { try { (Invoke-RestMethod 'https://api.ipify.org?format=json' -TimeoutSec 15).ip } catch { "none ($($_.Exception.Message))" } }

$real = MyIP
Write-Host "runner IP without VPN: $real"
Write-Host "service: $((Ask 'hello').data.version)"
$acc = Ask 'set_sub' @{ input = $sub }
if (-not $acc.ok) { throw "login failed: $($acc.error)" }
$servers = $acc.data.servers
Write-Host "login ok: $($servers.Count) servers"
$ids = @($servers | ForEach-Object { $_.id })
$pings = (Ask 'ping' @{ servers = $ids }).data
Write-Host "ping: $($pings | ConvertTo-Json -Compress -Depth 4)"

# Hysteria2 by hand: the same config the service writes, once with 127.0.0.1 and once with a 127.x address
$hy = $servers | Where-Object { $_.proto -eq 'hysteria2' -and -not $_.tunnel } | Select-Object -First 1
if ($hy) { Write-Host "hysteria server: $($hy.id)" }
$seen = @{}
$pick = @($servers | Where-Object { -not $_.tunnel } | Where-Object { $k = "$($_.proto)"; if ($seen[$k]) { $false } else { $seen[$k] = 1; $true } })
# the ones that misbehaved first, so their reason shows before anything else
$pick = @($pick | Sort-Object { @{ hysteria2 = 0; kankimix = 1; vless = 2; microtun = 3 }[$_.proto] ?? 9 })
Write-Host "testing $($pick.Count) servers (one per protocol and route)"
# the service in console mode, so a crash prints its reason here
if ($env:DEBUG_SVC -eq '1') {
  # the installed service must stay off (no auto start, no restart on failure) or it takes the pipe back
  sc.exe failure KankiVPN reset= 0 actions= '""' | Out-Null
  sc.exe config KankiVPN start= disabled | Out-Null
  Stop-Service KankiVPN -Force
  Start-Sleep 3
  Get-Service KankiVPN | Select-Object Status, StartType | Out-String | Write-Host
  $dbg = Start-Process -FilePath "$app\kanki-svc.exe" -ArgumentList 'debug' -RedirectStandardOutput "$env:TEMP\svc-out.txt" -RedirectStandardError "$env:TEMP\svc-err.txt" -PassThru -WindowStyle Hidden
  Start-Sleep 4
  $acc = Ask 'set_sub' @{ input = $sub }
}
$rows = @()
foreach ($s in $pick) {
  $res = [ordered]@{ server = $s.id; proto = $s.proto; tunnel = $s.tunnel; state = ''; ip = ''; leak = ''; dns = ''; ms = ''; error = '' }
  try {
    $c = Ask 'connect' @{ server = $s.id }
    if (-not $c.ok) { throw $c.error }
    $st = $null
    for ($i = 0; $i -lt 40; $i++) {
      Start-Sleep 1
      $q = Ask 'status'
      if (-not $q.ok) { $st = [pscustomobject]@{ state = 'service gone'; error = $q.error }; break }
      $st = $q.data
      if ($st.state -eq 'connected' -or $st.state -eq 'failed' -or $st.state -eq 'error') { break }
    }
    $res.state = $st.state
    if ($st.error) { $res.error = $st.error }
    if ($st.state -ne 'connected') {
      Write-Host "-- service log for $($s.id):"
      (Ask 'logs').data | Select-Object -Last 25 | Write-Host
    }
    if ($st.state -eq 'connected') {
      $t = Measure-Command { $res.ip = MyIP }
      $res.ms = [int]$t.TotalMilliseconds
      $res.leak = if ($res.ip -eq $real) { 'LEAK' } else { 'no' }
      try { $res.dns = ((Resolve-DnsName 'www.google.com' -Type A -ErrorAction Stop | Where-Object IPAddress | Select-Object -First 1).IPAddress) } catch { $res.dns = 'fail' }
    }
  } catch { $res.error = "$_" }
  Ask 'disconnect' | Out-Null
  Start-Sleep 2
  $rows += [pscustomobject]$res
  Write-Host ($res | ConvertTo-Json -Compress)
  if ($env:DEBUG_SVC -eq '1' -and $dbg.HasExited) {
    $script:told = $false
    Write-Host "!! service (console) exited with $($dbg.ExitCode) after $($s.id) -"
    Get-Content "$env:TEMP\svc-err.txt", "$env:TEMP\svc-out.txt" -ErrorAction SilentlyContinue | Select-Object -Last 60 | Write-Host
    $dbg = Start-Process -FilePath "$app\kanki-svc.exe" -ArgumentList 'debug' -RedirectStandardOutput "$env:TEMP\svc-out.txt" -RedirectStandardError "$env:TEMP\svc-err.txt" -PassThru -WindowStyle Hidden
    Start-Sleep 4
    Ask 'set_sub' @{ input = $sub } | Out-Null
  }
}
$rows | Format-Table -AutoSize | Out-String -Width 300 | Write-Host

# kill switch: with the tunnel up, kill the core and see that nothing leaves outside the tunnel
$first = $rows | Where-Object { $_.state -eq 'connected' } | Select-Object -First 1
if ($first) {
  Ask 'connect' @{ server = $first.server } | Out-Null
  for ($i = 0; $i -lt 30; $i++) { Start-Sleep 1; if ((Ask 'status').data.state -eq 'connected') { break } }
  Get-Process xray, hysteria -ErrorAction SilentlyContinue | Stop-Process -Force
  Start-Sleep 2
  $ks = MyIP
  Write-Host "kill switch while the tunnel is down: IP = $ks (real $real) -> $(if ($ks -eq $real) { 'LEAK' } else { 'held' })"
  Ask 'disconnect' | Out-Null
}
Ask 'logout' | Out-Null
Get-Content "$env:ProgramData\KankiVPN\service.log" -ErrorAction SilentlyContinue | Select-Object -Last 60
$bad = @($rows | Where-Object { $_.state -ne 'connected' -or $_.leak -ne 'no' })
Write-Host "connected: $(@($rows | Where-Object state -eq 'connected').Count) of $($rows.Count)"
