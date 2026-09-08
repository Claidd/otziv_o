package main

import (
	"crypto/sha256"
	"debug/elf"
	"debug/gosym"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"sort"
)

// Inspect Go's runtime function table without loading or executing the image.
// This is linked-function evidence only, not a complete inlining/call-graph proof.
func main() {
	if len(os.Args) != 2 { panic("one ELF path required") }
	b, err := os.ReadFile(os.Args[1]); if err != nil { panic(err) }
	f, err := elf.Open(os.Args[1]); if err != nil { panic(err) }; defer f.Close()
	text, pcln := f.Section(".text"), f.Section(".gopclntab")
	if text == nil || pcln == nil { panic("required Go ELF sections absent") }
	p, err := pcln.Data(); if err != nil { panic(err) }
	var sym []byte
	if s := f.Section(".gosymtab"); s != nil { sym, err = s.Data(); if err != nil { panic(err) } }
	tab, err := gosym.NewTable(sym, gosym.NewLineTable(p, text.Addr)); if err != nil { panic(err) }
	if len(tab.Funcs) < 1000 { panic("unexpected incomplete function table") }
	names := make([]string, 0, len(tab.Funcs))
	for _, fn := range tab.Funcs { if fn.Sym == nil || fn.Name == "" { panic("unnamed function") }; names = append(names, fn.Name) }
	sort.Strings(names)
	h := sha256.Sum256(b)
	result := map[string]any{"schema":"otziv-go-linked-functions-v1", "binarySha256":hex.EncodeToString(h[:]), "elfClass":f.Class.String(), "elfMachine":f.Machine.String(), "functionCount":len(names), "functions":names, "inspectedBinaryExecuted":false, "limitation":"Linked functions do not by themselves establish absence of inlined code; require a matching source import closure."}
	enc := json.NewEncoder(os.Stdout); enc.SetIndent("", "  "); if err := enc.Encode(result); err != nil { fmt.Fprintln(os.Stderr, err); os.Exit(1) }
}
