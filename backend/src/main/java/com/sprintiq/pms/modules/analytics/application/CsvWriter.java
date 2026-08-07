package com.sprintiq.pms.modules.analytics.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Minimal RFC 4180 CSV writer.
 *
 * <p>Hand-rolled rather than pulled in as a dependency: the whole surface is quoting and
 * a line separator, and the one thing a library would add — a formula-injection guard —
 * has to be written deliberately anyway, because most of them do not do it.</p>
 */
public final class CsvWriter {

    /**
     * Characters that make Excel and Sheets treat a cell as a formula.
     *
     * <p>A story titled {@code =HYPERLINK("http://evil","click")} is a perfectly legal
     * story title and a working attack the moment someone opens the export. Prefixing
     * with an apostrophe is the standard defusal: spreadsheets treat the cell as text
     * and hide the apostrophe, so the value still reads correctly.</p>
     */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private final StringBuilder buffer = new StringBuilder(8192);
    private int rows;

    public CsvWriter header(String... columns) {
        return row((Object[]) columns);
    }

    public CsvWriter row(Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) buffer.append(',');
            buffer.append(escape(values[index]));
        }
        buffer.append("\r\n");
        rows++;
        return this;
    }

    public CsvWriter rows(List<Object[]> values) {
        values.forEach(this::row);
        return this;
    }

    /** Data rows written, excluding the header. */
    public int dataRowCount() {
        return Math.max(0, rows - 1);
    }

    public String toCsv() {
        return buffer.toString();
    }

    public byte[] toBytes() {
        // A BOM, so Excel on Windows opens UTF-8 as UTF-8 instead of as Latin-1 and
        // turning every non-ASCII name into mojibake.
        String content = "﻿" + buffer;
        return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ internals

    private String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof BigDecimal decimal
                ? decimal.toPlainString()
                : String.valueOf(value);

        if (!text.isEmpty() && FORMULA_TRIGGERS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }

        boolean needsQuoting = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;

        return needsQuoting ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }
}
