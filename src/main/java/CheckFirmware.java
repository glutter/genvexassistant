import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public class CheckFirmware {
    public static void main(String[] args) {
        String ip = System.getenv("GENVEX_IP");
        String email = System.getenv("GENVEX_EMAIL");

        if (ip == null || email == null) {
            System.out.println("Please set GENVEX_IP and GENVEX_EMAIL environment variables.");
            return;
        }

        try {
            System.out.println("Connecting to " + ip + "...");
            
            // We need to manually do the handshake to get the raw PING response
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(5000);
            InetAddress address = InetAddress.getByName(ip);
            int PORT = 5570;

            // Generate Client ID
            byte[] clientId = new byte[4];
            new Random().nextBytes(clientId);
            byte[] tempServerId = new byte[]{0, 0, 0, 0};

            // Handshake
            byte[] ipxPayload = buildIpxPayload();
            byte[] cpIdPayload = buildCpIdPayload(email);
            byte[] packetData = buildPacket(clientId, tempServerId, (byte) 0x83, 0, ipxPayload, cpIdPayload);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, PORT);
            socket.send(packet);

            // Receive Response
            byte[] buffer = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);
            byte[] responseData = Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());

            // Extract Server ID
            byte[] serverId = Arrays.copyOfRange(responseData, 24, 28);
            System.out.println("Handshake successful. Server ID: " + bytesToHex(serverId));

            // Send PING
            byte[] pingCmd = buildPingCommand();
            byte[] cryptPayload = buildCryptPayload(pingCmd);
            int packetLength = 16 + cryptPayload.length + 2;

            ByteBuffer buf = ByteBuffer.allocate(packetLength);
            buf.put(clientId);
            buf.put(serverId);
            buf.put((byte) 0x16); // U_DATA
            buf.put((byte) 0x02);
            buf.put((byte) 0x00);
            buf.put((byte) 0x00);
            buf.putShort((short) 1); // Seq
            buf.putShort((short) packetLength);
            buf.put(cryptPayload);

            // Checksum
            byte[] packetBytes = buf.array();
            int sum = 0;
            for (int i = 0; i < packetBytes.length - 2; i++) {
                sum += (packetBytes[i] & 0xFF);
            }
            buf.putShort(packetLength - 2, (short) (sum & 0xFFFF));

            byte[] finalPacket = buf.array();
            DatagramPacket pingPacket = new DatagramPacket(finalPacket, finalPacket.length, address, PORT);
            socket.send(pingPacket);

            // Receive PING Response
            // Loop until we get a U_CRYPT (0x36) packet
            byte[] pingResponseData = null;
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 5000) {
                socket.receive(responsePacket);
                byte[] data = Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());
                
                if (data.length > 16) {
                    byte payloadType = data[16];
                    if (payloadType == (byte) 0x36) { // U_CRYPT
                        pingResponseData = data;
                        break;
                    } else if (payloadType == (byte) 0x34) {
                        System.out.println("Received U_NOTIFY (0x34), waiting for next packet...");
                    }
                }
            }

            if (pingResponseData == null) {
                System.out.println("Timeout waiting for PING response.");
                return;
            }
            
            System.out.println("Ping Response Raw: " + bytesToHex(pingResponseData));

            // Decrypt/Extract Payload
            // The payload starts after the header (16 bytes) + Crypt Header (6 bytes)
            // But we need to check the structure.
            // Response: Header(16) + U_CRYPT(1) + Flags(1) + Len(2) + Code(2) + DATA + Padding(1)
            
            if (pingResponseData.length > 22) {
                byte[] payload = Arrays.copyOfRange(pingResponseData, 22, pingResponseData.length - 1); // Strip padding
                System.out.println("Ping Payload: " + bytesToHex(payload));
                
                if (payload.length >= 24) {
                     int deviceNumber = ByteBuffer.wrap(payload, 4, 4).getInt();
                     int deviceModel = ByteBuffer.wrap(payload, 8, 4).getInt();
                     int slaveNumber = ByteBuffer.wrap(payload, 16, 4).getInt();
                     int slaveModel = ByteBuffer.wrap(payload, 20, 4).getInt();
                     
                     System.out.println("Device Number: " + deviceNumber);
                     System.out.println("Device Model: " + deviceModel);
                     System.out.println("Slave Device Number: " + slaveNumber);
                     System.out.println("Slave Device Model: " + slaveModel);
                }
            }

            socket.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Helpers copied from GenvexClient
    private static byte[] buildPacket(byte[] clientId, byte[] serverId, byte packetType, int sequenceId, byte[]... payloads) {
        int payloadLength = 0;
        for (byte[] p : payloads) payloadLength += p.length;
        int packetLength = payloadLength + 16;
        
        ByteBuffer buffer = ByteBuffer.allocate(packetLength);
        buffer.put(clientId);
        buffer.put(serverId);
        buffer.put(packetType);
        buffer.put((byte) 0x02);
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x00);
        buffer.putShort((short) sequenceId);
        buffer.putShort((short) packetLength);
        for (byte[] p : payloads) buffer.put(p);
        
        return buffer.array();
    }

    private static byte[] buildIpxPayload() {
        ByteBuffer buffer = ByteBuffer.allocate(17);
        buffer.put((byte) 0x35);
        buffer.put((byte) 0x00);
        buffer.putShort((short) 0x0011);
        byte[] data = new byte[13];
        data[12] = (byte) 0xa0;
        buffer.put(data);
        return buffer.array();
    }

    private static byte[] buildCpIdPayload(String email) {
        byte[] emailBytes = email.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(5 + emailBytes.length);
        buffer.put((byte) 0x3F);
        buffer.put((byte) 0x00);
        buffer.putShort((short) (5 + emailBytes.length));
        buffer.put((byte) 0x01);
        buffer.put(emailBytes);
        return buffer.array();
    }

    private static byte[] buildCryptPayload(byte[] data) {
        int lengthFieldVal = 6 + data.length + 3;
        ByteBuffer buffer = ByteBuffer.allocate(7 + data.length);
        buffer.put((byte) 0x36);
        buffer.put((byte) 0x00);
        buffer.putShort((short) lengthFieldVal);
        buffer.putShort((short) 0x000a);
        buffer.put(data);
        buffer.put((byte) 0x02);
        return buffer.array();
    }

    private static byte[] buildPingCommand() {
        byte[] pingStr = "ping".getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(4 + pingStr.length);
        buffer.putInt(0x00000011);
        buffer.put(pingStr);
        return buffer.array();
    }
    
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
