# Backend latency dashboard

The overview and full-HTTP P95/P99 graphs show histogram estimates whenever a
successful request increment is observable in the rolling five-minute window.
Sparse traffic is useful diagnostic data: it must not be hidden behind fixed
100/1000-request display thresholds.

The overview shows the worst endpoint/runtime estimate and **N for that same
endpoint/runtime**, not the sum of all traffic. Both queries use the same filters,
window and evaluation time. `increase` extrapolates to the range boundaries, so N
is explicitly approximate and can be fractional. The neutral color and visible
note avoid treating a small observed sample as evidence that the latency SLO is met.

The range picker positions the window in time; these panels always use five
minutes, as their titles state. The separate period-count panel uses the selected
range. Runtime labels remain separate across restarts. No observations, no
baseline scrape or unavailable telemetry produce no latency value; there is no
zero or previous-value fallback. Process counters separately expose first
observations that occurred before the first scrape.

Run `node --test infrastructure/grafana/dashboard-queries.test.mjs` from the
repository root. It executes the actual dashboard expressions with the pinned
production Prometheus `promtool` against sparse, idle, missing, first-scrape,
multiple-runtime, filtered and large-sample fixtures. CI runs the same test.

Dashboard-only rollout: after the exact main revision passes the existing release
CI gate, retain a copy/hash of the currently provisioned JSON outside the watched
directory and atomically replace only this dashboard JSON in the existing Grafana
directory bind mount. Verify the mounted hash, the Grafana API model and the live
query results. Record the dashboard revision separately from the running app
revision. Grafana file provisioning reloads the dashboard; application images,
database state and authentication settings do not need a restart or change.
If provisioning does not load the file, restore the retained JSON rather than
claiming success from the filesystem hash alone.

References: [Prometheus histograms](https://prometheus.io/docs/practices/histograms/)
and [Grafana file provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/#dashboards).
