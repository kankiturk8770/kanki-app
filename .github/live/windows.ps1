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
  $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', 'KankiVPN', [System.IO.Pipes.PipeDirection]::InOut)
  $pipe.Connect(10000)
  $w = New-Object System.IO.StreamWriter($pipe); $w.AutoFlush = $true
  $r = New-Object System.IO.StreamReader($pipe)
  $w.WriteLine(($o | ConvertTo-Json -Compress -Depth 6))
  $a = $r.ReadLine(); $pipe.Dispose()
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

$rows = @()
foreach ($s in $servers) {
  $res = [ordered]@{ server = $s.id; proto = $s.proto; tunnel = $s.tunnel; state = ''; ip = ''; leak = ''; dns = ''; ms = ''; error = '' }
  try {
    $c = Ask 'connect' @{ server = $s.id }
    if (-not $c.ok) { throw $c.error }
    $st = $null
    for ($i = 0; $i -lt 40; $i++) {
      Start-Sleep 1
      $st = (Ask 'status').data
      if ($st.state -eq 'connected' -or $st.state -eq 'failed') { break }
    }
    $res.state = $st.state
    if ($st.error) { $res.error = $st.error }
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
