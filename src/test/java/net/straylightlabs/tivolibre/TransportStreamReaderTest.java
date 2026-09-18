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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Reading a transport stream that was decrypted earlier. The promise this path makes, and the one
 * that lets a consumer treat a remux as lossless, is that every byte it reads it writes back
 * unaltered while the streams come out of the side.
 */
public class TransportStreamReaderTest {
    private static final int VIDEO_PID = 0x0031;
    private static final int AUDIO_PID = 0x0034;

    private RecordingFrameSink sink;
    private ByteArrayOutputStream output;

    private boolean read(TransportStreamBuilder builder) {
        sink = new RecordingFrameSink();
        output = new ByteArrayOutputStream();
        return new TransportStreamReader.Builder()
                .input(new ByteArrayInputStream(builder.toByteArray()))
                .output(output)
                .frameSink(sink)
                .build()
                .read();
    }

    private static TransportStreamBuilder program() {
        return new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, VIDEO_PID, new byte[0],
                        new StreamEntry(0x1b, VIDEO_PID),
                        new StreamEntry(0x81, AUDIO_PID));
    }

    /**
     * Null packets are stuffing and the decrypting path drops them, but this one is a pass through:
     * dropping bytes out of a file nobody is decrypting would be a surprise, not an optimisation.
     */
    @Test
    public void testEveryByteIsWrittenBack() {
        TransportStreamBuilder builder = program()
                .pesPacket(VIDEO_PID, 0xe0, 90000L, new byte[]{1, 2, 3, 4})
                .nullPacket()
                .pesPacket(AUDIO_PID, 0xc0, 90000L, new byte[]{5, 6, 7, 8})
                .nullPacket();

        assertTrue(read(builder));
        assertArrayEquals("The output is the input", builder.toByteArray(), output.toByteArray());
    }

    /**
     * A packet whose scrambling bits are set has no business being here, but compatibility mode
     * output is full of them: it keeps the regions it could not decrypt. Running one through the
     * decrypt path with no key would clear those bits in the output, count a failure against a key
     * nobody supplied, and report a stream that read perfectly as never decrypted.
     */
    @Test
    public void testScrambledPacketIsNeitherDecryptedNorAlteredNorFailed() {
        TransportStreamBuilder builder = program()
                .scrambledPesPacket(VIDEO_PID)
                .pesPacket(VIDEO_PID, 0xe0, 90000L, new byte[]{1, 2, 3, 4});

        assertTrue("A stream that read to its end is a successful read", read(builder));
        assertArrayEquals("Its scrambling bits are still set, because nothing decrypted it",
                builder.toByteArray(), output.toByteArray());
        assertTrue("and nothing is reported as never decrypted",
                sink.result.getPidsNeverDecrypted().isEmpty());
        assertTrue(sink.result.isUsable());
    }

    /** The streams still come out, which is the point of reading it at all. */
    @Test
    public void testStreamsAreDelivered() {
        assertTrue(read(program()
                .pesPacket(VIDEO_PID, 0xe0, 90000L, new byte[]{1, 2, 3, 4})
                .pesPacket(VIDEO_PID, 0xe0, 93003L, new byte[]{5, 6, 7, 8})));

        assertEquals(1, sink.programs.size());
        assertEquals(2, sink.payloadsOn(VIDEO_PID).size());
        assertEquals(90000L, sink.payloadsOn(VIDEO_PID).get(0).getPts());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, sink.payloadsOn(VIDEO_PID).get(0).getData());
        assertEquals("The decode ran to the end of the stream", 1, sink.endCount);
        assertTrue(sink.result.isComplete());
    }

    /** A source that stops mid packet is not a failure, and what came before it still arrives. */
    @Test
    public void testTruncatedSourceStillDeliversWhatItRead() {
        TransportStreamBuilder builder = program()
                .unboundedPesPacket(VIDEO_PID, 0xe0, 90000L, new byte[]{1, 2, 3, 4})
                .trailingBytes(VIDEO_PID, 3, false);

        assertTrue(read(builder));
        assertEquals(1, sink.payloadsOn(VIDEO_PID).size());
        assertEquals("A unit with no declared length had nothing to end it but the stream",
                PesPayload.Completeness.ENDED_WITH_STREAM,
                sink.payloadsOn(VIDEO_PID).get(0).getCompleteness());
    }

    /**
     * A unit that delivered every byte it declared is whole, even as the last thing in the file.
     * Audio always declares a length, so getting this wrong marks the final audio unit of every
     * recording truncated and costs a consumer a good frame group.
     */
    @Test
    public void testFullyDeliveredFinalUnitIsComplete() {
        assertTrue(read(program()
                .pesPacket(AUDIO_PID, 0xc0, 90000L, new byte[]{1, 2, 3, 4})));

        PesPayload last = sink.payloadsOn(AUDIO_PID).get(0);
        assertEquals(PesPayload.Completeness.COMPLETE, last.getCompleteness());
        assertTrue(last.isComplete());
    }

    /**
     * A hole in the middle of a unit is the case this path has to get right. The bytes are written
     * through, so the file is intact, but the stream that had a unit open across the run comes back
     * missing its middle. Without a break reported, the consumer is handed one unit spliced out of
     * the two sides of the hole and told it is whole.
     */
    @Test
    public void testResyncHoleIsReportedRatherThanSplicedOver() {
        byte[] head = new byte[TransportStream.FRAME_SIZE - 4 - 14];
        TransportStreamBuilder builder = program()
                .unboundedPesPacket(VIDEO_PID, 0xe0, 90000L, head)
                .unsynchronizedBytes(188)
                .continuationPacket(VIDEO_PID, new byte[]{9, 9, 9, 9})
                .unboundedPesPacket(VIDEO_PID, 0xe0, 93003L, new byte[]{1, 2, 3, 4})
                .pesPacket(AUDIO_PID, 0xc0, 90000L, new byte[]{5, 6, 7, 8})
                .pesPacket(AUDIO_PID, 0xc0, 93003L, new byte[]{5, 6, 7, 8})
                .pesPacket(AUDIO_PID, 0xc0, 96006L, new byte[]{5, 6, 7, 8})
                .pesPacket(AUDIO_PID, 0xc0, 99009L, new byte[]{5, 6, 7, 8});

        assertTrue(read(builder));
        assertArrayEquals("Every byte still reaches the output, hole included",
                builder.toByteArray(), output.toByteArray());
        assertTrue("and the consumer is told a stream broke",
                sink.discontinuityPids.contains(VIDEO_PID));
        assertEquals(DiscontinuityReason.Kind.EXCISION,
                sink.discontinuityReasons.get(sink.discontinuityPids.indexOf(VIDEO_PID)).getKind());
        for (PesPayload payload : sink.payloadsOn(VIDEO_PID)) {
            assertTrue("No unit spans the hole and claims to be whole",
                    payload.getData().length <= TransportStream.FRAME_SIZE);
        }
    }

    /**
     * Compatibility mode output carries whole regions it could not decrypt, tens of thousands of
     * packets on one corpus recording, and this path is what reads such a file back. Their PES
     * headers are in the clear so they parse perfectly; the payload behind them is ciphertext.
     */
    @Test
    public void testScrambledPayloadIsAnnouncedRatherThanDelivered() {
        assertTrue(read(program()
                .scrambledPesPacket(VIDEO_PID)
                .scrambledPesPacket(VIDEO_PID)));

        assertEquals("No ciphertext reached the consumer", 0, sink.payloadsOn(VIDEO_PID).size());
        assertEquals("and the run was announced once", 1, sink.discontinuityPids.size());
        assertEquals(DiscontinuityReason.Kind.DECRYPTION_FAILED,
                sink.discontinuityReasons.get(0).getKind());
    }
}
