# Volvo XC90 I / II — initial experimental support

## Что добавлено

В настройках подключения доступны два **раздельных семейства** профилей; разбивка по версиям приведена ниже:

- **Volvo XC90 I (P2)**: справочник Volvo P-кодов и три экспериментальных запроса Mode 22 для ISO-TP CAN с 29-битными адресами.
- **Volvo XC90 II (SPA, 2016–2021)**: тот же справочный словарь P-кодов и девять экспериментальных запросов по маршруту 7E0/7E8. Годы — модельные годы из определения источника, не дата первой регистрации.

Это начальная поддержка, **не полная диагностика VIDA**. Профили и все расширенные параметры явно помечены экспериментальными в английском и русском интерфейсе. Положительный ответ подтверждает поддержку запроса конкретным ЭБУ, но сам по себе не доказывает правильность формулы для его прошивки.

Код изолирован в `vehicles/volvo` в библиотеке и UI; словари — в `res/volvo`. Другие профили не отправляют Volvo-команды и не используют эти расшифровки. Переключение между поколениями также очищает старые значения и состояния запросов.

## Дорестайлинг, рестайлинги и границы поддержки (уточнение)

Выбор теперь содержит отдельные ключи версий. Это группировка для ограничения
команд по периоду, **не автоматическое определение прошивки ЭБУ**.

| Версия | Экспериментальные OEM PID |
|---|---|
| XC90 I, MY2003–2006, дорестайлинг | 3 P2-кандидата, только при обнаруженном 29-bit ISO-TP |
| XC90 I, MY2007–2011, рестайлинг | Те же 3 кандидата; отдельной верификации ЭБУ нет |
| XC90 I, MY2012–2014, позднее обновление | Те же 3 кандидата, не новый доказанный протокол |
| XC90 II, MY2016–2019, дорестайлинг | 9 SPA-кандидатов, только 7E8 |
| XC90 II, MY2020–2021, рестайлинг | Те же 9 кандидатов в пределах периода исходного набора |
| XC90 II, MY2022–начало MY2025 | Только стандартный OBD + справочник; OEM-запросы отключены |
| XC90 II, MY2025.5+, второй рестайлинг | Только стандартный OBD + справочник; OEM-запросы отключены |
| Любое поколение, год не уточнён | Только стандартный OBD + справочник; OEM-запросы отключены |

Старые сохранённые ключи `volvo_xc90_p2` / `volvo_xc90_spa` сохраняются, но
означают **неуточнённый год**: нужно явно выбрать период, чтобы разрешить
экспериментальные запросы. Двигатель, рынок, модельный год и идентификатор ЭБУ
ещё требуют проверки. MY2015/Classic и переходные комплектации не угадываются:
используйте неуточнённый профиль, пока не подтверждена применимость.

Рестайлинг MY2007 подтверждён заводским списком изменений (в том числе двигателя
и АКПП для соответствующего рынка): [1](https://www.media.volvocars.com/us/en-us/media/pressreleases/3835).
Позднее обновление P2 описано как 2012 в UK и MY2013 в Канаде — годы нельзя
считать универсальными для всех рынков:
[1](https://media.volvocars.com/uk/en-gb/media/pressreleases/41900),
[3](https://www.media.volvocars.com/ca/en-ca/media/pressreleases/41659).
Обновление SPA представлено в феврале 2019 с добавлением B-силовых установок:
[1](https://www.media.volvocars.com/global/en-gb/media/pressreleases/248275/volvo-cars-introduces-refreshed-volvo-xc90-suv).
Для нового обновления Volvo USA использует границу MY2025.5:
[4](https://www.volvocars.com/us/l/updated-xc90/).
Эти источники подтверждают обновления автомобилей, **не идентичность PID**.

## Команды Live Data

Все запросы — только чтение `22xxxx`. Нет записи конфигурации, Security Access, сброса адаптаций, управления исполнительными механизмами, принудительной регенерации или прошивки.

### XC90 I / P2

Запросы разрешаются только после обнаружения OBD-ответа `18DAF110`. На K-line и на 11-битном CAN эти расширенные запросы не отправляются; обычный OBD-II остаётся доступен.

| Команда | TX / RX | Декодирование | Назначение |
|---|---|---|---|
| 22F40A | 18DA10F1 / 18DAF110 | A, kPa | Давление топлива |
| 22E004 | 18DA18F1 / 18DAF118 | signed16(A,B), °C | Температура масла АКПП |
| 22E501 | 18DA18F1 / 18DAF118 | unsigned16(A,B), Nm | Момент двигателя, полученный TCM |

Все три определения помечены `dbg: true`, `to: 2014` в OBDb. Их совместимость с конкретным двигателем/TCM **не подтверждена здесь**. Они не являются командами Volvo D2 (`A6…`), не открывают диагностическое реле ранних P2 и не предоставляют доступ ко всем блокам автомобиля.

### XC90 II / SPA

Запросы разрешаются только после обнаружения OBD-ответа `7E8`. TX `7E0`, RX `7E8`; без принудительного изменения скорости или протокола адаптера.

| Команда | Формула | Параметр |
|---|---|---|
| 22033E | U16 / 131 − 0.053435, kPa | Давление после турбокомпрессора |
| 220341 | A × 0.625 − 40, °C | Температура воздуха наддува |
| 22D900 | U16 / 5, kPa | Давление кондиционера |
| 22D924 | U16 / 100, A | Ток генератора |
| 22D941 | U16 / 10, kPa | Давление наддува |
| 22D9A1 | A − 60, °C | Температура масла АКПП, вариант 3 |
| 22D9B8 | A × 0.625 − 40, °C | Температура ECM |
| 22D9DC | U16 / 10 − 273.14, °C | Температура масла двигателя |
| 22D9E4 | U16 / 10, kPa | Заданное давление наддува |

U16 = A×256+B, big endian. Температуры также имеют пересчёт в °F. Не выполняется автоматическая оценка исправности по этим экспериментальным значениям.

**Все выбранные маршруты 7E0 в OBDb помечены `dbg: true`, 2016–2021.** В источнике есть отдельные, лучше подтверждённые маршруты SPA `D016`/`1EC6…` с приоритетом `1D`, целевым адресом и специальным flow control. Они намеренно НЕ подменены обычным `7E0` и НЕ включены в эту реализацию. Полный OEM-доступ SPA/DoIP и гибридная батарея T8 остаются вне текущего объёма.

## Защита опроса и данных

- При обычном Live Data один OEM-запрос допускается после трёх обычных циклов; минимальный период одного DID — 2.5 секунды. Это не меняет установленный интервал записи Live 1 секунда: записывается последнее полученное значение, не обещается свежий ответ каждого PID каждую секунду.
- В последовательности сохраняются/восстанавливаются TX-заголовок и выбранный RX-фильтр. Скорость CAN и протокол не переключаются.
- После двух отрицательных ответов / NODATA запрос исключается до следующего подключения. NRC 78 (response pending) не считается отказом поддержки.
- Короткие, чужие и незапрошенные ответы не создают значения. Параметр появляется в Live только после принятого ответа, а не с выдуманным нулём.
- Выбор фиксированных PID в графике/панели ограничивает OEM-опрос; выбор только стандартных PID подавляет OEM-запросы.
- Демо запускается только прежней отдельной кнопкой; фиктивные Volvo OEM-показания и VIN не генерируются.

## Коды неисправностей

Добавлены 15 **справочных Volvo P-кодов**, EN/RU: P1000, P1111, P1171, P1174, P1175, P1238, P1332, P1480, P1481, P1550, P1618, P1633, P1670, P1671, P1672. Описания сформулированы заново по открытому справочнику; каждое предупреждает о необходимости сверить год и вариант ЭБУ. Это не заводской каталог конкретной комплектации XC90.

Особенно важно: Jaguar-значения «P1111 = System Pass» и «P1000 = readiness» **не переносятся на Volvo**. Volvo P1618 — запрос индикатора от TCM, а не доказательство конкретной механической поломки коробки.

Чтение/стирание стандартных OBD DTC работает через существующие команды приложения. В список доступных блоков включён только **Engine OBD-II**, с пояснением «не полный опрос VIDA». Не заявляется чтение всех CEM/DEM/BCM/SRS/TCM DTC. Volvo D2-коды вида `ECM-xxxx` не преобразуются механически в SAE `Pxxxx`; для них нужны отдельные декодер, адресация и словарь варианта ЭБУ. Для P2 не запрашивается Mode 0A автоматически, поскольку профиль охватывает ранние машины.

## Источники / attribution

1. OBDb contributors, [Volvo-XC90 signal definitions](https://github.com/OBDb/Volvo-XC90/blob/f0597dd842e5592ad671a5109e65337db9966c88/signalsets/v3/default.json), revision `f0597dd842e5592ad671a5109e65337db9966c88`. Definition data licensed **CC BY-SA 4.0**; full license included at `library/src/main/java/com/fr3ts0n/ecu/prot/obd/res/volvo/LICENSE-OBDb.txt`. Adaptations: limited subset, Java representations, Russian labels, imperial temperature conversion, probing constraints. Adapted definition data retains CC BY-SA 4.0. No endorsement by OBDb or Volvo is implied.
2. [Volvo P-code reference](https://github.com/petrpatek/obd2-mcp-server/blob/03d351e7cc33c0985ca816716273804d9bb24bfc/src/obd2_mcp/dtc_db/volvo/codes.json), revision `03d351e7cc33c0985ca816716273804d9bb24bfc`. A small selection of code meanings was used as factual reference, not bulk-imported. This community reference does not establish applicability to every XC90 ECU.
3. [Volvo-CAN-Gauge](https://github.com/Alfaa123/Volvo-CAN-Gauge) was reviewed to distinguish D2-like `A6` messages from Mode 22. Its example car is a **C30**, not an XC90; its commands/formulas were **not** imported into XC90 profiles.

## Проверка

- Non-GUI Java library compiled with ECJ: 0 errors (supplemental check, not Gradle/APK).
- `VolvoXc90Checks.main`: 38 assertions passed on JVM, using actual library classes and resources. Checks cover generation/brand isolation, headers/filters, transport gates, signed/scaled temperatures, imperial conversion, short/wrong replies, retries/NRC78, fixed selections, localization and DTC overlays.
- `VolvoXc90ProfileTest` invokes the same checks under the normal JUnit suite. Gradle/JUnit runner and Android APK/device checks have not been executed here.
- No vehicle validation performed. Before broadening support, capture adapter type, model year, engine/ECU identifiers and raw request/response logs from the actual XC90. Do not use experimental values as a basis for replacing parts.

Additional validation: `VehicleGuidanceChecks` — 56 JVM checks passed for variant policy and DTC guidance isolation. Existing Volvo checks — 38 passed after adapting tests to explicit-year keys. No APK/device verification.
