/**
 * The design's chart set, ported from its hand-rolled SVG helpers.
 *
 * <p>Kept as plain SVG rather than pulling in a charting library. These are four
 * small, fixed shapes; a library would add a large dependency and then need
 * configuring back into exactly this look. Rendering the elements directly also
 * means the charts inherit theme tokens automatically — a `var(--accent)` stroke
 * simply changes when the theme does, with no re-render.</p>
 *
 * <p>All of them are `preserveAspectRatio="none"` on a fixed viewBox, matching the
 * design: the chart stretches to its container's width and keeps its stated height.</p>
 */

export type Series = {
  values: number[];
  color: string;
  /** Dashed stroke — the design uses it for the ideal burndown line. */
  dashed?: boolean;
  /** Fills beneath the line at low opacity. */
  area?: boolean;
  label?: string;
};

export function BarChart({
  values,
  color = "var(--accent)",
  labels,
  showValues = false,
  height = 150,
  max,
  className,
}: {
  values: number[];
  color?: string | string[];
  labels?: string[];
  showValues?: boolean;
  height?: number;
  max?: number;
  className?: string;
}) {
  const width = 320;
  const pad = 8;
  const gap = 9;
  const labelHeight = labels ? 16 : 2;
  const ceiling = max ?? Math.max(...values, 1) * 1.1;
  const barWidth = (width - pad * 2 - gap * (values.length - 1)) / values.length;
  const plot = height - labelHeight - 6;

  return (
    <svg
      width="100%"
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      className={className}
      style={{ display: "block", overflow: "visible" }}
      role="presentation"
    >
      {values.map((value, index) => {
        const barHeight = Math.max(2, (value / ceiling) * plot);
        const x = pad + index * (barWidth + gap);
        const fill = Array.isArray(color) ? color[index] : color;
        return (
          <g key={index}>
            <rect x={x} y={6 + plot - barHeight} width={barWidth} height={barHeight} rx={4} fill={fill} />
            {showValues ? (
              <text
                x={x + barWidth / 2}
                y={plot - barHeight}
                dy={-3}
                textAnchor="middle"
                fontSize={10}
                fontWeight={600}
                fill="var(--ink-2)"
              >
                {value}
              </text>
            ) : null}
            {labels ? (
              <text
                x={x + barWidth / 2}
                y={height - 3}
                textAnchor="middle"
                fontSize={10}
                fill="var(--ink-3)"
              >
                {labels[index]}
              </text>
            ) : null}
          </g>
        );
      })}
    </svg>
  );
}

export function LineChart({
  series,
  height = 150,
  max,
  className,
}: {
  series: Series[];
  height?: number;
  max?: number;
  className?: string;
}) {
  const width = 320;
  const pad = 8;
  const plot = height - 18;
  const points = Math.max(...series.map((s) => s.values.length), 2);
  const ceiling = max ?? Math.max(...series.flatMap((s) => s.values), 1) * 1.1;

  const toX = (index: number) => pad + (index / (points - 1)) * (width - pad * 2);
  const toY = (value: number) => 6 + (1 - value / ceiling) * (plot - 6);

  return (
    <svg
      width="100%"
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      className={className}
      style={{ display: "block" }}
      role="presentation"
    >
      {/* Three gridlines, as the design has them — enough to read a value against. */}
      {[0, 0.5, 1].map((fraction, index) => (
        <line
          key={`grid-${index}`}
          x1={pad}
          x2={width - pad}
          y1={6 + fraction * (plot - 6)}
          y2={6 + fraction * (plot - 6)}
          stroke="var(--line)"
          strokeWidth={1}
        />
      ))}

      {series.map((s, seriesIndex) => {
        const path = s.values.map((value, index) => `${toX(index)},${toY(value)}`).join(" ");
        return (
          <g key={seriesIndex}>
            {s.area ? (
              <polygon
                points={`${path} ${toX(s.values.length - 1)},${plot} ${pad},${plot}`}
                fill={s.color}
                opacity={0.12}
              />
            ) : null}
            <polyline
              points={path}
              fill="none"
              stroke={s.color}
              strokeWidth={s.dashed ? 2 : 2.5}
              strokeDasharray={s.dashed ? "5 5" : undefined}
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          </g>
        );
      })}
    </svg>
  );
}

export function Donut({
  segments,
  size = 130,
  thickness = 16,
  className,
}: {
  segments: { value: number; color: string; label?: string }[];
  size?: number;
  thickness?: number;
  className?: string;
}) {
  const radius = (size - thickness) / 2;
  const circumference = 2 * Math.PI * radius;
  const centre = size / 2;
  const total = segments.reduce((sum, s) => sum + s.value, 0) || 1;

  // Arc lengths and their start offsets are computed up front rather than by
  // accumulating inside the map. React makes no promise about how many times a
  // render runs, and a counter that survives between those runs draws a donut whose
  // segments have crept round the circle.
  const lengths = segments.map((segment) => (segment.value / total) * circumference);
  const offsets = lengths.map((_, index) =>
    lengths.slice(0, index).reduce((sum, length) => sum + length, 0),
  );

  return (
    <svg width={size} height={size} className={className} role="presentation">
      <circle
        cx={centre}
        cy={centre}
        r={radius}
        fill="none"
        stroke="var(--surface-3)"
        strokeWidth={thickness}
      />
      {segments.map((segment, index) => (
        <circle
          key={index}
          cx={centre}
          cy={centre}
          r={radius}
          fill="none"
          stroke={segment.color}
          strokeWidth={thickness}
          strokeDasharray={`${lengths[index]} ${circumference - lengths[index]}`}
          strokeDashoffset={-offsets[index]}
          // Rotated so the first segment starts at twelve o'clock rather than
          // three, which is where SVG's zero angle is.
          transform={`rotate(-90 ${centre} ${centre})`}
        />
      ))}
    </svg>
  );
}

/**
 * Burndown: the ideal line dashed, actual solid, as the design draws it.
 *
 * <p>The two are deliberately the same chart rather than two stacked ones — the
 * whole point of a burndown is the gap between them, and separate charts make that
 * gap something the reader has to compute.</p>
 */
export function Burndown({
  ideal,
  actual,
  height = 150,
  className,
}: {
  ideal: number[];
  actual: number[];
  height?: number;
  className?: string;
}) {
  return (
    <LineChart
      className={className}
      height={height}
      series={[
        { values: ideal, color: "var(--ink-3)", dashed: true, label: "Ideal" },
        { values: actual, color: "var(--accent)", area: true, label: "Actual" },
      ]}
    />
  );
}
