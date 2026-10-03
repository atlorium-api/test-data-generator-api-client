# Russian Test Data Generator API — INN, SNILS, OGRN, bank account and card with valid checksums

[Русский](README.md) · **English**

[![Live API tests](https://github.com/atlorium-api/test-data-generator-api-client/actions/workflows/examples.yml/badge.svg)](https://github.com/atlorium-api/test-data-generator-api-client/actions/workflows/examples.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-Swagger-brightgreen)](https://atlorium.com/tdAPI)

Ready-to-run examples of the **Russian test data generator API** in six languages: **Python, TypeScript (Node.js), Go, Java, C#, PHP.**
Fictional but formally valid Russian identifiers for tests and demo environments: **full name**, **personal and company INN** (tax number), **SNILS** (social insurance number), **OGRN and OGRNIP** (state registration numbers), **KPP**, a **bank account matched to its BIC**, passport, phone, address, **payment card number**, VIN. **Every checksum is valid** — a generated INN passes the check-digit test, a generated card passes Luhn.

Every example **runs immediately — no sign-up, no key, no card.** A public demo key is hard-coded.

```bash
git clone https://github.com/atlorium-api/test-data-generator-api-client
cd test-data-generator-api-client/python && pip install -r requirements.txt && python main.py
```

```
Демо-ключ. Этот сервис данные не ищет, а придумывает, поэтому демо-ответ
ничем не отличается от боевого — моков здесь нет.

Записей: 2 · seed 20260823

Запись 1
  ФИО              Веселова Злата Руслановна
  ИНН физлица      611324308745           [OK] контрольная сумма сошлась
  СНИЛС            467-744-822 39         [OK] контрольная сумма сошлась
  ИНН юрлица       6113603441             [OK] контрольная сумма сошлась
  КПП              611344288
  ОГРН             1126172767874          [OK] контрольная сумма сошлась
  БИК              040349602
  Банк             Краснодарское отделение банка
  Расчётный счёт   40702810368794107058   [OK] контрольная сумма сошлась
  Номер карты      4890 2432 2928 3149    [OK] контрольная сумма сошлась

Запись 2
  ФИО              Яковлев Ренат Гордеевич
  ИНН физлица      641958431890           [OK] контрольная сумма сошлась
  СНИЛС            808-063-397 02         [OK] контрольная сумма сошлась
  ИНН юрлица       6419704581             [OK] контрольная сумма сошлась
  КПП              641901166
  ОГРН             1066442423805          [OK] контрольная сумма сошлась
  БИК              040813608
  Банк             Северо-Кавказский банк развития
  Расчётный счёт   40702810529562963663   [OK] контрольная сумма сошлась
  Номер карты      2200 5235 1765 0318    [OK] контрольная сумма сошлась

Проверено контрольных сумм: 12, не сошлось: 0
Воспроизводимость по seed 20260823: совпало

Данные вымышлены и предназначены исключительно для тестирования. Совпадение с реальными людьми и организациями случайно.
```

The examples print in Russian, because the identifiers they generate are Russian. The API itself is language-neutral, and the field catalogue carries English titles for every field.

> **The data is fictional.** The service queries no registry and does not verify that a generated identifier exists — it builds a number that follows the rules. Any resemblance to real people or organisations is coincidental. Using the output to mislead anyone, forge documents or bypass verification is not permitted.
>
> On the other hand, this service has **no mock mode**: it fetches nothing from external sources, so the demo key returns exactly what a live key returns. Everything above is the service doing its real work.

---

## What it is for

Filling a demo environment, exercising intake forms, building fixtures for automated tests, showing a client a working interface before real data exists, testing a CSV import on a hundred rows.

Foreign test-data generators emit "Russian" identifiers as random digits: such an INN fails the check-digit test and such an account does not match its BIC. Test data that your own validation rejects is useless exactly where it is needed.

The examples do more than print JSON — they **apply the data**: each one contains a `verifyRecord()` function that **independently recomputes the checksums** of every returned identifier — personal and company INN, SNILS, OGRN, the bank account together with its BIC, and the card number by the Luhn algorithm. That is both a check on the service's promise and a set of ready-made validators you would have had to write anyway: copy them.

## 60-second start

Try the API without cloning anything:

```bash
curl -H "Authorization: Bearer ak_sandbox_demo_mockdata_v1" \
     "https://atlorium.com/api/testdata?count=1&fields=fullName,innPerson,snils"
```

| Language | Run | Requires |
|----------|-----|----------|
| [Python](python/) | `pip install -r requirements.txt && python main.py` | Python 3.10+ |
| [TypeScript / Node.js](node/) | `npm install && npm start` | Node.js 20+ |
| [Go](go/) | `go run .` | Go 1.22+ |
| [Java](java/) | `java Main.java` | JDK 11+ (no dependencies) |
| [C#](csharp/) | `dotnet run` | .NET 8+ |
| [PHP](php/) | `php main.php` | PHP 8.1+ |

Ask for more records: `python main.py 10`

## Authentication

The key travels in the `Authorization` header:

```
Authorization: Bearer YOUR_KEY
```

| Key | What it does |
|-----|--------------|
| `ak_sandbox_demo_mockdata_v1` | **Demo key.** Public, shared by everyone. Charges nothing, requires no account. Returns the service's real output: there are no mocks here and there could not be |
| Live key | The same thing, billed to your account. The only difference in limits: they are counted against your key rather than against the public IP shared by everyone trying the demo. Get one at [atlorium.com](https://atlorium.com) |

Switching to a live key **requires no code change** — every example reads an environment variable:

```bash
export ATLORIUM_API_KEY="ak_your_live_key"
```

## Endpoints

Base URL: `https://atlorium.com`

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/testdata` | Generate using query-string parameters |
| `POST` | `/api/testdata` | The same, with a JSON body — handy when the field list is long |
| `GET` | `/api/testdata/fields` | The field catalogue. **Free** |

### `GET /api/testdata`

| Parameter | Type | Description |
|-----------|------|-------------|
| `count` | int | How many records: 1 to 100. Default 1. **This number is also the request cost** |
| `fields` | string | Comma-separated field list: `fullName,innPerson,snils`. Defaults to name, gender, birth date, personal INN, SNILS, phone, email, address |
| `seed` | int | Generator seed. The same seed with the same remaining parameters yields **exactly the same result** |
| `gender` | string | `any` (default), `male` or `female` |
| `format` | string | `json` (default) or `csv` |

### `POST /api/testdata`

```json
{
  "count": 10,
  "fields": ["fullName", "innPerson", "snils", "bic", "bankAccount"],
  "seed": 20260823,
  "gender": "female",
  "format": "json"
}
```

The same contract as `GET`, except `fields` is an array rather than a string.

### `GET /api/testdata/fields`

Returns the catalogue: field id, human title (Russian and English), group, whether the field carries a checksum, and a sample value. The ids from this catalogue go into the `fields` parameter. The request is **free**: the catalogue describes the contract rather than being a result of the service's work. It still takes a rate-limit slot like any other call.

## Available fields

30 fields in eight groups. The "CS" column marks fields that carry a checksum.

| Group | Fields | CS |
|-------|--------|----|
| Person | `lastName`, `firstName`, `middleName`, `fullName`, `gender`, `birthDate` | — |
| Tax identifiers | `innPerson` (12 digits), `innCompany` (10), `ogrn` (13), `ogrnip` (15) | yes |
| Tax identifiers | `kpp` | — |
| Documents | `snils` | yes |
| Documents | `passport` (series with region code, and number) | — |
| Bank | `bankAccount`, `correspondentAccount`, `cardNumber` | yes |
| Bank | `bic`, `bankName`, `cardBrand`, `cardExpiry` | — |
| Contacts | `phone`, `email`, `login` | — |
| Address | `address`, `region`, `city`, `postalCode` | — |
| Company | `companyName` | — |
| Vehicle | `vin` | yes |
| Vehicle | `vehiclePlate` | — |

All fields within a record are consistent with each other: the name matches the gender, email and login are derived from the name, the bank account matches the BIC of the same record, and the KPP starts with the region code from the company INN.

The exact, always-current list is at `GET /api/testdata/fields`.

## Response fields

| Field | Type | Contents |
|-------|------|----------|
| `seed` | int | The seed **actually used**. If you did not supply one, the service picked it — keep it to reproduce the output |
| `count` | int | How many records came back |
| `fields` | string[] | Fields in canonical order, which may differ from the order in your request |
| `records` | array | The records: an object of field name to value, all values are strings |
| `disclaimer` | string | A reminder that the data is fictional. Show it wherever a human will see the output |

In CSV format you get a flat table with a header row, and the seed arrives in the **`X-Atlorium-Seed` response header** — a table has no room for metadata. The header is set in both formats, so you never have to parse the response two different ways.

## Error handling

| Code | Cause | What to do |
|------|-------|------------|
| `400` | Record count outside 1–100, unknown field name, or unknown format | Check against `/api/testdata/fields` |
| `401` | Key missing, expired or invalid | Check the `Authorization` header |
| `402` | Not enough credits | Top up at [atlorium.com](https://atlorium.com) |
| `429` | Rate limit exceeded | Retry with a delay |
| `503` | Service temporarily unavailable: scheduled maintenance | Retry later; `message` links to the [status page](https://atlorium.com/status). **Not billed** |

This service has no `404`, and `503` only happens during scheduled platform maintenance. The generator itself is **entirely local** — no outbound calls, no database. There is nothing for it to fail on and nothing to look up, and the only error scenario on your side is a malformed request, which is rejected before any credits are reserved.

All six examples map the codes to human-readable causes — see the `AtloriumError` class.

## Pricing and limits

**Pay-as-you-go, no subscription.** The unit of work here is **one record, not one call**: a request for a hundred records costs a hundred units. That is deliberate — otherwise a client pulling data in batches of a hundred would ride for free on the one taking them one at a time.

The field catalogue is free. Current prices and limits: **[atlorium.com/pricing](https://atlorium.com/pricing)**.

## FAQ

**How do I generate a test INN that passes validation?** Request the `innPerson` field (individual, 12 digits) or `innCompany` (company, 10 digits). The check digits are computed by the official algorithm — the examples in this repository recompute them independently and show the result.

**How is this better than a generic faker library?** Faker libraries produce plausible strings but do not compute the checksums of Russian identifiers: their INN is rejected by your own form, and their account does not match its BIC. Here everything adds up, including the account-to-BIC pairing, which you cannot get by generating the fields independently.

**How do I get the same data on every test run?** Pass a `seed`. The same seed with the same `count`, `fields` and `gender` yields a byte-identical result — that is what makes automated tests stable. The examples verify this with a repeat request.

**Can I export to CSV?** Yes, `format=csv`. You get a flat table with a header row; the seed used arrives in the `X-Atlorium-Seed` response header.

**How many records per request?** Up to 100. For more, make several calls — and remember that billing counts records, not calls.

**Is this personal data?** No. Every value is invented by the generator, no real person stands behind it, and no registry is queried. Any resemblance is coincidental — which is exactly why such data belongs in demo environments, where real personal data must not go.

**May I use these identifiers in documents?** No. The data is for testing only. Using it to mislead anyone, forge documents or bypass verification is not permitted.

## Other Atlorium APIs

Test data is about life before release. For real checks in production, the same account and key give you:

- [EGRUL/EGRIP company registry](https://github.com/atlorium-api/egrul-api-client) — verify a real INN or OGRN against the state registry
- [Bank of Russia BIC directory](https://github.com/atlorium-api/cbr-bik-api-client) — real banks and the account check key
- [Phone validation](https://github.com/atlorium-api/phone-validation-api-client) — parse a number and check it falls in an allocated range
- [Email verification](https://github.com/atlorium-api/email-verification-api-client) — whether a mailbox actually exists
- [Address standardization](https://github.com/atlorium-api/address-standardization-api-client) — parse an address string into levels
- [Site performance audit](https://github.com/atlorium-api/core-web-vitals-api-client) — a release gate on Core Web Vitals and page load speed

Full catalogue — [atlorium.com](https://atlorium.com)

## Links

- **API documentation (Swagger):** [atlorium.com/tdAPI](https://atlorium.com/tdAPI)
- **Service description:** [atlorium.com/tdDescription](https://atlorium.com/tdDescription)
- **Web interface:** [atlorium.com/tdGUI](https://atlorium.com/tdGUI)
- **OpenAPI specification:** [testdata_en-US.json](https://atlorium.com/openapi/testdata_en-US.json)
- **Support:** support@atlorium.com

## License

[MIT](LICENSE) — take the code and use it however you like, commercial projects included.
