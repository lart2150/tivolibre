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

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Reads a transport stream that is already decrypted, and feeds a {@link FrameSink} from it.
 *
 * This exists because a decrypted recording is what most consumers actually have. A TiVo file is
 * usually decrypted once and then worked on: cut, scanned for commercials, remuxed. Anything
 * downstream of that first step holds a plain {@code .ts}, and without a way in it would have to
 * write a second transport stream parser tolerating the same damaged recordings this one already
 * handles.
 *
 * It is the same demux with the decryption taken out. No TiVo header, no chunks, no MAK, and none
 * of the pause and drop behaviour that a loss of synchronization triggers on an encrypted stream:
 * there is no keystream to lose position against here, so a resynchronization costs alignment and
 * nothing else. That gives this path an invariant the encrypted one does not have: every byte it
 * reads, it writes, unaltered and in order, including the runs it could not align to a packet
 * boundary.
 *
 * The exception is the tail of a source that stops early. Trailing bytes too short to hold a packet
 * header are logged and dropped, and so is a trailing run the decoder was still trying to
 * resynchronize on when the source ran out, which can be longer: it is holding those bytes waiting
 * to see four aligned packets confirm where the stream resumes, and that confirmation never comes.
 * Everything before the point where the source stopped being readable is written.
 *
 * <pre>
 * new TransportStreamReader.Builder()
 *         .input(inputStream)
 *         .frameSink(sink)
 *         .build()
 *         .read();
 * </pre>
 */
public class TransportStreamReader {
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final FrameSink frameSink;

    private TransportStreamReader(Builder builder) {
        this.inputStream = builder.inputStream;
        this.outputStream = builder.outputStream;
        this.frameSink = builder.frameSink;
    }

    /**
     * Read the stream to its end.
     *
     * @return true if the stream was read through; false if it could not be
     */
    public boolean read() {
        TerminatingFrameSink sink = new TerminatingFrameSink(frameSink);
        try (CountingDataInputStream input = new CountingDataInputStream(inputStream)) {
            OutputStream target = outputStream == null ? OutputStream.nullOutputStream() : outputStream;
            // The decoder needs a TuringDecoder to hold, but nothing here is scrambled, and with
            // decryption disabled no packet reaches the point of asking it for a keystream.
            TransportStreamDecoder decoder = new TransportStreamDecoder(
                    new TuringDecoder(new byte[Stream.KEY_LENGTH + 4]), 0, input, target, false,
                    sink, false);
            return decoder.process();
        } catch (java.io.IOException e) {
            return false;
        } finally {
            sink.endIfAbandoned();
        }
    }

    public static class Builder {
        private InputStream inputStream;
        private OutputStream outputStream;
        private FrameSink frameSink;

        /** The transport stream to read. Required. */
        public Builder input(InputStream is) {
            inputStream = is;
            return this;
        }

        /**
         * Where to copy the stream as it is read. Optional, and rarely wanted: what comes out is
         * byte for byte what went in. Useful mainly to prove exactly that.
         */
        public Builder output(OutputStream os) {
            outputStream = os;
            return this;
        }

        /** Where to deliver elementary streams. Required. */
        public Builder frameSink(FrameSink sink) {
            frameSink = sink;
            return this;
        }

        public TransportStreamReader build() {
            if (inputStream == null) {
                throw new IllegalStateException("Cannot read a transport stream without an InputStream");
            }
            if (frameSink == null) {
                throw new IllegalStateException("Cannot read a transport stream without a FrameSink; "
                        + "use TivoDecoder if you only want bytes copied");
            }
            return new TransportStreamReader(this);
        }
    }
}
