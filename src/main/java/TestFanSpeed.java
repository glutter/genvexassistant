import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class TestFanSpeed {
    private static final int PORT = 5570;
    private static final String HOST = System.getenv().getOrDefault("GENVEX_IP", "192.168.1.100");
    private static final byte U_DATA = 0x16;
    private static final byte U_CRYPT = 0x1e;
    private static final byte U_NOTIFY = 0x34;

    public static void main(String[] args) {
        try {
            DatagramSocket socket = new DatagramSocket();
            InetAddress address = InetAddress.getByName(HOST);
            socket.setSoTimeout(2000);

            // 1. Connect (U_CONNECT)
            System.out.println("Sending U_CONNECT...");
            byte[] connectPayload = new byte[] {
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // U_CONNECT
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01  // Version 1
            };
            DatagramPacket connectPacket = new DatagramPacket(connectPayload, connectPayload.length, address, PORT);
            socket.send(connectPacket);
            
            byte[] buffer = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);
            byte[] connectResponse = Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());
            
            byte[] clientId = Arrays.copyOfRange(connectResponse, 16, 20);
            byte[] serverId = Arrays.copyOfRange(connectResponse, 24, 28);
            
            System.out.println("Connected. Server ID: " + bytesToHex(serverId));
            
            int nextSeq = 1;
            
            // 2. Ping to establish session
            byte[] pingCmd = buildCommand("ping", "");
            sendPacketAndWaitForResponse(socket, address, clientId, serverId, nextSeq++, pingCmd, 3);
            
            // 3. Read Addr 32
            int addr32 = readDatapoint(socket, address, clientId, serverId, nextSeq++, 32);
            System.out.println("Current Addr 32: " + addr32);
            
            if (addr32 == 1) {
                System.out.println("Addr 32 is 1 (Speed 1). Writing 0 to Addr 32...");
                writeSetpoint(socket, address, clientId, serverId, nextSeq++, 32, 0);
                System.out.println("Write command sent.");
                
                System.out.println("Waiting 5 seconds...");
                Thread.sleep(5000);
                
                int dutyCycle = readDatapoint(socket, address, clientId, serverId, nextSeq++, 18);
                System.out.println("Duty Cycle (Addr 18): " + dutyCycle);
                
                int rpm = readDatapoint(socket, address, clientId, serverId, nextSeq++, 35);
                System.out.println("RPM (Addr 35): " + rpm);
                
                if (dutyCycle > 4000) {
                    System.out.println("SUCCESS! Fan speed increased to Speed 2!");
                } else {
                    System.out.println("No change detected.");
                }
                
                System.out.println("Restoring Speed 1 (Writing 1 to Addr 32)...");
                writeSetpoint(socket, address, clientId, serverId, nextSeq++, 32, 1);
                
            } else {
                System.out.println("Addr 32 is " + addr32 + ". Expected 1. Aborting write test.");
            }
            
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static int readDatapoint(DatagramSocket socket, InetAddress address, byte[] clientId, byte[] serverId, int seq, int addr) throws Exception {
        byte[] cmd = buildDatapointReadCommand(addr);
        byte[] response = sendPacketAndWaitForResponse(socket, address, clientId, serverId, seq, cmd, 3);
        if (response != null && response.length >= 4) {
            return ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
        }
        return -1;
    }
    
    private static void writeSetpoint(DatagramSocket socket, InetAddress address, byte[] clientId, byte[] serverId, int seq, int addr, int value) throws Exception {
        byte[] cmd = buildSetpointWriteCommand(addr, value);
        sendPacketAndWaitForResponse(socket, address, clientId, serverId, seq, cmd, 3);
    }

    private static byte[] sendPacketAndWaitForResponse(DatagramSocket socket, InetAddress address, byte[] clientId, byte[] serverId, int sequenceId, byte[] payloadData, int retries) throws Exception {
        byte[] cryptPayload = buildCryptPayload(payloadData);
        int packetLength = 16 + cryptPayload.length + 2;
        
        ByteBuffer buffer = ByteBuffer.allocate(packetLength);
        buffer.put(clientId);
        buffer.put(serverId);
        buffer.put(U_DATA);
        buffer.put((byte) 0x02);
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x00);
        buffer.putShort((short) sequenceId);
        buffer.putShort((short) packetLength);
        buffer.put(cryptPayload);
        
        byte[] packetBytes = buffer.array();
        int sum = 0;
        for (int i = 0; i < packetBytes.length - 2; i++) {
            sum += (packetBytes[i] & 0xFF);
        }
        buffer.putShort(packetLength - 2, (short) (sum & 0xFFFF));
        
        byte[] finalPacket = buffer.array();
        DatagramPacket packet = new DatagramPacket(finalPacket, finalPacket.length, address, PORT);
        
        byte[] receiveBuffer = new byte[1024];
        DatagramPacket responsePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        
        for (int i = 0; i < retries; i++) {
            socket.send(packet);
            try {
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < 2000) {
                    responsePacket.setLength(receiveBuffer.length);
                    socket.receive(responsePacket);
                    byte[] responseData = Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());
                    
                    if (responseData.length > 16 && responseData[8] == U_DATA) {
                         byte payloadType = responseData[16];
                         if (payloadType == U_CRYPT) {
                             if (responseData.length >= 22) {
                                 return Arrays.copyOfRange(responseData, 22, responseData.length);
                             }
                         }
                    }
                }
            } catch (SocketTimeoutException e) {
                // Retry
            }
        }
        return null;
    }

    private static byte[] buildCryptPayload(byte[] data) {
        int lengthFieldVal = 6 + data.length + 3;
        ByteBuffer buffer = ByteBuffer.allocate(4 + 2 + data.length + 1);
        buffer.put(U_CRYPT);
        buffer.put((byte) 0x00);
        buffer.putShort((short) lengthFieldVal);
        buffer.putShort((short) 0x000a);
        buffer.put(data);
        buffer.put((byte) 0x02);
        return buffer.array();
    }

    private static byte[] buildCommand(String cmd, String args) {
        byte[] cmdBytes = cmd.getBytes();
        byte[] argsBytes = args.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(2 + cmdBytes.length + 2 + argsBytes.length);
        buffer.putShort((short) cmdBytes.length);
        buffer.put(cmdBytes);
        buffer.putShort((short) argsBytes.length);
        buffer.put(argsBytes);
        ByteBuffer wrapper = ByteBuffer.allocate(4 + buffer.capacity());
        wrapper.putInt(0x00000021);
        wrapper.put(buffer.array());
        return wrapper.array();
    }
    
    private static byte[] buildDatapointReadCommand(int address) {
        ByteBuffer cmdBuffer = ByteBuffer.allocate(4 + 2 + 1 + 4 + 1);
        cmdBuffer.putInt(0x0000002d);
        cmdBuffer.putShort((short) 1);
        cmdBuffer.put((byte) 0);
        cmdBuffer.putInt(address);
        cmdBuffer.put((byte) 1);
        return cmdBuffer.array();
    }
    
    private static byte[] buildSetpointWriteCommand(int address, int value) {
        ByteBuffer cmdBuffer = ByteBuffer.allocate(4 + 2 + 1 + 4 + 2 + 1);
        cmdBuffer.putInt(0x0000002b);
        cmdBuffer.putShort((short) 1);
        cmdBuffer.put((byte) 0);
        cmdBuffer.putInt(address);
        cmdBuffer.putShort((short) value);
        cmdBuffer.put((byte) 1);
        return cmdBuffer.array();
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
