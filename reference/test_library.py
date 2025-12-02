import sys
import os
import time
import logging

# Add the library to the path
sys.path.append(os.path.abspath("temp_nilan_proxy/src"))

from nilan_proxy.nilan_proxy import NilanProxy

# Configure logging
logging.basicConfig(level=logging.DEBUG)

def main():
    print("Starting NilanProxy test...")
    proxy = NilanProxy(authorized_email="test@test.com")
    
    # Set the device manually since we know the IP
    proxy.set_device(device_id="DEVICE_ID.remote.lscontrol.dk", device_ip="192.168.1.100", device_port=5570)
    
    print("Connecting...")
    if proxy.connect_to_device():
        print("Connection initiated.")
    else:
        print("Failed to initiate connection.")
        return

    # Wait for connection
    print("Waiting for connection...")
    for _ in range(10):
        if proxy.is_connected():
            print("Connected!")
            break
        if proxy.get_connection_error():
            print(f"Connection error: {proxy.get_connection_error()}")
            break
        time.sleep(1)
        
    if proxy.is_connected():
        print(f"Device Model: {proxy.get_device_model()}")
        print(f"Device Number: {proxy.get_device_number()}")
        
        # Wait for data
        print("Waiting for data...")
        for _ in range(10):
            # Check if we have some data
            # We can check if model adapter is loaded
            if proxy._model_adapter:
                print("Model adapter loaded.")
                break
            time.sleep(1)
            
    else:
        print("Not connected.")

    proxy.stop_listening()
    proxy.close_socket()

if __name__ == "__main__":
    main()
