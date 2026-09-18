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
 * Why a stream broke.
 *
 * Four kinds are reported and a fifth deliberately is not. A continuity counter break carrying no
 * indicator is left alone: the damage that matters shows up directly as a jump in the timestamps a
 * consumer already has, and the counters are a weaker proxy for it. One corpus recording carries
 * 72 counter breaks, 70 of them on tables rather than media, against a single excision that
 * removed 39.4 seconds of content.
 *
 * More kinds may be added later. Handle an unrecognised one as "content is missing here", which is
 * what every kind means.
 */
public final class DiscontinuityReason {
    public enum Kind {
        /** A region this decoder left out of the output because it could not be decrypted. */
        EXCISION,
        /** A packet declaring the adaptation field discontinuity_indicator. */
        DECLARED_IN_STREAM,
        /**
         * A payload unit this library could not read, and dropped rather than deliver: one whose
         * PES header will not parse, or one that grew past any plausible size without ending.
         * Damage in the source rather than anything either side did.
         */
        UNREADABLE_PAYLOAD,
        /**
         * Payload that needed decrypting and could not be, usually because its key was missing from
         * the recording. The bytes exist and reach an output file, but they are ciphertext, so they
         * are not delivered here. Reported once where a run of them begins rather than per packet.
         */
        DECRYPTION_FAILED
    }

    private final Kind kind;
    private final long droppedBytes;
    private final long droppedPackets;

    private DiscontinuityReason(Kind kind, long droppedBytes, long droppedPackets) {
        this.kind = kind;
        this.droppedBytes = droppedBytes;
        this.droppedPackets = droppedPackets;
    }

    static DiscontinuityReason excision(long droppedBytes, long droppedPackets) {
        return new DiscontinuityReason(Kind.EXCISION, droppedBytes, droppedPackets);
    }

    static DiscontinuityReason declaredInStream() {
        return new DiscontinuityReason(Kind.DECLARED_IN_STREAM, 0, 0);
    }

    static DiscontinuityReason unreadablePayload(long droppedBytes) {
        return new DiscontinuityReason(Kind.UNREADABLE_PAYLOAD, droppedBytes, 0);
    }

    static DiscontinuityReason decryptionFailed() {
        return new DiscontinuityReason(Kind.DECRYPTION_FAILED, 0, 0);
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * How much the consumer did not receive: for an excision, across every stream caught in it; for
     * an unreadable unit, the bytes of that unit. Zero for a break the stream merely declared.
     *
     * There is no measurement of the gap in time. The PTS jump across it, which the consumer
     * already has, is what decides a muxed timeline, and measuring it here would need the PCR,
     * which nothing in this library parses.
     */
    public long getDroppedBytes() {
        return droppedBytes;
    }

    /** Packets left out by the same excision, on the same terms as {@link #getDroppedBytes()}. */
    public long getDroppedPackets() {
        return droppedPackets;
    }

    @Override
    public String toString() {
        if (kind == Kind.EXCISION) {
            return String.format("DiscontinuityReason{EXCISION, %,d bytes, %,d packets}",
                    droppedBytes, droppedPackets);
        }
        return "DiscontinuityReason{" + kind + "}";
    }
}
