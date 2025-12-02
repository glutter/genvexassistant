import sys
import os
import time
import logging

# Add the library to the path
sys.path.append(os.path.abspath("temp_genvex_nabto/src"))

from genvexnabto.genvexnabto import GenvexNabto

# Configure logging
logging.basicConfig(level=logging.DEBUG)

def main():
    print("Starting GenvexNabto test...")
    proxy = GenvexNabto(_authorized_email="test@test.com")
    
    # Set the device manually since we know the IP
    # GenvexNabto class might have different methods
    # Let's check the code or just try similar methods
    # In genvexnabto.py:
    # def setDevice(self, device_id, device_ip=None, device_port=None):
    
    proxy.setDevice(device_id="DEVICE_ID.remote.lscontrol.dk", device_ip="192.168.1.100", device_port=5570)
    
    print("Connecting...")
    if proxy.connect():
        print("Connection initiated.")
    else:
        print("Failed to initiate connection.")
        return

    # Wait for connection
    print("Waiting for connection...")
    for _ in range(10):
        if proxy.isConnected():
            print("Connected!")
            break
        if proxy.getConnectionError():
            print(f"Connection error: {proxy.getConnectionError()}")
            break
        time.sleep(1)
        
    if proxy.isConnected():
        print(f"Device Model: {proxy.getDeviceModel()}")
        print(f"Device Number: {proxy.getDeviceNumber()}")
    else:
        print("Not connected.")

    proxy.stopListening()
    proxy.closeSocket()

if __name__ == "__main__":
    main()
