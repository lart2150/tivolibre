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

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

/**
 * Checks the PES header lengths we compute, since that offset is where decryption starts. The expected
 * values here match what tivodecode-ng's MPEG-2 parser produces for the same bytes.
 */
public class PesHeaderTest {
    /** 00 00 01 E0, packet length, two flag bytes, and PES_header_data_length: nine bytes. */
    private static final byte[] VIDEO_PES_HEADER = {
            0x00, 0x00, 0x01, (byte) 0xe0, 0x00, 0x00, (byte) 0x80, 0x00, 0x00
    };

    private static int headerSize(byte... trailing) {
        byte[] bytes = new byte[VIDEO_PES_HEADER.length + trailing.length];
        System.arraycopy(VIDEO_PES_HEADER, 0, bytes, 0, VIDEO_PES_HEADER.length);
        System.arraycopy(trailing, 0, bytes, VIDEO_PES_HEADER.length, trailing.length);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.limit(bytes.length);
        return PesHeader.createFrom(buffer).size();
    }

    @Test
    public void testPesHeaderFollowedBySlice() {
        // A slice start code ends the header; the payload begins at the start code itself
        assertEquals(9, headerSize((byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x01, (byte) 0x0f));
    }

    @Test
    public void testPesHeaderFollowedByAccessUnitDelimiter() {
        // H.264 NAL headers all fall in the slice range, so they end the scan the same way
        assertEquals(9, headerSize((byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x09, (byte) 0xf0));
    }

    @Test
    public void testFourByteStartCodePrefix() {
        // The extra zero byte of a four-byte start code prefix counts toward the header
        assertEquals(10, headerSize((byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x09,
                (byte) 0xf0));
    }

    @Test
    public void testPictureCodingExtension() {
        // 00 00 01 B5 with extension id 8: four bytes of start code plus 34 bits, rounded up
        assertEquals(9 + 9, headerSize((byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0xb5, (byte) 0x8f,
                (byte) 0xff, (byte) 0xf0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
                (byte) 0x01));
    }

    /**
     * Extension ids we don't model (3 is the quantiser matrix extension) end the header scan, but the
     * four bytes of the extension start code stay in the clear and so must still be counted. Dropping
     * them puts the Turing keystream four bytes out of phase and corrupts the rest of the packet, which
     * is what fflewddur/tivolibre#24 reports. tivodecode-ng counts those four bytes too.
     */
    @Test
    public void testUnhandledExtensionCountsItsStartCode() {
        assertEquals(9 + 4, headerSize((byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0xb5, (byte) 0x35,
                (byte) 0x55, (byte) 0x55, (byte) 0x55));
    }

    @Test
    public void testSequenceEndCode() {
        // 00 00 01 B7 carries no payload of its own, then a slice ends the scan
        assertEquals(9 + 4, headerSize((byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0xb7,
                (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x01, (byte) 0x0f));
    }

    /** An unrecognized start code is treated as the start of the payload, as it is for slices. */
    @Test
    public void testUnknownStartCodeEndsHeader() {
        assertEquals(9, headerSize((byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0xbf, (byte) 0x00,
                (byte) 0x04));
    }
}
