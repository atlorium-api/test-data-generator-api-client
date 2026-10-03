"""
Клиент API генератора тестовых данных РФ Atlorium — вымышленные, но формально
корректные реквизиты: ФИО, ИНН, СНИЛС, ОГРН, расчётный счёт, номер карты.

Запуск (работает сразу, без регистрации — на демо-ключе):
    pip install -r requirements.txt
    python main.py 2

Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
ATLORIUM_API_KEY. Код при этом не меняется.
"""

import os
import re
import sys

import requests

# Публичный демо-ключ. Этот сервис — особый случай: он ничего не ищет во внешних
# источниках, а ПРИДУМЫВАЕТ данные по правилам. Поэтому подменять его ответ моком
# нечем и незачем — с демо-ключом приходит ровно то же, что придёт с боевым.
SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1"

API_KEY = os.environ.get("ATLORIUM_API_KEY", SANDBOX_KEY)
BASE_URL = os.environ.get("ATLORIUM_BASE_URL", "https://atlorium.com")

TIMEOUT = 30

# Фиксированный seed делает пример воспроизводимым: одна и та же команда всегда
# печатает одни и те же реквизиты. На этом же строятся стабильные автотесты.
DEFAULT_SEED = 20260823

# Набор полей примера подобран так, чтобы каждое второе поле можно было ПРОВЕРИТЬ
# локально — в этом весь смысл сервиса.
DEFAULT_FIELDS = "fullName,innPerson,snils,innCompany,ogrn,kpp,bic,bankName,bankAccount,cardNumber"


class AtloriumError(RuntimeError):
    """Ошибка API. Код HTTP разложен в человекочитаемую причину."""

    REASONS = {
        400: "Недопустимое количество записей, неизвестное имя поля или неизвестный формат",
        401: "API-ключ отсутствует, просрочен или недействителен",
        402: "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        429: "Превышен лимит запросов — повторите позже",
        503: "Сервис временно недоступен (плановые работы) — повторите позже",
    }

    def __init__(self, status: int, body: str):
        reason = self.REASONS.get(status, "Неизвестная ошибка")
        super().__init__(f"HTTP {status}: {reason}. Ответ сервера: {body[:200]}")
        self.status = status


def _get(path: str, params: dict) -> requests.Response:
    response = requests.get(
        f"{BASE_URL}{path}",
        params=params,
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Accept": "application/json",
        },
        timeout=TIMEOUT,
    )
    if not response.ok:
        raise AtloriumError(response.status_code, response.text)
    return response


# ── Вызовы API ────────────────────────────────────────────────────────────────


def generate(
    count: int = 1,
    fields: str | None = None,
    seed: int | None = None,
    gender: str = "any",
) -> dict:
    """Генерирует набор записей.

    Единица тарификации — ОДНА ЗАПИСЬ, а не один вызов: запрос на сто записей
    стоит сто единиц. Одинаковый seed при одинаковых остальных параметрах даёт
    в точности одинаковый результат.
    """
    params: dict = {"count": count, "gender": gender}
    if fields:
        params["fields"] = fields
    if seed is not None:
        params["seed"] = seed
    return _get("/api/testdata", params).json()


def generate_csv(count: int = 1, fields: str | None = None, seed: int | None = None) -> str:
    """То же самое плоской таблицей CSV.

    У CSV нет места под метаданные, поэтому использованный seed возвращается
    заголовком X-Atlorium-Seed — он ставится в обоих форматах.
    """
    params: dict = {"count": count, "format": "csv"}
    if fields:
        params["fields"] = fields
    if seed is not None:
        params["seed"] = seed
    return _get("/api/testdata", params).text


def get_fields() -> list[dict]:
    """Справочник доступных полей: идентификатор, группа, пример значения.

    Запрос бесплатный: справочник описывает контракт, а не результат работы.
    """
    return _get("/api/testdata/fields", {}).json()


# ── Применение данных: проверка контрольных сумм на своей стороне ─────────────
# Тестовые данные ценны ровно настолько, насколько они проходят ВАШУ валидацию.
# Ниже — те же алгоритмы, что стоят в приёмных формах: если сгенерированный
# реквизит их проходит, он пройдёт и в вашем коде. Заодно это готовые валидаторы,
# которые всё равно пришлось бы написать.


def _digits(value: str) -> str:
    """Убирает форматирование: пробелы и дефисы в СНИЛС и номере карты."""
    return re.sub(r"\D", "", value or "")


def valid_inn_person(inn: str) -> bool:
    """ИНН физлица: 12 цифр, два контрольных разряда."""
    digits = _digits(inn)
    if len(digits) != 12:
        return False
    weights_11 = [7, 2, 4, 10, 3, 5, 9, 4, 6, 8]
    weights_12 = [3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8]
    eleventh = sum(int(digits[i]) * weights_11[i] for i in range(10)) % 11 % 10
    twelfth = sum(int(digits[i]) * weights_12[i] for i in range(11)) % 11 % 10
    return eleventh == int(digits[10]) and twelfth == int(digits[11])


def valid_inn_company(inn: str) -> bool:
    """ИНН юрлица: 10 цифр, один контрольный разряд."""
    digits = _digits(inn)
    if len(digits) != 10:
        return False
    weights = [2, 4, 10, 3, 5, 9, 4, 6, 8]
    control = sum(int(digits[i]) * weights[i] for i in range(9)) % 11 % 10
    return control == int(digits[9])


def valid_snils(snils: str) -> bool:
    """СНИЛС: 9 цифр номера + двузначное контрольное число.

    Веса убывают с 9 до 1. Суммы 100 и 101 дают контроль 00, большие — остаток
    от деления на 101 (и снова 00, если остаток равен 100 или 101).
    """
    digits = _digits(snils)
    if len(digits) != 11:
        return False
    total = sum(int(digits[i]) * (9 - i) for i in range(9))
    if total in (100, 101):
        control = 0
    elif total > 101:
        remainder = total % 101
        control = 0 if remainder in (100, 101) else remainder
    else:
        control = total
    return control == int(digits[9:11])


def valid_ogrn(ogrn: str) -> bool:
    """ОГРН: 13 цифр. Контрольная — последняя цифра остатка от деления на 11."""
    digits = _digits(ogrn)
    if len(digits) != 13:
        return False
    return int(digits[:12]) % 11 % 10 == int(digits[12])


def valid_account(bic: str, account: str) -> bool:
    """Расчётный счёт проверяется ВМЕСТЕ с БИК — сам по себе он не проверяем.

    К счёту слева приписывается префикс: для корсчетов (начинаются на 301) —
    «0» и пятая-шестая цифры БИК, для остальных — три последние цифры БИК.
    Сумма младших разрядов произведений на веса 7-1-3 должна оканчиваться нулём.
    """
    bic_digits, account_digits = _digits(bic), _digits(account)
    if len(bic_digits) != 9 or len(account_digits) != 20:
        return False

    prefix = "0" + bic_digits[4:6] if account_digits.startswith("301") else bic_digits[6:9]
    probe = prefix + account_digits

    weights = [7, 1, 3]
    total = sum(int(probe[i]) * weights[i % 3] % 10 for i in range(len(probe)))
    return total % 10 == 0


def valid_card(card: str) -> bool:
    """Номер банковской карты по алгоритму Луна (ISO/IEC 7812-1)."""
    digits = _digits(card)
    if len(digits) < 12:
        return False
    total = 0
    for index, symbol in enumerate(reversed(digits)):
        value = int(symbol)
        if index % 2 == 1:
            value *= 2
            if value > 9:
                value -= 9
        total += value
    return total % 10 == 0


# Поле → (человеческое имя, проверка). Порядок задаёт порядок печати.
CHECKS = [
    ("fullName", "ФИО", None),
    ("innPerson", "ИНН физлица", lambda record: valid_inn_person(record["innPerson"])),
    ("snils", "СНИЛС", lambda record: valid_snils(record["snils"])),
    ("innCompany", "ИНН юрлица", lambda record: valid_inn_company(record["innCompany"])),
    ("kpp", "КПП", None),
    ("ogrn", "ОГРН", lambda record: valid_ogrn(record["ogrn"])),
    ("bic", "БИК", None),
    ("bankName", "Банк", None),
    ("bankAccount", "Расчётный счёт", lambda record: valid_account(record["bic"], record["bankAccount"])),
    ("cardNumber", "Номер карты", lambda record: valid_card(record["cardNumber"])),
]


def verify_record(record: dict) -> tuple[int, int]:
    """Печатает запись и проверяет контрольные суммы. Возвращает (проверено, ошибок)."""
    checked = failed = 0

    for field, title, check in CHECKS:
        value = record.get(field)
        if value is None:
            continue

        if check is None:
            print(f"  {title:<16} {value}")
            continue

        ok = check(record)
        checked += 1
        if not ok:
            failed += 1
        mark = "[OK] контрольная сумма сошлась" if ok else "[!!] КОНТРОЛЬНАЯ СУММА НЕ СОШЛАСЬ"
        print(f"  {title:<16} {value:<22} {mark}")

    return checked, failed


def main() -> int:
    if API_KEY == SANDBOX_KEY:
        print("Демо-ключ. Этот сервис данные не ищет, а придумывает, поэтому демо-ответ")
        print("ничем не отличается от боевого — моков здесь нет.\n")

    count = int(sys.argv[1]) if len(sys.argv) > 1 else 2

    try:
        result = generate(count=count, fields=DEFAULT_FIELDS, seed=DEFAULT_SEED)
    except AtloriumError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1

    print(f"Записей: {result['count']} · seed {result['seed']}\n")

    total_checked = total_failed = 0
    for index, record in enumerate(result.get("records") or [], start=1):
        print(f"Запись {index}")
        checked, failed = verify_record(record)
        total_checked += checked
        total_failed += failed
        print()

    print(f"Проверено контрольных сумм: {total_checked}, не сошлось: {total_failed}")

    # Воспроизводимость — не обещание в документации, а проверяемый факт.
    # Повторяем тот же запрос и сверяем записи целиком.
    try:
        again = generate(count=count, fields=DEFAULT_FIELDS, seed=DEFAULT_SEED)
    except AtloriumError as error:
        print(f"Повторный запрос не удался: {error}", file=sys.stderr)
        return 0

    same = again.get("records") == result.get("records")
    print(f"Воспроизводимость по seed {DEFAULT_SEED}: {'совпало' if same else 'РАСХОЖДЕНИЕ'}")

    print(f"\n{result.get('disclaimer')}")
    return 0 if total_failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
