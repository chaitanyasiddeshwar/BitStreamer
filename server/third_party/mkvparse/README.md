# Vendored: go-mkvparse

Core parser package from [github.com/remko/go-mkvparse](https://github.com/remko/go-mkvparse)
**v0.14.0**, MIT licensed (see `LICENSE.txt`).

Only the root package files are vendored (`elements.go`, `handlers.go`, `mkvparse.go`,
`sectionparser.go`, `tags.go`, `vint.go`) — all pure Go standard library, no external
dependencies. The upstream module's `examples/` pull in `golang.org/x/image`; those are
deliberately **not** vendored, so the server stays dependency-free and cross-compiles
to a single exe.

Used by `server/chapters.go` to read MKV chapter markers. To update: re-copy the same
files from a newer tag and re-run `go test ./...`.
