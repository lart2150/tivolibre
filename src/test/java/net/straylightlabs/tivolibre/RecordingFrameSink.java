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
import java.util.List;

/** Keeps everything a FrameSink is handed, so a test can assert against it afterwards. */
class RecordingFrameSink implements FrameSink {
    final List<List<ElementaryStreamInfo>> programs = new ArrayList<>();
    final List<PesPayload> payloads = new ArrayList<>();
    final List<Integer> discontinuityPids = new ArrayList<>();
    final List<DiscontinuityReason> discontinuityReasons = new ArrayList<>();
    DecodeResult result;
    int endCount;

    @Override
    public void onProgram(List<ElementaryStreamInfo> streams) {
        programs.add(new ArrayList<>(streams));
    }

    @Override
    public void onPesPayload(PesPayload payload) {
        payloads.add(payload);
    }

    @Override
    public void onDiscontinuity(int pid, DiscontinuityReason reason) {
        discontinuityPids.add(pid);
        discontinuityReasons.add(reason);
    }

    @Override
    public void onEnd(DecodeResult result) {
        this.result = result;
        endCount++;
    }

    List<PesPayload> payloadsOn(int pid) {
        List<PesPayload> matching = new ArrayList<>();
        for (PesPayload payload : payloads) {
            if (payload.getPid() == pid) {
                matching.add(payload);
            }
        }
        return matching;
    }

    /** The PIDs of the most recent program announcement, which is always the complete set. */
    List<Integer> lastProgramPids() {
        List<Integer> pids = new ArrayList<>();
        if (!programs.isEmpty()) {
            for (ElementaryStreamInfo stream : programs.get(programs.size() - 1)) {
                pids.add(stream.getPid());
            }
        }
        return pids;
    }
}
