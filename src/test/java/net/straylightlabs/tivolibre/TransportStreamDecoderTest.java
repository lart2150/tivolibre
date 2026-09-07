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

import static net.straylightlabs.tivolibre.TransportStreamBuilder.StreamEntry;
import static net.straylightlabs.tivolibre.TransportStreamBuilder.TIVO_STREAM_TYPE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises TransportStreamDecoder against synthetic streams. These cover stream layouts that TiVo
 * produces but that our sample recordings don't happen to contain.
 */
public class TransportStreamDecoderTest extends DecoderTestHarness {
    /**
     * TiVo assigns elementary stream PIDs from a low base, and they sometimes land in the range MPEG
     * and DVB reserve for PSI tables. Dispatching on those reserved ranges made the decoder abort on
     * the first private data packet. See fflewddur/tivolibre#22, where a recording carried video on
     * PID 0x0011, audio on 0x0014, and the Turing keys on 0x0015.
     */
    @Test
    public void testStreamPidsInReservedRange() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0011, new byte[0],
                        new StreamEntry(0x1b, 0x0011),
                        new StreamEntry(0x81, 0x0014),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0015))
                .tivoPrivateData(0x0015, new int[]{0x0011, 0x0014}, new int[]{0xe0, 0xc0}, SET_KEY)
                .scrambledPesPacket(0x0014)
                .scrambledPesPacket(0x0011);

        assertTrue("Decoded a stream whose PIDs fall in the reserved range", decode(builder));
        assertEquals("Every packet was written", builder.packetCount() * TransportStream.FRAME_SIZE,
                output.size());
        assertNotNull("Created a stream for the video PID", stream(0x0011));
        assertEquals(TransportStream.StreamType.VIDEO, stream(0x0011).getType());
        assertEquals(TransportStream.StreamType.PRIVATE_DATA, stream(0x0015).getType());
        assertEquals("The video packet was decrypted", 0, scramblingControlOfLastPacket());
    }

    /**
     * Program-level descriptors are part of the PMT section, so their length has to come off
     * section_length. Otherwise the stream loop keeps reading past the end of the stream list.
     */
    @Test
    public void testPmtWithProgramDescriptors() {
        // A CA descriptor (tag 0x09), which cable PMTs routinely carry
        byte[] programDescriptors = {0x09, 0x04, 0x0f, (byte) 0xff, (byte) 0xe0, 0x50};
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x1291, programDescriptors,
                        new StreamEntry(0x02, 0x1291),
                        new StreamEntry(0x81, 0x1294),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x1296))
                .tivoPrivateData(0x1296, new int[]{0x1291}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x1291);

        assertTrue("Decoded a PMT carrying program-level descriptors", decode(builder));
        assertEquals(TransportStream.StreamType.VIDEO, stream(0x1291).getType());
        assertEquals(TransportStream.StreamType.AUDIO, stream(0x1294).getType());
        assertEquals(TransportStream.StreamType.PRIVATE_DATA, stream(0x1296).getType());
        // Nothing beyond the three declared streams should have been registered: the PAT PID, the PMT
        // PID and the three elementary streams make five.
        assertEquals("No streams invented from descriptor or CRC bytes", 5, decoder.streams.size());
        assertEquals("The video packet was decrypted", 0, scramblingControlOfLastPacket());
    }

    /** ES-level descriptors were already accounted for; pin them down alongside the above. */
    @Test
    public void testPmtWithElementaryStreamDescriptors() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x1291, new byte[0],
                        new StreamEntry(0x02, 0x1291, (byte) 0x02, (byte) 0x03, (byte) 0x1a, (byte) 0x44,
                                (byte) 0x3f),
                        new StreamEntry(0x81, 0x1294, (byte) 0x0a, (byte) 0x04, (byte) 0x65, (byte) 0x6e,
                                (byte) 0x67, (byte) 0x00),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x1296));

        assertTrue("Decoded a PMT carrying ES-level descriptors", decode(builder));
        assertEquals(TransportStream.StreamType.VIDEO, stream(0x1291).getType());
        assertEquals(TransportStream.StreamType.AUDIO, stream(0x1294).getType());
        assertEquals(TransportStream.StreamType.PRIVATE_DATA, stream(0x1296).getType());
        assertEquals("No streams invented from descriptor or CRC bytes", 5, decoder.streams.size());
    }

    /**
     * Null packets pad a stream out to a constant bit rate and carry nothing we need, so they're
     * dropped rather than treated as an unknown PID. tivodecode-ng aborts on these.
     */
    @Test
    public void testNullPacketsAreDropped() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .nullPacket()
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .nullPacket()
                .scrambledPesPacket(0x0031);

        assertTrue("Decoded a stream containing null packets", decode(builder));
        assertEquals("Null packets were not written",
                (builder.packetCount() - 2) * TransportStream.FRAME_SIZE, output.size());
        assertEquals("The video packet was decrypted", 0, scramblingControlOfLastPacket());
    }

    /**
     * PSI PIDs we have no use for shouldn't stop the decode; copy them through the way TiVo's own
     * filter does.
     */
    @Test
    public void testUnusedPsiPidsArePassedThrough() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .unusedTable(0x0011)
                .scrambledPesPacket(0x0031);

        assertTrue("Decoded a stream containing unused PSI PIDs", decode(builder));
        assertEquals("Every non-null packet was written",
                builder.packetCount() * TransportStream.FRAME_SIZE, output.size());
        assertEquals("The video packet was decrypted", 0, scramblingControlOfLastPacket());
    }

    /**
     * The Turing keys are useless if we can't tell which stream carries them, so fall back on the
     * "TiVo" signature when the PMT labels the private data stream with an unfamiliar stream type.
     */
    @Test
    public void testTivoPrivateDataWithUnknownStreamType() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(0x98, 0x0037)) // not 0x97
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0031);

        assertTrue("Decoded a stream with a mislabeled private data stream", decode(builder));
        assertEquals("The video packet was decrypted, so the keys were found", 0,
                scramblingControlOfLastPacket());
    }

    /**
     * A PMT big enough to span two packets can't be reassembled yet, but the streams it does declare
     * in its first packet have to survive, and the decode has to keep going.
     */
    @Test
    public void testOversizedPmtSectionDoesNotAbort() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .truncatedPmt(0x0064, 0x0031, 40,
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0031);

        assertTrue("An oversized PMT section did not abort the decode", decode(builder));
        assertEquals(TransportStream.StreamType.VIDEO, stream(0x0031).getType());
        assertEquals(TransportStream.StreamType.PRIVATE_DATA, stream(0x0037).getType());
        assertEquals("The video packet was decrypted", 0, scramblingControlOfLastPacket());
    }
}
