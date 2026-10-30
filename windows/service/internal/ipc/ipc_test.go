package ipc

import (
	"bufio"
	"encoding/json"
	"net"
	"strings"
	"testing"

	"kankivpn/internal/core"
	"kankivpn/internal/logx"
	"kankivpn/internal/state"
	"kankivpn/internal/sub"
)

type nopEngine struct{}

func (nopEngine) Up(core.Tunnel) error { return nil }
func (nopEngine) Down(bool, bool)      {}
func (nopEngine) Stats() core.Stats    { return core.Stats{} }

func TestPipeProtocol(t *testing.T) {
	st, err := state.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	log := logx.New("")
	m := core.New(nopEngine{}, st, log, sub.NewClient())
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	go Serve(l, m, log, "1.0.0")

	c, err := net.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	r := bufio.NewReader(c)
	ask := func(line string) Response {
		t.Helper()
		if _, err := c.Write([]byte(line + "\n")); err != nil {
			t.Fatal(err)
		}
		b, err := r.ReadBytes('\n')
		if err != nil {
			t.Fatal(err)
		}
		var resp Response
		if err := json.Unmarshal(b, &resp); err != nil {
			t.Fatal(err)
		}
		return resp
	}
	if resp := ask(`{"id":1,"cmd":"hello"}`); !resp.OK || resp.ID != 1 {
		t.Fatalf("%+v", resp)
	}
	if resp := ask(`{"id":2,"cmd":"status"}`); !resp.OK || !strings.Contains(toJSON(resp.Data), `"state":"disconnected"`) {
		t.Fatalf("%+v", resp)
	}
	if resp := ask(`{"id":3,"cmd":"connect","args":{"server":"x"}}`); resp.OK {
		t.Fatal("connect without subscription")
	}
	if resp := ask(`{"id":4,"cmd":"set_settings","args":{"settings":{"split_mode":"include"}}}`); resp.OK {
		t.Fatal("invalid settings accepted")
	}
	if resp := ask(`{"id":5,"cmd":"set_settings","args":{"settings":{"kill_switch":false,"block_ipv6":true,"split_mode":"exclude","split_list":["5.0.0.0/8"]}}}`); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	if resp := ask(`{"id":6,"cmd":"rm -rf"}`); resp.OK || resp.Error != "unknown command" {
		t.Fatalf("%+v", resp)
	}
	if resp := ask(`not json`); resp.OK {
		t.Fatal("garbage accepted")
	}
	// a line over the limit closes the connection instead of eating memory
	if _, err := c.Write([]byte(strings.Repeat("a", maxLine+10) + "\n")); err == nil {
		if _, err := r.ReadBytes('\n'); err == nil {
			t.Fatal("huge line answered")
		}
	}
}

func toJSON(v any) string { b, _ := json.Marshal(v); return string(b) }
