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

import java.util.List;

/**
 * Receives demuxed, decrypted elementary stream payload units, as an alternative to writing
 * container bytes to an OutputStream. Attach one through {@link TivoDecoder.Builder#frameSink} to
 * read a TiVo recording, or through {@link TransportStreamReader} to read a transport stream that
 * was decrypted earlier.
 *
 * The point of this interface is that the demux has already happened. TivoLibre walks every
 * packet, parses the PMT and knows which PID is which, so a consumer that wants elementary streams
 * does not need a second transport stream parser tolerating the same corrupt recordings.
 *
 * What this side does not do, deliberately: it does not parse codecs, it does not split a payload
 * unit into codec frames, and it does not repair timestamps. Those need a muxer's knowledge of
 * where the bytes are going.
 *
 * Calls arrive on the thread driving the decode, in stream order, and the decoder does not
 * continue until each returns.
 */
public interface FrameSink {
    /**
     * What the recording says about itself: title, description, air dates and the rest, out of the
     * metadata its own header carries.
     *
     * Called once, before anything else, and only for a source that has any. A transport stream
     * decrypted earlier carries no metadata, so this is never called for one and no empty stand-in
     * is invented.
     *
     * A default no-op, so a consumer that only wants streams does not have to say so.
     */
    default void onMetadata(TivoMetadata metadata) {
    }

    /**
     * Every elementary stream known so far, as the PMT declares them.
     *
     * Always the complete list, never a delta. This can be called more than once: a PMT repeats
     * through a recording and can name a PID that first appears part way in. An existing PID's
     * type is never revised, so entries are added but never changed.
     */
    void onProgram(List<ElementaryStreamInfo> streams);

    /**
     * One PES payload unit, decrypted, elementary stream bytes only.
     *
     * Not one codec frame. A video payload unit holds one picture, but an AC-3 payload unit
     * routinely holds several syncframes sharing a single PTS, and splitting those is the
     * consumer's job.
     */
    void onPesPayload(PesPayload payload);

    /**
     * A break in a stream, reported before any payload of that stream that follows it. Whatever
     * partial unit the consumer is holding for this PID should be discarded: the bytes on the far
     * side of the break do not continue it.
     */
    void onDiscontinuity(int pid, DiscontinuityReason reason);

    /**
     * The decode finished. Nothing else arrives after this, and a consumer writing a file should
     * not finalize it until the result says the decode was usable.
     */
    void onEnd(DecodeResult result);
}
