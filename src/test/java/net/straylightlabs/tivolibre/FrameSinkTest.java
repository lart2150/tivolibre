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
import java.util.Arrays;

import static net.straylightlabs.tivolibre.TransportStreamBuilder.StreamEntry;
import static net.straylightlabs.tivolibre.TransportStreamBuilder.TIVO_STREAM_TYPE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a FrameSink is promised: whole payload units, elementary stream bytes only, the timestamps
 * the stream carried and no others, the complete program list every time, and a break reported
 * before the bytes that do not continue what came before it.
 */
public class FrameSinkTest {
    private static final int VIDEO_PID = 0x0031;
    private static final int AUDIO_PID = 0x0034;
    private static final int PRIVATE_PID = 0x0037;
    private static final int VIDEO_STREAM_ID = 0xe0;
    private static final long A_PTS = 7583345447L;

    private RecordingFrameSink sink;

    private boolean decode(TransportStreamBuilder builder) {
        return decode(builder, false);
    }

    private boolean decode(TransportStreamBuilder builder, boolean compatibilityMode) {
        sink = new RecordingFrameSink();
        CountingDataInputStream input = new CountingDataInputStream(
                new ByteArrayInputStream(builder.toByteArray()));
        TransportStreamDecoder decoder = new TransportStreamDecoder(
                new TuringDecoder(new byte[Stream.KEY_LENGTH + 4]), 0, input,
                new ByteArrayOutputStream(), compatibilityMode, sink, true);
        return decoder.process();
    }

    private TransportStreamBuilder program() {
        return new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, VIDEO_PID, new byte[0],
                        new StreamEntry(0x1b, VIDEO_PID),
                        new StreamEntry(0x81, AUDIO_PID),
                        new StreamEntry(TIVO_STREAM_TYPE, PRIVATE_PID));
    }

    private static byte[] bytes(int from, int count) {
        byte[] data = new byte[count];
        for (int i = 0; i < count; i++) {
            data[i] = (byte) (from + i);
        }
        return data;
    }

    /** The PMT is handed over with the raw stream_type of each stream, not a category. */
    @Test
    public void testProgramCarriesRawStreamTypes() {
        assertTrue(decode(program().pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 8))));

        assertEquals("One announcement, since the PMT appeared once", 1, sink.programs.size());
        assertEquals(Arrays.asList(VIDEO_PID, AUDIO_PID, PRIVATE_PID), sink.lastProgramPids());
        assertEquals("H.264 is reported as 0x1b rather than as a video category",
                0x1b, sink.programs.get(0).get(0).getStreamType());
        assertEquals(0x81, sink.programs.get(0).get(1).getStreamType());
        assertEquals(1, sink.programs.get(0).get(0).getProgramNumber());
    }

    /** Descriptors are kept, since a muxer reads things out of them this library does not. */
    @Test
    public void testElementaryStreamDescriptorsAreKept() {
        byte[] descriptor = {0x0a, 0x04, 'e', 'n', 'g', 0x00};
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, VIDEO_PID, new byte[0],
                        new StreamEntry(0x1b, VIDEO_PID),
                        new StreamEntry(0x81, AUDIO_PID, descriptor));

        assertTrue(decode(builder));
        assertArrayEquals("The video stream had none", new byte[0],
                sink.programs.get(0).get(0).getDescriptors());
        assertArrayEquals("The audio stream's came through untouched", descriptor,
                sink.programs.get(0).get(1).getDescriptors());
    }

    /** A unit spread over several packets arrives as one payload, without its PES header. */
    @Test
    public void testPayloadUnitIsReassembledAcrossPackets() {
        // The head fills its packet exactly, so the tail in the next packet continues it directly.
        // Anything shorter would be padded out, and the padding would sit between the two halves.
        byte[] head = bytes(0, TransportStream.FRAME_SIZE - 4 - 14);
        byte[] tail = bytes(head.length, 30);
        TransportStreamBuilder builder = program()
                .pesPacketDeclaring(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, head, head.length + tail.length)
                .continuationPacket(VIDEO_PID, tail)
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS + 3003, bytes(100, 4));

        assertTrue(decode(builder));
        assertEquals("Two units started, and both closed", 2, sink.payloadsOn(VIDEO_PID).size());

        PesPayload first = sink.payloadsOn(VIDEO_PID).get(0);
        byte[] expected = new byte[head.length + tail.length];
        System.arraycopy(head, 0, expected, 0, head.length);
        System.arraycopy(tail, 0, expected, head.length, tail.length);
        assertArrayEquals("Both packets' payloads, and no PES header", expected, first.getData());
        assertEquals(A_PTS, first.getPts());
        assertTrue(first.hasPts());
        assertEquals("No DTS was in the stream, so none is invented", false, first.hasDts());
        assertEquals(VIDEO_STREAM_ID, first.getStreamId());
    }

    /**
     * A unit that declares no length runs to the next payload unit start, which is the only
     * boundary there is. Everything in between belongs to it, including whatever padded the last
     * packet: with no declared length there is nothing to say where content stopped.
     */
    @Test
    public void testUnboundedUnitRunsToTheNextPayloadUnitStart() {
        int payloadPerPacket = TransportStream.FRAME_SIZE - 4;
        TransportStreamBuilder builder = program()
                .unboundedPesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 20))
                .continuationPacket(VIDEO_PID, bytes(20, 30))
                .unboundedPesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS + 3003, bytes(100, 4));

        assertTrue(decode(builder));
        assertEquals(2, sink.payloadsOn(VIDEO_PID).size());
        assertEquals("Two packets' worth of payload, less the header that opened it",
                payloadPerPacket * 2 - 14, sink.payloadsOn(VIDEO_PID).get(0).getData().length);
        assertEquals("The next start closed it rather than ending the stream",
                A_PTS + 3003, sink.payloadsOn(VIDEO_PID).get(1).getPts());
    }

    /** The last unit of a stream only ends at EOF, and is delivered rather than lost. */
    @Test
    public void testFinalPayloadUnitIsFlushed() {
        assertTrue(decode(program()
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 12))));

        assertEquals(1, sink.payloadsOn(VIDEO_PID).size());
        assertArrayEquals(bytes(0, 12), sink.payloadsOn(VIDEO_PID).get(0).getData());
    }

    /** A unit that carried no timestamp says so rather than reporting zero or a guess. */
    @Test
    public void testMissingTimestampIsReportedAbsent() {
        assertTrue(decode(program()
                .pesPacketWithoutPts(VIDEO_PID, VIDEO_STREAM_ID, bytes(0, 10))));

        PesPayload payload = sink.payloadsOn(VIDEO_PID).get(0);
        assertEquals(false, payload.hasPts());
        assertEquals(false, payload.hasDts());
        assertArrayEquals(bytes(0, 10), payload.getData());
    }

    /**
     * A recording starts mid unit. That leading fragment has no header of its own, so it belongs to
     * a unit nobody has and is dropped rather than delivered as though it were whole.
     */
    @Test
    public void testFragmentBeforeTheFirstPayloadUnitStartIsDropped() {
        TransportStreamBuilder builder = program()
                .continuationPacket(VIDEO_PID, bytes(200, 40))
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 10));

        assertTrue(decode(builder));
        assertEquals("Only the unit that began with a header", 1, sink.payloadsOn(VIDEO_PID).size());
        assertArrayEquals(bytes(0, 10), sink.payloadsOn(VIDEO_PID).get(0).getData());
    }

    /**
     * The TiVo private data stream is announced in the PMT and never sets a payload unit start, so
     * it is listed as a stream and produces nothing. Measured on real recordings: 0 of 1,605 such
     * packets carried one on one of them, 0 of 3,521 on another.
     */
    @Test
    public void testPrivateDataStreamIsAnnouncedButEmitsNothing() {
        assertTrue(decode(program()
                .tivoPrivateData(PRIVATE_PID, new int[]{VIDEO_PID}, new int[]{0xe0}, new byte[16])
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 8))));

        assertTrue("It is announced", sink.lastProgramPids().contains(PRIVATE_PID));
        assertEquals("And delivers nothing", 0, sink.payloadsOn(PRIVATE_PID).size());
    }

    /** A PID no PMT announced has no stream_type, so nothing about it can be muxed. */
    @Test
    public void testUnannouncedPidIsSuppressed() {
        assertTrue(decode(program()
                .pesPacket(0x0099, VIDEO_STREAM_ID, A_PTS, bytes(0, 10))
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 10))));

        assertEquals(0, sink.payloadsOn(0x0099).size());
        assertEquals(1, sink.payloadsOn(VIDEO_PID).size());
    }

    /**
     * A stream that appears part way through is added to the list, and the announcement carries
     * every stream rather than only the new one. A consumer that cannot add a track late needs the
     * whole picture to decide what to do about it.
     */
    @Test
    public void testLaterPmtAnnouncesTheCompleteList() {
        TransportStreamBuilder builder = new TransportStreamBuilder()
                .pat(1, 0x0064)
                .pmt(0x0064, VIDEO_PID, new byte[0], new StreamEntry(0x1b, VIDEO_PID))
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 8))
                .pmt(0x0064, VIDEO_PID, new byte[0],
                        new StreamEntry(0x1b, VIDEO_PID),
                        new StreamEntry(0x81, AUDIO_PID));

        assertTrue(decode(builder));
        assertEquals("Announced again only because the set grew", 2, sink.programs.size());
        assertEquals(Arrays.asList(VIDEO_PID), sink.lastProgramPids().subList(0, 1));
        assertEquals("The second announcement is cumulative, not a delta",
                Arrays.asList(VIDEO_PID, AUDIO_PID), sink.lastProgramPids());
    }

    /** A repeated PMT that says nothing new does not announce again. */
    @Test
    public void testUnchangedPmtDoesNotReannounce() {
        assertTrue(decode(program()
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 8))
                .pmt(0x0064, VIDEO_PID, new byte[0],
                        new StreamEntry(0x1b, VIDEO_PID),
                        new StreamEntry(0x81, AUDIO_PID),
                        new StreamEntry(TIVO_STREAM_TYPE, PRIVATE_PID))));

        assertEquals(1, sink.programs.size());
    }

    /**
     * A packet declaring the adaptation field discontinuity indicator breaks the unit in progress.
     * The bytes after it do not continue what came before, so the partial unit is dropped and the
     * consumer is told before anything else of that stream arrives.
     */
    @Test
    public void testDeclaredDiscontinuityDropsThePartialUnit() {
        TransportStreamBuilder builder = program()
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 20))
                .discontinuityPacket(VIDEO_PID)
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS + 3003, bytes(50, 10));

        assertTrue(decode(builder));
        assertEquals("The break was reported once", 1, sink.discontinuityPids.size());
        assertEquals(VIDEO_PID, (int) sink.discontinuityPids.get(0));
        assertEquals(DiscontinuityReason.Kind.DECLARED_IN_STREAM,
                sink.discontinuityReasons.get(0).getKind());
        assertEquals("The unit open when it broke was dropped, the one after it was not",
                1, sink.payloadsOn(VIDEO_PID).size());
        assertArrayEquals(bytes(50, 10), sink.payloadsOn(VIDEO_PID).get(0).getData());
    }

    /**
     * A unit that is all there says so, and the two ways of being short say which. A source cut at
     * an arbitrary point, which is what a commercial cutter produces, leaves the last unit of every
     * stream short, so a consumer writing these into a container needs to know before it writes one
     * as though it were whole.
     */
    @Test
    public void testCompletenessDistinguishesATruncatedUnit() {
        TransportStreamBuilder builder = program()
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 10))
                .unboundedPesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS + 3003, bytes(0, 10));

        assertTrue(decode(builder));
        assertEquals(2, sink.payloadsOn(VIDEO_PID).size());
        PesPayload closedByTheNextUnit = sink.payloadsOn(VIDEO_PID).get(0);
        PesPayload closedByTheStreamEnding = sink.payloadsOn(VIDEO_PID).get(1);

        assertTrue("It ended where the next one began", closedByTheNextUnit.isComplete());
        assertEquals(PesPayload.Completeness.COMPLETE, closedByTheNextUnit.getCompleteness());
        assertEquals("Nothing closed the last one but the source running out",
                false, closedByTheStreamEnding.isComplete());
        assertEquals(PesPayload.Completeness.ENDED_WITH_STREAM,
                closedByTheStreamEnding.getCompleteness());
    }

    /** A declared length that never arrives marks the unit short wherever in the file it happens. */
    @Test
    public void testDeclaredLengthNotReachedIsReportedShort() {
        TransportStreamBuilder builder = program()
                .pesPacketDeclaring(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 10), 400)
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS + 3003, bytes(0, 10));

        assertTrue(decode(builder));
        PesPayload truncated = sink.payloadsOn(VIDEO_PID).get(0);
        assertEquals("It promised 400 bytes and one packet cannot hold them",
                PesPayload.Completeness.SHORT_OF_DECLARED_LENGTH, truncated.getCompleteness());
        assertEquals(false, truncated.isComplete());
    }

    /**
     * A region cut out of the output is reported to the consumer as well as marked in the bytes,
     * with the size of what it did not receive. This is the path the whole discontinuity contract
     * exists for, and it needs a stream over a megabyte long because that is where decryption
     * resumes after a loss of synchronization.
     */
    @Test
    public void testExcisionIsReportedWithWhatWasNotDelivered() {
        TransportStreamBuilder builder = program()
                .tivoPrivateData(PRIVATE_PID, new int[]{VIDEO_PID}, new int[]{0xe0},
                        DecoderTestHarness.SET_KEY)
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 8))
                .unsynchronizedBytes(100)
                .filler(VIDEO_PID, 6000)
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS + 3003, bytes(0, 8));

        assertTrue(decode(builder));
        assertTrue("The break was reported", sink.discontinuityPids.contains(VIDEO_PID));

        int index = sink.discontinuityPids.indexOf(VIDEO_PID);
        DiscontinuityReason reason = sink.discontinuityReasons.get(index);
        assertEquals(DiscontinuityReason.Kind.EXCISION, reason.getKind());
        assertTrue("and says how much went missing, rather than reporting a break of no size",
                reason.getDroppedBytes() > 0);
        assertTrue(reason.getDroppedPackets() > 0);
    }

    /**
     * Payload that could not be decrypted is ciphertext however plausible it looks, so it is
     * announced as a gap rather than assembled into a unit of noise carrying a real timestamp.
     * Reported once per run: one corpus recording fails on thousands of packets in a row.
     */
    @Test
    public void testUndecryptablePayloadIsAnnouncedRatherThanDelivered() {
        TransportStreamBuilder builder = program()
                // Keys for the audio PID only, so every video packet fails
                .tivoPrivateData(PRIVATE_PID, new int[]{AUDIO_PID}, new int[]{0xc0},
                        DecoderTestHarness.SET_KEY)
                .scrambledPesPacket(VIDEO_PID)
                .scrambledPesPacket(VIDEO_PID)
                .scrambledPesPacket(VIDEO_PID);

        assertEquals("A stream that never decrypted is not a usable decode", false, decode(builder));
        assertEquals("Nothing was handed over for it", 0, sink.payloadsOn(VIDEO_PID).size());
        assertEquals("and the run of failures was reported once, not three times",
                1, sink.discontinuityPids.size());
        assertEquals(DiscontinuityReason.Kind.DECRYPTION_FAILED,
                sink.discontinuityReasons.get(0).getKind());
        assertEquals(VIDEO_PID, (int) sink.discontinuityPids.get(0));
    }

    /**
     * Compatibility mode keeps a damaged region in the output rather than cutting it, but those
     * packets were never decrypted, so a consumer must not be handed them and must still be told
     * the stream broke. What the sink sees does not depend on a mode that exists to match another
     * implementation's bytes.
     *
     * The count has to be the total, which means reporting where the stream resumes rather than
     * where the damage starts. Reporting at the start announces a break of no size and leaves the
     * resume point silent.
     */
    @Test
    public void testCompatibilityModeReportsTheBreakWithItsFullSize() {
        TransportStreamBuilder builder = program()
                .tivoPrivateData(PRIVATE_PID, new int[]{VIDEO_PID}, new int[]{0xe0},
                        DecoderTestHarness.SET_KEY)
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 8))
                .unsynchronizedBytes(100)
                .filler(VIDEO_PID, 6000)
                .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS + 3003, bytes(0, 8));

        assertTrue(decode(builder, true));
        assertTrue("The break reached the consumer even though the bytes were kept",
                sink.discontinuityPids.contains(VIDEO_PID));

        DiscontinuityReason reason =
                sink.discontinuityReasons.get(sink.discontinuityPids.indexOf(VIDEO_PID));
        assertEquals(DiscontinuityReason.Kind.EXCISION, reason.getKind());
        assertTrue("carrying what the consumer did not receive, not zero",
                reason.getDroppedBytes() > 0);
        assertTrue(reason.getDroppedPackets() > 0);
    }

    /**
     * A consumer that throws gets its own exception back, not a disk error, and is not called again
     * on the way out. Flushing into a sink that just failed would call straight back into it while
     * its exception is still travelling, and a throw from there would replace the original.
     */
    @Test
    public void testAConsumerThatThrowsIsNotCalledAgain() {
        RuntimeException thrown = new IllegalStateException("the muxer gave up");
        CountingDataInputStream input = new CountingDataInputStream(new ByteArrayInputStream(
                program()
                        .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS, bytes(0, 8))
                        .pesPacket(VIDEO_PID, VIDEO_STREAM_ID, A_PTS + 3003, bytes(0, 8))
                        .toByteArray()));
        RecordingFrameSink recorder = new RecordingFrameSink() {
            @Override
            public void onPesPayload(PesPayload payload) {
                super.onPesPayload(payload);
                throw thrown;
            }
        };
        TransportStreamDecoder decoder = new TransportStreamDecoder(
                new TuringDecoder(new byte[Stream.KEY_LENGTH + 4]), 0, input,
                new ByteArrayOutputStream(), false, recorder, true);

        try {
            decoder.process();
            org.junit.Assert.fail("The consumer's exception should have reached the caller");
        } catch (RuntimeException e) {
            assertEquals("Its own exception, not one describing a write failure", thrown, e);
        }
        assertEquals("and it was handed nothing after it threw", 1, recorder.payloads.size());
    }

    /** The end result names the streams that never decrypted, so a consumer can salvage the rest. */
    @Test
    public void testResultNamesTheStreamThatNeverDecrypted() {
        TransportStreamBuilder builder = program()
                // Keys for the audio PID only, so the video packet below never decrypts
                .tivoPrivateData(PRIVATE_PID, new int[]{AUDIO_PID}, new int[]{0xc0},
                        DecoderTestHarness.SET_KEY)
                .scrambledPesPacket(AUDIO_PID)
                .scrambledPesPacket(VIDEO_PID);

        assertEquals("An undecryptable stream is not a usable decode", false, decode(builder));
        assertEquals(1, sink.endCount);
        assertEquals(false, sink.result.isUsable());
        assertTrue("and it says which one", sink.result.getPidsNeverDecrypted().contains(VIDEO_PID));
        assertTrue("without condemning the one that worked",
                !sink.result.getPidsNeverDecrypted().contains(AUDIO_PID));
    }
}
