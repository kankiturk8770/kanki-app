# Installs the service on the CI machine and talks to it over the pipe like the
# app does. Run as administrator from the folder with the built files.
$ErrorActionPreference = 'Stop'
$src = Resolve-Path $args[0]
# like a real install: Program Files, readable by every user
$dir = "$env:ProgramFiles\KankiSmoke"
New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item "$src\*" $dir -Recurse -Force

& "$dir\kanki-svc.exe" install
if ($LASTEXITCODE -ne 0) { throw "install failed" }
Start-Sleep -Seconds 2
$svc = Get-Service KankiVPN
if ($svc.Status -ne 'Running') { throw "service is $($svc.Status)" }

function Ask($line) {
  $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', 'KankiVPN', [System.IO.Pipes.PipeDirection]::InOut)
  $pipe.Connect(5000)
  $w = New-Object System.IO.StreamWriter($pipe); $w.AutoFlush = $true
  $r = New-Object System.IO.StreamReader($pipe)
  $w.WriteLine($line)
  $answer = $r.ReadLine()
  $pipe.Dispose()
  Write-Host "$line -> $answer"
  return ($answer | ConvertFrom-Json)
}

$h = Ask '{"id":1,"cmd":"hello"}'
if (-not $h.ok) { throw "hello failed" }
# the app itself, run by a normal (non-admin) user like on a real laptop: it must trust the service
$name = 'kankismoke'
$pass = 'Ks!' + [guid]::NewGuid().ToString('N').Substring(0, 16)
$sec = ConvertTo-SecureString $pass -AsPlainText -Force
New-LocalUser -Name $name -Password $sec -AccountNeverExpires | Out-Null
Add-LocalGroupMember -Group 'Users' -Member $name -ErrorAction SilentlyContinue
$out = "$env:PUBLIC\kanki-check.txt"
Remove-Item $out -ErrorAction SilentlyContinue
$cred = New-Object System.Management.Automation.PSCredential("$env:COMPUTERNAME\$name", $sec)
$p = Start-Process -FilePath "$dir\KankiVPN.exe" -ArgumentList '--check-service', $out -Credential $cred -LoadUserProfile -WorkingDirectory $env:PUBLIC -PassThru -Wait
$res = if (Test-Path $out) { Get-Content $out -Raw } else { "no result (exit $($p.ExitCode))" }
Remove-LocalUser -Name $name
Write-Host "app as normal user -> $res"
if ($res -ne 'ok') { throw "the app does not trust the service as a normal user: $res" }

$s = Ask '{"id":2,"cmd":"status"}'
if ($s.data.state -ne 'disconnected') { throw "unexpected state" }
$bad = Ask '{"id":3,"cmd":"set_settings","args":{"settings":{"split_mode":"include"}}}'
if ($bad.ok) { throw "invalid settings accepted" }
$ok = Ask '{"id":4,"cmd":"set_settings","args":{"settings":{"kill_switch":true,"block_ipv6":true,"allow_lan":true,"split_mode":"all"}}}'
if (-not $ok.ok) { throw "settings refused" }
$c = Ask '{"id":5,"cmd":"connect","args":{"server":"x"}}'
if ($c.ok) { throw "connect without subscription accepted" }

# the state folder must not be readable by normal users
$acl = (Get-Acl "$env:ProgramData\KankiVPN").Access | ForEach-Object { $_.IdentityReference.Value }
if ($acl -match 'Users|Everyone|Authenticated') { throw "state folder open to users: $acl" }

& "$dir\kanki-svc.exe" uninstall
if ($LASTEXITCODE -ne 0) { throw "uninstall failed" }
Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "service smoke test passed"
