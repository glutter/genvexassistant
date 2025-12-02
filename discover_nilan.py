import socket
import time

DISCOVERY_PORT = 5570
TIMEOUT = 5

def build_discovery_packet(device_id="*"):
    return b"".join([
        b'\x00\x00\x00\x01', # So called "Legacy header"
        b'\x00\x00\x00\x00\x00\x00\x00\x00', # Seems like unused space in header?
        device_id.encode("ascii"),
        b'\x00' # Zero terminator for string
    ])

def discover():
    print(f"Sending discovery packet to port {DISCOVERY_PORT}...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(TIMEOUT)
    sock.bind(("", 0))

    packet = build_discovery_packet()

    # Try broadcast first, but catch error
    try:
        print(f"Sending broadcast discovery packet to port {DISCOVERY_PORT}...")
        sock.sendto(packet, ("255.255.255.255", DISCOVERY_PORT))
    except OSError as e:
        print(f"Broadcast failed: {e}")

    # Try specific IP if provided
    target_ip = "192.168.1.100"
    print(f"Sending unicast discovery packet to {target_ip}:{DISCOVERY_PORT}...")
    sock.sendto(packet, (target_ip, DISCOVERY_PORT))

    try:
        while True:
            data, addr = sock.recvfrom(1024)
            print(f"Received response from {addr}: {data}")
            # Parse response if possible
            # The response format isn't fully detailed in the snippet I read, 
            # but seeing any response confirms communication.
    except socket.timeout:
        print("Discovery timed out.")
    finally:
        sock.close()

if __name__ == "__main__":
    discover()
