// Package ipc is how the app (normal user) talks to the service (SYSTEM):
// one JSON object per line each way over the named pipe \\.\pipe\KankiVPN.
//
//	-> {"id":1,"cmd":"connect","args":{"server":"local/amneziawg"}}
//	<- {"id":1,"ok":true}
//
// The app can only ask for these commands; nothing it sends is ever run as a
// program or written outside the service's own state file.
package ipc

import (
	"bufio"
	"encoding/json"
	"errors"
	"net"
	"time"

	"kankivpn/internal/core"
	"kankivpn/internal/logx"
	"kankivpn/internal/state"
)

// PipeName is the pipe both sides use.
const PipeName = `\\.\pipe\KankiVPN`

// Version of this protocol; the app shows a warning if they differ.
const Version = 1

type Request struct {
	ID   int             `json:"id"`
	Cmd  string          `json:"cmd"`
	Args json.RawMessage `json:"args,omitempty"`
}

type Response struct {
	ID    int    `json:"id"`
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	Data  any    `json:"data,omitempty"`
}

const maxLine = 64 << 10

// Serve answers every connection on l until l is closed.
func Serve(l net.Listener, m *core.Manager, log *logx.Log, version string) error {
	for {
		c, err := l.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			log.Printf("ipc accept: %v", err)
			time.Sleep(100 * time.Millisecond)
			continue
		}
		go handle(c, m, log, version)
	}
}

func handle(c net.Conn, m *core.Manager, log *logx.Log, version string) {
	defer c.Close()
	sc := bufio.NewScanner(c)
	sc.Buffer(make([]byte, 4096), maxLine)
	enc := json.NewEncoder(c)
	for sc.Scan() {
		var req Request
		var resp Response
		if err := json.Unmarshal(sc.Bytes(), &req); err != nil {
			resp.Error = "bad request"
		} else {
			resp = Do(req, m, log, version)
		}
		resp.ID = req.ID
		if err := enc.Encode(resp); err != nil {
			return
		}
	}
}

// Do runs one command.
func Do(req Request, m *core.Manager, log *logx.Log, version string) Response {
	ok := func(d any) Response { return Response{OK: true, Data: d} }
	fail := func(err error) Response { return Response{Error: err.Error()} }
	var args struct {
		Server   string          `json:"server"`
		Input    string          `json:"input"`
		Settings *state.Settings `json:"settings"`
		Servers  []string        `json:"servers"`
	}
	if len(req.Args) > 0 {
		if err := json.Unmarshal(req.Args, &args); err != nil {
			return fail(errors.New("bad arguments"))
		}
	}
	switch req.Cmd {
	case "hello":
		return ok(map[string]any{"protocol": Version, "version": version})
	case "status":
		return ok(m.Status())
	case "account":
		return ok(m.Account())
	case "set_sub":
		a, err := m.SetSubscription(args.Input)
		if err != nil {
			return fail(err)
		}
		return ok(a)
	case "refresh":
		a, err := m.Refresh()
		if err != nil {
			return Response{Error: err.Error(), Data: a}
		}
		return ok(a)
	case "logout":
		m.Logout()
		return ok(nil)
	case "connect":
		if err := m.Connect(args.Server); err != nil {
			return fail(err)
		}
		return ok(nil)
	case "disconnect":
		m.Disconnect()
		return ok(nil)
	case "settings":
		return ok(m.Settings())
	case "set_settings":
		if args.Settings == nil {
			return fail(errors.New("no settings"))
		}
		if err := m.SetSettings(*args.Settings); err != nil {
			return fail(err)
		}
		return ok(m.Settings())
	case "ping":
		return ok(m.Ping(args.Servers))
	case "logs":
		return ok(log.Lines())
	}
	return fail(errors.New("unknown command"))
}
