export type CabinetChartPoint = {
  label: string;
  value: number;
  height: number;
};

export type CabinetBarChart = {
  points: CabinetChartPoint[];
  ticks: string[];
};

export type CabinetLineChartPoint = {
  label: string;
  value: number;
  x: number;
  y: number;
};

export type CabinetLineChartSeries = {
  label: string;
  color: string;
  points: string;
  pointsData: CabinetLineChartPoint[];
};

export type CabinetLineChart = {
  series: CabinetLineChartSeries[];
  ticks: string[];
  months: string[];
  gridLines: number[];
  plotStart: number;
  plotEnd: number;
  viewBox: string;
};

type ChartScale = {
  max: number;
  ticks: string[];
  tickValues: number[];
};

type YearlyLineChartOptions = {
  fallbackYear?: number;
};

const MONTH_LABELS = ['Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн', 'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек'];
const YEAR_COLORS = ['#ea3362', '#4a9a86', '#f7a35c', '#6c9bcf', '#9a7bd9', '#1b9c85', '#b28405'];
const LINE_VIEWBOX_WIDTH = 100;
const LINE_VIEWBOX_HEIGHT = 100;
const LINE_CHART_TOP = 7;
const LINE_CHART_BOTTOM = 12;
const BAR_CHART_INTERVALS = 4;
const LINE_CHART_INTERVALS = 6;
const EMPTY_BAR_TICKS = ['0', '0', '0', '0', '0'];
const EMPTY_LINE_TICKS = ['0', '0', '0', '0', '0'];

export function cabinetDailyBarChartFrom(map: string | null | undefined, dateIso: string): CabinetBarChart {
  if (!map) {
    return emptyCabinetBarChart();
  }

  try {
    const parsed = JSON.parse(map) as Record<string, number | string>;
    const date = new Date(dateIso);
    const daysInMonth = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
    const points = Array.from({ length: daysInMonth }, (_, index) => {
      const day = index + 1;
      return {
        label: String(day),
        value: numberValue(parsed[String(day)])
      };
    });

    return barChart(points);
  } catch {
    return emptyCabinetBarChart();
  }
}

export function cabinetYearlyLineChartFrom(
  map: string | null | undefined,
  options: YearlyLineChartOptions = {}
): CabinetLineChart {
  if (!map) {
    return emptyCabinetLineChart();
  }

  try {
    const parsed = JSON.parse(map) as Record<string, Record<string, number | string> | number | string>;
    const yearlyData = yearlyDataFrom(parsed, options.fallbackYear);
    const years = Object.keys(yearlyData).sort();
    const allValues = years.flatMap((year) => {
      const monthlyData = yearlyData[year] ?? {};
      return MONTH_LABELS.map((_, index) => numberValue(monthlyData[String(index + 1)]));
    });
    const scale = buildScale(allValues, LINE_CHART_INTERVALS, true);
    const plotHeight = LINE_VIEWBOX_HEIGHT - LINE_CHART_TOP - LINE_CHART_BOTTOM;
    const yFor = (value: number) => LINE_CHART_TOP + plotHeight - (value / scale.max) * plotHeight;
    const xFor = (index: number) => ((index + 0.5) * LINE_VIEWBOX_WIDTH) / MONTH_LABELS.length;

    const series = years.map((year, index) => {
      const monthlyData = yearlyData[year] ?? {};
      const pointsData = MONTH_LABELS.map((label, monthIndex) => {
        const value = numberValue(monthlyData[String(monthIndex + 1)]);
        return {
          label,
          value,
          x: xFor(monthIndex),
          y: yFor(value)
        };
      });

      return {
        label: `Год: ${year}`,
        color: YEAR_COLORS[index % YEAR_COLORS.length],
        points: pointsData.map((point) => `${point.x},${point.y}`).join(' '),
        pointsData
      };
    });

    return {
      series,
      ticks: scale.ticks,
      months: MONTH_LABELS,
      gridLines: scale.tickValues.map((value) => yFor(value)),
      plotStart: 0,
      plotEnd: LINE_VIEWBOX_WIDTH,
      viewBox: `0 0 ${LINE_VIEWBOX_WIDTH} ${LINE_VIEWBOX_HEIGHT}`
    };
  } catch {
    return emptyCabinetLineChart();
  }
}

function yearlyDataFrom(
  parsed: Record<string, Record<string, number | string> | number | string>,
  fallbackYear?: number
): Record<string, Record<string, number | string>> {
  const yearlyEntries = Object.entries(parsed).filter((entry): entry is [string, Record<string, number | string>] =>
    isMonthlyRecord(entry[1])
  );

  if (yearlyEntries.length > 0) {
    return Object.fromEntries(yearlyEntries);
  }

  return fallbackYear == null ? {} : { [fallbackYear]: parsed as Record<string, number | string> };
}

function numberValue(value: unknown): number {
  return typeof value === 'number' || typeof value === 'string' ? Number(value) || 0 : 0;
}

function barChart(points: Array<Omit<CabinetChartPoint, 'height'>>): CabinetBarChart {
  const scale = buildScale(points.map((point) => point.value), BAR_CHART_INTERVALS);
  return {
    points: points.map((point) => ({
      ...point,
      height: point.value > 0 ? Math.max(4, Math.round((point.value / scale.max) * 100)) : 0
    })),
    ticks: scale.ticks
  };
}

function isMonthlyRecord(value: unknown): value is Record<string, number | string> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function buildScale(values: number[], intervals: number, clampToData = false): ChartScale {
  const maxValue = Math.max(...values, 0);
  if (maxValue <= 0) {
    return {
      max: 1,
      ticks: Array.from({ length: intervals + 1 }, () => '0'),
      tickValues: Array.from({ length: intervals + 1 }, () => 0)
    };
  }

  const step = niceStep(maxValue / intervals);
  const max = clampToData ? Math.ceil(maxValue / step) * step : step * intervals;
  const tickCount = Math.round(max / step);
  const tickValues = Array.from({ length: tickCount + 1 }, (_, index) => max - step * index);

  return {
    max,
    ticks: tickValues.map((value) => formatAxisValue(value)),
    tickValues
  };
}

function niceStep(value: number): number {
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const normalized = value / magnitude;
  let niceStep = 10;

  if (normalized <= 1) {
    niceStep = 1;
  } else if (normalized <= 2) {
    niceStep = 2;
  } else if (normalized <= 2.5) {
    niceStep = 2.5;
  } else if (normalized <= 5) {
    niceStep = 5;
  }

  return niceStep * magnitude;
}

function formatAxisValue(value: number): string {
  if (Math.abs(value) >= 1000) {
    return `${new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 }).format(value / 1000)}к`;
  }

  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(value);
}

function emptyCabinetBarChart(): CabinetBarChart {
  return {
    points: [],
    ticks: EMPTY_BAR_TICKS
  };
}

function emptyCabinetLineChart(): CabinetLineChart {
  return {
    series: [],
    ticks: EMPTY_LINE_TICKS,
    months: MONTH_LABELS,
    gridLines: [],
    plotStart: 0,
    plotEnd: LINE_VIEWBOX_WIDTH,
    viewBox: `0 0 ${LINE_VIEWBOX_WIDTH} ${LINE_VIEWBOX_HEIGHT}`
  };
}
