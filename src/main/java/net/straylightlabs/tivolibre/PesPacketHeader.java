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

/**
 * The PES packet header that opens a payload unit: start code prefix, stream_id, PES_packet_length,
 * and, for the stream ids that carry one, the optional header holding PTS and DTS.
 *
 * This is not the same question {@link PesHeader} answers. That class walks start codes to find
 * where TiVo's encryption begins, and its offset runs past sequence and extension headers, which
 * are elementary stream content a consumer must receive. This class stops at the end of the PES
 * header proper, which is where the elementary stream bytes start.
 *
 * Timestamps are reported exactly as the stream carries them: 33 bit values at 90 kHz, not rebased,
 * not unwrapped. A timestamp whose marker bits are wrong is reported as absent rather than as a
 * number nobody can trust.
 */
class PesPacketHeader {
    private final int streamId;
    private final int packetLength;
    private final int headerLength;
    private final long pts;
    private final long dts;
    private final boolean hasPts;
    private final boolean hasDts;

    /** Start code prefix (3) + stream_id (1) + PES_packet_length (2) */
    static final int MINIMUM_LENGTH = 6;
    /** The fixed part of the optional header: two flag bytes and PES_header_data_length */
    private static final int OPTIONAL_HEADER_PROLOGUE = 3;
    private static final int TIMESTAMP_LENGTH = 5;
    private static final int PTS_ONLY = 0x2;
    private static final int PTS_AND_DTS = 0x3;

    private PesPacketHeader(int streamId, int packetLength, int headerLength,
                            long pts, boolean hasPts, long dts, boolean hasDts) {
        this.streamId = streamId;
        this.packetLength = packetLength;
        this.headerLength = headerLength;
        this.pts = pts;
        this.hasPts = hasPts;
        this.dts = dts;
        this.hasDts = hasDts;
    }

    /**
     * Read the header at the front of @data, or return null if @length does not hold all of it.
     * A payload unit start is only the start of the header: the optional header can run to 264
     * bytes and the packet carrying it holds at most 184, so a caller that has not yet collected
     * the whole unit will legitimately get null here and should ask again with more bytes.
     */
    static PesPacketHeader parse(byte[] data, int offset, int length) {
        if (length < MINIMUM_LENGTH || !hasStartCodePrefix(data, offset)) {
            return null;
        }
        int streamId = data[offset + 3] & 0xff;
        int packetLength = ((data[offset + 4] & 0xff) << 8) | (data[offset + 5] & 0xff);

        if (!hasOptionalHeader(streamId)) {
            return new PesPacketHeader(streamId, packetLength, MINIMUM_LENGTH, 0, false, 0, false);
        }
        if (length < MINIMUM_LENGTH + OPTIONAL_HEADER_PROLOGUE) {
            return null;
        }
        if ((data[offset + 6] & 0xc0) != 0x80) {
            // Not a 13818-1 optional header. Reading flags out of it would invent timestamps.
            return null;
        }
        int timestampFlags = (data[offset + 7] & 0xc0) >> 6;
        int headerDataLength = data[offset + 8] & 0xff;
        int headerLength = MINIMUM_LENGTH + OPTIONAL_HEADER_PROLOGUE + headerDataLength;
        if (length < headerLength) {
            return null;
        }
        if (packetLength > 0 && MINIMUM_LENGTH + packetLength < headerLength) {
            // The unit claims to end inside its own header. Nothing can be read out of that, and a
            // caller that trusted both fields would compute a negative payload length.
            return null;
        }

        long pts = 0;
        long dts = 0;
        boolean hasPts = false;
        boolean hasDts = false;
        int timestampOffset = offset + MINIMUM_LENGTH + OPTIONAL_HEADER_PROLOGUE;
        if (timestampFlags == PTS_ONLY || timestampFlags == PTS_AND_DTS) {
            // '0010' when a PTS stands alone, '0011' when a DTS follows it
            int prefix = timestampFlags == PTS_AND_DTS ? 0x30 : 0x20;
            if (headerDataLength >= TIMESTAMP_LENGTH
                    && isWellFormedTimestamp(data, timestampOffset, prefix)) {
                pts = readTimestamp(data, timestampOffset);
                hasPts = true;
            }
        }
        if (timestampFlags == PTS_AND_DTS) {
            int dtsOffset = timestampOffset + TIMESTAMP_LENGTH;
            if (headerDataLength >= TIMESTAMP_LENGTH * 2
                    && isWellFormedTimestamp(data, dtsOffset, 0x10)) {
                dts = readTimestamp(data, dtsOffset);
                hasDts = true;
            }
        }

        return new PesPacketHeader(streamId, packetLength, headerLength, pts, hasPts, dts, hasDts);
    }

    static boolean hasStartCodePrefix(byte[] data, int offset) {
        return data[offset] == 0x00 && data[offset + 1] == 0x00 && data[offset + 2] == 0x01;
    }

    /**
     * Whether this stream_id carries the optional header at all. The ids below are the ones
     * 13818-1 excludes from it; everything else, audio and video included, has one.
     */
    private static boolean hasOptionalHeader(int streamId) {
        switch (streamId) {
            case 0xbc:  // program_stream_map
            case 0xbe:  // padding_stream
            case 0xbf:  // private_stream_2
            case 0xf0:  // ECM_stream
            case 0xf1:  // EMM_stream
            case 0xf2:  // DSMCC_stream
            case 0xf8:  // ITU-T Rec. H.222.1 type E
            case 0xff:  // program_stream_directory
                return false;
            default:
                return true;
        }
    }

    /**
     * A timestamp is a four bit prefix and then three marker bits spread through the five bytes.
     * Checking all four things is what separates a real timestamp from stuffing, or from a header
     * this parser has misaligned itself on. The marker bits alone would let one arbitrary run of
     * bytes in eight through; with the prefix it is one in a hundred and twenty eight, and a
     * fabricated 33 bit timestamp reported as genuine is the sync error nobody can trace.
     */
    private static boolean isWellFormedTimestamp(byte[] data, int offset, int prefix) {
        return (data[offset] & 0xf0) == prefix
                && (data[offset] & 0x01) == 0x01
                && (data[offset + 2] & 0x01) == 0x01
                && (data[offset + 4] & 0x01) == 0x01;
    }

    private static long readTimestamp(byte[] data, int offset) {
        return ((long) (data[offset] & 0x0e)) << 29
                | ((long) (data[offset + 1] & 0xff)) << 22
                | ((long) (data[offset + 2] & 0xfe)) << 14
                | ((long) (data[offset + 3] & 0xff)) << 7
                | ((long) (data[offset + 4] & 0xfe)) >> 1;
    }

    int getStreamId() {
        return streamId;
    }

    /**
     * The PES_packet_length field as the stream carries it. Zero is legal and common for video in
     * a transport stream, and means the unit ends where the next one starts rather than at a
     * length declared up front.
     */
    int getPacketLength() {
        return packetLength;
    }

    /** Bytes from the start code to the first elementary stream byte. */
    int getHeaderLength() {
        return headerLength;
    }

    long getPts() {
        return pts;
    }

    long getDts() {
        return dts;
    }

    boolean hasPts() {
        return hasPts;
    }

    boolean hasDts() {
        return hasDts;
    }

    @Override
    public String toString() {
        return String.format("PesPacketHeader{streamId=0x%02x, packetLength=%d, headerLength=%d, "
                        + "pts=%s, dts=%s}", streamId, packetLength, headerLength,
                hasPts ? Long.toString(pts) : "absent", hasDts ? Long.toString(dts) : "absent");
    }
}
