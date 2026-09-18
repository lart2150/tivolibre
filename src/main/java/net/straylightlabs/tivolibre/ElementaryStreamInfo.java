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

/**
 * One elementary stream as the PMT declares it.
 *
 * The stream_type is the raw byte rather than a category: a consumer choosing a codec has to tell
 * MPEG-2 (0x02) from H.264 (0x1b), and the coarse classification this library uses internally
 * collapses them together.
 */
public final class ElementaryStreamInfo {
    private final int pid;
    private final int streamType;
    private final byte[] descriptors;
    private final int programNumber;

    ElementaryStreamInfo(int pid, int streamType, byte[] descriptors, int programNumber) {
        this.pid = pid;
        this.streamType = streamType;
        this.descriptors = descriptors.clone();
        this.programNumber = programNumber;
    }

    public int getPid() {
        return pid;
    }

    /** The stream_type byte from the PMT, exactly as the recording carries it. */
    public int getStreamType() {
        return streamType;
    }

    /**
     * The descriptors from this stream's ES_info loop, unparsed. Empty when the PMT carried none,
     * which is common: TiVo's remux drops the AC-3 and language descriptors a broadcast carries.
     * Truncated to what the packet actually held if a PMT declared more than it delivered.
     */
    public byte[] getDescriptors() {
        return descriptors.clone();
    }

    public int getProgramNumber() {
        return programNumber;
    }

    @Override
    public String toString() {
        return String.format("ElementaryStreamInfo{pid=0x%04x, streamType=0x%02x, descriptors=%d bytes, "
                + "program=%d}", pid, streamType, descriptors.length, programNumber);
    }
}
