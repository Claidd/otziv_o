# Backend latency dashboard

The overview and full-HTTP P95/P99 graphs show histogram estimates whenever a
successful request increment is observable in the rolling five-minute window.
Sparse traffic is useful diagnostic data: it must not be hidden behind fixed
100/1000-request display thresholds.

The overview shows the worst endpoint/runtime estimate and **N for that same
endpoint/runtime**, not the sum of all traffic. When maximum estimates tie, show
the smaller matching N; independently evaluated `topk(1, ...)` queries must not
pair an estimate with an arbitrary tied endpoint's larger sample. Both queries use the same filters,
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
query results. Use the authenticated Grafana admin provisioning reload API when
filesystem notifications do not arrive through the bind mount. Record the dashboard revision separately from the running app
revision. Grafana file provisioning reloads the dashboard; application images,
database state and authentication settings do not need a restart or change.
If provisioning does not load the file, restore the retained JSON rather than
claiming success from the filesystem hash alone.

References: [Prometheus histograms](https://prometheus.io/docs/practices/histograms/)
and [Grafana file provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/#dashboards).

# Telegram alerts (audit INF-3)

Grafana's own alerting evaluates the rules in `provisioning/alerting/otziv-alert-rules.json`
and sends them to the admin chats through the application bot. There is no Alertmanager.

- **Delivery.** `alerting-entrypoint.sh` runs before the image's `/run.sh` at every start.
  It copies the read-only provisioning to `/tmp/otziv-grafana-provisioning` and adds one
  Telegram integration per id in `TELEGRAM_ADMIN_CHAT_IDS` (the application's value, taken
  through the `telegram-admin-chat-ids` Compose anchor). The bot token stays in the
  environment: the file holds the reference `$TELEGRAM_BOT_TOKEN`, which Grafana expands.
  With `TELEGRAM_PROXY_ENABLED=true` the script sets `HTTPS_PROXY` from `TELEGRAM_PROXY_*`
  (HTTP or SOCKS5); Grafana's own update and plugin checks stay direct. No token or no
  valid chat id switches delivery off with a log line; the rules still show in Grafana.
  The local stand never receives the token.
- **Rules.** Application and Keycloak down, process restart loop, Docker restarts and
  failing healthchecks of every Compose service, host disk and memory, old-generation heap,
  failed or stale database backups, an ERROR burst in the application log, the client
  delivery rules from `infrastructure/prometheus/delivery-rules.yml`, and a missing
  `containers` or `host` source. Prometheus failing to answer produces Grafana's own
  `DatasourceError` alert. Grafana down sends nothing: the external monitor in
  `infrastructure/monitoring` is the independent check.
- **Sources.** Container state comes from the docker-observer metrics listener
  (`docker-observer:9323`, network `observer_metrics_net` shared only with Prometheus).
  Host memory and disk come from `prometheus.exporter.unix "host"` in Alloy. Backups export
  `otziv_backup_failures_total` and `otziv_backup_last_verified_epoch` when `BACKUP_ENABLED=true`.
- **Text.** Summaries are Russian and use `.Labels`/`.Values` (a dollar sign would be
  expanded as an environment variable by provisioning). Telegram uses HTML parse mode, so
  annotations avoid `<`, `>` and `&`.

`node --test infrastructure/grafana/alert-rules.test.mjs` checks the rule text, parity with
the Prometheus delivery rules, every expression against synthetic failures with the pinned
production `promtool`, and the entrypoint in the pinned Grafana image. To tune a threshold,
edit the JSON and rerun the test. To stop delivery, drop `TELEGRAM_BOT_TOKEN` from the
Grafana service; to silence one alert, delete its rule from the JSON.
