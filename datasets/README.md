# Template datasets

Every public source is converted to the normalized NDJSON contract in
`template-event.schema.json` before it is replayed into Java Analytics. The
small files in `templates/` are executable format fixtures, not substituted
copies of the original datasets.

`baseline_scope` is mandatory. Java Analytics separates profiles by the pair
`baseline_scope + backend`; therefore, a public dataset cannot train or alter
the production `gateway` baseline.

Run a fixture after Java Analytics is listening on UDP 9090:

```bash
python3 tools/replay-template.py datasets/templates/world-cup-1998.ndjson --speed 10
```

For a real dataset, create an adapter that preserves the source event order,
maps source fields to this contract, assigns the catalogued scope, and writes
NDJSON. Do not use `baseline_scope: gateway` for any public source.

The sources are evaluated independently:

- `world-cup-1998` — initial volume, peak and seasonality validation;
- `online-shop-bots` — bot scenarios once exact Kaggle version and licence are pinned;
- `zenodo-web-server-logs` — проверка применимости механизма построения baseline на независимом HTTP-сервисе;
- `encrypted-web-traffic` — HTTP event/flow correlation; network evidence remains linked rather than being collapsed into the HTTP baseline;
- `microservice-tracing` — latency and execution-path scenarios, separate from edge HTTP traffic.

The current decision record is [the HTTP dataset audit](audits/http-dataset-audit-2026-09.md).
It approves no public source for `gateway` training: templates remain fixtures
until a source satisfies every mandatory field and has a documented trusted
normal collection period.

The initial VEC-24 baseline is instead collected from Gateway in a controlled
trusted-normal period. See [the capture procedure](trusted-normal-capture.md).
