package main

import (
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"time"
)

func ready(address string, timeout time.Duration) error {
	u, err := url.Parse(address)
	if err != nil || u.Scheme != "http" || !net.ParseIP(u.Hostname()).IsLoopback() || u.User != nil {
		return fmt.Errorf("expected loopback HTTP readiness URL")
	}
	client := &http.Client{Timeout: timeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Get(address)
	if err != nil {
		return fmt.Errorf("readiness request failed")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("readiness status %d", response.StatusCode)
	}
	return nil
}

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "expected one loopback HTTP readiness URL")
		os.Exit(1)
	}
	if err := ready(os.Args[1], 2*time.Second); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
