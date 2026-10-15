package com.sprintiq.pms.modules.ai.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drivers are stored as JSON on every assessment row, so adding the SHAP fix to the
 * record has to leave every row written before it readable — and the new field has to
 * survive the round trip, or the panel would show the reason without its remedy.
 */
class PredictionDriverJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a driver stored before fixes existed still reads, with no fix")
    void oldRowsStillRead() throws Exception {
        PredictionDriver driver = json.readValue("""
                {"feature":"complexity","label":"estimated complexity",
                 "contribution":1.99,"direction":"INCREASE","detail":"3.2"}
                """, PredictionDriver.class);

        assertThat(driver.feature()).isEqualTo("complexity");
        assertThat(driver.fix()).isNull();
    }

    @Test
    @DisplayName("a SHAP fix survives being stored and read back")
    void fixRoundTrips() throws Exception {
        PredictionDriver driver = json.readValue("""
                {"feature":"complexity","label":"estimated complexity",
                 "contribution":1.99,"direction":"INCREASE","detail":"3.2",
                 "fix":{"method":"shap","kind":"direct","shap_now":1.99,
                        "move":{"feature":"complexity","from":3.2,"to":2.24},
                        "probability_now":0.959,"probability_after":0.66,
                        "summary":"SHAP: estimated complexity adds +1.99."}}
                """, PredictionDriver.class);

        PredictionDriver back = json.readValue(json.writeValueAsString(driver), PredictionDriver.class);

        assertThat(back.fix()).containsEntry("method", "shap").containsEntry("kind", "direct");
        assertThat(back.fix().get("summary")).isEqualTo("SHAP: estimated complexity adds +1.99.");
    }

    @Test
    @DisplayName("the five-field constructor every other model uses still means no fix")
    void otherModelsCarryNoFix() {
        PredictionDriver driver = PredictionDriver.up("blocked_count", "Blocked work",
                BigDecimal.ONE, "2 blocked");

        assertThat(driver.fix()).isNull();
    }
}
