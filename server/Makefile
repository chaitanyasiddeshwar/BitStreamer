# BitStreamer server builds (pure Go stdlib — cross-compiles from any host).
#
#   make            native binary for THIS machine
#                     -> bitstreamer        (macOS/Linux)
#                     -> bitstreamer.exe     (Windows, if you have make)
#   make windows    -> dist/bitstreamer.exe            (Windows x64)
#   make darwin     -> dist/bitstreamer-macos          (universal: arm64 + x64)
#   make all        -> both of the above
#   make test / make clean
#
# On Windows without make, use build.bat instead.

BINARY := bitstreamer
DIST := dist

.PHONY: build test clean windows darwin darwin-arm64 darwin-amd64 all

# Host-native build. go env GOEXE is ".exe" on Windows, empty elsewhere,
# so the output is named correctly for whatever machine runs this.
build:
	go build -o $(BINARY)$(shell go env GOEXE) .

test:
	go test ./...

windows:
	GOOS=windows GOARCH=amd64 go build -o $(DIST)/bitstreamer.exe .

darwin-arm64:
	GOOS=darwin GOARCH=arm64 go build -o $(DIST)/bitstreamer-macos-arm64 .

darwin-amd64:
	GOOS=darwin GOARCH=amd64 go build -o $(DIST)/bitstreamer-macos-amd64 .

# Universal macOS binary (Apple Silicon + Intel). Requires lipo (macOS host).
darwin: darwin-arm64 darwin-amd64
	lipo -create -output $(DIST)/bitstreamer-macos \
		$(DIST)/bitstreamer-macos-arm64 $(DIST)/bitstreamer-macos-amd64

all: windows darwin

clean:
	rm -rf $(DIST) $(BINARY) $(BINARY).exe
