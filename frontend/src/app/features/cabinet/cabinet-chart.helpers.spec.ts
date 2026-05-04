import {
  cabinetDailyBarChartFrom,
  cabinetYearlyLineChartFrom
} from './cabinet-chart.helpers';

describe('cabinet chart helpers', () => {
  it('builds a daily bar chart for the selected month', () => {
    const chart = cabinetDailyBarChartFrom('{"1":100,"2":"50","4":"bad"}', '2024-02-15');

    expect(chart.points).toHaveLength(29);
    expect(chart.points[0]).toEqual({ label: '1', value: 100, height: 100 });
    expect(chart.points[1]).toEqual({ label: '2', value: 50, height: 50 });
    expect(chart.points[3]).toEqual({ label: '4', value: 0, height: 0 });
    expect(chart.ticks).toEqual(['100', '75', '50', '25', '0']);
  });

  it('returns empty charts for missing or invalid input', () => {
    expect(cabinetDailyBarChartFrom(null, '2026-05-01')).toEqual({
      points: [],
      ticks: ['0', '0', '0', '0', '0']
    });
    expect(cabinetYearlyLineChartFrom('bad-json').series).toEqual([]);
  });

  it('builds yearly line chart series from nested year maps', () => {
    const chart = cabinetYearlyLineChartFrom('{"2025":{"1":1000,"2":"2500"},"2026":{"1":500}}');

    expect(chart.series.map((series) => series.label)).toEqual(['Год: 2025', 'Год: 2026']);
    expect(chart.series[0].pointsData[0]).toMatchObject({ label: 'Янв', value: 1000 });
    expect(chart.series[0].pointsData[1]).toMatchObject({ label: 'Фев', value: 2500 });
    expect(chart.series[1].pointsData[0]).toMatchObject({ label: 'Янв', value: 500 });
    expect(chart.months).toHaveLength(12);
    expect(chart.gridLines.length).toBeGreaterThan(0);
  });

  it('keeps home profile support for flat monthly maps with fallback year', () => {
    const chart = cabinetYearlyLineChartFrom('{"1":10,"2":"20"}', { fallbackYear: 2026 });

    expect(chart.series).toHaveLength(1);
    expect(chart.series[0].label).toBe('Год: 2026');
    expect(chart.series[0].pointsData[0]).toMatchObject({ label: 'Янв', value: 10 });
    expect(chart.series[0].pointsData[1]).toMatchObject({ label: 'Фев', value: 20 });
  });
});
