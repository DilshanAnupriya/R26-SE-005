"use client";

import { useId, useState, type PointerEvent as ReactPointerEvent } from "react";
import { cn } from "@/lib/utils";

/**
 * Charts, as inline SVG.
 *
 * <p>Hand-drawn rather than pulled from a charting library. These are four small,
 * fixed shapes — a line pair, bars, a stacked area, a histogram — and a general-purpose
 * library would add a substantial bundle to every page that shows one, for
 * configurability nothing here needs.</p>
 *
 * <p>Every chart carries a table-shaped accessible summary. A chart is an image to a
 * screen reader, and "chart" is not a data point; the numbers have to be reachable some
 * other way, and a visually-hidden table is the way that does not compromise the
 * visual.</p>
 */

const PALETTE = {
  primary: "var(--color-primary, oklch(0.55 0.2 260))",
  muted: "var(--color-muted-foreground, oklch(0.6 0.02 260))",
  positive: "oklch(0.65 0.17 150)",
  warning: "oklch(0.75 0.16 75)",
  danger: "oklch(0.62 0.22 25)",
};

function niceMax(value: number) {
  if (value <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  return Math.ceil(value / magnitude) * magnitude;
}

// ------------------------------------------------------------------ line chart

export interface LineSeries {
  label: string;
  color?: string;
  dashed?: boolean;
  points: (number | null)[];
}

/**
 * The contiguous runs of scored points in a series.
 *
 * <p>A series is drawn as one path per run rather than as a single path with the nulls
 * dropped. Dropping them joins the two sides of a gap with a straight line, which
 * invents a reading for every day nobody scored; and a run reduced to a single point
 * would disappear, because the first command of a path is a move and a move draws
 * nothing. Runs of one are rendered as a dot instead.</p>
 */
function runsOf(points: (number | null)[]): number[][] {
  const runs: number[][] = [];
  let current: number[] = [];
  points.forEach((value, index) => {
    if (value == null) {
      if (current.length > 0) {
        runs.push(current);
      }
      current = [];
      return;
    }
    current.push(index);
  });
  if (current.length > 0) {
    runs.push(current);
  }
  return runs;
}

/** Burndown and burn-up: two or three series over the same x-axis. */
export function LineChart({
  labels,
  series,
  height = 200,
  yLabel,
  className,
  showXAxis = false,
  hover = false,
  valueSuffix = "",
}: {
  labels: string[];
  series: LineSeries[];
  height?: number;
  yLabel?: string;
  className?: string;
  /** Draw a tick under every point. Off by default — long labels need the room. */
  showXAxis?: boolean;
  /** Follow the cursor with a guide line and a readout of every series at that point. */
  hover?: boolean;
  /** Appended to values in the readout. `%` for a chart of percentages. */
  valueSuffix?: string;
}) {
  const titleId = useId();
  const [activeIndex, setActiveIndex] = useState<number | null>(null);

  const width = 640;
  const sideInset = { left: 36, right: 12 };
  const plotWidth = width - sideInset.left - sideInset.right;
  const step = labels.length <= 1 ? plotWidth : plotWidth / (labels.length - 1);
  /**
   * Ticks lie down once they would collide.
   *
   * <p>Every day of the sprint gets a tick, so the threshold is what keeps a four-week
   * sprint legible rather than a smear of overlapping dates.</p>
   */
  const rotateTicks = showXAxis && step < 34;
  const padding = {
    top: 12,
    right: sideInset.right,
    bottom: showXAxis ? (rotateTicks ? 48 : 32) : 24,
    left: sideInset.left,
  };
  const plotHeight = height - padding.top - padding.bottom;

  const allValues = series.flatMap((line) => line.points).filter((value): value is number => value != null);
  const max = niceMax(Math.max(1, ...allValues));

  const x = (index: number) => padding.left + (labels.length <= 1 ? 0 : index * step);
  const y = (value: number) => padding.top + plotHeight - (value / max) * plotHeight;
  const tickY = padding.top + plotHeight + 14;

  if (labels.length === 0) {
    return <ChartEmpty className={className} />;
  }

  const active = hover && activeIndex != null && activeIndex < labels.length ? activeIndex : null;

  /** Pointer position to nearest point. Measured off the box, so any scaling is included. */
  const trackPointer = (event: ReactPointerEvent<SVGSVGElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    if (box.width === 0) {
      return;
    }
    const local = ((event.clientX - box.left) / box.width) * width;
    const nearest = labels.length <= 1 ? 0 : Math.round((local - padding.left) / step);
    setActiveIndex(Math.min(labels.length - 1, Math.max(0, nearest)));
  };

  return (
    <figure className={cn("w-full", className)}>
      <div className="relative">
        <svg
          viewBox={`0 0 ${width} ${height}`}
          className="h-auto w-full"
          role="img"
          aria-labelledby={titleId}
          preserveAspectRatio="none"
          onPointerMove={hover ? trackPointer : undefined}
          onPointerLeave={hover ? () => setActiveIndex(null) : undefined}
        >
          <title id={titleId}>
            {series.map((line) => line.label).join(" and ")} over {labels.length} points
          </title>

          {/* Gridlines, quarters. More would be clutter; fewer would make the scale a guess. */}
          {[0, 0.25, 0.5, 0.75, 1].map((fraction) => (
            <g key={fraction}>
              <line
                x1={padding.left}
                x2={width - padding.right}
                y1={y(max * fraction)}
                y2={y(max * fraction)}
                stroke="currentColor"
                strokeOpacity={0.1}
              />
              <text
                x={padding.left - 6}
                y={y(max * fraction) + 3}
                textAnchor="end"
                className="fill-current text-[9px] opacity-50"
              >
                {Math.round(max * fraction)}
              </text>
            </g>
          ))}

          {showXAxis
            ? labels.map((label, index) => (
                <text
                  key={`tick-${label}-${index}`}
                  x={x(index)}
                  y={tickY}
                  textAnchor={rotateTicks ? "end" : "middle"}
                  transform={rotateTicks ? `rotate(-45 ${x(index)} ${tickY})` : undefined}
                  className={cn(
                    "fill-current text-[9px]",
                    index === active ? "font-semibold opacity-100" : "opacity-60",
                  )}
                >
                  {label}
                </text>
              ))
            : null}

          {active != null ? (
            <line
              x1={x(active)}
              x2={x(active)}
              y1={padding.top}
              y2={padding.top + plotHeight}
              stroke="currentColor"
              strokeOpacity={0.28}
              strokeWidth={1}
              strokeDasharray="3 3"
            />
          ) : null}

          {series.map((line) => {
            const stroke = line.color ?? PALETTE.primary;
            return (
              <g key={line.label}>
                {runsOf(line.points).map((run) => {
                  const first = run[0];
                  if (run.length === 1) {
                    return (
                      <circle
                        key={`dot-${first}`}
                        cx={x(first)}
                        cy={y(line.points[first] as number)}
                        r={2.5}
                        fill={stroke}
                      />
                    );
                  }
                  const path = run
                    .map(
                      (index, position) =>
                        `${position === 0 ? "M" : "L"}${x(index)},${y(line.points[index] as number)}`,
                    )
                    .join(" ");
                  return (
                    <path
                      key={`run-${first}`}
                      d={path}
                      fill="none"
                      stroke={stroke}
                      strokeWidth={2}
                      strokeDasharray={line.dashed ? "4 4" : undefined}
                      strokeLinejoin="round"
                      strokeLinecap="round"
                      vectorEffect="non-scaling-stroke"
                    />
                  );
                })}

                {active != null && line.points[active] != null ? (
                  <circle cx={x(active)} cy={y(line.points[active] as number)} r={3.5} fill={stroke} />
                ) : null}
              </g>
            );
          })}
        </svg>

        {active != null ? (
          <ChartReadout
            label={labels[active]}
            rows={series.map((line) => ({
              label: line.label,
              color: line.color ?? PALETTE.primary,
              value: line.points[active],
            }))}
            suffix={valueSuffix}
            offset={x(active) / width}
          />
        ) : null}
      </div>

      <ChartLegend items={series.map((line) => ({ label: line.label, color: line.color ?? PALETTE.primary, dashed: line.dashed }))} />
      <DataTable
        caption={yLabel ?? "Chart data"}
        columns={["Point", ...series.map((line) => line.label)]}
        rows={labels.map((label, index) => [label, ...series.map((line) => line.points[index] ?? "—")])}
      />
    </figure>
  );
}

/**
 * The hover readout.
 *
 * <p>Every series is listed, including the ones with nothing at this point. Dropping
 * them would make the popup change height as the cursor moves and, worse, would make
 * "nothing recorded" indistinguishable from "not in this chart".</p>
 */
function ChartReadout({
  label,
  rows,
  suffix,
  offset,
}: {
  label: string;
  rows: { label: string; color: string; value: number | null | undefined }[];
  suffix: string;
  offset: number;
}) {
  // Flipped past the middle so the popup never hangs off the right edge.
  const flip = offset > 0.6;
  return (
    <div
      role="status"
      aria-live="polite"
      className="pointer-events-none absolute top-2 z-10 min-w-[9rem] rounded-md border bg-popover px-2.5 py-2 text-popover-foreground shadow-md"
      style={{
        left: `${offset * 100}%`,
        transform: flip ? "translateX(calc(-100% - 10px))" : "translateX(10px)",
      }}
    >
      <p className="mb-1 text-[11px] font-semibold whitespace-nowrap">{label}</p>
      <ul className="space-y-0.5">
        {rows.map((row) => (
          <li key={row.label} className="flex items-center gap-2 text-[11px] whitespace-nowrap">
            <span
              aria-hidden
              className="inline-block size-2 shrink-0 rounded-full"
              style={{ background: row.color }}
            />
            <span className="text-muted-foreground">{row.label}</span>
            <span className="ml-auto font-medium tabular-nums">
              {row.value == null ? "—" : `${row.value}${suffix}`}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// ------------------------------------------------------------------- bar chart

/** Velocity: committed against completed, sprint by sprint. */
export function BarChart({
  labels,
  series,
  height = 200,
  className,
}: {
  labels: string[];
  series: { label: string; color?: string; values: number[] }[];
  height?: number;
  className?: string;
}) {
  const titleId = useId();
  const width = 640;
  const padding = { top: 12, right: 12, bottom: 32, left: 36 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;

  const max = niceMax(Math.max(1, ...series.flatMap((group) => group.values)));
  const groupWidth = labels.length > 0 ? plotWidth / labels.length : plotWidth;

  /**
   * Bars are capped as well as floored.
   *
   * <p>Without a ceiling, a project with one sprint renders a single bar a third of the
   * chart wide — which reads as a rendering fault rather than as one data point. The cap
   * keeps a sparse chart looking like a sparse chart.</p>
   */
  const barWidth = Math.min(
    48,
    Math.max(2, (groupWidth * 0.7) / Math.max(1, series.length)),
  );
  // Re-centre the group once the cap has narrowed it.
  const groupInset = (groupWidth - barWidth * series.length) / 2;

  if (labels.length === 0) {
    return <ChartEmpty className={className} />;
  }

  return (
    <figure className={cn("w-full", className)}>
      <svg viewBox={`0 0 ${width} ${height}`} className="h-auto w-full" role="img" aria-labelledby={titleId}>
        <title id={titleId}>{series.map((group) => group.label).join(" and ")} by sprint</title>

        {[0, 0.5, 1].map((fraction) => (
          <line
            key={fraction}
            x1={padding.left}
            x2={width - padding.right}
            y1={padding.top + plotHeight - fraction * plotHeight}
            y2={padding.top + plotHeight - fraction * plotHeight}
            stroke="currentColor"
            strokeOpacity={0.1}
          />
        ))}

        {labels.map((label, index) =>
          series.map((group, groupIndex) => {
            const value = group.values[index] ?? 0;
            const barHeight = (value / max) * plotHeight;
            const x =
              padding.left + index * groupWidth + groupInset + groupIndex * barWidth;
            return (
              <rect
                key={`${label}-${group.label}`}
                x={x}
                y={padding.top + plotHeight - barHeight}
                width={barWidth}
                height={Math.max(0, barHeight)}
                rx={2}
                fill={group.color ?? PALETTE.primary}
              />
            );
          }),
        )}

        {labels.map((label, index) => (
          <text
            key={label}
            x={padding.left + index * groupWidth + groupWidth / 2}
            y={height - 8}
            textAnchor="middle"
            className="fill-current text-[9px] opacity-60"
          >
            {label.length > 10 ? `${label.slice(0, 9)}…` : label}
          </text>
        ))}
      </svg>

      <ChartLegend items={series.map((group) => ({ label: group.label, color: group.color ?? PALETTE.primary }))} />
      <DataTable
        caption="Chart data"
        columns={["Sprint", ...series.map((group) => group.label)]}
        rows={labels.map((label, index) => [label, ...series.map((group) => group.values[index] ?? 0)])}
      />
    </figure>
  );
}

// ---------------------------------------------------------------- stacked area

/** Cumulative flow: bands that add up, drawn bottom-up. */
export function StackedAreaChart({
  labels,
  bands,
  height = 200,
  className,
}: {
  labels: string[];
  bands: { label: string; color?: string; values: number[] }[];
  height?: number;
  className?: string;
}) {
  const titleId = useId();
  const width = 640;
  const padding = { top: 12, right: 12, bottom: 20, left: 32 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;

  const totals = labels.map((_label, index) =>
    bands.reduce((sum, band) => sum + (band.values[index] ?? 0), 0),
  );
  const max = niceMax(Math.max(1, ...totals));

  const x = (index: number) =>
    padding.left + (labels.length <= 1 ? 0 : (index / (labels.length - 1)) * plotWidth);
  const y = (value: number) => padding.top + plotHeight - (value / max) * plotHeight;

  if (labels.length === 0) {
    return <ChartEmpty className={className} />;
  }

  const colors = [PALETTE.muted, PALETTE.warning, PALETTE.positive];

  /**
   * Each band's lower and upper edge, accumulated up front.
   *
   * <p>Computed in one pass before rendering rather than by carrying a running total
   * through the `map`. Mutating a variable while producing elements makes the output
   * depend on render order, which React is explicitly allowed to change.</p>
   */
  const edges = bands.reduce<{ lower: number[]; upper: number[] }[]>((accumulated, band) => {
    const lower = accumulated.at(-1)?.upper ?? labels.map(() => 0);
    const upper = labels.map((_label, index) => lower[index] + (band.values[index] ?? 0));
    return [...accumulated, { lower, upper }];
  }, []);

  return (
    <figure className={cn("w-full", className)}>
      <svg viewBox={`0 0 ${width} ${height}`} className="h-auto w-full" role="img" aria-labelledby={titleId}>
        <title id={titleId}>Cumulative flow across {labels.length} days</title>

        {bands.map((band, bandIndex) => {
          const { lower, upper } = edges[bandIndex];

          const top = upper
            .map((value, index) => `${index === 0 ? "M" : "L"}${x(index)},${y(value)}`)
            .join(" ");
          // Traced back along the lower edge to close the shape.
          const bottom = lower
            .map((_value, index) => {
              const reversed = lower.length - 1 - index;
              return `L${x(reversed)},${y(lower[reversed])}`;
            })
            .join(" ");

          return (
            <path
              key={band.label}
              d={`${top} ${bottom} Z`}
              fill={band.color ?? colors[bandIndex % colors.length]}
              fillOpacity={0.75}
            />
          );
        })}
      </svg>

      <ChartLegend
        items={bands.map((band, index) => ({
          label: band.label,
          color: band.color ?? colors[index % colors.length],
        }))}
      />
      <DataTable
        caption="Cumulative flow"
        columns={["Date", ...bands.map((band) => band.label)]}
        rows={labels.map((label, index) => [label, ...bands.map((band) => band.values[index] ?? 0)])}
      />
    </figure>
  );
}

// ------------------------------------------------------------------ primitives

function ChartLegend({
  items,
}: {
  items: { label: string; color: string; dashed?: boolean }[];
}) {
  return (
    <figcaption className="mt-2 flex flex-wrap gap-3">
      {items.map((item) => (
        <span key={item.label} className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
          <span
            aria-hidden
            className="h-0.5 w-4 rounded-full"
            style={{
              backgroundColor: item.dashed ? "transparent" : item.color,
              backgroundImage: item.dashed
                ? `repeating-linear-gradient(to right, ${item.color} 0 4px, transparent 4px 8px)`
                : undefined,
            }}
          />
          {item.label}
        </span>
      ))}
    </figcaption>
  );
}

/**
 * The numbers behind the picture, for screen readers.
 *
 * <p>Visually hidden rather than collapsed behind a toggle: a toggle is one more thing
 * to find, and the table costs nothing to anyone who is not reading it.</p>
 */
function DataTable({
  caption,
  columns,
  rows,
}: {
  caption: string;
  columns: string[];
  rows: (string | number)[][];
}) {
  return (
    <table className="sr-only">
      <caption>{caption}</caption>
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column} scope="col">
              {column}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={index}>
            {row.map((cell, cellIndex) => (
              <td key={cellIndex}>{cell}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ChartEmpty({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "flex h-40 items-center justify-center rounded-md border border-dashed text-xs text-muted-foreground",
        className,
      )}
    >
      Not enough data to chart yet
    </div>
  );
}

export const chartPalette = PALETTE;
