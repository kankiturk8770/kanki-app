; Kanki VPN for Windows: installs the app, the service and Wintun.
; Built in CI: iscc /DAppVersion=1.0.0 /DSrc=<folder with the three files> kanki.iss

#ifndef AppVersion
  #define AppVersion "1.2.1"
#endif
#ifndef Src
  #define Src "..\dist"
#endif

[Setup]
AppId={{6B616E6B-6976-706E-8A1F-524E0C773101}
AppName=Kanki VPN
AppVersion={#AppVersion}
AppPublisher=Kanki
DefaultDirName={autopf}\Kanki VPN
DefaultGroupName=Kanki VPN
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputBaseFilename=KankiVPN-Setup-{#AppVersion}
OutputDir={#Src}
SetupIconFile=..\ui\KankiVPN\Assets\kanki.ico
UninstallDisplayIcon={app}\KankiVPN.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#Src}\KankiVPN.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Src}\kanki-svc.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Src}\wintun.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Src}\xray.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Src}\hysteria.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Src}\LICENSES.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Kanki VPN"; Filename: "{app}\KankiVPN.exe"
Name: "{autodesktop}\Kanki VPN"; Filename: "{app}\KankiVPN.exe"; Tasks: desktopicon

[Run]
; adds the service (or replaces an older one) and starts it
Filename: "{app}\kanki-svc.exe"; Parameters: "install"; Flags: runhidden waituntilterminated; StatusMsg: "Installing the VPN service..."
Filename: "{app}\KankiVPN.exe"; Description: "{cm:LaunchProgram,Kanki VPN}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{app}\kanki-svc.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveService"
Filename: "{sys}\taskkill.exe"; Parameters: "/im KankiVPN.exe /f"; Flags: runhidden; RunOnceId: "CloseApp"

[Code]
// an older version's service holds kanki-svc.exe and wintun.dll open: stop it first
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/im KankiVPN.exe /f', '', SW_HIDE, ewWaitUntilTerminated, Code);
  if FileExists(ExpandConstant('{app}\kanki-svc.exe')) then
    Exec(ExpandConstant('{app}\kanki-svc.exe'), 'uninstall', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Result := '';
end;
