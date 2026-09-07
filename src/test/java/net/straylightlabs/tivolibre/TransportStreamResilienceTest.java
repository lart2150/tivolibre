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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static net.straylightlabs.tivolibre.TransportStreamBuilder.StreamEntry;
import static net.straylightlabs.tivolibre.TransportStreamBuilder.TIVO_STREAM_TYPE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A recording that has been through a signal dropout, or that TiVo wrote in a way we don't expect,
 * should cost us the affected packets and nothing more. These cases all used to abort the decode
 * partway through, leaving a truncated output file.
 */
public class TransportStreamResilienceTest extends DecoderTestHarness {
    /** The packet count the builder produced, in bytes of output. */
    private static int expectedBytes(TransportStreamBuilder builder) {
        return builder.packetCount() * TransportStream.FRAME_SIZE;
    }

    /**
     * A malformed table should not cost us the rest of the recording. tivodecode-ng logs and keeps
     * going here, and so do we now.
     */
    @Test
    public void testMalformedPmtIsNotFatal() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                // A later packet on the PMT PID carrying a table_id that is not 0x02
                .wrongTableId(0x0064, 0x42)
                .scrambledPesPacket(0x0031);

        assertTrue("A malformed PMT did not abort the decode", decode(builder));
        assertEquals("Every packet was still written", expectedBytes(builder), output.size());
        assertEquals("The streams from the valid PMT survived", TransportStream.StreamType.VIDEO,
                stream(0x0031).getType());
        assertEquals("The video packet was still decrypted", 0, scramblingControlOfLastPacket());
    }

    @Test
    public void testMalformedPatIsNotFatal() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .wrongTableId(0x0000, 0x42)
                .scrambledPesPacket(0x0031);

        assertTrue("A malformed PAT did not abort the decode", decode(builder));
        assertEquals("Every packet was still written", expectedBytes(builder), output.size());
        assertEquals("The video packet was still decrypted", 0, scramblingControlOfLastPacket());
    }

    /**
     * An adaptation_field_length that runs past the end of the frame leaves no payload behind, and
     * has to be clamped or the payload length goes negative. This is the shape of recording that
     * crashes tivodecode-ng (wmcbrine/tivodecode-ng#4, an over-the-air recording).
     */
    @Test
    public void testOversizedAdaptationFieldIsClamped() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .packetWithOversizedAdaptationField(0x0031)
                .scrambledPesPacket(0x0031);

        assertTrue("An oversized adaptation field did not abort the decode", decode(builder));
        assertEquals("Every packet was written", expectedBytes(builder), output.size());
        assertEquals("The following packet still decrypted", 0, scramblingControlOfLastPacket());
    }

    /**
     * The same thing on a recording that was cut off mid-packet. Here the buffer is smaller than a
     * frame, so clamping the header to FRAME_SIZE still leaves the payload length negative; it has
     * to be clamped to the bytes actually read.
     */
    @Test
    public void testOversizedAdaptationFieldInTruncatedPacket() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0031)
                .truncatedTrailingPacket(0x0031, 100, 150);

        assertTrue("A truncated trailing packet did not abort the decode", decode(builder));
        assertEquals("Everything read was written back out", builder.byteCount(), output.size());
    }

    /**
     * The signature sniff exists to find Turing keys on a private data stream the PMT mislabeled,
     * so it must not fire on a stream the PMT called audio or video. Without that guard, ordinary
     * payload beginning with these bytes would be parsed as a key table.
     */
    @Test
    public void testSignatureSniffIgnoresAudioAndVideoStreams() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(0x81, 0x0034),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                // Payload on the video and audio PIDs that looks like TiVo private data
                .tivoSignatureDecoy(0x0031)
                .tivoSignatureDecoy(0x0034);

        assertTrue(decode(builder));
        assertEquals("Every packet was written", expectedBytes(builder), output.size());
        // Nothing supplied a key, so neither stream should have been read as a key table
        assertEquals("Video stream was not treated as private data", 0, stream(0x0031).streamId);
        assertEquals("Audio stream was not treated as private data", 0, stream(0x0034).streamId);
    }

    /** The PAT may list more than one program; every PMT it names has to be recognized. */
    @Test
    public void testMultipleProgramsInPat() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(new int[]{1, 2}, new int[]{0x0064, 0x0065})
                .pmt(0x0064, 0x0031, new byte[0], new StreamEntry(0x02, 0x0031))
                .pmt(0x0065, 0x0041, new byte[0],
                        new StreamEntry(0x02, 0x0041),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0047))
                .tivoPrivateData(0x0047, new int[]{0x0041}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0041);

        assertTrue(decode(builder));
        assertEquals("The first PMT was parsed", TransportStream.StreamType.VIDEO,
                stream(0x0031).getType());
        assertEquals("The second PMT was parsed too", TransportStream.StreamType.VIDEO,
                stream(0x0041).getType());
        assertEquals("Its private data stream was found as well",
                TransportStream.StreamType.PRIVATE_DATA, stream(0x0047).getType());
        assertEquals("The video packet was decrypted", 0, scramblingControlOfLastPacket());
    }

    /**
     * A program_number of 0 in the PAT points at the Network Information Table rather than a
     * Program Map Table, so it must not be treated as one.
     */
    @Test
    public void testNetworkInformationTableEntryInPatIsSkipped() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(new int[]{0, 1}, new int[]{0x0010, 0x0064})
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0031);

        assertTrue(decode(builder));
        assertFalse("The NIT PID was not mistaken for a PMT",
                decoder.patData.isProgramMapPid(0x0010));
        assertTrue("The real PMT was still found", decoder.patData.isProgramMapPid(0x0064));
        assertEquals(TransportStream.StreamType.VIDEO, stream(0x0031).getType());
        assertEquals("The video packet was decrypted", 0, scramblingControlOfLastPacket());
    }

    /**
     * The private data can name a PID the PMT never declared. Discarding that key used to abort
     * the decode; now the stream is created so the key survives.
     */
    @Test
    public void testPrivateDataForUndeclaredPid() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                // Names 0x0031, which the PMT above did not list
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0031);

        assertTrue(decode(builder));
        assertNotNull("A stream was created for the undeclared PID", stream(0x0031));
        assertEquals("Its key was applied, so the packet decrypted", 0,
                scramblingControlOfLastPacket());
    }

    /**
     * A stream_bytes field claiming more key entries than the packet holds must not be read off
     * the end of the buffer, and the keys that are present still have to be applied.
     */
    @Test
    public void testTruncatedPrivateDataDoesNotOverrun() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .truncatedTivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY, 200)
                .scrambledPesPacket(0x0031);

        assertTrue("Overstated private data did not abort the decode", decode(builder));
        assertEquals("The key that was present still applied", 0,
                scramblingControlOfLastPacket());
    }

    /**
     * Every table starts with fixed fields that have to be read before any length inside the
     * packet can be trusted. A recording cut off inside those fields used to throw an
     * IndexOutOfBoundsException straight out of decode(), because nothing above the decoder
     * catches a RuntimeException.
     */
    @Test
    public void testPatTruncatedInsideItsFixedFields() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0031)
                .truncatedTrailingPacket(0x0000, 6, 0);

        assertTrue("A PAT truncated inside its header did not throw", decode(builder));
        assertEquals("Everything read was written back out", builder.byteCount(), output.size());
    }

    @Test
    public void testPmtTruncatedInsideItsFixedFields() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0031)
                .truncatedTrailingPacket(0x0064, 12, 0);

        assertTrue("A PMT truncated inside its header did not throw", decode(builder));
        assertEquals("Everything read was written back out", builder.byteCount(), output.size());
    }

    @Test
    public void testPrivateDataTruncatedInsideItsFixedFields() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0031)
                .truncatedTrailingPacket(0x0037, 8, 0);

        assertTrue("Private data truncated inside its header did not throw", decode(builder));
        assertEquals("Everything read was written back out", builder.byteCount(), output.size());
    }

    /**
     * The signature sniff has to demand as many bytes as the parser goes on to read. Matching on
     * the six byte signature alone let a shorter packet through, which then threw.
     */
    @Test
    public void testShortTivoSignatureIsRejected() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .scrambledPesPacket(0x0031)
                // Eight data bytes: enough for the signature, not for the fields behind it
                .truncatedTrailingPacket(0x0050, 12, TransportStreamBuilder.tivoSignature());

        assertTrue("A short signature match did not throw", decode(builder));
        assertEquals("Everything read was written back out", builder.byteCount(), output.size());
    }

    /**
     * Now that a malformed table no longer aborts, a recording whose keys we never read would
     * otherwise run to completion and report success while writing out payload that was never
     * decrypted. That has to be reported as a failure.
     */
    @Test
    public void testDecodeFailsWhenNoKeyWasEverApplied() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                // The only PMT is malformed, so the private data stream is never identified
                .wrongTableId(0x0064, 0x42)
                .scrambledPesPacket(0x0031)
                .scrambledPesPacket(0x0031);

        assertFalse("A decode that never decrypted anything reported failure", decode(builder));
    }

    /** But a recording with nothing scrambled in it is a success, not a failure. */
    @Test
    public void testDecodeSucceedsWhenNothingNeedsDecrypting() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0], new StreamEntry(0x02, 0x0031))
                .rawPacket(0x0031, true, false, new byte[]{0x00, 0x00, 0x01, (byte) 0xe0});

        assertTrue("An unscrambled recording still succeeds", decode(builder));
        assertEquals(expectedBytes(builder), output.size());
    }

    /**
     * The PAT repeats through a recording and can be revised. A stray packet that parses as a PAT
     * must not poison a PID permanently, so each first section replaces the table rather than
     * adding to it.
     */
    @Test
    public void testLaterPatReplacesTheProgramMapPids() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                // A PAT naming a different PMT PID, then the real one again
                .pat(1, 0x0099)
                .pat(1, 0x0064)
                .scrambledPesPacket(0x0031);

        assertTrue(decode(builder));
        assertTrue("The current PAT's PMT PID is recognized", decoder.patData.isProgramMapPid(0x0064));
        assertFalse("The superseded PMT PID was dropped", decoder.patData.isProgramMapPid(0x0099));
        assertEquals("The video packet still decrypted", 0, scramblingControlOfLastPacket());
    }

    private TransportStreamBuilder withKeys() {
        return new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY);
    }

    /**
     * A PES start code prefix can end one packet with its 8 bit value landing in the next. Only
     * that value is read from the second packet, so giving back a whole 32 bit start code drove
     * the header length to -3 and produced a negative decrypt offset.
     */
    @Test
    public void testStartCodePrefixStraddlingPacketBoundary() {
        for (int startCodeValue : new int[]{0x01, 0xbf}) {
            TransportStreamBuilder builder = withKeys()
                    .packetEndingOnStartCodePrefix(0x0031)
                    .packetStartingWithStartCodeValue(0x0031, startCodeValue);

            assertTrue("A straddled start code did not produce a negative offset", decode(builder));
            assertEquals("Every packet was written", expectedBytes(builder), output.size());
        }
    }

    /**
     * A PSI section can be longer than the packet carrying it. We have no reassembly, so a
     * continuation packet has to be skipped: parsing it as a fresh table discarded the real
     * program map PIDs and invented streams out of section payload bytes.
     */
    @Test
    public void testPatContinuationPacketIsIgnored() {
        byte[] continuation = new byte[TransportStream.FRAME_SIZE - 4];
        continuation[0] = 0x00;         // looks like table_id 0
        continuation[1] = (byte) 0xb0;
        continuation[2] = 0x0d;
        continuation[7] = 0x04;         // program_number 4
        continuation[8] = 0x56;
        continuation[9] = (byte) 0xe4;  // program_map_PID 0x0456
        continuation[10] = 0x56;

        TransportStreamBuilder builder = withKeys()
                .continuationPacket(0x0000, continuation)
                .scrambledPesPacket(0x0031);

        assertTrue(decode(builder));
        assertTrue("The real PMT PID survived", decoder.patData.isProgramMapPid(0x0064));
        assertFalse("No PMT PID was invented", decoder.patData.isProgramMapPid(0x0456));
        assertEquals("The video packet still decrypted", 0, scramblingControlOfLastPacket());
    }

    @Test
    public void testPmtContinuationPacketIsIgnored() {
        byte[] continuation = new byte[TransportStream.FRAME_SIZE - 4];
        continuation[0] = 0x02;         // looks like table_id 2
        continuation[1] = (byte) 0xb0;
        continuation[2] = 0x17;
        continuation[12] = 0x02;        // stream_type 2
        continuation[13] = (byte) 0xe7;
        continuation[14] = 0x77;        // PID 0x0777

        TransportStreamBuilder builder = withKeys()
                .continuationPacket(0x0064, continuation)
                .scrambledPesPacket(0x0031);

        assertTrue(decode(builder));
        assertFalse("No stream was invented from section payload bytes",
                decoder.streams.containsKey(0x0777));
        assertEquals("The video packet still decrypted", 0, scramblingControlOfLastPacket());
    }

    /**
     * A recording can end part way through a packet, leaving fewer bytes than a TS header. Every
     * one of these threw out of decode() before, either from the header read or from the
     * resynchronization search running past the shortened buffer limit.
     */
    @Test
    public void testTrailingBytesShorterThanAHeader() {
        for (int length = 1; length <= 6; length++) {
            for (boolean adaptationField : new boolean[]{false, true}) {
                TransportStreamBuilder builder = withKeys()
                        .scrambledPesPacket(0x0031)
                        .trailingBytes(0x0031, length, adaptationField);

                assertTrue("A " + length + " byte tail did not abort the decode", decode(builder));
            }
        }
    }

    /** A tail whose sync byte is wrong must not send the resync search past the buffer limit. */
    @Test
    public void testTrailingBytesWithBadSyncByte() {
        TransportStreamBuilder builder = withKeys().scrambledPesPacket(0x0031);
        byte[] bytes = builder.toByteArray();
        byte[] withTail = new byte[bytes.length + 40];
        System.arraycopy(bytes, 0, withTail, 0, bytes.length);
        withTail[bytes.length] = 0x21; // not a sync byte

        output = new ByteArrayOutputStream();
        CountingDataInputStream input = new CountingDataInputStream(new ByteArrayInputStream(withTail));
        decoder = new TransportStreamDecoder(new TuringDecoder(new byte[Stream.KEY_LENGTH + 4]), 0,
                input, output, false);
        assertTrue("A corrupt short tail did not abort the decode", decoder.process());
    }

    /**
     * The null packet PID can never carry a PMT, so it has to be recognized before the PMT check.
     * A garbage PAT naming 0x1fff otherwise routed every stuffing packet into the table parser and
     * copied them all into the output.
     */
    @Test
    public void testPatNamingTheNullPidDoesNotCaptureStuffing() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(new int[]{1, 2}, new int[]{0x0064, 0x1fff})
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                .tivoPrivateData(0x0037, new int[]{0x0031}, new int[]{0xe0}, SET_KEY)
                .nullPacket().nullPacket().nullPacket()
                .scrambledPesPacket(0x0031);

        assertTrue(decode(builder));
        assertFalse("0x1fff was not accepted as a PMT PID", decoder.patData.isProgramMapPid(0x1fff));
        assertEquals("The null packets were still dropped",
                (builder.packetCount() - 3) * TransportStream.FRAME_SIZE, output.size());
        assertEquals("The video packet still decrypted", 0, scramblingControlOfLastPacket());
    }

    /**
     * Losing one stream's keys is as unusable as losing all of them, so the check is per stream.
     * A recording that decrypted its audio but never found a video key used to report success.
     */
    @Test
    public void testDecodeFailsWhenOneStreamNeverDecrypts() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, 0x0031, new byte[0],
                        new StreamEntry(0x02, 0x0031),
                        new StreamEntry(0x81, 0x0034),
                        new StreamEntry(TIVO_STREAM_TYPE, 0x0037))
                // Keys for the audio PID only
                .tivoPrivateData(0x0037, new int[]{0x0034}, new int[]{0xc0}, SET_KEY)
                .scrambledPesPacket(0x0034)
                .scrambledPesPacket(0x0031);

        assertFalse("A stream that never decrypted reported failure", decode(builder));
    }
}
