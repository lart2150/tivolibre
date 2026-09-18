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
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The recording's own metadata, and the ways a consumer can read it. */
public class TivoMetadataTest {
    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static TivoMetadata metadata(String... documents) throws Exception {
        List<Document> parsed = new ArrayList<>();
        for (String document : documents) {
            parsed.add(parse(document));
        }
        return TivoMetadata.createFrom(parsed);
    }

    /**
     * Every document is handed over, whatever this library makes of its contents. That is what keeps
     * a consumer from being limited to the fields somebody here thought to parse.
     */
    @Test
    public void testEveryDocumentIsAvailableAsXml() throws Exception {
        TivoMetadata metadata = metadata(
                "<license><notice>Copyright</notice></license>",
                "<TvBusEnvelope><element><title>A Show</title></element></TvBusEnvelope>");

        assertEquals("Both documents, in the order the file holds them", 2,
                metadata.getDocuments().size());
        assertTrue(metadata.getDocuments().get(0).contains("Copyright"));
        assertTrue(metadata.getDocuments().get(1).contains("A Show"));
    }

    /** A recording with no metadata at all is empty rather than broken. */
    @Test
    public void testNoDocumentsIsNotAFailure() throws Exception {
        TivoMetadata metadata = metadata();

        assertEquals(0, metadata.getDocuments().size());
        assertEquals(false, metadata.getTitle().isPresent());
    }

    /**
     * The two layers disagree about a missing title on purpose. A sidecar has to carry one, so the
     * pyTivo text falls back to "Unknown", which it has always done. A getter that did the same
     * would hand a muxer a fabricated title to write into a tag, so it reports absence instead.
     */
    @Test
    public void testTheSidecarFallbackDoesNotLeakIntoTheGetters() throws Exception {
        TivoMetadata metadata = metadata("<license><notice>Copyright</notice></license>");

        assertTrue("the sidecar still names something", metadata.toPyTivoText().contains("Unknown"));
        assertEquals("but nothing invented reaches a consumer reading fields",
                false, metadata.getTitle().isPresent());
    }

    /** A field the recording does not carry is absent, not an empty string standing in for one. */
    @Test
    public void testMissingFieldsAreAbsent() throws Exception {
        TivoMetadata metadata = metadata("<license><notice>Copyright</notice></license>");

        assertEquals(false, metadata.getTitle().isPresent());
        assertEquals(false, metadata.getEpisodeTitle().isPresent());
        assertEquals(false, metadata.getAirDate().isPresent());
    }

    /**
     * A wrapper that leaves a default method alone inherits its empty body and swallows the call.
     * That is how this arrived the first time: the metadata reached the wrapper and stopped there,
     * silently, looking exactly like a decoder that produced none.
     */
    @Test
    public void testTheTerminatingWrapperForwardsMetadata() throws Exception {
        List<TivoMetadata> received = new ArrayList<>();
        TerminatingFrameSink wrapper = new TerminatingFrameSink(new RecordingFrameSink() {
            @Override
            public void onMetadata(TivoMetadata metadata) {
                received.add(metadata);
            }
        });

        wrapper.onMetadata(metadata("<license><notice>Copyright</notice></license>"));

        assertEquals("The wrapper passed it on rather than absorbing it", 1, received.size());
        assertTrue(received.get(0).getDocuments().get(0).contains("Copyright"));
    }

    /** A consumer that does not implement it compiles and runs, which is the point of the default. */
    @Test
    public void testASinkNeedNotImplementIt() throws Exception {
        FrameSink minimal = new FrameSink() {
            @Override
            public void onProgram(List<ElementaryStreamInfo> streams) {
            }

            @Override
            public void onPesPayload(PesPayload payload) {
            }

            @Override
            public void onDiscontinuity(int pid, DiscontinuityReason reason) {
            }

            @Override
            public void onEnd(DecodeResult result) {
            }
        };

        minimal.onMetadata(metadata("<license/>"));
        assertEquals(Arrays.asList(), Arrays.asList());
    }
}
