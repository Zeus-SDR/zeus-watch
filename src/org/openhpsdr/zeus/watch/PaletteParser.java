// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads literal colour tokens out of the station's design-token stylesheet.
 * The stylesheet is the project's single source of truth for the palette, so
 * the watch has no colours of its own to drift out of step.
 *
 * <p>Only the default {@code :root} block is read. Later theme blocks are
 * alternate themes; the watch follows the default one.
 */
public final class PaletteParser {
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern ROOT = Pattern.compile(":root\\s*\\{([^}]+)\\}", Pattern.DOTALL);
    private static final Pattern TOKEN =
            Pattern.compile("(--[a-zA-Z0-9-]+)\\s*:\\s*#([0-9a-fA-F]{6})\\s*;");

    private PaletteParser() { }

    /** Maps each literal hex token in the default theme to an opaque ARGB int. */
    public static Map<String, Integer> parse(String css) throws IOException {
        Matcher root = ROOT.matcher(COMMENT.matcher(css).replaceAll(""));
        if (!root.find()) throw new IOException("Station palette is missing its default theme");
        Map<String, Integer> colors = new HashMap<>();
        Matcher tokens = TOKEN.matcher(root.group(1));
        while (tokens.find()) {
            colors.put(tokens.group(1), 0xff000000 | Integer.parseInt(tokens.group(2), 16));
        }
        return colors;
    }

    public static int require(Map<String, Integer> colors, String key) throws IOException {
        Integer value = colors.get(key);
        if (value == null) throw new IOException("Station palette is missing " + key);
        return value;
    }
}
