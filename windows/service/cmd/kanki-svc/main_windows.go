// kanki-svc is the Kanki VPN Windows service. It runs as SYSTEM, owns the
// tunnel adapter, routes and firewall rules, and takes orders from the app
// over the named pipe \\.\pipe\KankiVPN.
//
//	kanki-svc install     add and start the service (installer, as admin)
//	kanki-svc uninstall   stop and remove it
//	kanki-svc debug       run in this console (as admin) for testing
package main

import (
	"errors"
	"fmt"
	"os"
	"os/signal"
	"time"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"

	"kankivpn/internal/core"
	"kankivpn/internal/ipc"
	"kankivpn/internal/logx"
	"kankivpn/internal/state"
	"kankivpn/internal/sub"
	"kankivpn/internal/tunnel"
)

// Version is set at build time (-ldflags "-X main.Version=...").
var Version = "dev"

const serviceName = "KankiVPN"

// Pipe access: SYSTEM and Administrators full, signed-in (interactive) users
// read/write, and nobody over the network.
// SY and BA all; interactive users query and start (RP) only
const serviceSDDL = "D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWRPLOCRRC;;;IU)(A;;CCLCSWLOCRRC;;;SU)"

const pipeSDDL = "D:P(D;;GA;;;NU)(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)"

func main() {
	if len(os.Args) > 1 {
		var err error
		switch os.Args[1] {
		case "install":
			err = install()
		case "uninstall":
			err = uninstall()
		case "debug":
			err = run(nil)
		case "version":
			fmt.Println("kanki-svc", Version)
		default:
			err = fmt.Errorf("unknown command %q (install, uninstall, debug, version)", os.Args[1])
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, "error:", err)
			os.Exit(1)
		}
		return
	}
	inService, err := svc.IsWindowsService()
	if err != nil || !inService {
		fmt.Fprintln(os.Stderr, "This is the Kanki VPN service; it is started by Windows. Commands: install, uninstall, debug, version")
		os.Exit(2)
	}
	if err := svc.Run(serviceName, handler{}); err != nil {
		os.Exit(1)
	}
}

type handler struct{}

func (handler) Execute(_ []string, req <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	status <- svc.Status{State: svc.StartPending}
	stop := make(chan struct{})
	done := make(chan error, 1)
	go func() { done <- run(stop) }()
	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for {
		select {
		case c := <-req:
			switch c.Cmd {
			case svc.Interrogate:
				status <- c.CurrentStatus
			case svc.Stop, svc.Shutdown:
				status <- svc.Status{State: svc.StopPending}
				close(stop)
				<-done
				return false, 0
			}
		case err := <-done:
			if err != nil {
				// a non-zero exit makes Windows restart us (recovery actions)
				return true, 1
			}
			return false, 0
		}
	}
}

// run is the service itself; it returns when stop closes (nil = console).
func run(stop chan struct{}) error {
	dir := state.Dir()
	store, err := state.Open(dir)
	if err != nil {
		return fmt.Errorf("state folder: %w", err)
	}
	log := logx.New(dir)
	log.Printf("Kanki VPN service %s starting", Version)

	m := core.New(tunnel.New(log), store, log, sub.NewClient())
	l, err := winio.ListenPipe(ipc.PipeName, &winio.PipeConfig{
		SecurityDescriptor: pipeSDDL, InputBufferSize: 64 << 10, OutputBufferSize: 64 << 10,
	})
	if err != nil {
		return fmt.Errorf("pipe: %w", err)
	}
	go ipc.Serve(l, m, log, Version)
	m.Start()

	if stop == nil {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, os.Interrupt)
		fmt.Println("running; Ctrl+C to stop")
		<-sig
	} else {
		<-stop
	}
	l.Close()
	m.Stop()
	log.Printf("service stopped")
	return nil
}

func install() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("service manager (run as administrator): %w", err)
	}
	defer m.Disconnect()
	if s, err := m.OpenService(serviceName); err == nil {
		// reinstall over an old version: stop, delete, add again
		_ = stopService(s)
		_ = s.Delete()
		s.Close()
		waitGone(m)
	}
	// an old copy that is still being removed (marked for delete) refuses a new one for a moment
	var s *mgr.Service
	for i := 0; ; i++ {
		s, err = m.CreateService(serviceName, exe, serviceConfig)
		if err == nil || i >= 50 || !errors.Is(err, windows.ERROR_SERVICE_MARKED_FOR_DELETE) && !errors.Is(err, windows.ERROR_SERVICE_EXISTS) {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	if err != nil {
		return err
	}
	defer s.Close()
	_ = s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: time.Second},
		{Type: mgr.ServiceRestart, Delay: 2 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 5 * time.Second},
	}, 60)
	_ = s.SetRecoveryActionsOnNonCrashFailures(true)
	// normal users may start it (the app does when it finds the service stopped), not stop or change it
	if sd, err := windows.SecurityDescriptorFromString(serviceSDDL); err == nil {
		if dacl, _, err := sd.DACL(); err == nil {
			_ = windows.SetSecurityInfo(s.Handle, windows.SE_SERVICE, windows.DACL_SECURITY_INFORMATION, nil, nil, dacl, nil)
		}
	}
	if err := s.Start(); err != nil {
		return fmt.Errorf("start: %w", err)
	}
	// started is not enough: it must stay running (a crash at start would leave the user with no service)
	time.Sleep(2 * time.Second)
	st, err := s.Query()
	if err != nil {
		return err
	}
	if st.State != svc.Running {
		return fmt.Errorf("the service is %d after start, not running", st.State)
	}
	return nil
}

var serviceConfig = mgr.Config{
	DisplayName:  "Kanki VPN",
	Description:  "Kanki VPN tunnel service (WireGuard / AmneziaWG, kill switch, DNS leak protection)",
	StartType:    mgr.StartAutomatic,
	ErrorControl: mgr.ErrorNormal,
	Dependencies: []string{"Nsi", "TcpIp"},
	SidType:      windows.SERVICE_SID_TYPE_UNRESTRICTED,
}

func uninstall() error {
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("service manager (run as administrator): %w", err)
	}
	defer m.Disconnect()
	s, err := m.OpenService(serviceName)
	if err != nil {
		return nil // already gone
	}
	defer s.Close()
	_ = stopService(s)
	return s.Delete()
}

func stopService(s *mgr.Service) error {
	st, err := s.Control(svc.Stop)
	if err != nil {
		return err
	}
	for i := 0; i < 50 && st.State != svc.Stopped; i++ {
		time.Sleep(200 * time.Millisecond)
		if st, err = s.Query(); err != nil {
			return err
		}
	}
	if st.State != svc.Stopped {
		return errors.New("the service did not stop")
	}
	return nil
}

// waitGone waits until a deleted service is really removed.
func waitGone(m *mgr.Mgr) {
	for i := 0; i < 50; i++ {
		s, err := m.OpenService(serviceName)
		if err != nil {
			return
		}
		s.Close()
		time.Sleep(200 * time.Millisecond)
	}
}
