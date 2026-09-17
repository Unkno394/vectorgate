# VectorGate

VectorGate — учебный контур для наблюдения за HTTP-трафиком и обнаружения аномалий. Он ставится перед backend-сервисом как reverse proxy: запросы проходят через Gateway без заметной задержки, а сведения о них отдельно уходят в аналитический сервис. Dashboard показывает агрегированные метрики, отклонения от baseline и решения Control Plane.

Проект уже содержит работающий demo-магазин. Его можно открыть, покликать по товарам и увидеть, как эти действия проходят через proxy и появляются на панели мониторинга.

## Как проходит запрос

Браузер видит только адрес demo-магазина `localhost:4173`. Переход к Gateway происходит внутри Docker-сети. Поэтому в DevTools запрос выглядит как `GET http://localhost:4173/api/products/p-101`, хотя Gateway всё равно получает его, проксирует на backend и создаёт telemetry-событие.

Событие содержит HTTP-метод, нормализованный endpoint, статус ответа, объём запроса и ответа, длительность, IP клиента и адрес backend. Отправка telemetry асинхронна, поэтому Analytics не добавляет задержку пользовательскому запросу.

## Компоненты

- `gateway` — C++ reverse proxy, маршрутизация по `Host`, failover и Control Plane.
- `backend` — Python API магазина: каталог, поиск, вход и заказы.
- `demo-store` — Nginx и HTML/JS-витрина. Показывает каталог, открывает карточки товаров и создаёт тестовый заказ; все её API-запросы проксируются через Gateway.
- `normal-traffic` — генератор обычной нагрузки для демо.
- `analytics` — Java-сервис, который принимает UDP-события, собирает минутные окна и рассчитывает anomaly score.
- `dashboard` — React-панель с текущим вектором, историей инцидентов, маршрутами и правилами.
- `clickhouse` — хранилище завершённых окон telemetry в `vectorgate.traffic_windows`.
- `tools` — Python-скрипты для генерации трафика, smoke-теста, baseline и NDJSON-датасетов.

## Быстрый старт

```bash
cd /home/user/Documents/vectorgate
cp .env.example .env
docker compose up -d --build
```

В `.env` замените `GATEWAY_CONTROL_TOKEN` на длинный случайный секрет. Он нужен только внутренним компонентам для изменения маршрутов и решений Gateway; браузер его не получает.

Dashboard: http://localhost:5173

Demo Store: http://localhost:4173

Gateway health: http://localhost:8080/__gateway/health

Analytics API: http://localhost:9091/api/state

ClickHouse HTTP API: http://localhost:8123

Откройте demo-магазин, выберите товар или создайте тестовый заказ. Сам ответ приходит сразу, а Dashboard обновляется после завершения 60-секундного окна Analytics. Фоновый `normal-traffic` уже создаёт обычную нагрузку через тот же Gateway, поэтому панель не остаётся пустой.

## Что делает Demo Store

`demo-store` — это не отдельная бизнес-система и не mock в браузере, а небольшой пользовательский сценарий для демонстрации VectorGate. Nginx отдаёт статическую HTML/JS-страницу на `localhost:4173` и принимает запросы браузера с префиксом `/api/`. Для них он выставляет `Host: shop.local` и пересылает запрос в `gateway:8080` внутри Docker-сети. Благодаря этому Gateway применяет своё правило маршрутизации, отправляет запрос в backend и одновременно формирует telemetry.

На витрине можно выполнить несколько разных типов запросов: загрузить каталог (`GET /catalog`), открыть карточку товара (`GET /products/{id}`), войти (`POST /auth/login`) и создать заказ (`POST /orders`). Сначала страница получает токен входа, затем передаёт его в заголовке `Authorization` при создании заказа. Такой набор действий специально содержит и GET-, и POST-запросы, разные endpoint'ы, JSON-тела, ответ `201 Created` и авторизованный запрос — поэтому в Dashboard виден реалистичный профиль трафика, а не только один health-check.

Python в этом сценарии работает не внутри `demo-store`, а в сервисе `backend`. Скрипт `tools/trusted_normal_backend.py` поднимает простой тестовый HTTP API на порту 3000: хранит фиксированный каталог, выдаёт временные токены, проверяет авторизацию и держит созданные заказы в памяти. Он также добавляет небольшую случайную задержку ответа, чтобы telemetry содержала правдоподобные значения времени обработки. Данные тестовые: после перезапуска backend токены и заказы пропадают, а вызовы не создают реальных покупок и не обращаются к внешним сервисам.

Полный путь, например при создании заказа, выглядит так: браузер → `demo-store` (Nginx) → `gateway` → Python `backend`; параллельно Gateway отправляет событие в `analytics`, откуда агрегированные результаты попадают в Dashboard и ClickHouse.

Остановить стек:

```bash
docker compose down
```

Данные ClickHouse сохраняются в volume `clickhouse_data`; команда `down` их не удаляет.

## Что показывает Dashboard

Analytics группирует события по паре `baseline_scope + backend` в 60-секундные окна. Для каждого окна рассчитываются:

- запросы в секунду и средний интервал между запросами;
- доля ошибок, средние размеры запросов и время обработки;
- число уникальных endpoint’ов и повторяемость;
- доли GET, POST и остальных методов.

Вектор сравнивается с baseline методом расстояния Махаланобиса. Score меньше `4.0` считается `normal`, от `4.0` до `6.0` — `warning`, от `6.0` — `critical`.

Высокий score не означает, что отдельный запрос опасен. Это сигнал, что совокупный профиль минутного окна не похож на ожидаемый: например, резко упала частота запросов, изменилось соотношение методов или появились новые endpoint’ы.

Исходный профиль лежит в `datasets/captures/initial-baseline-v1.json`. Нормальные окна постепенно обновляют online-профиль. Завершённые векторы сохраняются в ClickHouse, а сырые HTTP-запросы туда не записываются.

## Маршрутизация

Gateway выбирает backend по HTTP-заголовку `Host`. Если правила нет, используется default backend `backend:3000`.

Пример правила:

```text
Host: shop.local
Backend host: backend
Порт: 3000
```

После сохранения правило применяется сразу, без перезапуска Gateway. Оно работает только для запросов, которые уже пришли в ваш Gateway. Вписать адрес стороннего сайта недостаточно: его трафик не начнёт проходить через VectorGate.

Для нескольких сервисов можно создать отдельные правила для `shop.local`, `api.local` и `admin.local`, направляя каждое на свой backend и порт.

## Control Plane

- `GET /__gateway/health` — публичная health-проверка.
- `GET`, `POST /__gateway/routes` — просмотр и изменение маршрутов по `Host`.
- `GET`, `POST /__gateway/decisions` — просмотр и применение `block`, `rate_limit`, `allow`.

Все управляющие запросы, кроме health-проверки, требуют `X-Gateway-Control-Token`. Dashboard передаёт его только через свой внутренний Nginx proxy.

Пример решения:

```json
{"action":"block","client_ip":"203.0.113.10","ttl_seconds":300,"reason":"аномальный трафик"}
```

## Проверка и разработка

Smoke-тест проверяет dashboard, Gateway, Analytics и тестовый backend через proxy:

```bash
python3 tools/smoke_test.py
```

Проверки сборки:

```bash
python3 -m py_compile tools/*.py
cmake -S cpp-gateway -B cpp-gateway/build && cmake --build cpp-gateway/build
javac -d /tmp/vectorgate-java-out java-analytics/src/main/java/main.java
cd dashboard && npm run build
docker compose config
```

## Датасеты и инструменты

Папка `datasets/` содержит публичные шаблоны NDJSON, результаты аудита и стартовый baseline. В `tools/` лежат тестовый backend, генератор обычного трафика, smoke-тест, воспроизведение шаблонов и скрипты сборки baseline.

Пример воспроизведения шаблона:

```bash
python3 tools/replay-template.py datasets/templates/world-cup-1998.ndjson --speed 10
```
