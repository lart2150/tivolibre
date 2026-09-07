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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Runs a TransportStreamBuilder stream through a TransportStreamDecoder and exposes what the
 * decoder made of it.
 */
abstract class DecoderTestHarness {
    /** A key whose bits satisfy Stream.doHeader(), so decryption is considered enabled. */
    static final byte[] SET_KEY = new byte[Stream.KEY_LENGTH];

    static {
        Arrays.fill(SET_KEY, (byte) 0xff);
    }

    ByteArrayOutputStream output;
    TransportStreamDecoder decoder;

    boolean decode(TransportStreamBuilder builder) {
        output = new ByteArrayOutputStream();
        CountingDataInputStream input = new CountingDataInputStream(
                new ByteArrayInputStream(builder.toByteArray()));
        // TuringDecoder writes the stream and block ids into key[16..19]
        decoder = new TransportStreamDecoder(new TuringDecoder(new byte[Stream.KEY_LENGTH + 4]), 0,
                input, output, false);
        return decoder.process();
    }

    TransportStream stream(int pid) {
        return decoder.streams.get(pid);
    }

    /**
     * The transport_scrambling_control bits of the last packet written. Zero means the decoder
     * found a key for that stream, decrypted the packet and cleared the flag.
     */
    int scramblingControlOfLastPacket() {
        byte[] bytes = output.toByteArray();
        return bytes[bytes.length - TransportStream.FRAME_SIZE + 3] & 0xc0;
    }
}
