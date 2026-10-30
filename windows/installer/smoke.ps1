# Installs the service on the CI machine and talks to it over the pipe like the
# app does. Run as administrator from the folder with the built files.
$ErrorActionPreference = 'Stop'
$dir = Resolve-Path $args[0]

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
Write-Host "service smoke test passed"
