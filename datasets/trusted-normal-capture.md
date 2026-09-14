# VEC-24: сбор initial trusted-normal baseline через Gateway

## Назначение

Initial baseline формируется только из собственных Gateway-событий, собранных
в контролируемой среде. Внешние наборы не добавляются в этот профиль: они
используются лишь для проверки переносимости механизма.

## Условия доверенного сбора

Перед стартом capture фиксируются версия Gateway и backend, конфигурация
маршрутов, размер окна и период сбора. В периоде не должно быть:

- атак, сканирований, бот-трафика и synthetic load tests;
- известных инцидентов, deploy/restart, failover и деградаций backend;
- ручных экспериментов с rate limit или routing decisions.

Допускается только заранее определённый набор легитимных сценариев клиентов:
обычная навигация, чтение, поиск и разрешённые операции записи. Источник
сценариев, окно времени и ответственный за подтверждение периода записываются
в журнал запуска.

## Схема данных

Gateway уже передаёт нужные поля: `timestamp`, `client_ip`, `method`,
`endpoint`, `request_bytes`, `response_status`, `response_bytes`,
`duration_ms` и backend identity. Java Analytics по умолчанию назначает таким
событиям `baseline_scope: gateway` и строит отдельный профиль для каждого
backend.

## Процедура

1. Запустить Gateway и Java Analytics с фиксированным окном 60 секунд.
2. Прогнать утверждённые легитимные сценарии в течение репрезентативного
   периода: не менее 30 полных окон для технической готовности baseline;
   для принятия initial baseline желательно покрыть все ожидаемые часы и
   типы операций.
3. Зафиксировать и исключить окна, пересекающиеся с запрещёнными условиями.
4. Проверить, что для каждого backend имеются достаточные окна и что ни одно
   из них не содержит технически неверных значений (например, пустой backend
   или отрицательная длительность).
5. Сохранить версию конфигурации, границы допущенных окон и рассчитанные
   mean/stddev/covariance как initial baseline v1.

## Локальный генератор реалистичных сценариев

В репозитории есть backend-стенд и генератор пользовательских сессий, не
сводящий нагрузку к одинаковым `GET /`. Он моделирует просмотр каталога,
поиск, авторизацию, создание и просмотр заказа; сценарии выбираются в
пропорции 55% / 25% / 20% и содержат случайный нормальный think time.

```bash
# Terminal 1: backend
python3 tools/trusted_normal_backend.py --port 3000

# Terminal 2: Java Analytics; preserve raw events and completed windows
cd java-analytics && RAW_EVENT_LOG_PATH=../datasets/captures/trusted-normal-events.ndjson VECTOR_LOG_PATH=../datasets/captures/trusted-normal-windows.ndjson gradle run --args='9090 60'

# Terminal 3: Gateway routes normal.local to the backend
cmake -S cpp-gateway -B cpp-gateway/build && cmake --build cpp-gateway
./cpp-gateway/build/reverse_proxy 8080 127.0.0.1 3000

# Terminal 4: legitimate sessions through Gateway for five minutes
python3 tools/generate_trusted_normal_traffic.py --users 5 --duration-seconds 300
```

The generator uses `Host: normal.local`; with the startup command above this is
served by the Gateway fallback backend. Run it only in the designated trusted
capture period, and record the exact command, duration and scenario mix.

The capture writes raw normalized Gateway telemetry to
`datasets/captures/trusted-normal-events.ndjson` and a completed vector per
window to `datasets/captures/trusted-normal-windows.ndjson`.

Before aggregation, Gateway removes a query string and replaces only recognised
dynamic path segments with `:id`: numeric IDs, UUIDs, order IDs of the form
`o-<hex>` and product IDs of the form `p-<digits>`. Static paths such as
`/catalog`, `/search`, `/auth/login` and `/orders` remain unchanged.

After at least 30 approved windows, freeze the initial model:

```bash
python3 tools/build_initial_baseline.py \
  datasets/captures/trusted-normal-windows.ndjson \
  datasets/captures/initial-baseline-v1.json
```

## Роль Online Shop Server Logs

Набор Online Shop Server Logs не обучает `gateway` baseline. Это внешний
тестовый HTTP-набор для проверки RPS, endpoint repeatability, распределения
методов, клиентских паттернов и поведения механизма на современном реальном
access-log трафике. Недостающие request bytes, processing time и backend не
должны синтезироваться для выдачи результата за полный baseline-тест.
