# Connecting players (smartphones)

This guide explains how to let players' smartphones join a game via the QR
code shown on the "Connect players" screen.

## The principle

Players' smartphones must be on the **same local network** as the computer
running the software. Once connected to the same network, players simply
scan the QR code shown on screen with their phone's native camera app (no
app to install) to join the game.

## Two ways to put the phones on the same network

### Recommended option: tethering from the computer

The computer creates its own Wi-Fi access point, which the phones connect
to. This is the most reliable option: it does not depend on any existing
network infrastructure at the venue.

- **Windows**: Settings → Network & Internet → Mobile hotspot.
- **macOS**: System Settings → Sharing → Internet Sharing.
- **Linux**: depends on the desktop environment; look for "Wi-Fi Hotspot" or
  "Hotspot" in the network settings.

Give it a network name and password, enable sharing, then have the phones
join that network before scanning the QR code.

### Alternative option: the venue's local Wi-Fi

If the venue already has a Wi-Fi network, phones can connect to it directly
— provided that network does not have **client isolation** enabled (a
security setting common on public/guest networks, which prevents connected
devices from communicating with each other). If phones can't reach the
application despite being connected to the same Wi-Fi, this is the most
likely cause: use the tethering option instead.

## Why not Bluetooth?

Bluetooth is designed for a connection between two devices, with tedious
individual pairing for several players, and lower throughput than Wi-Fi. It
is not suited to connecting several smartphones to the application at the
same time.

## Choosing the right network address

The "Connect players" screen may show several network addresses if the
computer is connected to several networks at once (Wi-Fi and Ethernet, for
example). The one marked **"Likely"** is generally the right one to use. If
in doubt, try the first address offered.
