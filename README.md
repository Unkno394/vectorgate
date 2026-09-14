# VectorGate

VectorGate — reverse proxy на C++ с маршрутизацией, передачей telemetry в Java
Analytics и dashboard для наблюдения за аномалиями.

## Состав проекта

- `cpp-gateway` — proxy, маршрутизация по `Host`, failover и Control Plane;
- `java-analytics` — UDP-приёмник, baseline и оценка аномалий;
- `dashboard` — React-панель с live-векторами, инцидентами и правилами;
- `clickhouse` — хранение завершённых векторов 90 дней;
- `tools` — demo-backend, воспроизведение NDJSON и smoke-тест.

## Запуск всего стека

```bash
cd /home/user/Documents/vectorgate
cp .env.example .env
docker compose up --build
```

Перед запуском замените `GATEWAY_CONTROL_TOKEN` в `.env` на длинный случайный
секрет.

Адреса сервисов:

- dashboard: `http://localhost:5173`;
- Gateway: `http://localhost:8080`;
- Analytics API: `http://localhost:9091/api/state`;
- ClickHouse: `http://localhost:8123`.

Остановка:

```bash
docker compose down
```

## Проверка стека

После запуска контейнеров:

```bash
python3 tools/smoke_test.py
```

Smoke-тест проверяет dashboard, Gateway API, Analytics API и запрос к
demo-backend через Gateway.

Отдельные проверки:

```bash
python3 -m py_compile tools/smoke_test.py tools/replay-template.py tools/trusted_normal_backend.py
cmake -S cpp-gateway -B cpp-gateway/build && cmake --build cpp-gateway/build
javac -d /tmp/vectorgate-java-out java-analytics/src/main/java/main.java
cd dashboard && npm run build
docker compose config
```

## Control Plane

Управляющие API Gateway требуют заголовок
`X-Gateway-Control-Token`. Токен хранится в `.env`, передаётся Analytics и
подставляется только внутренним proxy dashboard. Браузер его не получает.

- `GET`, `POST /__gateway/routes` — маршруты по HTTP-заголовку `Host`;
- `GET`, `POST /__gateway/decisions` — `block`, `rate_limit`, `allow`;
- `GET /__gateway/health` — публичная health-проверка.

Пример решения:

```json
{"action":"block","client_ip":"203.0.113.10","ttl_seconds":300,"reason":"аномальный трафик"}
```

## Локальный запуск без Docker

Выполните команды из корня проекта в отдельных терминалах:

```bash
cmake -S cpp-gateway -B cpp-gateway/build && cmake --build cpp-gateway/build
mkdir -p /tmp/vectorgate-java-out
javac -d /tmp/vectorgate-java-out java-analytics/src/main/java/main.java
python3 tools/trusted_normal_backend.py --port 3000
BASELINE_PATH=datasets/captures/initial-baseline-v1.json ANALYTICS_API_PORT=9091 java -cp /tmp/vectorgate-java-out main 9090 60
./cpp-gateway/build/reverse_proxy 8080 127.0.0.1 3000
cd dashboard && npm install && npm run dev
```

## Analytics и данные

Gateway отправляет UDP JSON-события с IP, методом, endpoint, размером запроса,
статусом, задержкой и backend. Analytics формирует векторы признаков для
каждой пары `baseline_scope + backend`.

После 30 baseline-окон оценка расстояния Махаланобиса имеет уровни:

- меньше `4.0` — `normal`;
- `4.0–6.0` — `warning`;
- от `6.0` — `critical`.

Только `normal`-окна обновляют online-профиль. Исходный baseline расположен в
`datasets/captures/initial-baseline-v1.json`. Завершённые векторы сохраняются
в ClickHouse в `traffic_windows`; сырые HTTP-запросы не сохраняются.

Публичные шаблоны NDJSON и правила их воспроизведения находятся в
[`datasets/`](datasets/README.md). Пример:

```bash
python3 tools/replay-template.py datasets/templates/world-cup-1998.ndjson --speed 10
```
