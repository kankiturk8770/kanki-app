// Package logx is the service log: the last lines in memory (for the app's
// log view) and a small file next to the state, cut when it grows past 1 MB.
package logx

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const keep = 400

type Log struct {
	mu    sync.Mutex
	lines []string
	path  string
}

func New(dir string) *Log {
	l := &Log{}
	if dir != "" {
		l.path = filepath.Join(dir, "service.log")
	}
	return l
}

// Printf adds one line. Never pass keys or the subscription code here.
func (l *Log) Printf(format string, a ...any) {
	line := time.Now().Format("2006-01-02 15:04:05 ") + fmt.Sprintf(format, a...)
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lines = append(l.lines, line)
	if len(l.lines) > keep {
		l.lines = l.lines[len(l.lines)-keep:]
	}
	if l.path == "" {
		return
	}
	if fi, err := os.Stat(l.path); err == nil && fi.Size() > 1<<20 {
		_ = os.Rename(l.path, l.path+".old")
	}
	if f, err := os.OpenFile(l.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
		fmt.Fprintln(f, line)
		f.Close()
	}
}

// Lines returns a copy of the last lines.
func (l *Log) Lines() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]string(nil), l.lines...)
}
