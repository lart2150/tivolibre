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

import java.util.Collections;
import java.util.List;

/**
 * Guarantees that a sink is told the decode is over, exactly once.
 *
 * {@link FrameSink#onEnd} promises that nothing arrives after it, so a consumer can close a file
 * there. The decoder delivers it when it reaches the end of a stream, but plenty of ways out never
 * reach the decoder at all: an unreadable TiVo header, an unknown format, a program stream where a
 * sink cannot be used, an IO error, or anything thrown from inside the packet loop. A consumer that
 * finalizes in onEnd would wait forever on any of them.
 */
class TerminatingFrameSink implements FrameSink {
    private final FrameSink delegate;
    private boolean ended;

    TerminatingFrameSink(FrameSink delegate) {
        this.delegate = delegate;
    }

    /**
     * Forwarded explicitly. A wrapper that leaves a default method alone inherits the do-nothing
     * body and swallows the call instead of passing it on, which is silent and looks like the
     * decoder never produced anything.
     */
    @Override
    public void onMetadata(TivoMetadata metadata) {
        delegate.onMetadata(metadata);
    }

    @Override
    public void onProgram(List<ElementaryStreamInfo> streams) {
        delegate.onProgram(streams);
    }

    @Override
    public void onPesPayload(PesPayload payload) {
        delegate.onPesPayload(payload);
    }

    @Override
    public void onDiscontinuity(int pid, DiscontinuityReason reason) {
        delegate.onDiscontinuity(pid, reason);
    }

    @Override
    public void onEnd(DecodeResult result) {
        if (!ended) {
            ended = true;
            delegate.onEnd(result);
        }
    }

    /**
     * Report an end nobody else did. Called on the way out of a decode however it went, so a
     * consumer always learns the decode stopped even when it stopped in a way the decoder never
     * saw. The result says incomplete, which is the truth: whatever happened, the source was not
     * read to its end.
     */
    void endIfAbandoned() {
        onEnd(new DecodeResult(false, Collections.emptyList(), 0, 0, 0, 0, 0));
    }
}
