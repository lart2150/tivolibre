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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How a decode turned out, delivered once at the end.
 *
 * It names the PIDs that never decrypted rather than reducing the decode to a single flag, because
 * a recording whose video is intact and whose second audio track never found a key is still a good
 * recording minus a track. A consumer can drop the dead stream and keep the rest.
 */
public final class DecodeResult {
    private final boolean completed;
    private final List<Integer> pidsNeverDecrypted;
    private final long decryptedPackets;
    private final long failedPackets;
    private final long droppedBytes;
    private final long droppedPackets;
    private final int resyncEvents;

    DecodeResult(boolean completed, List<Integer> pidsNeverDecrypted, long decryptedPackets,
                 long failedPackets, long droppedBytes, long droppedPackets, int resyncEvents) {
        this.completed = completed;
        this.pidsNeverDecrypted = Collections.unmodifiableList(new ArrayList<>(pidsNeverDecrypted));
        this.decryptedPackets = decryptedPackets;
        this.failedPackets = failedPackets;
        this.droppedBytes = droppedBytes;
        this.droppedPackets = droppedPackets;
        this.resyncEvents = resyncEvents;
    }

    /**
     * Whether the output is worth keeping: true unless the read failed part way through, or some
     * stream needed decrypting and not one of its packets ever succeeded. A recording with nothing
     * scrambled at all is usable.
     */
    public boolean isUsable() {
        return completed && pidsNeverDecrypted.isEmpty();
    }

    /**
     * Whether the source was read to the end. False means the decode stopped on an error, so the
     * output is short whatever the streams themselves managed.
     */
    public boolean isComplete() {
        return completed;
    }

    /**
     * The PIDs that needed decrypting and never managed it, usually because their keys were missing
     * from the TiVo private data stream. Empty on a clean decode.
     */
    public List<Integer> getPidsNeverDecrypted() {
        return pidsNeverDecrypted;
    }

    public long getDecryptedPackets() {
        return decryptedPackets;
    }

    /** Packets that should have decrypted and did not, including any on an otherwise usable stream. */
    public long getFailedPackets() {
        return failedPackets;
    }

    /**
     * Bytes of the recording left out of the output entirely, across the whole decode.
     *
     * Usually this is also reported stream by stream as it happens, but not always, and the
     * exception is the one worth checking for: when a recording is damaged close enough to its end
     * that no stream ever resumes, nothing is reported at all and the output simply stops early. One
     * corpus recording loses its last 9.2 MB that way. A consumer that cares whether it muxed a
     * whole recording should look here even when no break arrived.
     */
    public long getDroppedBytes() {
        return droppedBytes;
    }

    /** Packets behind {@link #getDroppedBytes()}. */
    public long getDroppedPackets() {
        return droppedPackets;
    }

    /** How many times the decoder lost and regained synchronization. */
    public int getResyncEvents() {
        return resyncEvents;
    }

    @Override
    public String toString() {
        StringBuilder pids = new StringBuilder();
        for (int pid : pidsNeverDecrypted) {
            pids.append(String.format("0x%04x ", pid));
        }
        return String.format("DecodeResult{usable=%s, complete=%s, decrypted=%,d, failed=%,d, "
                        + "dropped=%,d bytes in %,d packets over %d event(s), neverDecrypted=[%s]}",
                isUsable(), completed, decryptedPackets, failedPackets, droppedBytes, droppedPackets,
                resyncEvents, pids.toString().trim());
    }
}
