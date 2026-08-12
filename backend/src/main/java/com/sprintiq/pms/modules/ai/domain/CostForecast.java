package com.sprintiq.pms.modules.ai.domain;

import com.sprintiq.pms.common.domain.AppendOnlyTenantEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A Model 4 cost projection for one sprint at one stage, with its error margin.
 *
 * <p>The margin is not decoration. A forecast of $48,000 ± $2,000 and a forecast of
 * $48,000 ± $19,000 support completely different decisions, and a UI that shows only
 * the point estimate invites a manager to commit to the second as if it were the
 * first. Both bounds are stored so the widget cannot render one without the other.</p>
 *
 * <p>Planning only, deliberately: this is not billing, not payroll and not an invoice.
 * It exists to answer "can we afford this sprint as planned" while the plan can still
 * change.</p>
 */
@Entity
@Table(name = "cost_forecasts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CostForecast extends AppendOnlyTenantEntity {

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "sprint_id", nullable = false, updatable = false)
    private UUID sprintId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 20, updatable = false)
    private CostStage stage;

    @Column(name = "forecast_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal forecastAmount;

    @Column(name = "error_margin_amount", precision = 14, scale = 2)
    private BigDecimal errorMarginAmount;

    @Column(name = "lower_bound", precision = 14, scale = 2)
    private BigDecimal lowerBound;

    @Column(name = "upper_bound", precision = 14, scale = 2)
    private BigDecimal upperBound;

    @Builder.Default
    @Column(name = "currency", nullable = false, columnDefinition = "bpchar(3)")
    private String currency = "USD";

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    /** {@code UP}, {@code FLAT} or {@code DOWN} against the previous forecast. */
    @Column(name = "trend", length = 10)
    private String trend;

    @Column(name = "variance_vs_budget_pct", precision = 6, scale = 2)
    private BigDecimal varianceVsBudgetPct;

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "inputs", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputs = new HashMap<>();

    @Builder.Default
    @Type(JsonType.class)
    @Column(name = "drivers", nullable = false, columnDefinition = "jsonb")
    private List<PredictionDriver> drivers = new ArrayList<>();

    @Column(name = "explanation", length = 1000)
    private String explanation;

    @Column(name = "prediction_id")
    private UUID predictionId;

    @Builder.Default
    @Column(name = "snapshot_at", nullable = false)
    private Instant snapshotAt = Instant.now();

    public static final String TREND_UP = "UP";
    public static final String TREND_FLAT = "FLAT";
    public static final String TREND_DOWN = "DOWN";
}
