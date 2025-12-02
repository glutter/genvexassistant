try:
    val = 70000
    b = val.to_bytes(2, 'big')
    print(f"Success: {b.hex()}")
except Exception as e:
    print(f"Error: {e}")
