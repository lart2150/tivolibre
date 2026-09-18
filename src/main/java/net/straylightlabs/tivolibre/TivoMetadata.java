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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What a TiVo recording says about itself: the metadata documents carried in the file's own header,
 * ahead of any video.
 *
 * A recording holds a licence notice and two TvBus envelopes describing the programme. Between them
 * they carry the title, the series and episode titles, a description, air dates, duration, ratings
 * and advisories, genres, and eight separate credit lists. This class offers the same content three
 * ways, because which one is convenient depends entirely on what the consumer already has:
 *
 * <ul>
 * <li>{@link #getDocuments()} is the raw XML, exactly what the command line tool's metadata option
 *     writes out. Nothing is lost, including every field nothing here bothers to parse.</li>
 * <li>{@link #toPyTivoText()} is the pyTivo sidecar format, which is what most tooling around TiVo
 *     recordings already reads.</li>
 * <li>The getters below cover the handful of fields a container usually wants as tags.</li>
 * </ul>
 *
 * Nothing here is mapped to any container's tag names. Which metadata belongs in which tag is a
 * decision about the destination format, and this library does not know the destination.
 */
public final class TivoMetadata {
    private final List<String> documents;
    private final PyTivoMetadata parsed;

    private final static Logger logger = LoggerFactory.getLogger(TivoMetadata.class);

    private TivoMetadata(List<String> documents, PyTivoMetadata parsed) {
        this.documents = Collections.unmodifiableList(documents);
        this.parsed = parsed;
    }

    static TivoMetadata createFrom(List<Document> xmlDocuments) {
        List<String> serialized = new ArrayList<>(xmlDocuments.size());
        for (Document document : xmlDocuments) {
            String text = serialize(document);
            if (text != null) {
                serialized.add(text);
            }
        }
        return new TivoMetadata(serialized, PyTivoMetadata.createFromMetadata(xmlDocuments));
    }

    private static String serialize(Document document) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString();
        } catch (TransformerException | IllegalArgumentException e) {
            logger.error("Could not read a metadata document: ", e);
            return null;
        }
    }

    /**
     * Every metadata document the recording carries, as XML, in the order the file holds them. This
     * is the complete picture: anything the getters below do not cover is in here.
     */
    public List<String> getDocuments() {
        return documents;
    }

    /**
     * The metadata in the pyTivo sidecar format, the same text the command line tool writes beside a
     * decoded recording. Tooling that already reads those files needs no new parser for this.
     */
    public String toPyTivoText() {
        return parsed.toText();
    }

    /** The programme title. For an episode this is usually the series title. */
    public Optional<String> getTitle() {
        return text(parsed.getTitle());
    }

    public Optional<String> getSeriesTitle() {
        return text(parsed.getSeriesTitle());
    }

    public Optional<String> getEpisodeTitle() {
        return text(parsed.getEpisodeTitle());
    }

    public Optional<String> getDescription() {
        return text(parsed.getDescription());
    }

    /** When this showing was broadcast, which is not when the programme first aired. */
    public Optional<ZonedDateTime> getAirDate() {
        return Optional.ofNullable(parsed.getAirDate());
    }

    /** When the programme originally aired, where the recording says so. */
    public Optional<ZonedDateTime> getOriginalAirDate() {
        return Optional.ofNullable(parsed.getOriginalAirDate());
    }

    /** Whether this is an episode of a series rather than a one-off or a film. */
    public boolean isEpisode() {
        return parsed.isEpisode();
    }

    public OptionalInt getEpisodeNumber() {
        return number(parsed.getEpisodeNumber());
    }

    public OptionalInt getMovieYear() {
        return number(parsed.getMovieYear());
    }

    /** TiVo's own star rating, as the small integer the recording carries rather than in stars. */
    public OptionalInt getStarRating() {
        return number(parsed.getStarRating());
    }

    /** TiVo's own television rating code. */
    public OptionalInt getTvRating() {
        return number(parsed.getTvRating());
    }

    /**
     * What kind of programme this is, as the recording names it: MOVIE, SERIES, SPECIAL and so on.
     * The one field here that says whether a recording is a film without having to infer it from
     * whether a year or an episode title happens to be filled in.
     */
    public Optional<String> getShowType() {
        return text(parsed.getShowType());
    }

    public Optional<String> getMpaaRating() {
        return text(parsed.getMpaaRating());
    }

    /** The flags TiVo sets about this showing, such as whether it was a repeat. */
    public OptionalInt getShowingBits() {
        return number(parsed.getShowingBits());
    }

    public OptionalInt getColorCode() {
        return number(parsed.getColorCode());
    }

    /** TiVo's identifier for the series, stable across episodes. */
    public Optional<String> getSeriesId() {
        return text(parsed.getSeriesId());
    }

    /** TiVo's identifier for this particular programme. */
    public Optional<String> getProgramId() {
        return text(parsed.getProgramId());
    }

    public List<String> getProgramGenres() {
        return list(parsed.getProgramGenres());
    }

    /**
     * The cast, as the recording writes them: "Surname|Forename", which is worth knowing before
     * putting one in a tag. The lists below are the part of this metadata a container can carry and
     * the usual alternatives cannot, so they are given separately rather than flattened together.
     */
    public List<String> getActors() {
        return list(parsed.getActors());
    }

    public List<String> getGuestStars() {
        return list(parsed.getGuestStars());
    }

    public List<String> getDirectors() {
        return list(parsed.getDirectors());
    }

    public List<String> getWriters() {
        return list(parsed.getWriters());
    }

    public List<String> getProducers() {
        return list(parsed.getProducers());
    }

    public List<String> getExecProducers() {
        return list(parsed.getExecProducers());
    }

    public List<String> getHosts() {
        return list(parsed.getHosts());
    }

    public List<String> getChoreographers() {
        return list(parsed.getChoreographers());
    }

    /** An empty string is a field the recording did not fill in, not a value. */
    private static Optional<String> text(String value) {
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** These fields are absent rather than zero when the recording does not carry them. */
    private static OptionalInt number(int value) {
        return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
    }

    private static List<String> list(List<String> values) {
        return values == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override
    public String toString() {
        return String.format("TivoMetadata{%d document(s), title=%s}", documents.size(),
                getTitle().orElse("absent"));
    }
}
