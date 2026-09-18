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
 * One PES payload unit: the elementary stream bytes between one payload unit start and the next,
 * with whatever timestamps its PES header carried.
 *
 * The PES header itself is not here. It is read for its timestamps and then dropped, because a PES
 * header written into a container is corruption rather than something a consumer should have to
 * strip back off.
 */
public final class PesPayload {
    /**
     * How a unit ended, which is how much of it there is reason to trust.
     *
     * A source cut at an arbitrary point, which is what a commercial cutter produces, leaves the
     * last unit of every stream short. Writing one of those into a container as though it were
     * whole gives a corrupt trailing frame on the common path rather than the rare one, and nothing
     * downstream can tell without re-deriving what the assembler already knew.
     */
    public enum Completeness {
        /** Ended where the next unit began, having delivered any length it declared. */
        COMPLETE,
        /** Its PES_packet_length declared more bytes than arrived. */
        SHORT_OF_DECLARED_LENGTH,
        /**
         * Still open when the source ended, so whether it is whole cannot be established.
         *
         * This is the ordinary state of the last unit of any stream that declares no length, which
         * in a transport stream means every video stream. It says the boundary that would have
         * proved the unit complete never arrived, not that anything is known to be missing, so it
         * is a poor thing to discard on: doing so costs one good picture per recording.
         */
        ENDED_WITH_STREAM
    }

    private final int pid;
    private final int streamId;
    private final byte[] data;
    private final long pts;
    private final long dts;
    private final boolean hasPts;
    private final boolean hasDts;
    private final long sourceOffset;
    private final Completeness completeness;

    PesPayload(int pid, int streamId, byte[] data, long pts, boolean hasPts, long dts, boolean hasDts,
               long sourceOffset, Completeness completeness) {
        this.pid = pid;
        this.streamId = streamId;
        this.data = data;
        this.pts = pts;
        this.hasPts = hasPts;
        this.dts = dts;
        this.hasDts = hasDts;
        this.sourceOffset = sourceOffset;
        this.completeness = completeness;
    }

    /**
     * Whether this unit is known to be all there.
     *
     * False is not the same as damaged, and the difference matters. Only
     * {@link Completeness#SHORT_OF_DECLARED_LENGTH} says bytes are actually missing; the last unit
     * of every video stream reports {@link Completeness#ENDED_WITH_STREAM} simply because nothing
     * followed it to mark where it ended. Treat this as a reason to look at
     * {@link #getCompleteness()}, or to tell a codec parser to expect a short tail, rather than as
     * a reason to drop a unit.
     */
    public boolean isComplete() {
        return completeness == Completeness.COMPLETE;
    }

    /** Why a unit is incomplete, for a consumer that wants to say so rather than only act on it. */
    public Completeness getCompleteness() {
        return completeness;
    }

    public int getPid() {
        return pid;
    }

    /** The PES stream_id: 0xe0 and up for video, 0xc0 and up for MPEG audio, 0xbd for private. */
    public int getStreamId() {
        return streamId;
    }

    /**
     * The elementary stream bytes. The caller owns this array outright: a fresh one is allocated
     * for every payload unit and this library keeps no reference to it, so it can be queued,
     * reordered or held without copying.
     */
    public byte[] getData() {
        return data;
    }

    /**
     * The presentation timestamp, a 33 bit value at 90 kHz, exactly as the stream carries it. Not
     * rebased to zero and not unwrapped: a recording commonly starts tens of thousands of seconds
     * in, and audio and video commonly start at different times. Only meaningful when
     * {@link #hasPts()} is true.
     */
    public long getPts() {
        return pts;
    }

    /** The decode timestamp, on the same terms as {@link #getPts()}. */
    public long getDts() {
        return dts;
    }

    /**
     * Whether this unit actually carried a PTS. Many do not, and none is invented for those: a
     * guess here becomes a sync error the consumer cannot trace back.
     */
    public boolean hasPts() {
        return hasPts;
    }

    public boolean hasDts() {
        return hasDts;
    }

    /**
     * Roughly where this unit began in the source, for diagnostics. It is the decoder's read
     * position when the unit's first packet was consumed, which can run ahead of the packet itself
     * by whatever was buffered, so treat it as a landmark rather than an address.
     */
    public long getSourceOffset() {
        return sourceOffset;
    }

    @Override
    public String toString() {
        return String.format("PesPayload{pid=0x%04x, streamId=0x%02x, %d bytes, pts=%s, dts=%s}",
                pid, streamId, data.length, hasPts ? Long.toString(pts) : "absent",
                hasDts ? Long.toString(dts) : "absent");
    }
}
