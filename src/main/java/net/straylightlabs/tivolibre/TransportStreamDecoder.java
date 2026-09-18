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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** Bytes and packets left out of the output because decryption was paused. */
    private long droppedBytes;
    private long droppedPackets;
    private long eventDroppedBytes;
    private long eventDroppedPackets;
    private int resyncEvents;
    /**
     * PIDs we have written a packet for, the streams an excision can interrupt. A flag per PID
     * rather than a set: this is touched on every packet written, and boxing a PID above 127 to
     * re-add it to a set allocates tens of millions of times across a large recording.
     */
    private final boolean[] writtenPids = new boolean[PID_COUNT];
    /**
     * PIDs whose payload is worth assembling: the announced streams, less the TiVo private data one.
     * That stream is announced like any other and carries no PES at all, so trying to read units out
     * of it would report a break for every one of them. Real recordings never mark a payload unit
     * start on it, but nothing guarantees that.
     */
    private final boolean[] assembledPids = new boolean[PID_COUNT];
    /**
     * PIDs in the middle of a run of packets that would not decrypt, so the run is reported once.
     * A flag per PID for the same reason as writtenPids: it is cleared on every delivered packet.
     */
    private final boolean[] pidsFailingDecryption = new boolean[PID_COUNT];
    /** PIDs still owed a discontinuity marker after the most recent excision. */
    private final Set<Integer> pidsAwaitingDiscontinuity = new HashSet<>();
    private long discontinuityMarkers;
    private boolean ended;
    private boolean usable;
    /**
     * Payload withheld from the sink during the current pause. Not the same as the bytes left out
     * of the output: compatibility mode writes those bytes and still cannot decrypt them.
     */
    private long eventUndeliveredBytes;
    private long eventUndeliveredPackets;
    /** Null unless a consumer asked for elementary streams instead of, or as well as, bytes. */
    private final FrameSink frameSink;
    private final PayloadAssembler assembler;
    /** Every stream a PMT has announced, in the order the tables named them. */
    private final Map<Integer, ElementaryStreamInfo> announcedStreams = new LinkedHashMap<>();
    /**
     * False for a stream that was decrypted earlier. Nothing is scrambled, so there is no keystream
     * to lose position against and none of the pause, drop and mask machinery applies.
     */
    private final boolean decryptionEnabled;

    private static final byte SYNC_BYTE_VALUE = 0x47;
    private static final int PAT_PID = 0x0000;
    private static final int NULL_PACKET_PID = 0x1fff;
    /** The PID space is 13 bits wide */
    private static final int PID_COUNT = 0x2000;
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
    /** adaptation_field_control value for a packet carrying an adaptation field and no payload */
    private static final int ADAPTATION_FIELD_ONLY = 0x20;
    /** discontinuity_indicator, the first flag of an adaptation field */
    private static final int DISCONTINUITY_INDICATOR = 0x80;
    private static final byte STUFFING_BYTE = (byte) 0xff;
    private static final byte[] NO_DESCRIPTORS = new byte[0];

    private final static Logger logger = LoggerFactory.getLogger(TransportStreamDecoder.class);
    
    public TransportStreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
                                  OutputStream outputStream, boolean compatibilityMode) {
        this(decoder, mpegOffset, inputStream, outputStream, compatibilityMode, null, true);
    }

    TransportStreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
                           OutputStream outputStream, boolean compatibilityMode, FrameSink frameSink,
                           boolean decryptionEnabled) {
        super(decoder, mpegOffset, inputStream, outputStream);
        inputBuffer = ByteBuffer.allocate(TransportStream.FRAME_SIZE);
        this.compatibilityMode = compatibilityMode;
        this.frameSink = frameSink;
        this.decryptionEnabled = decryptionEnabled;
        this.assembler = frameSink == null ? null : new PayloadAssembler(frameSink);
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
                    if (!compatibilityMode && decryptionEnabled) {
                        bytesWritten += packet.length();
                        continue;
                    }
                    if (!decryptionEnabled) {
                        // Stuffing has nothing in it for anyone, but this path promises to pass
                        // every byte through, so write it and skip the rest of the work. Routing it
                        // onward would invent a stream for PID 0x1fff and walk 184 bytes of padding
                        // looking for PES headers, on up to a third of the packets in a broadcast.
                        writePacketBytes(NULL_PACKET_PID, packet.getBytes());
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
            logDroppedSummary();
            return finish(true);
        } catch (IOException e) {
            logger.error("Error reading transport stream: ", e);
        } finally {
            // Anything thrown out of the packet loop ends the sink here rather than above, where the
            // counts are not in reach: a decode that read most of a recording and then failed should
            // say so, not report that nothing was ever attempted. Only when no other exit already
            // did it, since building the result a second time would repeat everything it logs.
            if (!ended) {
                finish(false);
            }
        }

        return finish(false);
    }

    /**
     * Deliver the tail of the decode: the last payload unit of each stream, then the verdict. A
     * consumer that is writing a file needs the verdict before it finalizes one, so it arrives even
     * when the read failed part way through, where @completed is false and the result is unusable
     * whatever the streams themselves managed.
     */
    private boolean finish(boolean completed) {
        if (ended) {
            // Idempotent rather than guarded at each call site: an exit that both catches and falls
            // through would otherwise flush the assembler twice, log the whole verdict twice, and
            // break the one promise onEnd makes, which is that nothing follows it.
            return usable;
        }
        ended = true;
        DecodeResult result = buildResult(completed);
        if (frameSink != null) {
            if (!assembler.sinkFailed()) {
                assembler.flush();
            }
            try {
                frameSink.onEnd(result);
            } catch (RuntimeException e) {
                // onEnd is the consumer's last word either way, so its failure is not worth
                // replacing whatever is already on its way out of the decode.
                logger.error("The consumer threw from onEnd: ", e);
            }
        }
        usable = result.isUsable();
        return usable;
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
    private DecodeResult buildResult(boolean completed) {
        long decrypted = 0;
        long failed = 0;
        for (TransportStream stream : streams.values()) {
            decrypted += stream.getDecryptedPacketCount();
            failed += stream.getFailedDecryptionCount();
        }
        if (failed > 0) {
            logger.warn("Failed to decrypt {} of {} packets that needed it", failed, decrypted + failed);
        }
        List<Integer> neverDecrypted = new ArrayList<>();
        for (Map.Entry<Integer, TransportStream> entry : streams.entrySet()) {
            TransportStream stream = entry.getValue();
            if (stream.getFailedDecryptionCount() > 0 && stream.getDecryptedPacketCount() == 0) {
                // Checked per stream rather than in total: a recording that decrypted its audio
                // but never found a key for its video is still a recording nobody can watch.
                logger.error(String.format(
                        "No packet on PID 0x%04x was ever decrypted, so its output is unusable",
                        entry.getKey()));
                neverDecrypted.add(entry.getKey());
            }
        }
        if (!neverDecrypted.isEmpty()) {
            logger.error("At least one stream was never decrypted. This usually means its keys "
                    + "were missing from the TiVo private data stream.");
        }
        return new DecodeResult(completed, neverDecrypted, decrypted, failed, droppedBytes,
                droppedPackets, resyncEvents);
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
                    if (unsynchronizedLength > 0 && !decryptionEnabled) {
                        // Nothing was encrypted, so losing sync cost alignment and nothing else.
                        // Write the run through and carry on: pausing here would discard good data
                        // to the next megabyte boundary for no reason, and the masking that goes
                        // with it would corrupt bytes rather than preserve anyone's parity.
                        resyncEvents++;
                        logger.debug("Writing {} unsynchronized bytes through", unsynchronizedLength);
                        outputStream.write(inputBuffer.array(), startPos, unsynchronizedLength);
                        bytesWritten += unsynchronizedLength;
                        // The bytes survive, but a stream holding a unit open across the run comes
                        // back missing its middle. Saying nothing hands the consumer one unit
                        // spliced out of the two sides of a hole, and calls it whole.
                        armDiscontinuity(unsynchronizedLength, 0);
                    } else if (unsynchronizedLength > 0) {
                        resyncEvents++;
                        eventDroppedBytes = 0;
                        eventDroppedPackets = 0;
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
        } else {
            droppedBytes += length;
            eventDroppedBytes += length;
        }
        if (!compatibilityMode || frameSink != null) {
            armDiscontinuity(0, 0);
        }
        // Pretend we wrote the extra bytes; we use this offset to determine when to resume decryption
        bytesWritten += length;
    }

    /**
     * Report anything the decoder left out of the output. Without this a recording that lost
     * several megabytes to signal loss looks exactly like a clean one, because decode() still
     * returns true.
     */
    private void logDroppedSummary() {
        if (droppedBytes > 0) {
            logger.warn(String.format(
                    "Recovered from %d loss of synchronization event(s), leaving %,d bytes "
                            + "(%,d packets) out of the output. Use compatibility mode to keep them.",
                    resyncEvents, droppedBytes, droppedPackets)
            );
            if (discontinuityMarkers > 0) {
                logger.info("Wrote {} discontinuity marker(s) where the streams resume after the "
                        + "missing content", discontinuityMarkers);
            }
        } else if (resyncEvents > 0) {
            logger.warn(String.format("Recovered from %d loss of synchronization event(s)", resyncEvents));
        }
    }

    /**
     * Tell each stream to stop decrypting packets until its key changes.
     */
    /**
     * Mark every stream written so far as owing a discontinuity, reported as each one resumes.
     * Compatibility mode writes no marker of its own, but a consumer attached to it still has to
     * hear about the break.
     *
     * The magnitude accumulates rather than resetting when a cut lands before the previous one has
     * drained. A stream can take a whole PSI interval to reappear, so a second cut inside that
     * window would otherwise report only the newer total to a stream that missed both, and the
     * older magnitude would reach nobody.
     */
    private void armDiscontinuity(long undeliveredBytes, long undeliveredPackets) {
        if (pidsAwaitingDiscontinuity.isEmpty()) {
            eventUndeliveredBytes = 0;
            eventUndeliveredPackets = 0;
        }
        eventUndeliveredBytes += undeliveredBytes;
        eventUndeliveredPackets += undeliveredPackets;
        for (int pid = 0; pid < PID_COUNT; pid++) {
            if (writtenPids[pid]) {
                pidsAwaitingDiscontinuity.add(pid);
            }
        }
    }

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

        List<ElementaryStreamInfo> announced = new ArrayList<>();
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
            if (frameSink == null) {
                packet.advanceDataOffset(esInfoLength);
            } else {
                announced.add(new ElementaryStreamInfo(streamPid, streamTypeId,
                        readDescriptors(packet, esInfoLength), programNumber));
            }
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
        publishProgram(announced);
        if (sectionLength >= STREAM_ENTRY_LENGTH) {
            logger.warn("PMT section continues past the end of its packet; ignoring {} remaining bytes",
                    sectionLength);
        }

        return true;
    }

    /**
     * Read a stream's ES_info descriptors, clamped to what the packet actually holds. A PMT can
     * declare a longer ES_info_length than it delivers, either because the section runs into the
     * next packet or because the recording is damaged, and reading past the end of the buffer
     * throws where merely advancing the offset past it does not. The offset ends up in the same
     * place either way, so the parse that follows is unaffected.
     */
    private static byte[] readDescriptors(TransportStreamPacket packet, int esInfoLength) {
        int available = Math.min(esInfoLength, packet.remainingDataLength());
        byte[] descriptors = available > 0 ? packet.readBytesFromData(available) : NO_DESCRIPTORS;
        if (available < esInfoLength) {
            packet.advanceDataOffset(esInfoLength - available);
        }
        return trimToWholeDescriptors(descriptors);
    }

    /**
     * Cut a descriptor loop back to its last complete entry. A consumer walks these by tag and
     * length, so half a descriptor at the end is worse than no descriptor: the walk reads a length
     * whose bytes were never delivered and runs off the end of what it was given.
     */
    private static byte[] trimToWholeDescriptors(byte[] descriptors) {
        int end = 0;
        while (end + 2 <= descriptors.length) {
            int length = 2 + (descriptors[end + 1] & 0xff);
            if (end + length > descriptors.length) {
                break;
            }
            end += length;
        }
        return end == descriptors.length ? descriptors : Arrays.copyOf(descriptors, end);
    }

    /**
     * Hand the consumer every stream announced so far, whenever that set grows. Always the whole
     * list rather than what changed: a consumer can diff it against what it had, and one that
     * cannot add a stream late (Matroska writes its track list before the first frame) needs to see
     * the full picture to decide what to do about the new arrival.
     */
    private void publishProgram(List<ElementaryStreamInfo> announced) {
        if (frameSink == null) {
            return;
        }
        boolean changed = false;
        for (ElementaryStreamInfo stream : announced) {
            // A PID already announced keeps the type it was first given, matching what the decoder
            // does with the streams themselves.
            if (announcedStreams.putIfAbsent(stream.getPid(), stream) == null) {
                assembledPids[stream.getPid()] = TransportStream.StreamType.valueOf(
                        stream.getStreamType()) != TransportStream.StreamType.PRIVATE_DATA;
                changed = true;
            }
        }
        if (changed && !assembler.sinkFailed()) {
            try {
                frameSink.onProgram(new ArrayList<>(announcedStreams.values()));
            } catch (RuntimeException e) {
                assembler.markSinkFailed();
                throw e;
            }
        }
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

        // Nothing on this path is encrypted, so there is no PES header offset to track and no
        // keystream to apply. Handing the packet to the decrypt path anyway would clear the
        // scrambling bits of anything that happens to have them set, count a failure against a key
        // nobody supplied, and alter bytes this path promises to pass through untouched.
        long failuresBefore = frameSink == null ? 0 : stream.getFailedDecryptionCount();
        byte[] packetBytes = decryptionEnabled ? stream.processPacket(packet) : packet.getBytes();
        boolean decryptionFailed = frameSink != null
                && stream.getFailedDecryptionCount() > failuresBefore;
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

        boolean written = writePacketBytes(packet.getPID(), packetBytes);
        if (frameSink != null) {
            if (decryptionPaused) {
                // Compatibility mode writes these bytes out, but they were never decrypted, so
                // they are ciphertext however they look. The consumer hears about the gap when the
                // stream resumes instead. This keeps what the sink sees independent of a mode that
                // exists only to match another implementation's bytes.
                eventUndeliveredBytes += packetBytes.length;
                eventUndeliveredPackets++;
            } else if (decryptionFailed || stillScrambled(packetBytes)) {
                // Two ways to reach here with ciphertext: decryption ran and failed, which clears
                // the scrambling bits before it finds out, or nothing tried at all. The second is
                // the whole of the decrypt free path, where a compatibility mode recording carries
                // tens of thousands of packets nobody could decrypt. TiVo leaves PES headers in the
                // clear, so those parse perfectly and would deliver noise behind a real timestamp.
                reportFailedDecryption(packet.getPID());
            } else if (written) {
                feedSink(packet, packetBytes);
            }
        }

        if (resumeDecryptionAtByte > 0 && resumeDecryptionAtByte <= bytesWritten) {
            logger.warn(String.format("Resuming decryption at 0x%x, bytesWritten = 0x%x",
                    resumeDecryptionAtByte, bytesWritten)
            );
            if (eventDroppedBytes > 0) {
                logger.warn(String.format(
                        "Left %,d bytes (%,d packets) out of the output while decryption was paused",
                        eventDroppedBytes, eventDroppedPackets)
                );
            }
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

    /**
     * Cutting a damaged region out of the output breaks both the continuity counter and the clock
     * of every stream that runs through it, and ISO/IEC 13818-1 expects the packet where that shows
     * to carry the discontinuity indicator. We cannot put it in @resumingPacket: the flag lives in
     * an adaptation field, and a packet that has none cannot grow one without losing payload. So
     * announce the break in a packet of our own, written directly ahead of the stream's first
     * packet back: an adaptation field, no payload, and the indicator set.
     *
     * Its continuity counter is one step behind where the stream resumes, which leaves the break
     * declared on the marker (where the indicator allows it) and the rest of the stream continuous
     * behind it. Giving the marker the counter the stream left off with instead would push the jump
     * onto the packet after it, which has no way to say it is expected.
     *
     * Only for the default mode. Compatibility mode keeps the damaged region instead of cutting it,
     * and adding a packet the TiVo DirectShow filter never wrote would break binary compatibility
     * with it.
     */
    private void writeDiscontinuityMarker(int pid, byte[] resumingPacket) throws IOException {
        if (pidsAwaitingDiscontinuity.isEmpty() || decryptionPaused || resumingPacket.length <= 3
                || !pidsAwaitingDiscontinuity.remove(pid)) {
            // Nothing is reported until the break is over. Compatibility mode reaches here during
            // the pause as well, because it writes those packets, and reporting there would announce
            // the break before anything had been counted and leave the resume point silent.
            return;
        }
        if (!compatibilityMode && decryptionEnabled) {
            // Never on the decrypt free path: a packet of our own in the output would break the one
            // promise that path makes, which is that what it reads is what it writes.
            int continuityCounter = ((resumingPacket[3] & 0x0f) - 1) & 0x0f;
            outputStream.write(buildDiscontinuityPacket(pid, continuityCounter));
            discontinuityMarkers++;
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Marked a discontinuity on PID 0x%04x, which resumes at "
                        + "continuity counter %d", pid, resumingPacket[3] & 0x0f));
            }
        }
        if (frameSink != null && assembledPids[pid]) {
            // The same moment, reported two ways: a marker for whoever reads the bytes later, and a
            // break for whoever is consuming the streams now. The counts are what the consumer did
            // not receive, which is why they are reported in compatibility mode too, where those
            // bytes reach the output undecrypted rather than being left out of it.
            assembler.discard(pid);
            assembler.report(pid, DiscontinuityReason.excision(eventUndeliveredBytes,
                    eventUndeliveredPackets));
        }
    }

    /** Package private so tests build this shape from here rather than copying its layout. */
    static byte[] buildDiscontinuityPacket(int pid, int continuityCounter) {
        byte[] packet = new byte[TransportStream.FRAME_SIZE];
        packet[0] = SYNC_BYTE_VALUE;
        packet[1] = (byte) ((pid >> 8) & 0x1f);
        packet[2] = (byte) (pid & 0xff);
        packet[3] = (byte) (ADAPTATION_FIELD_ONLY | (continuityCounter & 0x0f));
        // With no payload the adaptation field fills the rest of the packet
        packet[4] = (byte) (TransportStream.FRAME_SIZE - 5);
        packet[5] = (byte) DISCONTINUITY_INDICATOR;
        Arrays.fill(packet, 6, packet.length, STUFFING_BYTE);
        return packet;
    }

    /**
     * Bytes written here are the real output. @bytesWritten counts the stream the TiVo DirectShow
     * filter would have produced, which is what the resume and masking offsets are measured
     * against, so a marker of our own is deliberately left out of it.
     */
    private boolean writePacketBytes(int pid, byte[] packetBytes) {
        boolean written;
        try {
            written = !decryptionPaused || compatibilityMode;
            if (written) {
                writeDiscontinuityMarker(pid, packetBytes);
                outputStream.write(packetBytes);
                if (pid != NULL_PACKET_PID) {
                    // Stuffing is not a stream, so it is never owed a discontinuity marker
                    writtenPids[pid] = true;
                }
            } else {
                droppedBytes += packetBytes.length;
                droppedPackets++;
                eventDroppedBytes += packetBytes.length;
                eventDroppedPackets++;
            }
            bytesWritten += packetBytes.length;
        } catch (RuntimeException e) {
            // Sink callbacks run from in here, so this catch sees consumer exceptions as well as
            // write failures. Rethrowing as a bare RuntimeException used to discard the cause and
            // label a consumer's bug as a disk error.
            throw e;
        } catch (Exception e) {
            logger.error("Error writing file: ", e);
            throw new RuntimeException("Error writing the decoded stream", e);
        }
        return written;
    }

    /**
     * Hand one packet's payload to the assembler, and report any break that lands on it first, so a
     * consumer discards what it is holding before the bytes that do not continue it arrive.
     *
     * Only streams a PMT announced are fed. A PID nobody declared has no stream_type, so a consumer
     * cannot tell a muxer what the bytes are, and an unusable stream is worse than an absent one.
     * That also keeps the indicator below honest: a run of arbitrary bytes from a damaged region can
     * satisfy the flag checks, and one corpus recording does exactly that on a PID that never
     * existed.
     *
     * The bytes come from @packetBytes rather than from the packet, whose own buffer still holds
     * ciphertext where the payload was decrypted into a copy.
     */
    /**
     * A packet that needed decrypting and could not be is ciphertext, whatever it looks like, so it
     * is not handed on: assembling it would produce a unit of noise carrying a plausible timestamp,
     * which is worse for a consumer than an announced gap.
     *
     * Reported once where a run of failures starts rather than once per packet. One corpus recording
     * fails on thousands of packets in a row, and thousands of identical callbacks would be noise of
     * a different kind. The run ends when payload for that stream is delivered again.
     */
    /**
     * Whether the packet as written still declares itself scrambled. Read from the output bytes
     * rather than the packet, whose header holds what the scrambling bits said on arrival and does
     * not change when decryption clears them.
     */
    private static boolean stillScrambled(byte[] packetBytes) {
        return packetBytes.length > 3 && (packetBytes[3] & 0xc0) != 0;
    }

    private void reportFailedDecryption(int pid) {
        if (!assembledPids[pid] || pidsFailingDecryption[pid]) {
            return;
        }
        pidsFailingDecryption[pid] = true;
        assembler.discard(pid);
        assembler.report(pid, DiscontinuityReason.decryptionFailed());
    }

    private void feedSink(TransportStreamPacket packet, byte[] packetBytes) {
        int pid = packet.getPID();
        if (!assembledPids[pid]) {
            return;
        }
        pidsFailingDecryption[pid] = false;
        if (packet.declaresDiscontinuity()) {
            if (!packet.isPayloadStart()) {
                // Only a break landing mid unit ruins the unit. When the packet declaring it also
                // begins a new one, whatever was in flight ended where it always would have, and it
                // is whole: throwing it away would lose a picture per break on a recording that is
                // otherwise perfectly good.
                assembler.discard(pid);
            }
            frameSink.onDiscontinuity(pid, DiscontinuityReason.declaredInStream());
        }
        int payloadOffset = packet.getHeader().getLength();
        int payloadLength = packetBytes.length - payloadOffset;
        if (payloadLength <= 0) {
            if (packet.isPayloadStart()) {
                // No payload of its own, but it is still a boundary: an adaptation field can fill a
                // packet and leave nothing behind it. Letting the unit stay open would hand the
                // consumer one unit with the next one's continuations spliced onto it.
                assembler.endUnit(pid);
            }
            return;
        }
        assembler.accept(pid, packetBytes, payloadOffset, payloadLength,
                packet.isPayloadStart(), inputStream.getPosition());
    }
}
