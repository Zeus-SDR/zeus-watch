// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Reads native colors from the same default palette as the station web client. */
public final class ThemeLoader {
    private ThemeLoader() { }

    public static WatchView.Theme load(Context context) throws IOException {
        String css;
        try (InputStream input = context.getAssets().open("design-tokens.css");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            css = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        Map<String, Integer> colors = PaletteParser.parse(css);
        return new WatchView.Theme(
                PaletteParser.require(colors, "--bg-app"),
                PaletteParser.require(colors, "--bg-1"),
                PaletteParser.require(colors, "--fg-0"),
                PaletteParser.require(colors, "--fg-2"),
                PaletteParser.require(colors, "--accent-bright"),
                PaletteParser.require(colors, "--tx"),
                PaletteParser.require(colors, "--line-strong"));
    }
}
