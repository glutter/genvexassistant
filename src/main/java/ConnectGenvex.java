import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public class ConnectGenvex {

    private static final String TARGET_IP = System.getenv().getOrDefault("GENVEX_IP", "192.168.1.1");
    private static final int TARGET_PORT = 5570;
    // NOTE: You must use a valid email address registered with the device (e.g., via the official app)
    private static final String EMAIL = System.getenv().getOrDefault("GENVEX_EMAIL", "");

    private static final byte U_CONNECT = (byte) 0x83;
    private static final byte U_DATA = (byte) 0x16;
    private static final byte U_IPX = (byte) 0x35;
    private static final byte U_CRYPT = (byte) 0x36;
    private static final byte U_CP_ID = (byte) 0x3F;

    public static void main(String[] args) {
        try {
            connect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void connect() throws IOException, InterruptedException {
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(5000);

        try {
            // Generate Client ID
            byte[] clientId = new byte[4];
            new Random().nextBytes(clientId);
            byte[] serverId = new byte[]{0, 0, 0, 0};

            // Build Payloads
            byte[] ipxPayload = buildIpxPayload();
            byte[] cpIdPayload = buildCpIdPayload(EMAIL);

            // Build Packet
            byte[] packetData = buildPacket(clientId, serverId, U_CONNECT, 0, ipxPayload, cpIdPayload);

            System.out.println("Sending U_CONNECT to " + TARGET_IP + ":" + TARGET_PORT + "...");
            InetAddress address = InetAddress.getByName(TARGET_IP);
            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, TARGET_PORT);
            socket.send(packet);

            // Receive Response
            byte[] buffer = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);

            byte[] responseData = Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());
            System.out.println("Received response: " + bytesToHex(responseData));

            // Parse Response
            // Status at 20:24
            byte[] status = Arrays.copyOfRange(responseData, 20, 24);
            System.out.println("Status: " + bytesToHex(status));

            // Extract Server ID at 24:28
            byte[] newServerId = Arrays.copyOfRange(responseData, 24, 28);
            System.out.println("Extracted Server ID: " + bytesToHex(newServerId));

            int nextSeq = 0;
            // Send PING
            boolean connected = false;
            byte[] pingCmd = buildPingCommand();
            // Try Sequence ID 1, 3 retries
            byte[] pingResponse = sendPacketAndWaitForResponse(socket, address, clientId, newServerId, 1, pingCmd, 3);
            
            if (pingResponse != null) {
                 // PING response starts with "pong" (4 bytes)
                 if (pingResponse.length >= 24) {
                     long deviceNumber = readUnsignedInt(pingResponse, 4);
                     long deviceModel = readUnsignedInt(pingResponse, 8);
                     long slaveDeviceNumber = readUnsignedInt(pingResponse, 16);
                     long slaveDeviceModel = readUnsignedInt(pingResponse, 20);

                     System.out.println("Device Number: " + deviceNumber);
                     System.out.println("Device Model: " + deviceModel);
                     System.out.println("Slave Device Number: " + slaveDeviceNumber);
                     System.out.println("Slave Device Model: " + slaveDeviceModel);
                     
                     connected = true;
                     nextSeq = 2;
                 }
            }
            
            if (connected) {
                System.out.println("Connected! Reading System Status...");
                
                // Read interesting datapoints
                int tempSupply = readDatapoint(socket, address, clientId, newServerId, nextSeq++, 20);
                int tempOutside = readDatapoint(socket, address, clientId, newServerId, nextSeq++, 21);
                int humidity = readDatapoint(socket, address, clientId, newServerId, nextSeq++, 26);
                int dutySupply = readDatapoint(socket, address, clientId, newServerId, nextSeq++, 18);
                int rpmSupply = readDatapoint(socket, address, clientId, newServerId, nextSeq++, 35);
                int fanSpeedRead = readDatapoint(socket, address, clientId, newServerId, nextSeq++, 7); // Known to be 0
                
                System.out.println("--- Status ---");
                System.out.println("Temp Supply: " + (tempSupply / 10.0) + " C");
                System.out.println("Temp Outside: " + (tempOutside / 10.0) + " C");
                System.out.println("Humidity: " + humidity + " %");
                System.out.println("Fan Speed (Duty): " + (dutySupply / 100) + " %");
                System.out.println("Fan RPM: " + rpmSupply);
                System.out.println("Fan Speed Setpoint (Addr 7): " + fanSpeedRead + " (Note: This register seems inactive)");
                System.out.println("----------------");
                
                // Example of how to change speed:
                // byte[] setSpeed2 = buildSetpointWriteCommand(24, 2);
                // sendPacketAndWaitForResponse(socket, address, clientId, newServerId, nextSeq++, setSpeed2, 3);
            }
        } finally {
            socket.close();
            System.out.println("Socket closed.");
        }
    }
    
    private static int readDatapoint(DatagramSocket socket, InetAddress address, byte[] clientId, byte[] serverId, int seq, int addr) throws IOException, InterruptedException {
        byte[] cmd = buildDatapointReadCommand(addr);
        byte[] response = sendPacketAndWaitForResponse(socket, address, clientId, serverId, seq, cmd, 3);
        if (response != null && response.length >= 4) {
            return ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
        }
        return -1;
    }
    
    private static byte[] buildDatapointReadCommand(int address) {
        ByteBuffer cmdBuffer = ByteBuffer.allocate(4 + 2 + 1 + 4 + 1);
        cmdBuffer.putInt(0x0000002d); // DATAPOINT_READ
        cmdBuffer.putShort((short) 1); // Count
        cmdBuffer.put((byte) 0);       // Obj
        cmdBuffer.putInt(address);     // Address
        cmdBuffer.put((byte) 1);       // Terminator
        return cmdBuffer.array();
    }

    private static byte[] buildSetpointWriteCommand(int address, int value) {
        // Command: 00 00 00 2b (SETPOINT_WRITELIST)
        // Count: 00 01
        // Item: Obj(1) + Addr(4) + Value(2)
        // Terminator: 01
        
        ByteBuffer cmdBuffer = ByteBuffer.allocate(4 + 2 + 1 + 4 + 2 + 1);
        cmdBuffer.putInt(0x0000002b); // SETPOINT_WRITELIST
        cmdBuffer.putShort((short) 1); // Count
        
        cmdBuffer.put((byte) 0);       // Obj
        cmdBuffer.putInt(address);     // Address
        cmdBuffer.putShort((short) value); // Value
        
        cmdBuffer.put((byte) 1);       // Terminator
        return cmdBuffer.array();
    }
    
    private static byte[] sendPacketAndWaitForResponse(DatagramSocket socket, InetAddress address, byte[] clientId, byte[] serverId, int sequenceId, byte[] payloadData, int retries) throws IOException, InterruptedException {
        byte[] cryptPayload = buildCryptPayload(payloadData);
        
        int packetLength = 16 + cryptPayload.length + 2;
        
        ByteBuffer buffer = ByteBuffer.allocate(packetLength);
        buffer.put(clientId);
        buffer.put(serverId);
        buffer.put(U_DATA);
        buffer.put((byte) 0x02); // Version
        buffer.put((byte) 0x00); // Retransmission
        buffer.put((byte) 0x00); // Flags
        buffer.putShort((short) sequenceId);
        buffer.putShort((short) packetLength);
        
        buffer.put(cryptPayload);
        
        // Checksum
        byte[] packetBytes = buffer.array();
        int sum = 0;
        for (int i = 0; i < packetBytes.length - 2; i++) {
            sum += (packetBytes[i] & 0xFF);
        }
        buffer.putShort(packetLength - 2, (short) (sum & 0xFFFF));
        
        byte[] finalPacket = buffer.array();
        DatagramPacket packet = new DatagramPacket(finalPacket, finalPacket.length, address, TARGET_PORT);
        
        byte[] receiveBuffer = new byte[1024];
        DatagramPacket responsePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        
        for (int i = 0; i < retries; i++) {
            System.out.println("Sending Packet (Seq " + sequenceId + ", Attempt " + (i+1) + "): " + bytesToHex(finalPacket));
            socket.send(packet);
            
            try {
                // Try to receive multiple times in case of NOTIFY packets
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < 2000) { // 2 second window per attempt
                    socket.receive(responsePacket);
                    byte[] responseData = Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());
                    System.out.println("Received packet: " + bytesToHex(responseData));
                    
                    if (responseData.length > 16 && responseData[8] == U_DATA) {
                         byte payloadType = responseData[16];
                         if (payloadType == U_CRYPT) {
                             System.out.println("Got CRYPT payload!");
                             if (responseData.length >= 22) {
                                 return Arrays.copyOfRange(responseData, 22, responseData.length);
                             }
                         } else if (payloadType == (byte)0x34) {
                             System.out.println("Got NOTIFY payload. Ignoring...");
                         }
                    }
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout waiting for response.");
            }
            
            Thread.sleep(1000);
        }
        return null;
    }

    private static byte[] buildIpxPayload() {
        // Payload Type (1) + Flags (1) + Length (2) + Data (13) = 17 bytes total
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 2 + 13);
        buffer.put(U_IPX);
        buffer.put((byte) 0x00); // Flags
        buffer.putShort((short) 0x0011); // Length 17 (includes header)
        
        byte[] data = new byte[13];
        data[12] = (byte) 0xa0;
        
        buffer.put(data);
        return buffer.array();
    }

    private static byte[] buildCpIdPayload(String email) {
        byte[] emailBytes = email.getBytes();
        int lengthVal = 4 + 1 + emailBytes.length;
        
        ByteBuffer buffer = ByteBuffer.allocate(4 + 1 + emailBytes.length);
        buffer.put(U_CP_ID);
        buffer.put((byte) 0x00); // Flags
        buffer.putShort((short) lengthVal);
        buffer.put((byte) 0x01); // ID Type Email
        buffer.put(emailBytes);
        
        return buffer.array();
    }

    private static byte[] buildCryptPayload(byte[] data) {
        // Length calculation based on nilan_proxy code:
        // (6+len(self.data)+3).to_bytes(2, 'big')
        // It seems to include some overhead in the length field.
        // 6 bytes header (Type, Flags, Length, CryptoCode) + data + 1 byte padding + 2 bytes checksum = 9 + data.length
        // But the length field itself is 2 bytes.
        // The length value written is 6 + data.length + 3.
        // Let's verify:
        // Type(1) + Flags(1) + Length(2) + Crypto(2) + Data(N) + Padding(1)
        // Total bytes = 1 + 1 + 2 + 2 + N + 1 = 7 + N.
        // If length value is 6 + N + 3 = 9 + N.
        // Why +3? Maybe checksum (2) + padding (1)?
        // If so, 6 + N + 3 = 9 + N.
        // The packet builder adds checksum at the end of the PACKET, not payload.
        // But maybe the payload length field includes the checksum bytes that will be appended?
        
        int lengthFieldVal = 6 + data.length + 3;
        
        ByteBuffer buffer = ByteBuffer.allocate(4 + 2 + data.length + 1);
        buffer.put(U_CRYPT);
        buffer.put((byte) 0x00); // Flags
        buffer.putShort((short) lengthFieldVal);
        buffer.putShort((short) 0x000a); // Crypto Code
        buffer.put(data);
        buffer.put((byte) 0x02); // Padding
        
        return buffer.array();
    }

    private static byte[] buildPingCommand() {
        // 00 00 00 11 + "ping"
        byte[] pingStr = "ping".getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(4 + pingStr.length);
        buffer.putInt(0x00000011);
        buffer.put(pingStr);
        return buffer.array();
    }

    private static byte[] buildPacket(byte[] clientId, byte[] serverId, byte packetType, int sequenceId, byte[]... payloads) {
        int payloadLength = 0;
        for (byte[] p : payloads) {
            payloadLength += p.length;
        }
        
        int packetLength = payloadLength + 16;
        
        // Check if checksum is required (if any payload is U_CRYPT)
        boolean requiresChecksum = false;
        for (byte[] p : payloads) {
            if (p.length > 0 && p[0] == U_CRYPT) {
                requiresChecksum = true;
                break;
            }
        }
        
        if (requiresChecksum) {
            packetLength += 2;
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(packetLength);
        buffer.put(clientId);
        buffer.put(serverId);
        buffer.put(packetType);
        buffer.put((byte) 0x02); // Version
        buffer.put((byte) 0x00); // Retransmission
        
        // Flags: 0x00 usually, but keep alive uses 0x40.
        buffer.put((byte) 0x00); // Flags
        
        buffer.putShort((short) sequenceId);
        buffer.putShort((short) packetLength);
        
        for (byte[] p : payloads) {
            buffer.put(p);
        }
        
        if (requiresChecksum) {
            // Calculate checksum
            byte[] packetBytes = buffer.array();
            int sum = 0;
            // Sum all bytes except the last 2 (which are currently 0)
            for (int i = 0; i < packetBytes.length - 2; i++) {
                // Java bytes are signed, so we need & 0xFF to treat as unsigned
                sum += (packetBytes[i] & 0xFF);
            }
            // Truncate to 2 bytes (unsigned short)
            // Python: sum.to_bytes(2, 'big')
            // Java: putShort writes big endian by default.
            buffer.putShort(packetLength - 2, (short) (sum & 0xFFFF));
        }
        
        return buffer.array();
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF) << 24) |
               ((long)(data[offset + 1] & 0xFF) << 16) |
               ((long)(data[offset + 2] & 0xFF) << 8) |
               ((long)(data[offset + 3] & 0xFF));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
