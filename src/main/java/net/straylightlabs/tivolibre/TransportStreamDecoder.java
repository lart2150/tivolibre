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

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class TransportStreamDecoder extends StreamDecoder {
    private ByteBuffer inputBuffer;
    private int extraBufferSize;
    private long bytesWritten;
    private long resumeDecryptionAtByte;
    private boolean decryptionPaused;
    private long nextResumeDecryptionByteOffset;
    private long nextMaskByteOffset;
    private boolean showDebugOutput;
    private final boolean compatibilityMode;
    private final Set<Integer> reportedTableErrors = new HashSet<>();

    private static final byte SYNC_BYTE_VALUE = 0x47;
    private static final int PAT_PID = 0x0000;
    private static final int NULL_PACKET_PID = 0x1fff;
    /** Bytes per PMT stream entry: stream_type (1) + elementary_PID (2) + ES_info_length (2) */
    private static final int STREAM_ENTRY_LENGTH = 5;
    /** Fixed PMT fields between section_length and the first stream entry */
    private static final int PMT_HEADER_LENGTH = 9;
    private static final int CRC_LENGTH = 4;
    /**
     * PMT fields we read before any length in the packet can be trusted: table_id (1),
     * section_length (2) and the nine bytes of PMT_HEADER_LENGTH.
     */
    private static final int PMT_PROLOGUE_LENGTH = 3 + PMT_HEADER_LENGTH;
    /**
     * TiVo private data fields read before its stream_bytes length applies: the "TiVo" signature
     * (4), the validator (2), three unknown bytes and stream_bytes itself (1).
     */
    private static final int TIVO_PROLOGUE_LENGTH = 10;
    /** Bytes per TiVo private data key entry: PID (2) + stream_id (1) + reserved (1) + key (16) */
    private static final int KEY_ENTRY_LENGTH = 20;
    /** The four byte TS header, the least a packet can consist of */
    private static final int MINIMUM_PACKET_LENGTH = Integer.BYTES;
    private static final int PACKETS_UNTIL_RESYNC = 4;
    private static final int DECRYPTION_PAUSED_INTERVAL = 0x100000;

    private final static Logger logger = LoggerFactory.getLogger(TransportStreamDecoder.class);
    
    public TransportStreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
                                  OutputStream outputStream, boolean compatibilityMode) {
        super(decoder, mpegOffset, inputStream, outputStream);
        inputBuffer = ByteBuffer.allocate(TransportStream.FRAME_SIZE);
        this.compatibilityMode = compatibilityMode;
    }

    @Override
    public boolean process() {
        try {
            advanceToMpegOffset();
            logger.debug("Starting TS processing at position {}", inputStream.getPosition());

            while (true) {
                fillBuffer();

//                if (bytesWritten > 0x38700L) {
//                    return false;
//                }

                TransportStreamPacket packet;
                try {
                    packet = TransportStreamPacket.createFrom(inputBuffer, ++packetCounter);
                } catch (TransportStreamException e) {
                    logger.warn("{}", e.getLocalizedMessage());
                    packet = null;
                    while (packet == null) {
                        try {
                            packet = createPacketAtNextSyncByte(++packetCounter);
                        } catch (TransportStreamException e2) {
                            logger.warn("Problem with this packet, moving on to the next: ", e);
                        }
                    }
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("Re-synched at packet %d (byte 0x%x)", packetCounter, bytesWritten));
                    }
                }

                int pid = packet.getPID();
                if (pid == NULL_PACKET_PID) {
                    // Stuffing that keeps the bit rate constant. Tested before the PMT check
                    // because a garbage PAT naming 0x1fff would otherwise route every one of
                    // these into the table parser and copy them all to the output.
                    if (!compatibilityMode) {
                        bytesWritten += packet.length();
                        continue;
                    }
                } else if (pid == PAT_PID) {
                    if (!processPatPacket(packet)) {
                        // Keep going: one bad table should not cost us the rest of the recording.
                        reportTableError(pid, "PAT");
                    }
                } else if (patData.isProgramMapPid(pid)) {
                    packet.setIsPmt(true);
                    if (!processPmtPacket(packet)) {
                        reportTableError(pid, "PMT");
                    }
                } else {
                    TransportStream stream = streams.get(pid);
                    if (stream != null && stream.getType() == TransportStream.StreamType.PRIVATE_DATA) {
                        packet.setIsTivo(true);
                    } else if (mayCarryTivoPrivateData(stream)
                            && packet.looksLikeTivoPrivateData(TIVO_PROLOGUE_LENGTH)) {
                        // The PMT gave this stream a type we don't recognize, but it's carrying Turing keys
                        if (logger.isInfoEnabled()) {
                            logger.info(String.format("Found TiVo private data on unlabeled PID 0x%04x", pid));
                        }
                        packet.setIsTivo(true);
                    }

                    if (packet.isTivo()) {
                        if (!processTivoPacket(packet)) {
                            reportTableError(pid, "TiVo private data");
                        }
                    }
                }

                decryptAndWritePacket(packet);
                if (logger.isDebugEnabled() && packetCounter % 100000 == 0) {
//                if (bytesWritten > 0x38000L - 188L) {
//                    showDebugOutput = true;
                    logger.debug(String.format("PacketId: %,d PID: 0x%04x Position after reading: %,d",
                            packetCounter, packet.getPID(), inputStream.getPosition())
                    );
//                    logger.debug("{}", packet);
//                    logger.debug("Packet data:\n" + packet.dump());
                }
            }
        } catch (EOFException e) {
            logger.info("End of file reached");
            return decryptedSomething();
        } catch (IOException e) {
            logger.error("Error reading transport stream: ", e);
        }

        return false;
    }

    /**
     * Report a table we could not parse once per PID at error level, then drop to debug. These
     * tables repeat for the length of the recording, so a persistent problem would otherwise
     * flood the log with the same line and bury everything else.
     */
    private void reportTableError(int pid, String tableName) {
        String message = String.format("Error processing %s packet on PID 0x%04x", tableName, pid);
        if (reportedTableErrors.add(pid)) {
            logger.error("{} (further occurrences logged at debug)", message);
        } else {
            logger.debug("{}", message);
        }
    }

    /**
     * A malformed table no longer aborts the decode, which means a recording whose PMT or private
     * data we never managed to read would otherwise run to completion and report success while
     * writing out payload that was never decrypted. Report failure when packets needed decrypting
     * and not one of them worked; a recording with nothing scrambled at all still succeeds.
     */
    private boolean decryptedSomething() {
        long decrypted = 0;
        long failed = 0;
        for (TransportStream stream : streams.values()) {
            decrypted += stream.getDecryptedPacketCount();
            failed += stream.getFailedDecryptionCount();
        }
        if (failed > 0) {
            logger.warn("Failed to decrypt {} of {} packets that needed it", failed, decrypted + failed);
        }
        boolean usable = true;
        for (Map.Entry<Integer, TransportStream> entry : streams.entrySet()) {
            TransportStream stream = entry.getValue();
            if (stream.getFailedDecryptionCount() > 0 && stream.getDecryptedPacketCount() == 0) {
                // Checked per stream rather than in total: a recording that decrypted its audio
                // but never found a key for its video is still a recording nobody can watch.
                logger.error(String.format(
                        "No packet on PID 0x%04x was ever decrypted, so its output is unusable",
                        entry.getKey()));
                usable = false;
            }
        }
        if (!usable) {
            logger.error("At least one stream was never decrypted. This usually means its keys "
                    + "were missing from the TiVo private data stream.");
        }
        return usable;
    }

    /**
     * Only sniff for TiVo private data on streams the PMT didn't identify as audio or video. Without this,
     * a recording whose private data stream carries an unfamiliar stream type never yields its Turing keys,
     * and everything decodes to noise without a single error being logged.
     */
    private static boolean mayCarryTivoPrivateData(TransportStream stream) {
        if (stream == null) {
            return true;
        }
        TransportStream.StreamType type = stream.getType();
        return type != TransportStream.StreamType.AUDIO && type != TransportStream.StreamType.VIDEO;
    }

    private void fillBuffer() throws IOException {
        if (extraBufferSize == 0) {
            int bytesRead = inputStream.read(inputBuffer.array());
            inputBuffer.rewind();
            if (bytesRead == -1) {
                throw new EOFException();
            } else if (bytesRead < TransportStream.FRAME_SIZE) {
                logger.warn("Only read {} bytes, expected {}", bytesRead, TransportStream.FRAME_SIZE);
                if (bytesRead < MINIMUM_PACKET_LENGTH) {
                    // Too short to hold even a TS header, so there is nothing to parse and no
                    // payload to recover. This is the tail of a truncated recording.
                    logger.warn("Discarding {} trailing bytes that cannot form a packet", bytesRead);
                    throw new EOFException();
                }
                inputBuffer.limit(bytesRead);
            }
        } else {
            extraBufferSize -= TransportStream.FRAME_SIZE;
            if (extraBufferSize == 0) {
                resizeBuffer(TransportStream.FRAME_SIZE, inputBuffer.position());
            }
        }
    }

    /**
     * Start searching FRAME_SIZE bytes from each byte with a value of SYNC_BYTE_VALUE.
     * Each such byte we find indicates one good packet. Once we find PACKETS_UNTIL_RESYNC sequential packets,
     * return the first of them and setup our buffers to point to the rest.
     */
    private TransportStreamPacket createPacketAtNextSyncByte(long nextPacketId) throws IOException {
        if (inputBuffer.limit() < inputBuffer.capacity()) {
            // The buffer holds a short final read, so there is nothing left to resynchronize with.
            // Bailing out here also keeps the search below, which bounds itself on capacity, from
            // reading past the reduced limit.
            throw new EOFException();
        }

        TransportStreamPacket packet = null;
        int startPos = Math.max(0, inputBuffer.position() - TransportStream.FRAME_SIZE);
        int currentPos = startPos + 1; // Ensure we pass the start of the frame with the invalid sync byte or error flag set
        while (packet == null) {
            if (currentPos == inputBuffer.capacity()) {
                resizeAndFillInputBuffer(inputBuffer.capacity() + TransportStream.FRAME_SIZE);
            }
            if (inputBuffer.get(currentPos) == SYNC_BYTE_VALUE) {
                int syncedPackets = 0;
                for (int i = 1; i <= PACKETS_UNTIL_RESYNC; i++) {
                    int nextSyncPos = currentPos + (i * TransportStream.FRAME_SIZE);
                    int neededBytes = (nextSyncPos - inputBuffer.capacity()) + 1;
                    if (neededBytes > 0) {
                        resizeAndFillInputBuffer(inputBuffer.capacity() + neededBytes);
                    }
                    if (inputBuffer.get(nextSyncPos) != 0x47) {
                        // Nope, can't resynchronize from @currentPos
                        break;
                    } else {
                        syncedPackets++;
                    }
                }

                if (syncedPackets == PACKETS_UNTIL_RESYNC) {
                    // Looks like we re-synchronized!
                    int unsynchronizedLength = currentPos - startPos;
                    if (unsynchronizedLength > 0) {
                        long deltaToNextInterval = DECRYPTION_PAUSED_INTERVAL - (bytesWritten & 0xfffff);
                        resumeDecryptionAtByte = bytesWritten + unsynchronizedLength;
                        logger.debug(String.format("Starting value for resumeDecryptionAtByte: 0x%x", resumeDecryptionAtByte));
                        // We'll resume decryption at the next position that's evenly divisible by the TS frame size
                        while (resumeDecryptionAtByte % DECRYPTION_PAUSED_INTERVAL != 0) {
                            resumeDecryptionAtByte += TransportStream.FRAME_SIZE;
                        }
                        logger.debug(String.format("Resume decryption at: 0x%x", resumeDecryptionAtByte));
                        boolean maskThirdByte = nextResumeDecryptionByteOffset == 0;
                        nextResumeDecryptionByteOffset = bytesWritten + deltaToNextInterval;
                        outputUnsynchronizedBytes(startPos, unsynchronizedLength, maskThirdByte);
                        pauseDecryption();
                    }
                    inputBuffer.position(currentPos);
                    packet = TransportStreamPacket.createFrom(inputBuffer, nextPacketId);
                    // Read the rest of the following packet into our buffer
                    resizeAndFillInputBuffer(inputBuffer.capacity() + (TransportStream.FRAME_SIZE - 1));
                    extraBufferSize = inputBuffer.capacity() - currentPos - TransportStream.FRAME_SIZE;
                }
            }
            currentPos++;
        }
        return packet;
    }

    private void resizeAndFillInputBuffer(int newSize) throws IOException {
        // Resize the buffer
        int oldSize = inputBuffer.capacity();
        int neededBytes = newSize - oldSize;
        resizeBuffer(newSize, 0);

        // And fill it to capacity
        int bytesRead = inputStream.read(inputBuffer.array(), oldSize, neededBytes);
        if (bytesRead < neededBytes) {
            throw new EOFException();
        }
    }

    private void resizeBuffer(int newSize, int sourceOffset) {
        int oldSize = inputBuffer.capacity();
        ByteBuffer newBuffer = ByteBuffer.allocate(newSize);
        System.arraycopy(inputBuffer.array(), sourceOffset, newBuffer.array(), 0, Math.min(newSize, oldSize));
        int oldPos = inputBuffer.position();
        if (oldPos <= newBuffer.capacity()) {
            newBuffer.position(oldPos);
        }
        inputBuffer = newBuffer;
    }

    /**
     * Write out the unsynchronized data. We do this to ensure binary compliance with the TiVo DirectShow filter.
     */
    private void outputUnsynchronizedBytes(int offset, int length, boolean maskThirdByte) throws IOException {
        // The TiVo DirectShow filter includes these bytes in the output, so we will, too.

        byte[] bytes = inputBuffer.array();
        if (maskThirdByte && length >= 3) {
            // The DirectShow filter seems to do this; it's purpose is a mystery
            bytes[offset + 3] &= 0x3F;
        }
        while (nextResumeDecryptionByteOffset <= bytesWritten + length) {
            bytes[offset + (int) (nextResumeDecryptionByteOffset - bytesWritten) + 3] &= 0x3F;
            nextResumeDecryptionByteOffset += 0x100000;
        }
        if (compatibilityMode) {
            logger.debug(String.format(
                    "Writing unsynchronized bytes from %d to %d (0x%x to 0x%x)%noffset = %d, length = %d",
                    bytesWritten, bytesWritten + length, bytesWritten, bytesWritten + length, offset, length)
            );
            outputStream.write(inputBuffer.array(), offset, length);
        }
        // Pretend we wrote the extra bytes; we use this offset to determine when to resume decryption
        bytesWritten += length;
    }

    /**
     * Tell each stream to stop decrypting packets until its key changes.
     */
    private void pauseDecryption() {
        decryptionPaused = true;
        streams.forEach((id, stream) -> stream.pauseDecrypting());
    }

    private void resumeDecryption() {
        decryptionPaused = false;
        resumeDecryptionAtByte = 0;
        nextResumeDecryptionByteOffset = 0;
        nextMaskByteOffset = 0;
        streams.forEach((id, stream) -> stream.resumeDecrypting());
    }

    private boolean processPmtPacket(TransportStreamPacket packet) {
        if (!packet.isPayloadStart()) {
            // The tail of a section split across packets. Parsing it as a new table registered
            // streams invented from section payload bytes; see processPatPacket.
            logger.debug("Skipping PMT continuation packet");
            return true;
        }

        // See processPatPacket: these fields are read before any length in the packet applies, so
        // a packet truncated inside them would otherwise read off the end of the buffer.
        int prologueLength = PMT_PROLOGUE_LENGTH + (packet.isPayloadStart() ? 1 : 0);
        if (packet.remainingDataLength() < prologueLength) {
            logger.warn("PMT packet holds only {} bytes of data, need {}",
                    packet.remainingDataLength(), prologueLength);
            return false;
        }

        if (packet.isPayloadStart()) {
            // Advance past pointer field
            packet.advanceDataOffset(1);
        }

        // Advance past table_id field
        int tableId = packet.readUnsignedByteFromData();
        if (tableId != 0x02) {
            logger.error(String.format("Unexpected Table ID for PMT: 0x%02x", tableId & 0xff));
            return false;
        }

        int pmtField = packet.readUnsignedShortFromData();
        boolean longSyntax = (pmtField & 0x8000) == 0x8000;
        if (!longSyntax) {
            logger.error("PMT packet uses unknown syntax");
            return false;
        }
        int sectionLength = pmtField & 0x0fff;
        if (sectionLength < PMT_HEADER_LENGTH + CRC_LENGTH) {
            logger.error("PMT section length is too short: {}", sectionLength);
            return false;
        }

        int programNumber = packet.readUnsignedShortFromData();
        sectionLength -= 2;
        int versionAndNextField = packet.readUnsignedByteFromData();
        int version = versionAndNextField & 0x3e;
        boolean currentNextIndicator = (versionAndNextField & 0x01) == 0x01;
        sectionLength -= 1;
        int sectionNumber = packet.readUnsignedByteFromData();
        sectionLength--;
        int lastSectionNumber = packet.readUnsignedByteFromData();
        sectionLength--;
        int pcrPid = packet.readUnsignedShortFromData() & 0x1fff;
        sectionLength -= 2;
        int programInfoLength = packet.readUnsignedShortFromData() & 0x0fff;
        sectionLength -= 2;

        if (logger.isTraceEnabled()) {
            logger.trace(
                    String.format("Program number: 0x%04x Section number: 0x%02x Last section number: 0x%02x " + "" +
                                    "Version: 0x%02x CurrentNextIndicator: %s PCR PID: 0x%04x",
                            programNumber, sectionNumber, lastSectionNumber, version, currentNextIndicator, pcrPid));
        }
        if (programInfoLength > 0) {
            logger.trace("Skipping {} bytes of descriptors", programInfoLength);
            packet.advanceDataOffset(programInfoLength);
            // The program-level descriptors are part of the section, so they have to come off its length
            // too. Cable PMTs commonly carry them; without this the loop below runs past the stream list.
            sectionLength -= programInfoLength;
        }

        // Ignore the CRC at the end
        sectionLength -= 4;

        // A PMT section can be longer than the packet carrying it, and each stream entry is at least five
        // bytes, so stop as soon as either the section or the packet runs out.
        while (sectionLength >= STREAM_ENTRY_LENGTH && packet.remainingDataLength() >= STREAM_ENTRY_LENGTH) {
            int streamTypeId = packet.readUnsignedByteFromData();
            sectionLength--;
            TransportStream.StreamType streamType = TransportStream.StreamType.valueOf(streamTypeId);

            pmtField = packet.readUnsignedShortFromData();
            sectionLength -= 2;
            int streamPid = pmtField & 0x1fff;

            pmtField = packet.readUnsignedShortFromData();
            sectionLength -= 2;
            int esInfoLength = pmtField & 0x0fff;
            packet.advanceDataOffset(esInfoLength);
            sectionLength -= esInfoLength;

            // Create a stream for this PID unless one already exists
            if (!streams.containsKey(streamPid)) {
                logger.debug(String.format("Creating a new %s stream for PID 0x%04x (type=0x%02x)",
                        streamType, streamPid, streamTypeId)
                );
                TransportStream stream = new TransportStream(turingDecoder, streamType);
                streams.put(streamPid, stream);
            }
        }
        if (sectionLength >= STREAM_ENTRY_LENGTH) {
            logger.warn("PMT section continues past the end of its packet; ignoring {} remaining bytes",
                    sectionLength);
        }

        return true;
    }

    private boolean processTivoPacket(TransportStreamPacket packet) {
        // As with the tables above, these fields precede the stream_bytes length that bounds the
        // key entries, so they need their own check.
        if (packet.remainingDataLength() < TIVO_PROLOGUE_LENGTH) {
            logger.warn("TiVo private data packet holds only {} bytes of data, need {}",
                    packet.remainingDataLength(), TIVO_PROLOGUE_LENGTH);
            return false;
        }

        int fileType = packet.readIntFromData();
        if (fileType != 0x5469566f) {
            logger.error(String.format("Invalid TiVo private data fileType: 0x%08x", fileType));
            return false;
        }

        int validator = packet.readUnsignedShortFromData();
        if (validator != 0x8103) {
            logger.error(String.format("Invalid TiVo private data validator: 0x%04x", validator));
            return false;
        }

        packet.advanceDataOffset(3);

        int streamLength = packet.readUnsignedByteFromData();
        while (streamLength >= KEY_ENTRY_LENGTH && packet.remainingDataLength() >= KEY_ENTRY_LENGTH) {
            int packetId = packet.readUnsignedShortFromData();
            streamLength -= 2;
            int streamId = packet.readUnsignedByteFromData();
            streamLength--;
            // Advance past reserved field
            packet.advanceDataOffset(1);
            streamLength--;

            byte[] key = packet.readBytesFromData(Stream.KEY_LENGTH);
            streamLength -= Stream.KEY_LENGTH;

            TransportStream stream = streams.get(packetId);
            if (stream == null) {
                // The private data can name a PID the PMT didn't; give it a stream so its key isn't lost.
                logger.warn(String.format("No TransportStream with ID 0x%04x found, creating one", packetId));
                stream = new TransportStream(turingDecoder, TransportStream.StreamType.NOT_IN_PMT);
                streams.put(packetId, stream);
            }
//            logger.debug(String.format("Setting key for stream 0x%03x (0x%04x)", streamId, packetId));
            stream.setStreamId(streamId);
            stream.setKey(key);
        }
        if (streamLength >= KEY_ENTRY_LENGTH) {
            logger.warn("TiVo private data continues past the end of its packet; ignoring {} remaining bytes",
                    streamLength);
        }

        return true;
    }

    private void decryptAndWritePacket(TransportStreamPacket packet) {
        TransportStream stream = getPacketStream(packet);

        byte[] packetBytes = stream.processPacket(packet);
//        byte[] packetBytes;
//        if (showDebugOutput) {
//            packetBytes = stream.processPacket(packet, true, 152);
//            logger.debug("Decrypted packetBytes:\n{}", TivoDecoder.bytesToHexString(packetBytes));
//        } else {
//            packetBytes = stream.processPacket(packet);
//        }

        if (decryptionPaused) {
            maskBytes(packetBytes);
        }

        writePacketBytes(packetBytes);

        if (resumeDecryptionAtByte > 0 && resumeDecryptionAtByte <= bytesWritten) {
            logger.warn(String.format("Resuming decryption at 0x%x, bytesWritten = 0x%x",
                    resumeDecryptionAtByte, bytesWritten)
            );
            resumeDecryption();
        }
    }

    private TransportStream getPacketStream(TransportStreamPacket packet) {
        TransportStream stream = streams.get(packet.getPID());
        if (stream == null) {
            logger.warn(String.format("No TransportStream exists with PID 0x%04x, creating one",
                    packet.getPID())
            );
            stream = new TransportStream(turingDecoder, TransportStream.StreamType.NOT_IN_PMT);
            streams.put(packet.getPID(), stream);
        }
        return stream;
    }

    /**
     * This is for binary compatibility with the TiVo DirectShow filter: it masks bytes at certain intervals
     * after a loss of synchronization. The exact rules TiVo uses for this masking are unknown; this is a best
     * guess based on the output of their DirectShow filter.
     */
    private void maskBytes(byte[] packetBytes) {
        if (nextResumeDecryptionByteOffset > 0 && packetBytes.length + bytesWritten > nextResumeDecryptionByteOffset + 3) {
            int offset = (int) (nextResumeDecryptionByteOffset - bytesWritten);
            int headerBits = intFromByteArray(packetBytes, offset);
            TransportStreamPacket.Header header = new TransportStreamPacket.Header(headerBits);
            if (header.isValid() && !header.isPriority()) {
                logger.debug(String.format("Found a valid TS header at 0x%x, pid=0x%04x, checking next frame", nextResumeDecryptionByteOffset, header.getPID()));
                nextMaskByteOffset = nextResumeDecryptionByteOffset + TransportStream.FRAME_SIZE;
            }
            nextResumeDecryptionByteOffset += 0x100000;
            packetBytes[offset + 3] &= 0x3F;
        }
        if (nextMaskByteOffset > 0 && packetBytes.length + bytesWritten > nextMaskByteOffset + 3) {
            logger.debug(String.format("Masking byte at 0x%x", nextMaskByteOffset));
            int offset = (int) (nextMaskByteOffset - bytesWritten);
            packetBytes[offset + 3] &= 0x3F;

            int headerBits = intFromByteArray(packetBytes, offset);
            TransportStreamPacket.Header header = new TransportStreamPacket.Header(headerBits);
            if (header.isValid()) {
                nextMaskByteOffset += TransportStream.FRAME_SIZE;
            } else {
                nextMaskByteOffset = 0;
            }
        }
    }

    int intFromByteArray(byte[] bytes, int offset) {
        return bytes[offset] << 24 | (bytes[offset + 1] & 0xFF) << 16 |
                (bytes[offset + 2] & 0xFF) << 8 | (bytes[offset + 3] & 0xFF);
    }

    private void writePacketBytes(byte[] packetBytes) {
        try {
            if (!decryptionPaused || compatibilityMode) {
                outputStream.write(packetBytes);
            }
            bytesWritten += packetBytes.length;
        } catch (Exception e) {
            logger.error("Error writing file: ", e);
            throw new RuntimeException();
        }
    }
}
