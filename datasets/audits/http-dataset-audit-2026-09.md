# VEC-24: аудит публичных HTTP-датасетов для initial baseline

Дата аудита: 12 сентября 2026. Решение принимается только по документации
первичного владельца набора. `✅` означает, что поле прямо заявлено и пригодно
для маппинга; `❌` — поля нет; `?` — описание не подтверждает требование.

| Кандидат | timestamp | client / IP | method | endpoint | request bytes | status | response bytes | processing time | backend/service | benign / trusted period | Решение |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [Online Shop Server Logs, Dec 2025](https://zenodo.org/records/18895701) | ✅ | ✅ | ✅ | ✅ (sanitized) | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | Отклонить |
| [Encrypted Web Traffic: Event Logs and Packet Traces](https://zenodo.org/records/7687642) | ✅* | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | Отклонить |
| [IBM Docker Registry traces](https://github.com/cacheMon/cache_dataset) | ✅ | ✅ | ✅ | ✅ (anonymized) | ? | ✅ | ? | ✅ | ✅ | ❌ | Отклонить |

## 1. Online Shop Server Logs

Источник — анонимизированные access logs польской e-commerce-платформы,
собранные в декабре 2025 года; заявлены 178 939 запросов. Формат — Combined
Log Format: стабильный псевдоним client IP, timestamp, HTTP request (method и
sanitized URI), status и response size. В нём нет request bytes, processing
time и идентификатора backend/service. Описание не выделяет подтверждённый
benign/trusted период; назначение набора включает анализ bot traffic.

Лицензия: CC BY 4.0. Происхождение и период сбора документированы. Несмотря
на это, набор не подходит для обучения normal baseline из-за отсутствующих
обязательных полей и неподтверждённой чистоты периода.

## 2. Encrypted Web Traffic

Набор содержит семь дней мониторинга восьми серверов более чем с 800 сайтами:
IIS host events и TLS packet traces. IIS events содержат `cs-method`,
`cs-bytes` (входящий размер), `sc-status`, `sc-bytes` (исходящий размер),
`time-taken`, client/server IP и host. Время фиксируется при завершении
обработки; момент поступления можно восстановить вычитанием `time-taken`.

Однако при анонимизации из event logs были удалены `cs-uri-stem` и
`cs-uri-query`. Следовательно, endpoint отсутствует — это блокирующее
несоответствие. Авторы также не заявляют, что семь дней являются чистым
benign/trusted периодом. На странице Zenodo лицензия не указана, поэтому
право повторного использования также нельзя считать подтверждённым.

`*` Timestamp есть, но точность IIS ограничена секундами; это отдельный риск
для интервалов между запросами.

## 3. IBM Docker Registry traces

Трассы описаны как production workload IBM Docker Registry: 75 дней,
20 июня — 2 сентября 2017, семь availability zones, 38+ млн запросов.
Документированы timestamp UTC, anonymized client address, method, URI,
response status, duration и anonymized host. Поле `http.response.written`
описано неоднозначно как data received/sent, поэтому из документации нельзя
доказать наличие **раздельных** request bytes и response bytes.

Не опубликовано утверждение, что выбранный период очищен от атак, ботов,
нагрузочных испытаний и отказов. Лицензия на сами trace-файлы в проверенной
документации не зафиксирована. Поэтому это перспективный workload-кандидат
для теста переносимости, но не источник trusted normal traffic.

## Итог и следующий допустимый шаг

**Одобренных публичных источников для initial baseline: 0 из 3.** Ни один из
них не конвертируется в `baseline_scope: gateway` и не используется для
initial baseline. Online Shop Server Logs остаётся внешним тестовым HTTP-набором
для RPS, endpoint repeatability, распределения методов и клиентских паттернов.

Следующий поиск должен быть ограничен наборами, где владелец одновременно
документирует W3C/IIS-подобные поля (`cs-bytes`, `sc-bytes`, `time-taken`,
method, URI, status, timestamp) и явно определённый trusted-normal период.
Корректный источник initial baseline — выделенный доверенный период
собственных Gateway-логов, собранный в контролируемой среде и прошедший
исключение инцидентов, ботов и нагрузочных тестов. Полная процедура находится
в [trusted-normal-capture.md](../trusted-normal-capture.md).
