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
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

abstract class StreamDecoder {
    protected final TuringDecoder turingDecoder;
    protected final int mpegOffset;
    protected final CountingDataInputStream inputStream;
    protected final OutputStream outputStream;
    protected final Map<Integer, TransportStream> streams;

    protected long packetCounter;
    protected PatData patData;

    /** Bytes per program entry in the PAT: program_number (2) + program_map_PID (2) */
    private static final int PROGRAM_ENTRY_LENGTH = 4;
    /**
     * PAT fields we read before any length in the packet can be trusted: table_id (1),
     * section_length (2), transport_stream_id (2), version (1), section_number (1) and
     * last_section_number (1).
     */
    private static final int PAT_PROLOGUE_LENGTH = 8;
    private static final int RESERVED_PAT_PID = 0x0000;
    private static final int RESERVED_NULL_PID = 0x1fff;
    /** The five PAT fields counted against section_length, plus its CRC */
    private static final int PAT_SECTION_MINIMUM = 5 + 4;

    private final static Logger logger = LoggerFactory.getLogger(StreamDecoder.class);

    protected StreamDecoder(TuringDecoder decoder, int mpegOffset, CountingDataInputStream inputStream,
                            OutputStream outputStream) {
        this.turingDecoder = decoder;
        this.mpegOffset = mpegOffset;
        this.inputStream = inputStream;
        this.outputStream = outputStream;

        packetCounter = 0;
        patData = new PatData();
        streams = new HashMap<>();
        initPatStream();
    }

    private void initPatStream() {
        TransportStream stream = new TransportStream(turingDecoder);
        streams.put(0, stream);
    }

    protected void advanceToMpegOffset() throws IOException {
        int bytesToSkip = (int) (mpegOffset - inputStream.getPosition());
        if (bytesToSkip < 0) {
            logger.error("Error: Stream advanced past MPEG data (MPEG at {}, current position = {})",
                    mpegOffset, inputStream.getPosition()
            );
        }
        inputStream.skipBytes(bytesToSkip);
    }

    abstract boolean process();

    protected boolean processPatPacket(TransportStreamPacket packet) {
        if (!packet.isPayloadStart()) {
            // The tail of a section split across packets. We have no reassembly, and parsing it as
            // a new table would register nonsense and could discard the PIDs we already have.
            logger.debug("Skipping PAT continuation packet");
            return true;
        }

        // A recording cut off mid-packet can leave less than the fixed fields below, and reading
        // past the end of the buffer throws an IndexOutOfBoundsException that nothing above us
        // catches. Check for them before trusting any length inside the packet.
        int prologueLength = PAT_PROLOGUE_LENGTH + (packet.isPayloadStart() ? 1 : 0);
        if (packet.remainingDataLength() < prologueLength) {
            logger.warn("PAT packet holds only {} bytes of data, need {}",
                    packet.remainingDataLength(), prologueLength);
            return false;
        }

        if (packet.isPayloadStart()) {
            // Advance past pointer field
            packet.advanceDataOffset(1);
        }

        int tableId = packet.readUnsignedByteFromData();
        if (tableId != 0) {
            logger.error(String.format("PAT Table ID must be 0x00 (found 0x%02x)", tableId));
            return false;
        }

        int patField = packet.readUnsignedShortFromData();
        int sectionLength = patField & 0x0fff;

        if ((patField & 0xC000) != 0x8000) {
            logger.error(String.format("Failed to validate PAT Misc field: 0x%04x", patField));
            return false;
        }
        if ((patField & 0x0C00) != 0x0000) {
            logger.error("Failed to validate PAT MBZ of section length");
            return false;
        }
        if (sectionLength < PAT_SECTION_MINIMUM) {
            logger.error("PAT section length is too short: {}", sectionLength);
            return false;
        }

        // Stream ID
        packet.readUnsignedShortFromData();
        sectionLength -= 2;

        patData.setVersionNumber(packet.readUnsignedByteFromData() & 0x3E);
        sectionLength--;
        int sectionNumber = packet.readUnsignedByteFromData();
        patData.setSectionNumber(sectionNumber);
        sectionLength--;
        patData.setLastSectionNumber(packet.readUnsignedByteFromData());
        sectionLength--;

        // Collect this section's PIDs separately, then publish them below. The PAT repeats
        // throughout a recording and can be revised mid-stream, so accumulating PIDs forever means
        // one stray packet that happens to parse as a PAT poisons a PID for the rest of the file.
        Set<Integer> sectionPids = new HashSet<>();

        sectionLength -= 4; // Ignore the CRC

        // A section can be longer than the packet that carries it; only parse the part we actually have.
        // Each program entry is four bytes long.
        while (sectionLength >= PROGRAM_ENTRY_LENGTH && packet.remainingDataLength() >= PROGRAM_ENTRY_LENGTH) {
            // Program number
            int programNumber = packet.readUnsignedShortFromData();
            sectionLength -= 2;

            patField = packet.readUnsignedShortFromData();
            sectionLength -= 2;
            int programMapPid = patField & 0x1fff;

            if (programNumber == 0) {
                // This entry points at the Network Information Table, not a Program Map Table
                logger.debug("Skipping NIT PID 0x{} in PAT", Integer.toHexString(programMapPid));
                continue;
            }
            if (programMapPid == RESERVED_PAT_PID || programMapPid == RESERVED_NULL_PID) {
                // A PMT can live on neither of these. Accepting 0x1fff in particular would make
                // every null packet get parsed as a table and copied to the output.
                logger.warn("Ignoring implausible PMT PID 0x{} in PAT",
                        Integer.toHexString(programMapPid));
                continue;
            }

            sectionPids.add(programMapPid);

            // Create a stream for this PID unless one already exists
            if (!streams.containsKey(programMapPid)) {
                if (logger.isInfoEnabled()) {
                    logger.info(String.format("Creating a new stream for PMT PID 0x%04x", programMapPid));
                }
                TransportStream stream = new TransportStream(turingDecoder);
                streams.put(programMapPid, stream);
            }
        }
        if (sectionLength >= PROGRAM_ENTRY_LENGTH) {
            logger.warn("PAT section continues past the end of its packet; ignoring {} remaining bytes", sectionLength);
        }

        if (!sectionPids.isEmpty()) {
            // The first section replaces the table; later sections of a multi-section PAT extend
            // it. A section we couldn't read anything from leaves the previous table alone.
            patData.setProgramMapPids(sectionPids, sectionNumber == 0);
        }

        return true;
    }

    protected static class PatData {
        private int versionNumber;
        private int currentNextIndicator;
        private int sectionNumber;
        private int lastSectionNumber;
        private final Set<Integer> programMapPids = new HashSet<>();

        @SuppressWarnings("unused")
        public int getVersionNumber() {
            return versionNumber;
        }

        public void setVersionNumber(int versionNumber) {
            this.versionNumber = versionNumber;
        }

        @SuppressWarnings("unused")
        public int getCurrentNextIndicator() {
            return currentNextIndicator;
        }

        @SuppressWarnings("unused")
        public void setCurrentNextIndicator(int currentNextIndicator) {
            this.currentNextIndicator = currentNextIndicator;
        }

        @SuppressWarnings("unused")
        public int getSectionNumber() {
            return sectionNumber;
        }

        public void setSectionNumber(int sectionNumber) {
            this.sectionNumber = sectionNumber;
        }

        @SuppressWarnings("unused")
        public int getLastSectionNumber() {
            return lastSectionNumber;
        }

        public void setLastSectionNumber(int lastSectionNumber) {
            this.lastSectionNumber = lastSectionNumber;
        }

        /**
         * A TiVo recording normally holds a single program, but the PAT is allowed to list several. Track all of
         * them so we don't mistake a second program's PMT for an elementary stream (or vice versa).
         */
        public void setProgramMapPids(Set<Integer> pids, boolean replaceExisting) {
            if (replaceExisting) {
                programMapPids.clear();
            }
            programMapPids.addAll(pids);
        }

        public boolean isProgramMapPid(int pid) {
            return programMapPids.contains(pid);
        }
    }
}
