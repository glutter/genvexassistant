import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class GenvexClientResponseTest {
    private static final byte[] CLIENT_ID = {1, 2, 3, 4};
    private static final byte[] SERVER_ID = {5, 6, 7, 8};
    private static final int PORT = 5570;
    private static final int SEQUENCE = 42;

    @Test
    void acceptsCorrelatedResponse() throws Exception {
        InetAddress address = InetAddress.getLoopbackAddress();
        byte[] data = response(SEQUENCE);
        DatagramPacket packet = new DatagramPacket(data, data.length, address, PORT);

        assertTrue(GenvexClient.isExpectedCryptResponse(
                packet, data, address, PORT, CLIENT_ID, SERVER_ID, SEQUENCE));
    }

    @Test
    void rejectsPreviousSequenceResponse() throws Exception {
        InetAddress address = InetAddress.getLoopbackAddress();
        byte[] data = response(SEQUENCE - 1);
        DatagramPacket packet = new DatagramPacket(data, data.length, address, PORT);

        assertFalse(GenvexClient.isExpectedCryptResponse(
                packet, data, address, PORT, CLIENT_ID, SERVER_ID, SEQUENCE));
    }

    @Test
    void rejectsForeignEndpointOrSession() throws Exception {
        InetAddress address = InetAddress.getLoopbackAddress();
        byte[] data = response(SEQUENCE);
        DatagramPacket packet = new DatagramPacket(data, data.length, address, PORT + 1);

        assertFalse(GenvexClient.isExpectedCryptResponse(
                packet, data, address, PORT, CLIENT_ID, SERVER_ID, SEQUENCE));

        data[4]++;
        packet = new DatagramPacket(data, data.length, address, PORT);
        assertFalse(GenvexClient.isExpectedCryptResponse(
                packet, data, address, PORT, CLIENT_ID, SERVER_ID, SEQUENCE));
    }

    @Test
    void rejectsInvalidLengthOrChecksum() throws Exception {
        InetAddress address = InetAddress.getLoopbackAddress();
        byte[] data = response(SEQUENCE);
        DatagramPacket packet = new DatagramPacket(data, data.length, address, PORT);

        data[15]--;
        assertFalse(GenvexClient.isExpectedCryptResponse(
                packet, data, address, PORT, CLIENT_ID, SERVER_ID, SEQUENCE));

        data = response(SEQUENCE);
        data[22]++;
        packet = new DatagramPacket(data, data.length, address, PORT);
        assertFalse(GenvexClient.isExpectedCryptResponse(
                packet, data, address, PORT, CLIENT_ID, SERVER_ID, SEQUENCE));
    }

        @Test
        void rejectsInvalidCryptLengthOrCode() throws Exception {
        InetAddress address = InetAddress.getLoopbackAddress();
        byte[] data = response(SEQUENCE);
        DatagramPacket packet = new DatagramPacket(data, data.length, address, PORT);

        data[19]--;
        assertFalse(GenvexClient.isExpectedCryptResponse(
            packet, data, address, PORT, CLIENT_ID, SERVER_ID, SEQUENCE));

        data = response(SEQUENCE);
        data[21]++;
        packet = new DatagramPacket(data, data.length, address, PORT);
        assertFalse(GenvexClient.isExpectedCryptResponse(
            packet, data, address, PORT, CLIENT_ID, SERVER_ID, SEQUENCE));
        }

    @Test
    void decodesUnavailableDatapointSentinel() {
        assertEquals(-1, GenvexClient.decodeDatapointValue(new byte[] {0, 0, (byte) 0xFF, (byte) 0xFF}));
        assertEquals(529, GenvexClient.decodeDatapointValue(new byte[] {0, 0, 0x02, 0x11}));
    }

    private static byte[] response(int sequence) {
        ByteBuffer buffer = ByteBuffer.allocate(28);
        buffer.put(CLIENT_ID);
        buffer.put(SERVER_ID);
        buffer.put((byte) 0x16);
        buffer.put((byte) 0x02);
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x01);
        buffer.putShort((short) sequence);
        buffer.putShort((short) buffer.capacity());
        buffer.put((byte) 0x36);
        buffer.put((byte) 0x00);
        buffer.putShort((short) 0x000c);
        buffer.putShort((short) 0x000a);
        buffer.putInt(1234);

        byte[] data = buffer.array();
        int checksum = 0;
        for (int i = 0; i < data.length - 2; i++) {
            checksum = (checksum + (data[i] & 0xFF)) & 0xFFFF;
        }
        buffer.putShort(data.length - 2, (short) checksum);
        return data;
    }
}