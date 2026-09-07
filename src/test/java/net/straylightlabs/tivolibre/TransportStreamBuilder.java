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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles synthetic TiVo transport stream packets so we can exercise the decoder against stream
 * layouts we don't have sample recordings for.
 */
class TransportStreamBuilder {
    private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

    static final int TIVO_STREAM_TYPE = 0x97;

    /** stream_type / elementary PID pair for a PMT entry, optionally with ES descriptors. */
    static class StreamEntry {
        final int streamType;
        final int pid;
        final byte[] descriptors;

        StreamEntry(int streamType, int pid, byte... descriptors) {
            this.streamType = streamType;
            this.pid = pid;
            this.descriptors = descriptors;
        }
    }

    byte[] toByteArray() {
        return stream.toByteArray();
    }

    int packetCount() {
        return stream.size() / TransportStream.FRAME_SIZE;
    }

    TransportStreamBuilder pat(int programNumber, int programMapPid) {
        return pat(new int[]{programNumber}, new int[]{programMapPid});
    }

    /**
     * A PAT listing several entries. A program_number of 0 marks the Network Information Table
     * rather than a Program Map Table.
     */
    TransportStreamBuilder pat(int[] programNumbers, int[] programMapPids) {
        List<Byte> body = new ArrayList<>();
        for (int i = 0; i < programNumbers.length; i++) {
            addShort(body, programNumbers[i]);
            addShort(body, 0xe000 | programMapPids[i]);
        }
        return section(0x0000, 0x00, 0x0001, 0, toArray(body));
    }

    TransportStreamBuilder pmt(int pmtPid, int pcrPid, byte[] programDescriptors, StreamEntry... entries) {
        return pmt(pmtPid, pcrPid, programDescriptors, 0, entries);
    }

    /**
     * A PMT whose declared section_length runs @overstate bytes past what the packet actually holds, as
     * happens when a section is long enough to be split across packets.
     */
    TransportStreamBuilder truncatedPmt(int pmtPid, int pcrPid, int overstate, StreamEntry... entries) {
        return pmt(pmtPid, pcrPid, new byte[0], overstate, entries);
    }

    /** A PSI table on a PID we have no use for, such as a CAT or a DVB service description table. */
    TransportStreamBuilder unusedTable(int pid) {
        return section(pid, 0x42, 0x0001, 0, new byte[]{0x00, 0x00, 0x00, 0x00});
    }

    private TransportStreamBuilder pmt(int pmtPid, int pcrPid, byte[] programDescriptors, int overstate,
                                       StreamEntry... entries) {
        List<Byte> body = new ArrayList<>();
        addShort(body, 0xe000 | pcrPid);
        addShort(body, 0xf000 | programDescriptors.length);
        for (byte b : programDescriptors) {
            body.add(b);
        }
        for (StreamEntry entry : entries) {
            body.add((byte) entry.streamType);
            addShort(body, 0xe000 | entry.pid);
            addShort(body, 0xf000 | entry.descriptors.length);
            for (byte b : entry.descriptors) {
                body.add(b);
            }
        }
        return section(pmtPid, 0x02, 0x0001, overstate, toArray(body));
    }

    /**
     * A TiVo private data packet: the "TiVo" signature, the 0x8103 validator, three unknown bytes, the
     * number of key bytes that follow, and then one 20-byte entry per elementary stream.
     */
    TransportStreamBuilder tivoPrivateData(int pid, int[] streamPids, int[] streamIds, byte[] key) {
        return tivoPrivateData(pid, streamPids, streamIds, key, 0);
    }

    /**
     * TiVo private data whose stream_bytes field claims @overstate more bytes of key entries than
     * are actually present, as a section split across packets would.
     */
    TransportStreamBuilder truncatedTivoPrivateData(int pid, int[] streamPids, int[] streamIds,
                                                    byte[] key, int overstate) {
        return tivoPrivateData(pid, streamPids, streamIds, key, overstate);
    }

    private TransportStreamBuilder tivoPrivateData(int pid, int[] streamPids, int[] streamIds,
                                                   byte[] key, int overstate) {
        List<Byte> payload = new ArrayList<>();
        for (byte b : new byte[]{'T', 'i', 'V', 'o'}) {
            payload.add(b);
        }
        addShort(payload, 0x8103);
        payload.add((byte) 0x7d);
        payload.add((byte) 0x00);
        payload.add((byte) 0x00);
        payload.add((byte) (streamPids.length * 20 + overstate));
        for (int i = 0; i < streamPids.length; i++) {
            addShort(payload, streamPids[i]);
            payload.add((byte) streamIds[i]);
            payload.add((byte) 0x10);
            for (byte b : key) {
                payload.add(b);
            }
        }
        return packet(pid, true, false, toArray(payload));
    }

    /** A scrambled elementary stream packet carrying a video PES header followed by filler. */
    TransportStreamBuilder scrambledPesPacket(int pid) {
        List<Byte> payload = new ArrayList<>();
        for (byte b : new byte[]{0x00, 0x00, 0x01, (byte) 0xe0, 0x00, 0x00, (byte) 0x80, 0x00, 0x00}) {
            payload.add(b);
        }
        // An access unit delimiter start code ends the header scan; everything after it is payload.
        for (byte b : new byte[]{0x00, 0x00, 0x01, 0x09, (byte) 0xf0}) {
            payload.add(b);
        }
        return packet(pid, true, true, toArray(payload));
    }

    TransportStreamBuilder nullPacket() {
        return packet(0x1fff, false, false, new byte[0]);
    }

    /**
     * A packet whose adaptation_field_length runs past the end of the frame. tivodecode-ng crashes
     * on these (its issue #4, an over-the-air recording); we clamp and copy the packet through.
     */
    TransportStreamBuilder packetWithOversizedAdaptationField(int pid) {
        byte[] frame = new byte[TransportStream.FRAME_SIZE];
        frame[0] = 0x47;
        frame[1] = (byte) ((pid >> 8) & 0x1f);
        frame[2] = (byte) (pid & 0xff);
        frame[3] = 0x30;         // adaptation field and payload both present
        frame[4] = (byte) 0xff;  // 255 bytes of adaptation field in a 184 byte payload
        frame[5] = 0x00;
        stream.write(frame, 0, frame.length);
        return this;
    }

    /** A packet on @pid carrying a PSI section with an unexpected table_id. */
    TransportStreamBuilder wrongTableId(int pid, int tableId) {
        return section(pid, tableId, 0x0001, 0, new byte[]{0x00, 0x00, 0x00, 0x00});
    }

    /** An unscrambled packet whose payload begins with the TiVo private data signature. */
    TransportStreamBuilder tivoSignatureDecoy(int pid) {
        List<Byte> payload = new ArrayList<>();
        for (byte b : new byte[]{'T', 'i', 'V', 'o'}) {
            payload.add(b);
        }
        addShort(payload, 0x8103);
        return packet(pid, true, false, toArray(payload));
    }

    /** An arbitrary packet, for cases the helpers above don't cover. */
    TransportStreamBuilder rawPacket(int pid, boolean payloadStart, boolean scrambled, byte[] payload) {
        return packet(pid, payloadStart, scrambled, payload);
    }

    /**
     * A final packet cut short at @frameLength bytes, declaring an adaptation field longer than
     * what is left. A recording truncated mid-packet gives the decoder a buffer smaller than a
     * frame, so clamping the header to FRAME_SIZE is not enough to keep the payload length
     * positive. Must be the last packet added.
     */
    TransportStreamBuilder truncatedTrailingPacket(int pid, int frameLength, int adaptationFieldLength) {
        byte[] frame = new byte[frameLength];
        frame[0] = 0x47;
        frame[1] = (byte) ((pid >> 8) & 0x1f);
        frame[2] = (byte) (pid & 0xff);
        frame[3] = 0x30; // adaptation field and payload both present
        frame[4] = (byte) adaptationFieldLength;
        stream.write(frame, 0, frame.length);
        return this;
    }

    /**
     * A final packet cut short at @frameLength bytes, carrying @payload and no adaptation field.
     * Must be the last packet added.
     */
    TransportStreamBuilder truncatedTrailingPacket(int pid, int frameLength, byte[] payload) {
        byte[] frame = new byte[frameLength];
        frame[0] = 0x47;
        frame[1] = (byte) (0x40 | ((pid >> 8) & 0x1f)); // payload unit start
        frame[2] = (byte) (pid & 0xff);
        frame[3] = 0x10; // payload present, no adaptation field
        System.arraycopy(payload, 0, frame, 4, Math.min(payload.length, frameLength - 4));
        stream.write(frame, 0, frame.length);
        return this;
    }

    /** The six byte TiVo private data signature on its own, with none of the fields that follow. */
    static byte[] tivoSignature() {
        return new byte[]{'T', 'i', 'V', 'o', (byte) 0x81, 0x03};
    }

    /**
     * A scrambled packet whose PES header runs to the last three bytes of the payload, leaving a
     * start code prefix straddling the boundary into the next packet.
     */
    TransportStreamBuilder packetEndingOnStartCodePrefix(int pid) {
        byte[] payload = new byte[TransportStream.FRAME_SIZE - 4];
        java.util.Arrays.fill(payload, (byte) 0xff);
        // PES header declaring 171 bytes of header data, so it ends at offset 180
        byte[] head = {0x00, 0x00, 0x01, (byte) 0xe0, 0x00, 0x00, (byte) 0x80, 0x00, (byte) 171};
        System.arraycopy(head, 0, payload, 0, head.length);
        payload[181] = 0x00;
        payload[182] = 0x00;
        payload[183] = 0x01;
        return packet(pid, true, true, payload);
    }

    /** A scrambled continuation packet whose first byte completes a straddled start code. */
    TransportStreamBuilder packetStartingWithStartCodeValue(int pid, int startCodeValue) {
        byte[] payload = new byte[TransportStream.FRAME_SIZE - 4];
        java.util.Arrays.fill(payload, (byte) 0xff);
        payload[0] = (byte) startCodeValue;
        return packet(pid, false, true, payload);
    }

    /** A continuation packet, with the payload unit start indicator clear, carrying @payload. */
    TransportStreamBuilder continuationPacket(int pid, byte[] payload) {
        return packet(pid, false, false, payload);
    }

    /** Appends @length raw bytes that do not form a whole packet. Must be last. */
    TransportStreamBuilder trailingBytes(int pid, int length, boolean adaptationField) {
        byte[] frag = new byte[length];
        frag[0] = 0x47;
        if (length > 1) {
            frag[1] = (byte) ((pid >> 8) & 0x1f);
        }
        if (length > 2) {
            frag[2] = (byte) (pid & 0xff);
        }
        if (length > 3) {
            frag[3] = (byte) (adaptationField ? 0x30 : 0x10);
        }
        if (length > 4) {
            frag[4] = (byte) 200; // longer than what is left
        }
        stream.write(frag, 0, frag.length);
        return this;
    }

    /** Total bytes written, which packetCount() cannot express once a packet is truncated. */
    int byteCount() {
        return stream.size();
    }

    /** Wraps @body in a PSI section (pointer field, table header, CRC) and emits it as one packet. */
    private TransportStreamBuilder section(int pid, int tableId, int tableIdExtension, int overstate,
                                           byte[] body) {
        List<Byte> section = new ArrayList<>();
        section.add((byte) tableId);
        int sectionLength = 5 + body.length + 4 + overstate; // header fields + body + CRC
        addShort(section, 0xb000 | sectionLength);
        addShort(section, tableIdExtension);
        section.add((byte) 0xc1); // version 0, current_next_indicator
        section.add((byte) 0x00); // section_number
        section.add((byte) 0x00); // last_section_number
        for (byte b : body) {
            section.add(b);
        }
        addInt(section, 0xdeadbeef); // CRC; nothing verifies it

        List<Byte> payload = new ArrayList<>();
        payload.add((byte) 0x00); // pointer field
        payload.addAll(section);
        return packet(pid, true, false, toArray(payload));
    }

    private TransportStreamBuilder packet(int pid, boolean payloadStart, boolean scrambled, byte[] payload) {
        byte[] frame = new byte[TransportStream.FRAME_SIZE];
        frame[0] = 0x47;
        frame[1] = (byte) (((payloadStart ? 1 : 0) << 6) | ((pid >> 8) & 0x1f));
        frame[2] = (byte) (pid & 0xff);
        frame[3] = (byte) ((scrambled ? 0xc0 : 0x00) | 0x10); // scrambling control, payload present
        System.arraycopy(payload, 0, frame, 4, Math.min(payload.length, frame.length - 4));
        stream.write(frame, 0, frame.length);
        return this;
    }

    private static void addShort(List<Byte> out, int val) {
        out.add((byte) ((val >> 8) & 0xff));
        out.add((byte) (val & 0xff));
    }

    private static void addInt(List<Byte> out, int val) {
        addShort(out, (val >> 16) & 0xffff);
        addShort(out, val & 0xffff);
    }

    private static byte[] toArray(List<Byte> in) {
        byte[] out = new byte[in.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = in.get(i);
        }
        return out;
    }
}
