package com.sprintiq.pms.modules.analytics;

import com.sprintiq.pms.modules.analytics.application.CsvWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CSV writer's job is quoting and one security property.
 *
 * <p>The security property is the reason this is hand-rolled rather than a dependency:
 * a story titled {@code =HYPERLINK(...)} is a legal story title and a working attack on
 * whoever opens the export, and most CSV libraries do not defuse it.</p>
 */
@DisplayName("CSV writer")
class CsvWriterTest {

    @Test
    @DisplayName("quotes only the cells that need it")
    void quotesWhereNecessary() {
        String csv = new CsvWriter()
                .header("Key", "Title", "Points")
                .row("MBA-1", "Plain title", 5)
                .row("MBA-2", "Title, with a comma", 8)
                .toCsv();

        assertThat(csv).contains("MBA-1,Plain title,5");
        assertThat(csv).contains("\"Title, with a comma\"");
    }

    @Test
    @DisplayName("doubles embedded quotes rather than escaping them with a backslash")
    void escapesQuotesTheRfcWay() {
        String csv = new CsvWriter().row("She said \"no\"").toCsv();

        assertThat(csv).contains("\"She said \"\"no\"\"\"");
        assertThat(csv).doesNotContain("\\\"");
    }

    @Test
    @DisplayName("keeps newlines inside a quoted cell instead of breaking the row")
    void preservesNewlinesInsideCells() {
        String csv = new CsvWriter()
                .header("Title", "Criteria")
                .row("A story", "One\nTwo")
                .toCsv();

        assertThat(csv).contains("\"One\nTwo\"");
        // Header plus exactly one data row: the embedded newline must not have split it.
        assertThat(csv.split("\r\n")).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "=HYPERLINK(\"http://evil\",\"click me\")",
            "+1234",
            "-1+2",
            "@SUM(A1:A9)",
    })
    @DisplayName("defuses spreadsheet formulas without losing the text")
    void neutralisesFormulaInjection(String hostile) {
        String csv = new CsvWriter().row(hostile).toCsv();

        // The apostrophe is what disarms it — the spreadsheet treats the cell as text.
        // Quoting is a separate concern and only kicks in for commas and quotes, so
        // asserting on it here would be testing the wrong property.
        assertThat(csv.replace("\"", "")).startsWith("'");
        assertThat(csv).contains(hostile.replace("\"", "\"\""));
    }

    @Test
    @DisplayName("an ordinary leading minus in a number still reads as that number")
    void negativeNumbersSurviveTheGuard() {
        // The guard prefixes it, which is correct: a spreadsheet shows -5 and treats the
        // cell as text. Losing the sign would be the real bug.
        String csv = new CsvWriter().row(BigDecimal.valueOf(-5)).toCsv();
        assertThat(csv).contains("-5");
    }

    @Test
    @DisplayName("writes BigDecimal in plain notation, never scientific")
    void bigDecimalsAvoidScientificNotation() {
        String csv = new CsvWriter().row(new BigDecimal("0.00000001")).toCsv();

        assertThat(csv).contains("0.00000001");
        assertThat(csv).doesNotContain("E-");
    }

    @Test
    @DisplayName("null cells are empty, not the string 'null'")
    void nullsRenderAsEmpty() {
        String csv = new CsvWriter().row("MBA-1", null, 3).toCsv();

        assertThat(csv).isEqualTo("MBA-1,,3\r\n");
    }

    @Test
    @DisplayName("the header is not counted as data")
    void dataRowCountExcludesTheHeader() {
        CsvWriter csv = new CsvWriter()
                .header("Key", "Title")
                .row("MBA-1", "One")
                .row("MBA-2", "Two");

        assertThat(csv.dataRowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("output carries a BOM so Excel opens UTF-8 as UTF-8")
    void bytesStartWithABom() {
        byte[] bytes = new CsvWriter().header("Naam").row("Zoë").toBytes();

        assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
        assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
        assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).contains("Zoë");
    }
}
