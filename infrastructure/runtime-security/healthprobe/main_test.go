package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestReadyRequiresActual200(t *testing.T) {
	for _, status := range []int{200, 204, 302, 503} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(status) }))
		err := ready(server.URL+"/ready", time.Second)
		server.Close()
		if (err == nil) != (status == 200) {
			t.Fatalf("status %d, result %v", status, err)
		}
	}
}

func TestReadyTimesOutAndRejectsClosedServer(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { time.Sleep(60 * time.Millisecond); w.WriteHeader(200) }))
	if ready(server.URL, 10*time.Millisecond) == nil { t.Fatal("hung response accepted") }
	server.Close()
	if ready(server.URL, time.Second) == nil { t.Fatal("closed server accepted") }
}

func TestReadyDoesNotFollowRedirectsOrContactExternalTargets(t *testing.T) {
	for _, address := range []string{"https://127.0.0.1/ready", "http://example.invalid/ready", "http://user:password@127.0.0.1/ready"} {
		if ready(address, time.Second) == nil { t.Fatal("invalid target accepted") }
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Header().Set("Location", "http://example.invalid/ready"); w.WriteHeader(302) }))
	defer server.Close()
	if ready(server.URL, time.Second) == nil { t.Fatal("redirect accepted") }
}
