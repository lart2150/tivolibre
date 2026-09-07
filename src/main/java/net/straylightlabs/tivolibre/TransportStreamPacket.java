/*
 * Copyright 2015 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of TivoLibre. TivoLibre is derived from
 * TivoDecode 0.4.4 by Jeremy Drake. See the LICENSE-TivoDecode
 * file for the licensing terms for TivoDecode.
 *
 * TivoLibre is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TivoLibre is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TivoLibre.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package net.straylightlabs.tivolibre;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;

class TransportStreamPacket {
    private long packetId;
    private boolean isPmt;
    private boolean isTivo;
    private Header header;
    private AdaptationField adaptationField;
    private ByteBuffer buffer;
    private int dataOffset;
    private int pesHeaderOffset;

    private final static Logger logger = LoggerFactory.getLogger(TransportStreamPacket.class);

    /** The characters "TiVo", which open a TiVo private data payload */
    private static final int TIVO_SIGNATURE = 0x5469566f;
    private static final int TIVO_VALIDATOR = 0x8103;
    private static final int SIGNATURE_LENGTH = 6;

    public static TransportStreamPacket createFrom(ByteBuffer source, long packetId) throws IOException {
        TransportStreamPacket packet = new TransportStreamPacket();
        packet.setPacketId(packetId);
        packet.readFrom(source);
        return packet;
    }

    public boolean readFrom(ByteBuffer source) throws IOException {
        // Make a local copy of the buffer
        int bufferSize = Math.min(source.limit() - source.position(), TransportStream.FRAME_SIZE);
        buffer = ByteBuffer.allocate(bufferSize);
        System.arraycopy(source.array(), source.position(), buffer.array(), 0, bufferSize);

        // Advance the position of the source buffer
        source.position(source.position() + bufferSize);

        header = readHeader(buffer);
        dataOffset = 0;

        return true;
    }

    private Header readHeader(ByteBuffer source) {
        int headerLength = Integer.BYTES;
        if (source.remaining() < Integer.BYTES) {
            throw new TransportStreamException(String.format(
                    "Packet %d holds only %d bytes, too few for a TS header", packetId, source.remaining()));
        }

        int headerBits = source.getInt();
        int adaptationFieldLength;
        int adaptationFieldBits;

        header = new Header(headerBits);
        if (!checkHeader(header)) {
            throw new TransportStreamException("TransportStream appears to be corrupt, cannot find sync bytes");
        }

        if (header.hasAdaptationField && source.remaining() >= 1) {
            adaptationFieldLength = source.get() & 0xff;
            headerLength += adaptationFieldLength > 0 ? adaptationFieldLength + 1 : 1;
            if (adaptationFieldLength > 0 && source.remaining() >= 1) {
                // The flags are optional to us; a truncated packet can cut them off without
                // changing where the payload would have started.
                adaptationFieldBits = source.get() & 0xff;
                adaptationField = new AdaptationField(adaptationFieldBits);
            }
        } else if (header.hasAdaptationField) {
            // Truncated before its adaptation field, so there is no payload to find
            logger.warn("Packet {} declares an adaptation field but was cut off before it", packetId);
        }

        if (headerLength > source.capacity()) {
            // A bogus adaptation_field_length would otherwise leave us with a negative payload length.
            // TODO fix adaptation field length when not in compatibility mode
            logger.warn(String.format("Header length %d exceeds packet size %d for packet %d; clamping",
                    headerLength, source.capacity(), packetId)
            );
            headerLength = source.capacity();
        }
        header.setLength(headerLength);

        return header;
    }

    private boolean checkHeader(Header header) {
        if (!header.isValid()) {
            logger.warn("Invalid TS packet header for packet {}", packetId);
            return false;
        } else if (header.hasTransportError()) {
            logger.warn("Transport error flag set for packet {}", packetId);
            return false;
        }
        return true;
    }

    /**
     * Don't decode packets unless there is payload data to decrypt, even if the isScrambled bit is set.
     */
    public boolean needsDecoding() {
        return (isScrambled() && header.getLength() + pesHeaderOffset < buffer.capacity());
    }

    /** The size of this packet in bytes, without copying it the way getBytes() does. */
    public int length() {
        return buffer.capacity();
    }

    public byte[] getBytes() {
        if (!buffer.hasArray()) {
            throw new IllegalStateException("Cannot get bytes from empty packet");
        }

        byte[] bytes = new byte[buffer.capacity()];
        System.arraycopy(buffer.array(), 0, bytes, 0, buffer.capacity());
        return bytes;
    }

    public byte[] getScrambledBytes(byte[] decrypted) {
        byte[] bytes = new byte[buffer.capacity()];
        if (buffer.hasArray()) {
            System.arraycopy(buffer.array(), 0, bytes, 0, buffer.capacity() - decrypted.length);
            System.arraycopy(decrypted, 0, bytes, header.getLength() + getPesHeaderOffset(), decrypted.length);
        } else {
            throw new IllegalStateException("Cannot get bytes from empty packet");
        }
        return bytes;
    }

    public boolean isScrambled() {
        return header.isScrambled();
    }

    public void clearScrambled() {
        byte[] bytes = buffer.array();
        bytes[3] &= ~(0xC0);
    }

    public int getPayloadLength() {
        return buffer.capacity() - header.getLength();
    }

    public void setPesHeaderOffset(int val) {
        pesHeaderOffset = val;
    }

    public int getPesHeaderOffset() {
        return pesHeaderOffset;
    }

    public void setPacketId(long id) {
        packetId = id;
    }

//    public long getPacketId() {
//        return packetId;
//    }

    public int getPID() {
        return header.getPID();
    }

    public boolean isPayloadStart() {
        return header.isPayloadStart();
    }

//    public int getDataOffset() {
//        return dataOffset;
//    }

    public byte[] getData() {
        return getDataAt(0);
    }

    public byte[] getDataAt(int offset) {
        byte[] data = new byte[buffer.capacity() - header.getLength() - offset];
        System.arraycopy(buffer.array(), header.getLength() + offset, data, 0, data.length);
        return data;
    }

    public byte[] readBytesFromData(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++, dataOffset += Byte.BYTES) {
            bytes[i] = buffer.get(header.getLength() + dataOffset);
        }
        return bytes;
    }

    public int readIntFromData() {
        int val = buffer.getInt(header.getLength() + dataOffset);
        dataOffset += Integer.BYTES;
        return val;
    }

    public int readUnsignedByteFromData() {
        int val = buffer.get(header.getLength() + dataOffset) & 0xff; // Treat as unsigned byte
        dataOffset += Byte.BYTES;
        return val;
    }

    public int readUnsignedShortFromData() {
        int val = buffer.getShort(header.getLength() + dataOffset) & 0xffff; // Treat as unsigned short
        dataOffset += Short.BYTES;
        return val;
    }

    public void advanceDataOffset(int bytes) {
        dataOffset += bytes;
    }

    /**
     * The number of unread data bytes left in this packet. PSI sections can be longer than the packet that
     * carries them, so parsers need to know when to stop rather than reading off the end of the buffer.
     */
    public int remainingDataLength() {
        return Math.max(0, buffer.capacity() - header.getLength() - dataOffset);
    }

    /**
     * Returns true if this packet's payload starts with the TiVo private data signature ("TiVo" followed by
     * the 0x8103 validator). Some recordings label the private data stream with a stream type we don't
     * recognize, so we fall back on sniffing the payload to find the Turing keys.
     *
     * @param minimumLength the number of data bytes the caller goes on to read, so a packet too short to
     *                      parse is rejected here rather than part way through
     */
    public boolean looksLikeTivoPrivateData(int minimumLength) {
        if (minimumLength < SIGNATURE_LENGTH) {
            throw new IllegalArgumentException(
                    "Cannot match the TiVo signature in fewer than " + SIGNATURE_LENGTH + " bytes");
        }
        if (isScrambled() || remainingDataLength() < minimumLength) {
            return false;
        }
        int base = header.getLength() + dataOffset;
        return buffer.getInt(base) == TIVO_SIGNATURE && (buffer.getShort(base + 4) & 0xffff) == TIVO_VALIDATOR;
    }

    public void setIsPmt(boolean val) {
        isPmt = val;
    }

    @SuppressWarnings("unused")
    public boolean isPmt() {
        return isPmt;
    }

    public void setIsTivo(boolean val) {
        isTivo = val;
    }

    public boolean isTivo() {
        return isTivo;
    }

    public Header getHeader() {
        return header;
    }

    @SuppressWarnings("unused")
    public String dump() {
        return TivoDecoder.bytesToHexString(buffer.array());
    }

    @Override
    public String toString() {
        String s = String.format("====== Packet: %d ======%nHeader = %s", packetId, header);
        if (header.hasAdaptationField) {
            s += String.format("%nAdaptation field = %s", adaptationField);
        }
        s += String.format("%nBody: isPmt=%s, pesHeaderOffset=%d", isPmt, pesHeaderOffset);
        return s;
    }

    static class Header {
        private int length;
        private int sync;
        private boolean hasTransportError;
        private boolean isPayloadStart;
        private boolean isPriority;
        private int pid;
        private boolean isScrambled;
        private boolean hasAdaptationField;
        private boolean hasPayloadData;
        private int counter;

        public Header(int bits) {
            initFromBits(bits);
        }

        private void initFromBits(int bits) {
            sync = (bits & 0xFF000000) >>> 24;
            hasTransportError = ((bits & 0x800000) >> 23) == 1;
            isPayloadStart = ((bits & 0x400000) >> 22) == 1;
            isPriority = ((bits & 0x200000) >> 21) == 1;
            pid = (bits & 0x1FFF00) >> 8;
            isScrambled = (bits & 0xC0) != 0;
            hasAdaptationField = (bits & 0x20) == 0x20;
            hasPayloadData = (bits & 0x10) == 0x10;
            counter = (bits & 0xF);
        }

        public void setLength(int val) {
            if (val > TransportStream.FRAME_SIZE || val <= 0) {
                throw new IllegalArgumentException("Invalid header length: " + val);
            }
            length = val;
        }

        public int getLength() {
            return length;
        }

        public boolean isValid() {
            return sync == 0x47;
        }

        public boolean hasTransportError() {
            return hasTransportError;
        }

        public boolean isPayloadStart() {
            return isPayloadStart;
        }

        public boolean isPriority() {
            return isPriority;
        }

        public int getPID() {
            return pid;
        }

        public boolean isScrambled() {
            return isScrambled;
        }

        @SuppressWarnings("unused")
        public boolean hasAdaptationField() {
            return hasAdaptationField;
        }

        @SuppressWarnings("unused")
        public boolean hasPayloadData() {
            return hasPayloadData;
        }

        @SuppressWarnings("unused")
        public int getCounter() {
            return counter;
        }

        @Override
        public String toString() {
            return "Header{" +
                    "length=" + length +
                    ", sync=" + sync +
                    ", hasTransportError=" + hasTransportError +
                    ", isPayloadStart=" + isPayloadStart +
                    ", isPriority=" + isPriority +
                    String.format(", pid=0x%04x", pid) +
                    ", isScrambled=" + isScrambled +
                    ", hasAdaptationField=" + hasAdaptationField +
                    ", hasPayloadData=" + hasPayloadData +
                    ", counter=" + counter +
                    '}';
        }
    }

    static class AdaptationField {
        private boolean isDiscontinuity;
        private boolean isRandomAccess;
        private boolean isPriority;
        private boolean isPcr;
        private boolean isOpcr;
        private boolean isSplicePoint;
        private boolean isPrivate;
        private boolean isExtension;

        public AdaptationField(int bits) {
            initFromBits(bits);
        }

        private void initFromBits(int bits) {
            isDiscontinuity = (bits & 0x80) == 0x80;
            isRandomAccess = (bits & 0x40) == 0x40;
            isPriority = (bits & 0x20) == 0x20;
            isPcr = (bits & 0x10) == 0x10;
            isOpcr = (bits & 0x08) == 0x08;
            isSplicePoint = (bits & 0x04) == 0x04;
            isPrivate = (bits & 0x02) == 0x02;
            isExtension = (bits & 0x01) == 0x01;
        }

        public boolean isPrivate() {
            return isPrivate;
        }

        @SuppressWarnings("unused")
        public boolean isDiscontinuity() {
            return isDiscontinuity;
        }

        @Override
        public String toString() {
            return "AdaptationField{" +
                    "isDiscontinuity=" + isDiscontinuity +
                    ", isRandomAccess=" + isRandomAccess +
                    ", isPriority=" + isPriority +
                    ", isPcr=" + isPcr +
                    ", isOpcr=" + isOpcr +
                    ", isSplicePoint=" + isSplicePoint +
                    ", isPrivate=" + isPrivate +
                    ", isExtension=" + isExtension +
                    '}';
        }
    }
}
