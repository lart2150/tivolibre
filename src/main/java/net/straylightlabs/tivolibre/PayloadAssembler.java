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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Collects transport packets back into PES payload units and hands each one to a {@link FrameSink}.
 *
 * A unit runs from one payload unit start to the next. PES_packet_length cannot be relied on to
 * end it, because it is legally zero for video in a transport stream, so the next start is the
 * only boundary there is and the last unit of a stream has to be flushed at EOF.
 *
 * The whole unit is buffered before its header is read. That costs nothing and it handles the
 * awkward case for free: the optional PES header can run to 264 bytes while a packet carries at
 * most 184, so a header can straddle a packet boundary and cannot always be parsed where it
 * starts.
 *
 * Three things are deliberately dropped rather than delivered. Whatever precedes the first payload
 * unit start, because a recording begins mid-unit and that fragment has no header. Whatever is
 * open when a stream breaks, because the bytes on the far side do not continue it. And any unit
 * whose header will not parse. A unit that parses to zero elementary stream bytes is delivered,
 * not suppressed: a consumer that does not want it can say so, but a library that silently
 * swallows things is harder to debug than one that does not.
 */
class PayloadAssembler {
    private final FrameSink sink;
    private final Map<Integer, PendingUnit> pending;
    /**
     * Set once a consumer has thrown. Nothing is handed to it after that: a sink that failed part
     * way through is in an unknown state, and the flush at the end of a decode would otherwise call
     * straight back into it while its exception is still on its way out.
     */
    private boolean sinkFailed;

    /**
     * Where a unit is abandoned rather than grown further. Nothing legitimate approaches this: a
     * unit is one picture at most. It exists so that a stream whose payload unit starts are lost
     * to corruption cannot accumulate a recording's worth of bytes in memory.
     */
    private static final int MAXIMUM_UNIT_LENGTH = 4 * 1024 * 1024;
    private static final int INITIAL_UNIT_CAPACITY = 8192;

    private final static Logger logger = LoggerFactory.getLogger(PayloadAssembler.class);

    PayloadAssembler(FrameSink sink) {
        this.sink = sink;
        this.pending = new HashMap<>();
    }

    /**
     * Take one packet's payload for @pid. @payloadStart says whether this packet begins a new unit.
     * A packet with no payload reaches {@link #endUnit} instead, since it can still be a boundary.
     */
    void accept(int pid, byte[] packetBytes, int payloadOffset, int payloadLength, boolean payloadStart,
                long sourceOffset) {
        if (payloadLength <= 0) {
            return;
        }
        PendingUnit unit = pending.get(pid);
        if (payloadStart) {
            if (unit != null) {
                emit(pid, unit, false);
            } else {
                unit = new PendingUnit(pid);
                pending.put(pid, unit);
            }
            unit.restart(sourceOffset);
        } else if (unit == null || !unit.isOpen()) {
            // Either the head of the recording, which begins mid-unit, or the far side of a break.
            // Both are fragments with no header of their own.
            return;
        }
        unit.append(packetBytes, payloadOffset, payloadLength);
    }

    /**
     * Close the unit held for @pid and deliver it, without starting another. For a payload unit
     * start that carries no payload of its own: it is still the boundary the open unit ends at.
     */
    void endUnit(int pid) {
        PendingUnit unit = pending.get(pid);
        if (unit != null && unit.isOpen()) {
            emit(pid, unit, false);
        }
    }

    /** Throw away whatever is held for @pid, without delivering it. */
    void discard(int pid) {
        PendingUnit unit = pending.get(pid);
        if (unit != null && unit.isOpen()) {
            logger.debug("Discarding {} buffered bytes on PID 0x{} at a discontinuity",
                    unit.size, Integer.toHexString(pid));
            unit.close();
        }
    }

    /** Whether a consumer has thrown, in which case it is no longer being called. */
    boolean sinkFailed() {
        return sinkFailed;
    }

    /** Note that a consumer threw somewhere this class did not call it. */
    void markSinkFailed() {
        sinkFailed = true;
    }

    /**
     * Report a break to the consumer. Routed through here rather than called directly so that one
     * place knows whether the consumer is still able to receive anything.
     */
    void report(int pid, DiscontinuityReason reason) {
        if (sinkFailed) {
            return;
        }
        try {
            sink.onDiscontinuity(pid, reason);
        } catch (RuntimeException e) {
            sinkFailed = true;
            throw e;
        }
    }

    /** Deliver the last unit of every stream. Without this each stream loses its final unit. */
    void flush() {
        for (Map.Entry<Integer, PendingUnit> entry : pending.entrySet()) {
            PendingUnit unit = entry.getValue();
            if (unit.isOpen()) {
                emit(entry.getKey(), unit, true);
            }
        }
    }

    private void emit(int pid, PendingUnit unit, boolean endOfStream) {
        if (!unit.isOpen()) {
            return;
        }
        int size = unit.size;
        unit.close();
        PesPacketHeader header = PesPacketHeader.parse(unit.buffer, 0, size);
        if (header == null) {
            // Saying nothing here would leave the consumer splicing the units either side of this
            // one together as though they were adjacent.
            logger.debug("Dropping {} bytes on PID 0x{}: no PES header to read them behind",
                    size, Integer.toHexString(pid));
            report(pid, DiscontinuityReason.unreadablePayload(size));
            return;
        }
        if (sinkFailed) {
            return;
        }
        byte[] data = Arrays.copyOfRange(unit.buffer, header.getHeaderLength(), endOf(header, size));
        try {
            sink.onPesPayload(new PesPayload(pid, header.getStreamId(), data, header.getPts(),
                    header.hasPts(), header.getDts(), header.hasDts(), unit.sourceOffset,
                    completenessOf(header, size, endOfStream)));
        } catch (RuntimeException e) {
            sinkFailed = true;
            throw e;
        }
    }

    /**
     * How much of a unit is there.
     *
     * A declared length that was not reached is the more specific answer and wins over the end of
     * the stream, because it says the unit is short whether or not anything followed it: a source
     * cut mid recording leaves that trace part way through as well as at the end.
     */
    private static PesPayload.Completeness completenessOf(PesPacketHeader header, int collected,
                                                          boolean endOfStream) {
        if (header.getPacketLength() > 0) {
            // A declared length settles it either way. A unit that delivered every byte it promised
            // is whole whether or not the recording ended behind it, and audio declares a length, so
            // treating the end of the stream as the answer here would mark the last unit of every
            // recording truncated and cost a consumer a good frame group.
            return collected < PesPacketHeader.MINIMUM_LENGTH + header.getPacketLength()
                    ? PesPayload.Completeness.SHORT_OF_DECLARED_LENGTH
                    : PesPayload.Completeness.COMPLETE;
        }
        return endOfStream ? PesPayload.Completeness.ENDED_WITH_STREAM
                : PesPayload.Completeness.COMPLETE;
    }

    /**
     * Where the elementary stream bytes stop.
     *
     * A unit that declares its PES_packet_length ends there, whatever else was collected behind it:
     * the last packet of a unit can be padded, and that padding is not content. A length of zero
     * declares nothing, which is legal and usual for video in a transport stream, and then the unit
     * runs to wherever the next one started. A declared length longer than what arrived means the
     * unit was cut short, so what arrived is all there is.
     */
    private static int endOf(PesPacketHeader header, int collected) {
        if (header.getPacketLength() == 0) {
            return collected;
        }
        return Math.min(collected, PesPacketHeader.MINIMUM_LENGTH + header.getPacketLength());
    }

    /**
     * One stream's unit in progress. The buffer outlives the unit and is reused, so a stream
     * allocates once and then grows only if some later unit is bigger than every unit before it.
     * The array handed to the consumer is always a fresh copy of exactly the right size.
     */
    private class PendingUnit {
        private final int pid;
        private byte[] buffer = new byte[INITIAL_UNIT_CAPACITY];
        private int size;
        private boolean open;
        private long sourceOffset;

        PendingUnit(int pid) {
            this.pid = pid;
        }

        void restart(long sourceOffset) {
            this.size = 0;
            this.open = true;
            this.sourceOffset = sourceOffset;
        }

        void close() {
            open = false;
            size = 0;
        }

        boolean isOpen() {
            return open;
        }

        void append(byte[] source, int offset, int length) {
            if (size + length > MAXIMUM_UNIT_LENGTH) {
                logger.warn("Abandoning a payload unit on PID 0x{} that grew past {} bytes without "
                        + "ending", Integer.toHexString(pid), MAXIMUM_UNIT_LENGTH);
                int abandoned = size;
                close();
                report(pid, DiscontinuityReason.unreadablePayload(abandoned));
                return;
            }
            if (size + length > buffer.length) {
                int capacity = Math.max(buffer.length * 2, size + length);
                buffer = Arrays.copyOf(buffer, Math.min(capacity, MAXIMUM_UNIT_LENGTH));
            }
            System.arraycopy(source, offset, buffer, size, length);
            size += length;
        }
    }
}
