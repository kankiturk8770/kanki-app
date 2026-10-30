package tunnel

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/amnezia-vpn/amneziawg-go/tun"
	t2score "github.com/xjasonlyu/tun2socks/v2/core"
	t2sdev "github.com/xjasonlyu/tun2socks/v2/core/device"
	t2stun "github.com/xjasonlyu/tun2socks/v2/core/device/tun"
	"github.com/xjasonlyu/tun2socks/v2/proxy/socks5"
	t2stunnel "github.com/xjasonlyu/tun2socks/v2/tunnel"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"kanki-tunnel/mobile"
	"mtuncore/mtun"

	"kankivpn/internal/core"
	"kankivpn/internal/cores"
	"kankivpn/internal/logx"
)

// packetDev gives a Wintun adapter the "one Read = one IP packet" face the
// KANKI MIX and microtun cores were written for (an Android TUN file).
type packetDev struct {
	d         tun.Device
	rmu, wmu  sync.Mutex
	sizes     []int
	closeOnce sync.Once
}

func newPacketDev(mtu int) (*packetDev, winipcfg.LUID, error) {
	td, err := tun.CreateTUN(AdapterName, mtu)
	if err != nil {
		return nil, 0, adapterErr(err)
	}
	return &packetDev{d: td, sizes: make([]int, 1)}, winipcfg.LUID(td.(*tun.NativeTun).LUID()), nil
}

func (p *packetDev) Read(b []byte) (int, error) {
	p.rmu.Lock()
	defer p.rmu.Unlock()
	if _, err := p.d.Read([][]byte{b}, p.sizes, 0); err != nil {
		return 0, err
	}
	return p.sizes[0], nil
}

func (p *packetDev) Write(b []byte) (int, error) {
	p.wmu.Lock()
	defer p.wmu.Unlock()
	if _, err := p.d.Write([][]byte{b}, 0); err != nil {
		return 0, err
	}
	return len(b), nil
}

func (p *packetDev) Close() error {
	p.closeOnce.Do(func() { p.d.Close() })
	return nil
}

// ---------------------------------------------------------------- KANKI MIX

type kmixRun struct {
	dev  *packetDev
	luid winipcfg.LUID
}

func upKmix(t core.Tunnel) (running, winipcfg.LUID, error) {
	k, err := cores.ParseKmix(t.Text)
	if err != nil {
		return nil, 0, err
	}
	dev, luid, err := newPacketDev(t.MTU)
	if err != nil {
		return nil, 0, err
	}
	// t.Endpoint is the resolved address (with the chosen port): the core never asks DNS
	if err := mobile.StartDevice(dev, t.Endpoint.String(), k.Key, k.FecData, k.FecPrty, k.Gaming); err != nil {
		dev.Close()
		return nil, 0, fmt.Errorf("KANKI MIX: %w", err)
	}
	return &kmixRun{dev, luid}, luid, nil
}

func (r *kmixRun) close() { mobile.Stop() } // closes the adapter too

func (r *kmixRun) stats() (rx, tx uint64, alive bool) {
	return uint64(mobile.RxBytes()), uint64(mobile.TxBytes()), mobile.IsRunning() && mobile.GetPing() >= 0
}

// ---------------------------------------------------------------- microtun

type mtunRun struct {
	c *mtun.Client
}

func upMtun(log *logx.Log, t core.Tunnel) (running, winipcfg.LUID, []netip.Prefix, error) {
	// the link's host becomes the resolved address: the client never asks DNS
	u, err := url.Parse(strings.TrimSpace(t.Text))
	if err != nil {
		return nil, 0, nil, err
	}
	u.Host = t.Endpoint.String()
	c, err := mtun.Dial(u.String(), t.DeviceID, func(line string) { log.Printf("microtun: %s", line) })
	if err != nil {
		return nil, 0, nil, fmt.Errorf("microtun: %w", err)
	}
	// "<v4/bits> <v6/64|->": the address the server gave this device
	var addrs []netip.Prefix
	for _, f := range strings.Fields(c.Address()) {
		if p, err := netip.ParsePrefix(f); err == nil && p.Addr().Is4() {
			addrs = append(addrs, p)
		}
	}
	if len(addrs) == 0 {
		c.Close()
		return nil, 0, nil, errors.New("microtun: the server gave no address")
	}
	dev, luid, err := newPacketDev(t.MTU)
	if err != nil {
		c.Close()
		return nil, 0, nil, err
	}
	c.SetTUN(dev)
	return &mtunRun{c}, luid, addrs, nil
}

func (r *mtunRun) close() { r.c.Close() }

func (r *mtunRun) stats() (rx, tx uint64, alive bool) {
	up, _, rxb, txb := r.c.Stats()
	return uint64(rxb), uint64(txb), up
}

// ---------------------------------------------------------------- VLESS / Hysteria2

type programRun struct {
	proc  *program
	dev   t2sdev.Device
	stack *stack.Stack
	luid  winipcfg.LUID
}

func freePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

func (e *Engine) upProgram(t core.Tunnel) (running, winipcfg.LUID, error) {
	port, err := freePort()
	if err != nil {
		return nil, 0, err
	}
	var p *program
	switch t.Proto {
	case cores.VLESS:
		v, err := cores.ParseVless(t.Text)
		if err != nil {
			return nil, 0, err
		}
		// Xray reads its config from stdin: the user's keys never touch the disk
		p, err = e.start("xray.exe", []string{"run", "-config", "stdin:"}, v.XrayConfig(t.Endpoint.Addr().String(), port), "")
		if err != nil {
			return nil, 0, err
		}
	case cores.Hysteria2:
		h, err := cores.ParseHy2(t.Text)
		if err != nil {
			return nil, 0, err
		}
		p, err = e.start("hysteria.exe", []string{"client", "-c"}, "", h.Config(t.Endpoint.String(), port))
		if err != nil {
			return nil, 0, err
		}
	}
	// the program listens once it is up (Hysteria: once its server answered)
	socks := fmt.Sprintf("127.0.0.1:%d", port)
	deadline := time.Now().Add(15 * time.Second)
	for {
		c, err := net.DialTimeout("tcp", socks, 300*time.Millisecond)
		if err == nil {
			c.Close()
			break
		}
		if p.exited() || time.Now().After(deadline) {
			p.kill()
			return nil, 0, fmt.Errorf("%s did not start: %s", t.Proto, p.lastLines())
		}
		time.Sleep(150 * time.Millisecond)
	}
	px, err := socks5.New(socks, "", "")
	if err != nil {
		p.kill()
		return nil, 0, err
	}
	t2stunnel.T().SetProxy(px)
	dev, err := t2stun.Open(AdapterName, uint32(t.MTU))
	if err != nil {
		p.kill()
		return nil, 0, adapterErr(err)
	}
	luid, err := winipcfg.LUIDFromGUID(&adapterGUID)
	if err != nil {
		dev.Close()
		p.kill()
		return nil, 0, adapterErr(err)
	}
	st, err := t2score.CreateStack(&t2score.Config{LinkEndpoint: dev, TransportHandler: t2stunnel.T()})
	if err != nil {
		dev.Close()
		p.kill()
		return nil, 0, err
	}
	return &programRun{proc: p, dev: dev, stack: st, luid: luid}, luid, nil
}

func (r *programRun) close() {
	r.dev.Close()
	r.stack.Close()
	r.stack.Wait()
	r.proc.kill()
}

func (r *programRun) stats() (rx, tx uint64, alive bool) {
	rx, tx = adapterCounters(r.luid)
	return rx, tx, !r.proc.exited()
}

// PingProgram: Hysteria2's own ping (as the Android app does): connect with the
// user's password and time one stream through the server.
func (e *Engine) PingProgram(ctx context.Context, proto, text string, ep netip.AddrPort) (int64, error) {
	if proto != cores.Hysteria2 {
		return 0, errors.New("no program ping")
	}
	h, err := cores.ParseHy2(text)
	if err != nil {
		return 0, err
	}
	port, err := freePort()
	if err != nil {
		return 0, err
	}
	p, err := e.start("hysteria.exe", []string{"ping", "127.0.0.1:1", "-c"}, "", h.Config(ep.String(), port))
	if err != nil {
		return 0, err
	}
	defer p.kill()
	select {
	case <-p.done:
	case <-time.After(10 * time.Second):
	case <-ctx.Done():
	}
	m := timeRe.FindStringSubmatch(p.lastLines())
	if m == nil {
		return 0, errors.New("no answer")
	}
	v, _ := strconv.ParseFloat(m[1], 64)
	ms := map[string]float64{"ns": v / 1e6, "µs": v / 1e3, "us": v / 1e3, "ms": v, "s": v * 1000}[m[2]]
	return max(1, int64(ms)), nil
}

var timeRe = regexp.MustCompile(`"time":\s*"([0-9.]+)(ns|µs|us|ms|s)"`)

// ---------------------------------------------------------------- core programs

// program is a core program run by the service. It sits in a job object that
// kills it when the service ends, however the service ends.
type program struct {
	cmd  *exec.Cmd
	job  windows.Handle
	done chan struct{}
	mu   sync.Mutex
	out  []string
}

// start runs dir\name with args. stdin is fed to it; a non-empty file is a
// config written to the service's private folder for the moment the program
// reads it (Hysteria cannot read stdin) and removed right after.
func (e *Engine) start(name string, args []string, stdin, file string) (*program, error) {
	exe := filepath.Join(e.dir, name)
	if _, err := os.Stat(exe); err != nil {
		return nil, fmt.Errorf("%s is missing next to the service (reinstall Kanki VPN)", name)
	}
	var cfgPath string
	if file != "" {
		dir := filepath.Join(os.Getenv("ProgramData"), "KankiVPN") // SYSTEM and Administrators only
		f, err := os.CreateTemp(dir, "core-*.json")
		if err != nil {
			return nil, err
		}
		cfgPath = f.Name()
		_, err = f.WriteString(file)
		f.Close()
		if err != nil {
			os.Remove(cfgPath)
			return nil, err
		}
		args = append(args, cfgPath)
	}
	cmd := exec.Command(exe, args...)
	cmd.Dir = e.dir
	cmd.Env = append(os.Environ(), "HYSTERIA_DISABLE_UPDATE_CHECK=1", "XRAY_LOCATION_ASSET="+e.dir)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
	if stdin != "" {
		cmd.Stdin = strings.NewReader(stdin)
	}
	p := &program{cmd: cmd, done: make(chan struct{})}
	pr, pw := io.Pipe()
	cmd.Stdout, cmd.Stderr = pw, pw
	if err := cmd.Start(); err != nil {
		if cfgPath != "" {
			os.Remove(cfgPath)
		}
		return nil, err
	}
	p.job = killWithService(cmd.Process.Pid)
	go func() {
		buf := make([]byte, 4096)
		var line []byte
		for {
			n, err := pr.Read(buf)
			for _, b := range buf[:n] {
				if b == '\n' {
					p.mu.Lock()
					p.out = append(p.out, strings.TrimSpace(string(line)))
					if len(p.out) > 40 {
						p.out = p.out[len(p.out)-40:]
					}
					p.mu.Unlock()
					line = line[:0]
				} else {
					line = append(line, b)
				}
			}
			if err != nil {
				return
			}
		}
	}()
	go func() {
		_ = cmd.Wait()
		pw.Close()
		close(p.done)
	}()
	if cfgPath != "" {
		go func() {
			// it is read at start; gone after a moment or when the program ends
			select {
			case <-time.After(3 * time.Second):
			case <-p.done:
			}
			os.Remove(cfgPath)
		}()
	}
	return p, nil
}

func (p *program) exited() bool {
	select {
	case <-p.done:
		return true
	default:
		return false
	}
}

func (p *program) lastLines() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return strings.Join(p.out, "\n")
}

func (p *program) kill() {
	if !p.exited() {
		_ = p.cmd.Process.Kill()
		<-p.done
	}
	if p.job != 0 {
		windows.CloseHandle(p.job)
		p.job = 0
	}
}

// killWithService puts the process in a job object that ends it when the job
// handle (held by the service) closes, so no core outlives a crashed service.
func killWithService(pid int) windows.Handle {
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return 0
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); err != nil {
		windows.CloseHandle(job)
		return 0
	}
	h, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(pid))
	if err == nil {
		_ = windows.AssignProcessToJobObject(job, h)
		windows.CloseHandle(h)
	}
	return job
}
