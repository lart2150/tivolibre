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

import org.w3c.dom.Document;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Decodes a TiVo file to a standard MPEG file.
 */
public class TivoDecoder {
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final String mak;
    private final boolean compatibilityMode;
    private TivoStream tivoStream;
    private FrameSink frameSink;

    /** Kept in step with the version in build.gradle by VersionTest. */
    public final static String VERSION = "0.9.0-alpha1";

    private TivoDecoder(InputStream inputStream, OutputStream outputStream, String mak,
                        boolean compatibilityMode, FrameSink frameSink) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.mak = mak;
        this.compatibilityMode = compatibilityMode;
        this.frameSink = frameSink;
    }

    /**
     * DEPRECATED: Use the TivoDecoder.Builder class to create TivoDecoder objects.
     *
     * @param inputStream the input stream representing an encrypted .TiVo file
     * @param outputStream the output stream to write the decrypted .TiVo file to
     * @param mak the media access key to use when decrypting @inputStream
     */
    @Deprecated
    public TivoDecoder(InputStream inputStream, OutputStream outputStream, String mak) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.mak = mak;
        this.compatibilityMode = false;
    }

    /**
     * Decode the specified @inputStream with @mak, printing the results to @outputStream.
     *
     * @return true if the stream is successfully decoded
     */
    public boolean decode() {
        verifyInternalState();
        // A sink-only decode still needs somewhere for the decoder to write, since every packet
        // goes through the same path whether or not anyone wants the bytes.
        OutputStream target = outputStream == null ? OutputStream.nullOutputStream() : outputStream;
        TerminatingFrameSink sink = frameSink == null ? null : new TerminatingFrameSink(frameSink);
        tivoStream = new TivoStream(inputStream, target, mak);
        tivoStream.setCompatibilityMode(compatibilityMode);
        tivoStream.setFrameSink(sink);
        try {
            return tivoStream.process();
        } finally {
            // Every way out of a decode ends the sink, including the ones that never reach the
            // decoder: an unreadable header, an unknown format, an exception from anywhere below.
            if (sink != null) {
                sink.endIfAbandoned();
            }
        }
    }

    /**
     * Decode only the metadata from @inputStream. Prints nothing to @outputStream.
     *
     * @return true if the metadata is successfully decoded
     */
    public boolean decodeMetadata() {
        tivoStream = new TivoStream(inputStream, null, mak);
        tivoStream.setCompatibilityMode(compatibilityMode);
        tivoStream.setProcessVideo(false);
        return tivoStream.process();
    }

    /**
     * Most of this is already handled by Builder, but we need to ensure @outputStream exists for non-metadata processing.
     */
    private void verifyInternalState() {
        if (outputStream == null && frameSink == null) {
            throw new IllegalStateException(
                    "Cannot decode a video without an OutputStream or a FrameSink");
        }
    }

    /**
     * Return a list of XML Documents representing the recording metadata for the processed TiVo file.
     *
     * @return List of Documents representing the stream's metadata
     */
    public List<Document> getMetadata() {
        if (tivoStream == null) {
            throw new IllegalStateException("Cannot call getMetadata() before processing a TivoStream");
        }
        return tivoStream.getMetadata();
    }

    /**
     * Save the TiVo file's metadata in PyTivo's format.
     *
     * @param path Directory path in which to save the stream's metadata
     */
    public void saveMetadata(Path path) {
        if (tivoStream == null) {
            throw new IllegalStateException("Cannot call saveMetadata() before processing a TivoStream");
        }
        PyTivoMetadata metadata = PyTivoMetadata.createFromMetadata(getMetadata());
        metadata.writeToFile(path);
    }


    public static String bytesToHexString(byte[] bytes, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        sb.append("    ");
        for (int i = offset; i < offset + length; i++) {
            sb.append(String.format("%02X", bytes[i]));
            if ((i + 1) % 40 == 0) {
                sb.append("\n    ");
            } else if ((i + 1) % 4 == 0) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    public static String bytesToHexString(byte[] bytes) {
        return bytesToHexString(bytes, 0, bytes.length);
    }

    /**
     * Class for building TivoDecoder objects.
     */
    public static class Builder {
        private InputStream inputStream;
        private OutputStream outputStream;
        private String mak;
        private boolean compatibilityMode;
        private FrameSink frameSink;

        /**
         * @param is an input stream representing the .TiVo file to decrypt
         * @return a reference to this Builder object
         */
        public Builder input(InputStream is) {
            inputStream = is;
            return this;
        }

        /**
         * @param os the output stream to save the decrypted .TiVo file to
         * @return a reference to this Builder object
         */
        public Builder output(OutputStream os) {
            outputStream = os;
            return this;
        }

        /**
         * @param mak the media access key to use when decrypting the input stream
         * @return a reference to this Builder object
         */
        public Builder mak(String mak) {
            this.mak = mak;
            return this;
        }

        /**
         * In compatibility mode, TivoLibre will attempt to produce output files that are identical to TiVo's
         * DirectShow filter. Since TiVo's DirectShow filter includes some bugs, this setting defaults to false.
         *
         * @param val true to enable binary compatibility mode with TiVo's DirectShow filter
         * @return a reference to this Builder object
         */
        public Builder compatibilityMode(boolean val) {
            compatibilityMode = val;
            return this;
        }

        /**
         * Receive demuxed elementary streams instead of, or as well as, container bytes. A sink and
         * an output stream can both be attached: the sink gets the streams while the file is
         * written, which is what a consumer wanting the intermediate .ts as well as the streams
         * needs. Transport streams only; a program stream recording with a sink attached will
         * refuse to decode rather than silently deliver nothing.
         *
         * @param sink where to deliver elementary stream payload units
         * @return a reference to this Builder object
         */
        public Builder frameSink(FrameSink sink) {
            frameSink = sink;
            return this;
        }

        /**
         * Builds a new TivoDecoder instance from the list of given parameters.
         *
         * @return a new TivoDecoder instance
         */
        public TivoDecoder build() {
            verifyInternalState();
            return new TivoDecoder(inputStream, outputStream, mak, compatibilityMode, frameSink);
        }

        private void verifyInternalState() {
            if (inputStream == null) {
                throw new IllegalStateException("Cannot build a TivoDecoder without an InputStream");
            }
            if (mak == null) {
                throw new IllegalStateException("Cannot build a TivoDecoder without a MAK");
            }
        }
    }
}
