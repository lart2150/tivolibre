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

import static org.junit.Assert.assertEquals;

/**
 * The version lives in two places: the one build.gradle publishes artifacts under, and the
 * TivoDecoder.VERSION constant that the command line app and the jar manifest report. Nothing
 * stops those from drifting apart, so check them against each other.
 */
public class VersionTest {
    @Test
    public void testVersionMatchesTheBuild() {
        String buildVersion = System.getProperty("project.version");
        if (buildVersion == null) {
            // Running outside Gradle, so there is nothing to compare against
            return;
        }
        assertEquals("TivoDecoder.VERSION must match the version in build.gradle",
                buildVersion, TivoDecoder.VERSION);
    }
}
