import socket
import struct
import random
import time
import traceback

TARGET_IP = "192.168.1.100"
TARGET_PORT = 5570
EMAIL = "test@test.com" # Dummy email

# Constants
U_CONNECT = b'\x83'
U_DATA = b'\x16'
U_IPX = b'\x35'
U_CRYPT = b'\x36'
U_CP_ID = b'\x3F'

def build_ipx_payload():
    # Payload Type (1) + Flags (1) + Length (2) + Content
    # Content: 17 bytes
    content = b'\x00\x11' + b'\x00'*16 + b'\xa0'
    
    payload_type = U_IPX
    payload_flags = b'\x00'
    length = b'\x00\x11' # 17 bytes of data following
    data = b'\x00'*4 + b'\x00'*2 + b'\x00'*4 + b'\x00'*2 + b'\xa0'
    
    return payload_type + payload_flags + length + data

def build_cp_id_payload(email):
    payload_type = U_CP_ID
    payload_flags = b'\x00'
    
    email_bytes = email.encode("ascii")
    length_val = 4 + 1 + len(email_bytes)
    
    return payload_type + payload_flags + length_val.to_bytes(2, 'big') + b'\x01' + email_bytes

def build_crypt_payload(data):
    payload_type = U_CRYPT
    payload_flags = b'\x00'
    
    # Length = 6 + len(data) + 3 (from code) -> 9 + len(data)
    # But code writes 7 + len(data) bytes.
    # Let's follow the code's logic for the length field value.
    length_field_val = 6 + len(data) + 3
    
    # Content: Crypto Code (2) + Data + Padding (1)
    content = b'\x00\x0a' + data + b'\x02'
    
    return payload_type + payload_flags + length_field_val.to_bytes(2, 'big') + content

def build_ping_command():
    # ProxyCommandType.PING = b'\x11'
    return b'\x00\x00\x00\x11\x70\x69\x6e\x67'

def build_packet(client_id, server_id, packet_type, sequence_id, payloads):
    payload_bundle = b''.join(payloads)
    
    packet_length = len(payload_bundle) + 16
    
    header = b''.join([
        client_id,
        server_id,
        packet_type,
        b'\x02', # Version
        b'\x00', # Retransmision count
        b'\x00', # Flags
        sequence_id.to_bytes(2, 'big'),
        packet_length.to_bytes(2, 'big')
    ])
    
    return header + payload_bundle

def connect():
    client_id = random.randint(0, 0xffffffff).to_bytes(4, 'big')
    server_id = b'\x00\x00\x00\x00'
    
    ipx = build_ipx_payload()
    cp_id = build_cp_id_payload(EMAIL)
    
    packet = build_packet(client_id, server_id, U_CONNECT, 0, [ipx, cp_id])
    
    print(f"Sending U_CONNECT to {TARGET_IP}:{TARGET_PORT}...", flush=True)
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.settimeout(5)
    
    try:
        sock.sendto(packet, (TARGET_IP, TARGET_PORT))
        
        response = None
        while True:
            data, addr = sock.recvfrom(1024)
            print(f"Received response from {addr}: {data.hex()}", flush=True)
            response = data
            break
        
        if response:
            print("Parsing response...", flush=True)
            # Parse response
            # message[20:24]
            status = response[20:24]
            print(f"Status: {status.hex()}", flush=True)
            
            # Even if status is not 00000001, let's try to extract server_id and ping
            # Assuming server_id is at 24:28
            new_server_id = response[24:28]
            print(f"Extracted Server ID: {new_server_id.hex()}", flush=True)
            
            # Send PING
            print("Sending PING...", flush=True)
            ping_cmd = build_ping_command()
            crypt_payload = build_crypt_payload(ping_cmd)
            
            # Sequence ID 50 for PING
            ping_packet = build_packet(client_id, new_server_id, U_DATA, 50, [crypt_payload])
            print(f"Sending PING packet: {ping_packet.hex()}", flush=True)
            
            sock.sendto(ping_packet, (TARGET_IP, TARGET_PORT))
            
            print("Waiting for PING response...", flush=True)
            while True:
                data, addr = sock.recvfrom(1024)
                print(f"Received PING response from {addr}: {data.hex()}", flush=True)
                # Parse PING response
                if data[8] == 0x16:
                    # Find payload
                    # Header is 16 bytes
                    payload_type = data[16]
                    if payload_type == 0x36:
                        print("Got CRYPT payload!", flush=True)
                        # Length at 18:20
                        p_len = int.from_bytes(data[18:20], 'big')
                        
                        # Payload content starts at 20? No, 22?
                        # Payload structure: Type(1) Flags(1) Length(2) Content...
                        # So content starts at 16+4 = 20.
                        # Content: Crypto(2) + Data + Padding(1)
                        # Data starts at 20+2 = 22.
                        
                        payload_data = data[22:]
                        print(f"Payload Data: {payload_data.hex()}", flush=True)
                        
                        # Parse model info
                        # self._device_number = int.from_bytes(payload[4:8], 'big')
                        # self._device_model = int.from_bytes(payload[8:12], 'big')
                        # self._slave_device_number = int.from_bytes(payload[16:20], 'big')
                        # self._slave_device_model = int.from_bytes(payload[20:24], 'big')
                        
                        # Payload data here includes the command response header?
                        # build_ping_command returns b'\x00\x00\x00\x11\x70\x69\x6e\x67'
                        # Response probably has similar header.
                        # Let's assume the first 4 bytes are command header.
                        
                        if len(payload_data) >= 24:
                            device_number = int.from_bytes(payload_data[4:8], 'big')
                            device_model = int.from_bytes(payload_data[8:12], 'big')
                            slave_device_number = int.from_bytes(payload_data[16:20], 'big')
                            slave_device_model = int.from_bytes(payload_data[20:24], 'big')
                            
                            print(f"Device Number: {device_number}", flush=True)
                            print(f"Device Model: {device_model}", flush=True)
                            print(f"Slave Device Number: {slave_device_number}", flush=True)
                            print(f"Slave Device Model: {slave_device_model}", flush=True)
                        
                break

    except socket.timeout:
        print("Connection timed out.", flush=True)
    except Exception as e:
        print(f"An error occurred: {e}", flush=True)
        traceback.print_exc()
    finally:
        sock.close()
        print("Socket closed.", flush=True)

if __name__ == "__main__":
    connect()
