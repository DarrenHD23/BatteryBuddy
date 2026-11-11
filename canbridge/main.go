// canbridge: SocketCAN helper for the HUD's live car state. It opens the given interfaces, keeps only the
// listed CAN IDs (kernel CAN_RAW filters), and prints each frame as "<iface> <id-hex> <data-hex>" on
// stdout: every change at once, and every ID's current payload again each KEEPALIVE even when it hasn't
// changed, so the page can tell a steady value from a silent bus.
//
// It can also transmit, but only what it was told it may: an allowlist of IDs per interface given on the
// command line, nothing else. The frames themselves come from the parent over stdin, one per line:
//
//	canbridge can1=234,2F5,335 can2=236,321 tx:can1=04E,530 tx:can2=
//	stdin:  tx can1 04E 0000000000000000
//
// A line "# ready" is printed once the sockets are bound; "# tx <iface> <id> <ok|error ...>" answers each
// transmit. Transmitted frames are not looped back into the receive path. Stdin closing (the parent going
// away) ends it, and so does a socket that can't read (exit code 1, the reason on stderr): an interface
// that went away -- Pulse's app reloads the CAN driver, which makes new can1/can2 -- leaves a socket that
// never reads again, so the parent starts a new helper on the new interfaces. An interface that is only
// down is waited out: its socket reads again once it's up.
package main

import (
	"bufio"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

const (
	afCAN     = 29
	canRAW    = 1
	solCANRAW = 101
	optFilter = 1 // CAN_RAW_FILTER
	optLoop   = 3 // CAN_RAW_LOOPBACK
	effFlag   = 0x80000000
	errFlag   = 0x20000000
	rtrFlag   = 0x40000000
	keepalive = time.Second
)

type sockaddrCAN struct {
	Family  uint16
	_       uint16
	Ifindex int32
	Addr    [16]byte
}

type filter struct {
	ID   uint32
	Mask uint32
}

func ifindex(iface string) (int32, error) {
	ifi, err := net.InterfaceByName(iface)
	if err != nil {
		return 0, fmt.Errorf("interface %s: %w", iface, err)
	}
	return int32(ifi.Index), nil
}

func bind(fd int, iface string) error {
	idx, err := ifindex(iface)
	if err != nil {
		return err
	}
	sa := sockaddrCAN{Family: afCAN, Ifindex: idx}
	if _, _, e := syscall.Syscall(syscall.SYS_BIND, uintptr(fd), uintptr(unsafe.Pointer(&sa)), unsafe.Sizeof(sa)); e != 0 {
		return fmt.Errorf("bind %s: %v", iface, e)
	}
	return nil
}

// openRead binds a raw CAN socket to iface, accepting only the given IDs.
func openRead(iface string, ids []uint32) (int, error) {
	fd, err := syscall.Socket(afCAN, syscall.SOCK_RAW, canRAW)
	if err != nil {
		return -1, fmt.Errorf("socket: %w", err)
	}
	if len(ids) > 0 {
		filters := make([]filter, len(ids))
		for i, id := range ids {
			filters[i] = filter{ID: id, Mask: 0x7FF}
		}
		_, _, e := syscall.Syscall6(syscall.SYS_SETSOCKOPT, uintptr(fd), solCANRAW, optFilter,
			uintptr(unsafe.Pointer(&filters[0])), unsafe.Sizeof(filters[0])*uintptr(len(filters)), 0)
		if e != 0 {
			return -1, fmt.Errorf("set filter on %s: %v", iface, e)
		}
	}
	_ = syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, 33 /* SO_RCVBUFFORCE */, 4<<20)
	if err := bind(fd, iface); err != nil {
		return -1, err
	}
	return fd, nil
}

// openWrite binds a raw CAN socket for sending on iface, with loopback off so what this program sends
// never comes back through its own receive sockets as if the car had sent it.
func openWrite(iface string) (int, error) {
	fd, err := syscall.Socket(afCAN, syscall.SOCK_RAW, canRAW)
	if err != nil {
		return -1, fmt.Errorf("socket: %w", err)
	}
	_ = syscall.SetsockoptInt(fd, solCANRAW, optLoop, 0)
	if err := bind(fd, iface); err != nil {
		return -1, err
	}
	return fd, nil
}

func parseIDs(csv string) []uint32 {
	var ids []uint32
	for _, s := range strings.Split(csv, ",") {
		if s = strings.TrimSpace(s); s != "" {
			v, err := strconv.ParseUint(strings.TrimPrefix(strings.ToLower(s), "0x"), 16, 32)
			if err == nil && v <= 0x7FF {
				ids = append(ids, uint32(v))
			}
		}
	}
	return ids
}

type txIface struct {
	fd      int
	allowed map[uint32]bool
}

func main() {
	var ifaces [][2]string     // iface, csv-ids to receive
	txAllow := map[string][]uint32{} // iface -> ids it may send
	for _, a := range os.Args[1:] {
		if strings.HasPrefix(a, "tx:") {
			if i := strings.IndexByte(a, '='); i > 3 {
				txAllow[a[3:i]] = parseIDs(a[i+1:])
			}
			continue
		}
		if i := strings.IndexByte(a, '='); i > 0 {
			ifaces = append(ifaces, [2]string{a[:i], a[i+1:]})
		}
	}
	if len(ifaces) == 0 {
		fmt.Fprintln(os.Stderr, "usage: canbridge can1=234,2F5 can2=236 [tx:can1=04E,530]")
		os.Exit(2)
	}
	// every line goes out through here, under one lock: the readers, the keepalive and the replies all write,
	// and a bufio.Writer used from two goroutines at once corrupts itself and then drops everything for good.
	// A write that fails means the parent is gone: end, and let it start a new one.
	var mu sync.Mutex
	out := bufio.NewWriter(os.Stdout)
	emit := func(format string, a ...any) {
		mu.Lock()
		defer mu.Unlock()
		fmt.Fprintf(out, format, a...)
		if err := out.Flush(); err != nil {
			os.Exit(1)
		}
	}
	fds := []int{}
	type lastFrame struct {
		data string
		at   time.Time
	}
	latest := map[string]map[uint32]*lastFrame{} // iface -> id -> last payload and when it was printed
	for _, spec := range ifaces {
		fd, err := openRead(spec[0], parseIDs(spec[1]))
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		fds = append(fds, fd)
		iface := spec[0]
		last := map[uint32]*lastFrame{}
		latest[iface] = last
		go func() {
			buf := make([]byte, 16)
			for {
				n, err := syscall.Read(fd, buf)
				if err != nil {
					if err == syscall.EINTR {
						continue
					}
					if err == syscall.ENETDOWN {
						// down, not gone (or bound while down: between Pulse's insmod and its ip link up): the
						// socket stays bound and reads again once the interface is up, and the other bus reads on
						fmt.Fprintf(os.Stderr, "read %s: %v: waiting for it\n", iface, err)
						time.Sleep(100 * time.Millisecond)
						continue
					}
					// the interface is gone (ENODEV: the driver was reloaded): this socket is done, and so is the helper
					fmt.Fprintf(os.Stderr, "read %s: %v\n", iface, err)
					os.Exit(1)
				}
				if n < 16 {
					continue
				}
				raw := uint32(buf[0]) | uint32(buf[1])<<8 | uint32(buf[2])<<16 | uint32(buf[3])<<24
				if raw&(errFlag|rtrFlag) != 0 || raw&effFlag != 0 {
					continue // only standard data frames
				}
				id := raw & 0x7FF
				dlc := int(buf[4])
				if dlc > 8 {
					dlc = 8
				}
				data := hex.EncodeToString(buf[8 : 8+dlc])
				mu.Lock()
				f := last[id]
				changed := f == nil || f.data != data // forward at once on change (and the keepalive repeats the rest)
				if changed {
					if f == nil {
						f = &lastFrame{}
						last[id] = f
					}
					f.data = data
					f.at = time.Now()
				}
				mu.Unlock()
				if changed {
					emit("%s %03X %s\n", iface, id, data)
				}
			}
		}()
	}
	// what may be sent, and the sockets to send it on
	tx := map[string]*txIface{}
	for iface, ids := range txAllow {
		if len(ids) == 0 {
			continue
		}
		fd, err := openWrite(iface)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		fds = append(fds, fd)
		allowed := map[uint32]bool{}
		for _, id := range ids {
			allowed[id] = true
		}
		tx[iface] = &txIface{fd: fd, allowed: allowed}
	}
	// a steady value is printed again every KEEPALIVE, so the page can tell it from a silent bus
	go func() {
		for range time.Tick(keepalive / 4) {
			now := time.Now()
			var due []string
			mu.Lock()
			for iface, last := range latest {
				for id, f := range last {
					if now.Sub(f.at) >= keepalive {
						f.at = now
						due = append(due, fmt.Sprintf("%s %03X %s\n", iface, id, f.data))
					}
				}
			}
			mu.Unlock()
			for _, line := range due {
				emit("%s", line)
			}
		}
	}()
	emit("# ready\n")
	// transmit requests from the parent, until it closes our stdin
	in := bufio.NewScanner(os.Stdin)
	for in.Scan() {
		fields := strings.Fields(in.Text())
		if len(fields) != 4 || fields[0] != "tx" {
			continue
		}
		iface, idText, dataText := fields[1], fields[2], fields[3]
		reply := func(status string) {
			emit("# tx %s %s %s\n", iface, idText, status)
		}
		t := tx[iface]
		id, err := strconv.ParseUint(strings.TrimPrefix(strings.ToLower(idText), "0x"), 16, 32)
		if t == nil || err != nil || !t.allowed[uint32(id)] {
			reply("error not allowed")
			continue
		}
		data, err := hex.DecodeString(dataText)
		if err != nil || len(data) == 0 || len(data) > 8 {
			reply("error bad data")
			continue
		}
		frame := make([]byte, 16)
		frame[0], frame[1], frame[2], frame[3] = byte(id), byte(id>>8), byte(id>>16), byte(id>>24)
		frame[4] = byte(len(data))
		copy(frame[8:], data)
		if _, err := syscall.Write(t.fd, frame); err != nil {
			reply("error " + err.Error())
			continue
		}
		reply("ok")
	}
	for _, fd := range fds {
		syscall.Close(fd)
	}
}
