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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

class TransportStream extends Stream {
    private final TuringDecoder turingDecoder;
    private final StreamType type;
    private final ByteBuffer pesBuffer;
    private final byte[] pesBufferArray;
    private int nextPacketPesOffset;
    private boolean decryptingPaused;
    private PesHeader lastPesHeader;
    private boolean debug;
    private int debugOffset;
    private long decryptedPacketCount;
    private long failedDecryptionCount;

    private final static Logger logger = LoggerFactory.getLogger(TransportStream.class);

    public static final int FRAME_SIZE = 188;

    public TransportStream(TuringDecoder decoder) {
        super();
        this.turingDecoder = decoder;
        this.type = StreamType.NONE;
        pesBufferArray = new byte[FRAME_SIZE];
        pesBuffer = ByteBuffer.wrap(pesBufferArray);
        lastPesHeader = new PesHeader();
    }

    public TransportStream(TuringDecoder decoder, StreamType type) {
        super();
        this.turingDecoder = decoder;
        this.type = type;
        pesBufferArray = new byte[FRAME_SIZE];
        pesBuffer = ByteBuffer.wrap(pesBufferArray);
        lastPesHeader = new PesHeader();
    }

    /**
     * Update the @turingKey and re-enabled decryption
     */
    public void setKey(byte[] val) {
        if (!decryptingPaused) {
            turingKey = val;
        }
    }

    /**
     * Tell the stream not to decrypt another packet until the @turingKey changes
     */
    public void pauseDecrypting() {
        decryptingPaused = true;
    }

    public void resumeDecrypting() {
        decryptingPaused = false;
    }

    public StreamType getType() {
        return type;
    }

    /** Packets this stream decrypted successfully. */
    public long getDecryptedPacketCount() {
        return decryptedPacketCount;
    }

    /** Packets this stream should have decrypted but had no usable key for. */
    public long getFailedDecryptionCount() {
        return failedDecryptionCount;
    }

    /**
     * Process @packet's headers and decrypt it if necessary.
     *
     * @return The byte array to write to the output stream
     */
    public byte[] processPacket(TransportStreamPacket packet) {
        return processPacket(packet, false, 0);
    }

    public byte[] processPacket(TransportStreamPacket packet, boolean debug, int debugAtOffset) {
        this.debug = debug;
        this.debugOffset = debugAtOffset;
        try {
            copyPayloadToPesBuffer(packet);
            calculatePesHeaderOffset(packet);
        } catch (RuntimeException e) {
            logger.error("Exception while calculating PES header offset: ", e);
            logger.info("{}", packet);
            logger.info("Packet data:\n{}", TivoDecoder.bytesToHexString(packet.getBytes()));
            logger.info("PES buffer:\n{}", TivoDecoder.bytesToHexString(pesBufferArray, 0, pesBuffer.limit()));
            throw e;
        }

        byte[] packetBytes;
        if (!decryptingPaused && packet.needsDecoding()) {
            packetBytes = decryptPacket(packet);
        } else {
            packetBytes = packet.getBytes();
        }

        return packetBytes;
    }

    /**
     * Put @packet's data in a ByteBuffer for easier consumption. If we already know the PES header is too long
     * to end in this packet, don't bother copying @packet's data.
     */
    private void copyPayloadToPesBuffer(TransportStreamPacket packet) {
        byte[] data = packet.getData();
        if (nextPacketPesOffset < data.length) {
            // The PES header might end in this packet
            int copiedLength = data.length - nextPacketPesOffset;
            System.arraycopy(data, nextPacketPesOffset, pesBufferArray, 0, copiedLength);
            pesBuffer.position(0);
            // Limit the parser to the bytes we just copied. The tail of the array still holds bytes from
            // an earlier packet, and treating those as payload throws off the header length.
            pesBuffer.limit(copiedLength);
        }
    }

    /**
     * Figure out the PES header offset for @packet. We don't decryptBuffer PES headers, so we need to know exactly
     * where in the buffer to start the decrypt process. If the header extends past the boundary of @packet,
     * store extra length in @nextPacketOffset so the next packet can be decrypted at the right offset.
     * If @nextPacketOffset is larger than this packet's payload, don't try to parse its PES headers.
     */
    private void calculatePesHeaderOffset(TransportStreamPacket packet) {
        int payloadLength = packet.getPayloadLength();
        if (nextPacketPesOffset < payloadLength) {
            int packetPesOffset = nextPacketPesOffset;
            int sumOfPesHeaderLengths = packetPesOffset;
            if (sumOfPesHeaderLengths > 0 || packet.isPayloadStart() || !lastPesHeader.isFinished()) {
                // Only get PES header length if we know this is a payload start, or our prior header extended into it
                sumOfPesHeaderLengths += getPesHeaderLength();
            }

            if (sumOfPesHeaderLengths <= payloadLength) {
                // PES headers end in this packet
                packet.setPesHeaderOffset(sumOfPesHeaderLengths);
                nextPacketPesOffset = 0;
            } else {
                nextPacketPesOffset = sumOfPesHeaderLengths - payloadLength;
                packet.setPesHeaderOffset(payloadLength);
            }
        } else {
            // We already know the PES header extends into the next packet, so skip over this one
            nextPacketPesOffset -= payloadLength;
            packet.setPesHeaderOffset(payloadLength);
        }
    }

    private int getPesHeaderLength() {
        PesHeader pesHeader;
        if (lastPesHeader.isFinished()) {
            pesHeader = PesHeader.createFrom(pesBuffer);
        } else {
            pesHeader = PesHeader.createFrom(pesBuffer, lastPesHeader.getUnfinishedStartCode(),
                    lastPesHeader.getUnfinishedStartCodeValue(), lastPesHeader.getTrailingZeroBits(),
                    lastPesHeader.endsWithStartPrefix());
        }
        lastPesHeader = pesHeader;
        return pesHeader.size();
    }

    public boolean decryptBuffer(byte[] buffer) {
        if (doHeader()) {
            // Only decrypt the buffer if the stream's key has been set
            TuringStream turingStream = turingDecoder.prepareFrame(streamId, turingBlockNumber);
            turingDecoder.decryptBytes(turingStream, buffer);
        } else {
            return false;
        }

        return true;
    }

    private byte[] decryptPacket(TransportStreamPacket packet) {
        packet.clearScrambled();
        byte[] encryptedData = packet.getData();
        int encryptedLength = encryptedData.length - packet.getPesHeaderOffset();
        byte[] data = new byte[encryptedLength];
        System.arraycopy(encryptedData, packet.getPesHeaderOffset(), data, 0, encryptedLength);
//        if (debug) {
//            logger.debug("Data to decrypt:\n{}", TivoDecoder.bytesToHexString(data));
//            data[debugOffset] = -124;
//            logger.debug(String.format("Value at offset %d: 0x%02x", debugOffset, data[debugOffset]));
//        }
        if (decryptBuffer(data)) {
            decryptedPacketCount++;
        } else {
            failedDecryptionCount++;
            logger.error(String.format("Decrypting packet in stream 0x%04x failed", packet.getPID()));
        }
//        if (debug) {
//            logger.debug("Decrypted data:\n{}", TivoDecoder.bytesToHexString(data));
//            logger.debug(String.format("Decrypted value at offset %d: 0x%02x <---- \n", debugOffset, data[debugOffset]));
//            if (data[debugOffset] == 0x35) {
//                logger.error("\n\nFOUND IT!!!\n\n===========\n\n");
//            }
//        }
        return packet.getScrambledBytes(data);
    }

    /**
     * The stream_type values a PMT can give an elementary stream. A modern TiVo records the
     * provider's broadcast or cable stream as it arrives, so the codec is whatever the provider
     * chose and this table has to cover more than the MPEG-2 and H.264 the older units produced.
     *
     * Values are taken from ffmpeg's libavformat/mpegts.h and mpegts.c, which is treated as the
     * authoritative list here: a stream_type ffmpeg does not recognize is assumed unsupported.
     * That rules a couple of things out. AC-4, one of the two ATSC 3.0 audio codecs, has no
     * stream_type at all; ffmpeg identifies it from a descriptor on a 0x06 private PES stream.
     * MPEG-H 3D Audio, the other one, ffmpeg does not read from a transport stream, so it is
     * absent here too. The non-media values below 0x20 are likewise missing from ffmpeg's tables
     * because it does not demux them, but they are real assignments and belong under OTHER.
     *
     * Two ranges need care. 0x00 to 0x7f is assigned by ISO/IEC 13818-1 and unambiguous. 0x80 to
     * 0xff is "user private" and means different things to different standards, so the entries
     * below follow the ATSC and SCTE assignments rather than the Blu-ray ones, which collide with
     * them. 0x86 in particular is SCTE-35 splice information in a broadcast stream, not the
     * Blu-ray DTS-HD Master audio that shares the value.
     *
     * Classification only affects two things: finding the TiVo private data stream at 0x97, and
     * keeping audio and video streams out of the private data signature sniff. Nothing about
     * decryption depends on the codec, so an unrecognized value is harmless.
     */
    public enum StreamType {
        AUDIO,
        VIDEO,
        PRIVATE_DATA,
        OTHER,
        NOT_IN_PMT,
        NONE;

        private static final Map<Integer, StreamType> typeMap;

        static {
            typeMap = new HashMap<>();

            // Video, assigned by ISO/IEC 13818-1
            typeMap.put(0x01, VIDEO);   // MPEG-1 video
            typeMap.put(0x02, VIDEO);   // MPEG-2 video
            typeMap.put(0x10, VIDEO);   // MPEG-4 Part 2 visual
            typeMap.put(0x1b, VIDEO);   // H.264 / AVC
            typeMap.put(0x20, VIDEO);   // H.264 MVC sub-bitstream
            typeMap.put(0x21, VIDEO);   // JPEG 2000
            typeMap.put(0x24, VIDEO);   // H.265 / HEVC, also the ATSC 3.0 video codec
            typeMap.put(0x32, VIDEO);   // JPEG XS
            typeMap.put(0x33, VIDEO);   // H.266 / VVC
            typeMap.put(0x36, VIDEO);   // LCEVC enhancement
            typeMap.put(0x42, VIDEO);   // AVS / Chinese Video Standard
            typeMap.put(0xd1, VIDEO);   // Dirac
            typeMap.put(0xd2, VIDEO);   // AVS2
            typeMap.put(0xd4, VIDEO);   // AVS3
            typeMap.put(0xea, VIDEO);   // VC-1

            // Audio, assigned by ISO/IEC 13818-1
            typeMap.put(0x03, AUDIO);   // MPEG-1 audio, typically MP2 or MP3
            typeMap.put(0x04, AUDIO);   // MPEG-2 audio, typically MP2 or MP3
            typeMap.put(0x0f, AUDIO);   // AAC in ADTS
            typeMap.put(0x11, AUDIO);   // AAC in LATM/LOAS
            typeMap.put(0x1c, AUDIO);   // MPEG-4 audio with no transport syntax

            // Non-media streams, assigned by ISO/IEC 13818-1. Note 0x06: a private PES stream is
            // how AC-4 and DVB-style AC-3 and DTS arrive, identified by a descriptor rather than
            // by stream_type. ffmpeg reads AC-4 that way, so it never appears in a table like this.
            typeMap.put(0x05, OTHER);   // Private sections
            typeMap.put(0x06, OTHER);   // Private data in PES packets
            typeMap.put(0x07, OTHER);   // MHEG
            typeMap.put(0x08, OTHER);   // DSM-CC
            typeMap.put(0x09, OTHER);   // H.222.1
            typeMap.put(0x0a, OTHER);   // DSM-CC type A, multiprotocol encapsulation
            typeMap.put(0x0b, OTHER);   // DSM-CC type B, UN messages
            typeMap.put(0x0c, OTHER);   // DSM-CC type C, stream descriptors
            typeMap.put(0x0d, OTHER);   // DSM-CC type D, sections
            typeMap.put(0x0e, OTHER);   // MPEG-2 auxiliary
            typeMap.put(0x12, OTHER);   // MPEG-4 SL or FlexMux in PES packets
            typeMap.put(0x13, OTHER);   // MPEG-4 SL or FlexMux in sections
            typeMap.put(0x14, OTHER);   // DSM-CC synchronized download
            typeMap.put(0x15, OTHER);   // Metadata in PES packets
            typeMap.put(0x16, OTHER);   // Metadata in metadata sections
            typeMap.put(0x17, OTHER);   // Metadata in a DSM-CC data carousel
            typeMap.put(0x18, OTHER);   // Metadata in a DSM-CC object carousel
            typeMap.put(0x19, OTHER);   // Metadata in a synchronized download protocol
            typeMap.put(0x1a, OTHER);   // MPEG-2 IPMP
            typeMap.put(0x7f, OTHER);   // IPMP

            // User private, per the ATSC and SCTE assignments
            // 0x80 is the one value here ffmpeg gives no broadcast meaning: it knows it only as
            // Blu-ray LPCM audio. Kept as video, which is what tivodecode-ng inherited it as for
            // cable streams, but treat the classification as unverified.
            typeMap.put(0x80, VIDEO);   // Cable video, per tivodecode-ng. Blu-ray LPCM in ffmpeg
            typeMap.put(0x81, AUDIO);   // AC-3, ATSC A/52
            typeMap.put(0x86, OTHER);   // SCTE-35 splice information, a cue signal rather than media
            typeMap.put(0x87, AUDIO);   // E-AC-3, ATSC A/52 Annex E
            typeMap.put(0x8a, AUDIO);   // DTS

            // User private, per Apple's HLS sample encryption. Not something a broadcaster sends,
            // but ffmpeg recognizes them and they cost nothing to carry here.
            typeMap.put(0xc1, AUDIO);   // AC-3 with HLS sample encryption
            typeMap.put(0xc2, AUDIO);   // E-AC-3 with HLS sample encryption
            typeMap.put(0xcf, AUDIO);   // AAC with HLS sample encryption
            typeMap.put(0xdb, VIDEO);   // H.264 with HLS sample encryption

            // User private, TiVo's own. This is the stream carrying the Turing keys.
            typeMap.put(0x97, PRIVATE_DATA);

            // Allow us to track packet streams that weren't in the PMT
            typeMap.put(0xffff, NOT_IN_PMT);

            typeMap.put(0x00, NONE);
        }

        public static StreamType valueOf(int val) {
            return typeMap.getOrDefault(val, NONE);
        }
    }
}
