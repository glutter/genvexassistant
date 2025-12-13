import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public class GenvexClient {
    private static final int PORT = 5570;
    private static final byte U_DATA = (byte) 0x16;
    private static final byte U_IPX = (byte) 0x35;
    private static final byte U_CRYPT = (byte) 0x36;
    private static final byte U_CP_ID = (byte) 0x3F;
    private static final byte U_CONNECT = (byte) 0x83;

    private String ipAddress;
    private String email;
    private DatagramSocket socket;
    private InetAddress address;
    private byte[] clientId;
    private byte[] serverId;
    private int sequenceId = 0;
    private boolean connected = false;

    public GenvexClient(String ipAddress, String email) {
        this.ipAddress = ipAddress;
        this.email = email;
    }

    public void connect() throws IOException, InterruptedException {
        socket = new DatagramSocket();
        socket.setSoTimeout(5000);
        address = InetAddress.getByName(ipAddress);

        // Generate Client ID
        clientId = new byte[4];
        new Random().nextBytes(clientId);
        byte[] tempServerId = new byte[]{0, 0, 0, 0};

        // Handshake
        byte[] ipxPayload = buildIpxPayload();
        byte[] cpIdPayload = buildCpIdPayload(email);
        byte[] packetData = buildPacket(clientId, tempServerId, U_CONNECT, 0, ipxPayload, cpIdPayload);

        DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, PORT);
        socket.send(packet);

        // Receive Response
        byte[] buffer = new byte[1024];
        DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(responsePacket);
        byte[] responseData = Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());

        // Extract Server ID
        serverId = Arrays.copyOfRange(responseData, 24, 28);

        // Send PING to verify connection
        byte[] pingCmd = buildPingCommand();
        byte[] pingResponse = sendPacketAndWaitForResponse(1, pingCmd, 3);

        if (pingResponse != null && pingResponse.length >= 24) {
            connected = true;
            sequenceId = 2;
        } else {
            throw new IOException("Failed to complete handshake (Ping failed)");
        }
    }

    public void disconnect() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        connected = false;
    }

    public boolean isConnected() {
        return connected;
    }

    public int readDatapoint(int addr) throws IOException, InterruptedException {
        if (!connected) throw new IOException("Not connected");
        
        byte[] cmd = buildDatapointReadCommand(addr);
        byte[] response = sendPacketAndWaitForResponse(sequenceId++, cmd, 3);
        
        if (response != null && response.length >= 4) {
            return ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
        }
        return -1;
    }

    public void setFanSpeed(int speed) throws IOException, InterruptedException {
        if (!connected) throw new IOException("Not connected");
        if (speed < 0 || speed > 4) throw new IllegalArgumentException("Speed must be 0-4");

        byte[] cmd = buildSetpointWriteCommand(24, speed);
        sendPacketAndWaitForResponse(sequenceId++, cmd, 3);
    }

    // --- Private Helpers ---

    private byte[] sendPacketAndWaitForResponse(int seq, byte[] payloadData, int retries) throws IOException, InterruptedException {
        byte[] cryptPayload = buildCryptPayload(payloadData);
        int packetLength = 16 + cryptPayload.length + 2;

        ByteBuffer buffer = ByteBuffer.allocate(packetLength);
        buffer.put(clientId);
        buffer.put(serverId);
        buffer.put(U_DATA);
        buffer.put((byte) 0x02);
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x00);
        buffer.putShort((short) seq);
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
        DatagramPacket packet = new DatagramPacket(finalPacket, finalPacket.length, address, PORT);
        byte[] receiveBuffer = new byte[1024];
        DatagramPacket responsePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

        for (int i = 0; i < retries; i++) {
            socket.send(packet);
            try {
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < 2000) {
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
            Thread.sleep(500);
        }
        return null;
    }

    private byte[] buildPacket(byte[] clientId, byte[] serverId, byte packetType, int sequenceId, byte[]... payloads) {
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

    private byte[] buildIpxPayload() {
        ByteBuffer buffer = ByteBuffer.allocate(17);
        buffer.put(U_IPX);
        buffer.put((byte) 0x00);
        buffer.putShort((short) 0x0011);
        byte[] data = new byte[13];
        data[12] = (byte) 0xa0;
        buffer.put(data);
        return buffer.array();
    }

    private byte[] buildCpIdPayload(String email) {
        byte[] emailBytes = email.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(5 + emailBytes.length);
        buffer.put(U_CP_ID);
        buffer.put((byte) 0x00);
        buffer.putShort((short) (5 + emailBytes.length));
        buffer.put((byte) 0x01);
        buffer.put(emailBytes);
        return buffer.array();
    }

    private byte[] buildCryptPayload(byte[] data) {
        int lengthFieldVal = 6 + data.length + 3;
        ByteBuffer buffer = ByteBuffer.allocate(7 + data.length);
        buffer.put(U_CRYPT);
        buffer.put((byte) 0x00);
        buffer.putShort((short) lengthFieldVal);
        buffer.putShort((short) 0x000a);
        buffer.put(data);
        buffer.put((byte) 0x02);
        return buffer.array();
    }

    private byte[] buildPingCommand() {
        byte[] pingStr = "ping".getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(4 + pingStr.length);
        buffer.putInt(0x00000011);
        buffer.put(pingStr);
        return buffer.array();
    }

    private byte[] buildDatapointReadCommand(int address) {
        ByteBuffer cmdBuffer = ByteBuffer.allocate(12);
        cmdBuffer.putInt(0x0000002d);
        cmdBuffer.putShort((short) 1);
        cmdBuffer.put((byte) 0);
        cmdBuffer.putInt(address);
        cmdBuffer.put((byte) 1);
        return cmdBuffer.array();
    }

    private byte[] buildSetpointWriteCommand(int address, int value) {
        ByteBuffer cmdBuffer = ByteBuffer.allocate(14);
        cmdBuffer.putInt(0x0000002b);
        cmdBuffer.putShort((short) 1);
        cmdBuffer.put((byte) 0);
        cmdBuffer.putInt(address);
        cmdBuffer.putShort((short) value);
        cmdBuffer.put((byte) 1);
        return cmdBuffer.array();
    }
}
