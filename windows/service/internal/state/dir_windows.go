package state

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"golang.org/x/sys/windows"
)

// Dir is %ProgramData%\KankiVPN.
func Dir() string {
	base := os.Getenv("ProgramData")
	if base == "" {
		base = `C:\ProgramData`
	}
	return filepath.Join(base, "KankiVPN")
}

// Only SYSTEM and Administrators, not inherited from ProgramData (where every
// user may read).
const dirSDDL = "D:PAI(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)"

func makeDir(dir string) error {
	// 1.3.2 security review: a standard user may create %ProgramData%\KankiVPN (or a junction there)
	// before the service first runs. MkdirAll accepts it and a new DACL alone leaves that user as owner
	// (owners can always re-grant themselves access), so SYSTEM would then write state, logs and core
	// configs into a folder the user controls. A folder that is a reparse point or not owned by SYSTEM
	// or Administrators (or this process's own user) is moved aside and made again; the service then makes SYSTEM the owner.
	if untrusted(dir) {
		aside := dir + ".untrusted-" + strconv.FormatInt(time.Now().Unix(), 10)
		if err := os.Rename(dir, aside); err != nil {
			return fmt.Errorf("state folder %s is not owned by SYSTEM and could not be moved aside: %w", dir, err)
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	// set before anything is written, and fix a folder left by an older install
	sd, err := windows.SecurityDescriptorFromString(dirSDDL)
	if err != nil {
		return err
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	info := windows.SECURITY_INFORMATION(windows.DACL_SECURITY_INFORMATION | windows.PROTECTED_DACL_SECURITY_INFORMATION)
	var owner *windows.SID
	// only SYSTEM (the real service) may make SYSTEM the owner; tests run as an administrator keep their own
	if me := self(); me != nil && me.IsWellKnown(windows.WinLocalSystemSid) {
		owner = me
		info |= windows.OWNER_SECURITY_INFORMATION
	}
	return windows.SetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT, info, owner, nil, dacl, nil)
}

// self is the user of this process (SYSTEM for the service).
func self() *windows.SID {
	u, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return nil
	}
	return u.User.Sid
}

// untrusted: the folder exists and is a reparse point (junction, symlink) or is owned by someone
// other than SYSTEM or Administrators. A missing folder is not untrusted.
func untrusted(dir string) bool {
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return false
	}
	attrs, err := windows.GetFileAttributes(p)
	if err != nil {
		return false
	}
	if attrs&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		return true
	}
	sd, err := windows.GetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION)
	if err != nil {
		return true
	}
	owner, _, err := sd.Owner()
	if err != nil || owner == nil {
		return true
	}
	if me := self(); me != nil && owner.Equals(me) {
		return false
	}
	return !owner.IsWellKnown(windows.WinLocalSystemSid) && !owner.IsWellKnown(windows.WinBuiltinAdministratorsSid)
}
