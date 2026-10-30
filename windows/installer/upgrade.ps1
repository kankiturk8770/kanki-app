# Does what a user does: installs the previous release, installs the new setup over it,
# then opens the app as a normal (non-admin) user and logs in. Run as administrator.
# upgrade.ps1 <old setup.exe> <new setup.exe> <expected version>
$ErrorActionPreference = 'Stop'
$old, $new, $want = $args[0], $args[1], $args[2]
$app = "$env:ProgramFiles\Kanki VPN"

function Setup($exe) {
  $p = Start-Process -FilePath $exe -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-' -PassThru -Wait
  if ($p.ExitCode -ne 0) { throw "$exe exit $($p.ExitCode)" }
}

function Ask($line) {
  $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', 'KankiVPN', [System.IO.Pipes.PipeDirection]::InOut)
  $pipe.Connect(10000)
  $w = New-Object System.IO.StreamWriter($pipe); $w.AutoFlush = $true
  $r = New-Object System.IO.StreamReader($pipe)
  $w.WriteLine($line)
  $answer = $r.ReadLine()
  $pipe.Dispose()
  return ($answer | ConvertFrom-Json)
}

function Running($why) {
  Start-Sleep -Seconds 3
  $s = Get-Service KankiVPN -ErrorAction SilentlyContinue
  if (-not $s -or $s.Status -ne 'Running') { throw "service not running $why ($($s.Status))" }
}

if ($old) {
  Setup $old
  Running 'after the old setup'
  # the old app was open on the user's desktop
  Start-Process -FilePath "$app\KankiVPN.exe" -ArgumentList '--tray' | Out-Null
  Start-Sleep -Seconds 3
}
Setup $new
Running 'after the new setup over the old one'
$v = (Ask '{"id":1,"cmd":"hello"}').data.version
if ($v -ne $want) { throw "service is $v, not $want" }
Write-Host "service $v running after the upgrade"

# a normal user, like on a laptop
$name = 'kankiup'
$pass = 'Ku!' + [guid]::NewGuid().ToString('N').Substring(0, 16)
$sec = ConvertTo-SecureString $pass -AsPlainText -Force
New-LocalUser -Name $name -Password $sec -AccountNeverExpires | Out-Null
$cred = New-Object System.Management.Automation.PSCredential("$env:COMPUTERNAME\$name", $sec)
try {
  $out = "$env:PUBLIC\kanki-up.txt"
  # login with a code the panel does not know: the real network path, the service must answer
  Remove-Item $out -ErrorAction SilentlyContinue
  $p = Start-Process -FilePath "$app\KankiVPN.exe" -ArgumentList '--check-service', $out, 'kanki-9-1234567890123456789' -Credential $cred -LoadUserProfile -UseNewEnvironment -WorkingDirectory $env:PUBLIC -PassThru -Wait
  $res = if (Test-Path $out) { Get-Content $out -Raw } else { "no result (exit $($p.ExitCode))" }
  Write-Host "login as a normal user -> $res"
  if ($res -ne 'ok') { throw "login as a normal user failed: $res" }
  Running 'after the login'

  # the service stopped: the app (normal user) starts it again by itself
  Stop-Service KankiVPN
  Remove-Item $out -ErrorAction SilentlyContinue
  $p = Start-Process -FilePath "$app\KankiVPN.exe" -ArgumentList '--check-service', $out -Credential $cred -LoadUserProfile -UseNewEnvironment -WorkingDirectory $env:PUBLIC -PassThru -Wait
  $res = if (Test-Path $out) { Get-Content $out -Raw } else { "no result (exit $($p.ExitCode))" }
  Write-Host "stopped service, app as a normal user -> $res"
  if ($res -ne 'ok') { throw "the app did not start the stopped service: $res" }
  Running 'after the app started it'

  # the whole app for 20 seconds: no crash
  $a = Start-Process -FilePath "$app\KankiVPN.exe" -Credential $cred -LoadUserProfile -UseNewEnvironment -WorkingDirectory $env:PUBLIC -PassThru
  Start-Sleep -Seconds 20
  $crash = Get-ChildItem "C:\Users\$name*\AppData\Roaming\KankiVPN\crash.log" -ErrorAction SilentlyContinue | Get-Content -Raw
  $alive = -not $a.HasExited
  if ($alive) { Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue }
  if ($crash) { Write-Host $crash; throw "the app wrote a crash log" }
  if (-not $alive) { throw "the app closed by itself (exit $($a.ExitCode))" }
} finally {
  Get-Content "$env:ProgramData\KankiVPN\*.log" -ErrorAction SilentlyContinue | Select-Object -Last 30
  Remove-LocalUser -Name $name -ErrorAction SilentlyContinue
}

# uninstall leaves no service behind
Start-Process -FilePath "$app\unins000.exe" -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait
Start-Sleep -Seconds 3
if (Get-Service KankiVPN -ErrorAction SilentlyContinue) { throw "service still there after uninstall" }
Write-Host "upgrade test passed"
