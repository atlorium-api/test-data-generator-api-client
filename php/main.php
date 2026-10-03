<?php

/**
 * Клиент API генератора тестовых данных РФ Atlorium — вымышленные, но формально
 * корректные реквизиты: ФИО, ИНН, СНИЛС, ОГРН, расчётный счёт, номер карты.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   php main.php 2
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

declare(strict_types=1);

/**
 * Публичный демо-ключ. Этот сервис — особый случай: он ничего не ищет во внешних
 * источниках, а ПРИДУМЫВАЕТ данные по правилам. Поэтому подменять его ответ моком
 * нечем и незачем — с демо-ключом приходит ровно то же, что придёт с боевым.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const TIMEOUT = 30;

/**
 * Фиксированный seed делает пример воспроизводимым: одна и та же команда всегда
 * печатает одни и те же реквизиты. На этом же строятся стабильные автотесты.
 */
const DEFAULT_SEED = 20260823;

/**
 * Набор полей примера подобран так, чтобы каждое второе поле можно было ПРОВЕРИТЬ
 * локально — в этом весь смысл сервиса.
 */
const DEFAULT_FIELDS = 'fullName,innPerson,snils,innCompany,ogrn,kpp,bic,bankName,bankAccount,cardNumber';

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
final class AtloriumError extends RuntimeException
{
    private const REASONS = [
        400 => 'Недопустимое количество записей, неизвестное имя поля или неизвестный формат',
        401 => 'API-ключ отсутствует, просрочен или недействителен',
        402 => 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
        429 => 'Превышен лимит запросов — повторите позже',
        503 => 'Сервис временно недоступен (плановые работы) — повторите позже',
    ];

    public function __construct(public readonly int $status, string $body)
    {
        $reason = self::REASONS[$status] ?? 'Неизвестная ошибка';
        parent::__construct(sprintf(
            'HTTP %d: %s. Ответ сервера: %s',
            $status,
            $reason,
            mb_substr($body, 0, 200)
        ));
    }
}

final class TestDataClient
{
    private string $apiKey;
    private string $baseUrl;

    public function __construct(?string $apiKey = null, ?string $baseUrl = null)
    {
        $this->apiKey = $apiKey ?? (getenv('ATLORIUM_API_KEY') ?: SANDBOX_KEY);
        $this->baseUrl = $baseUrl ?? (getenv('ATLORIUM_BASE_URL') ?: 'https://atlorium.com');
    }

    public function isSandbox(): bool
    {
        return $this->apiKey === SANDBOX_KEY;
    }

    /**
     * Возвращает тело ответа и заголовки: seed приходит заголовком X-Atlorium-Seed,
     * и в формате CSV это единственный способ его узнать.
     *
     * @param array<string, string|int> $params
     * @return array{body: string, headers: array<string, string>}
     */
    private function request(string $path, array $params): array
    {
        $url = $this->baseUrl . $path;
        if ($params !== []) {
            $url .= '?' . http_build_query($params);
        }

        $headers = [];

        $curl = curl_init($url);
        curl_setopt($curl, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($curl, CURLOPT_TIMEOUT, TIMEOUT);
        curl_setopt($curl, CURLOPT_HTTPHEADER, [
            'Authorization: Bearer ' . $this->apiKey,
            'Accept: application/json',
        ]);
        curl_setopt($curl, CURLOPT_HEADERFUNCTION, static function ($handle, string $line) use (&$headers): int {
            $parts = explode(':', $line, 2);
            if (count($parts) === 2) {
                $headers[strtolower(trim($parts[0]))] = trim($parts[1]);
            }
            return strlen($line);
        });

        $response = curl_exec($curl);
        $status = (int) curl_getinfo($curl, CURLINFO_HTTP_CODE);
        $error = curl_error($curl);
        curl_close($curl);

        if ($response === false) {
            throw new RuntimeException('Сетевая ошибка: ' . $error);
        }
        if ($status !== 200) {
            throw new AtloriumError($status, (string) $response);
        }

        return ['body' => (string) $response, 'headers' => $headers];
    }

    // ── Вызовы API ──────────────────────────────────────────────────────────

    /**
     * Генерирует набор записей. Единица тарификации — ОДНА ЗАПИСЬ, а не один вызов:
     * запрос на сто записей стоит сто единиц. Одинаковый seed при одинаковых
     * остальных параметрах даёт в точности одинаковый результат.
     *
     * @return array<string, mixed>
     */
    public function generate(int $count = 1, ?string $fields = null, ?int $seed = null, string $gender = 'any'): array
    {
        $params = ['count' => $count, 'gender' => $gender];
        if ($fields !== null) {
            $params['fields'] = $fields;
        }
        if ($seed !== null) {
            $params['seed'] = $seed;
        }

        $response = $this->request('/api/testdata', $params);

        /** @var array<string, mixed> $decoded */
        $decoded = json_decode($response['body'], true, 512, JSON_THROW_ON_ERROR);
        return $decoded;
    }

    /**
     * То же самое плоской таблицей CSV. У CSV нет места под метаданные, поэтому
     * использованный seed возвращается заголовком.
     *
     * @return array{csv: string, seed: ?string}
     */
    public function generateCsv(int $count = 1, ?string $fields = null, ?int $seed = null): array
    {
        $params = ['count' => $count, 'format' => 'csv'];
        if ($fields !== null) {
            $params['fields'] = $fields;
        }
        if ($seed !== null) {
            $params['seed'] = $seed;
        }

        $response = $this->request('/api/testdata', $params);
        return ['csv' => $response['body'], 'seed' => $response['headers']['x-atlorium-seed'] ?? null];
    }

    /**
     * Справочник доступных полей: идентификатор, группа, пример значения.
     * Запрос бесплатный: справочник описывает контракт, а не результат работы.
     *
     * @return list<array<string, mixed>>
     */
    public function getFields(): array
    {
        $response = $this->request('/api/testdata/fields', []);

        /** @var list<array<string, mixed>> $decoded */
        $decoded = json_decode($response['body'], true, 512, JSON_THROW_ON_ERROR);
        return $decoded;
    }
}

// ── Применение данных: проверка контрольных сумм на своей стороне ────────────
// Тестовые данные ценны ровно настолько, насколько они проходят ВАШУ валидацию.
// Ниже — те же алгоритмы, что стоят в приёмных формах: если сгенерированный
// реквизит их проходит, он пройдёт и в вашем коде. Заодно это готовые валидаторы,
// которые всё равно пришлось бы написать.

/** Убирает форматирование: пробелы и дефисы в СНИЛС и номере карты. */
function digitsOnly(?string $value): string
{
    return preg_replace('/[^0-9]/', '', $value ?? '') ?? '';
}

/** ИНН физлица: 12 цифр, два контрольных разряда. */
function validInnPerson(?string $inn): bool
{
    $digits = digitsOnly($inn);
    if (strlen($digits) !== 12) {
        return false;
    }

    $weights11 = [7, 2, 4, 10, 3, 5, 9, 4, 6, 8];
    $weights12 = [3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8];

    $sum11 = 0;
    for ($i = 0; $i < 10; $i++) {
        $sum11 += (int) $digits[$i] * $weights11[$i];
    }
    $sum12 = 0;
    for ($i = 0; $i < 11; $i++) {
        $sum12 += (int) $digits[$i] * $weights12[$i];
    }

    return $sum11 % 11 % 10 === (int) $digits[10] && $sum12 % 11 % 10 === (int) $digits[11];
}

/** ИНН юрлица: 10 цифр, один контрольный разряд. */
function validInnCompany(?string $inn): bool
{
    $digits = digitsOnly($inn);
    if (strlen($digits) !== 10) {
        return false;
    }

    $weights = [2, 4, 10, 3, 5, 9, 4, 6, 8];
    $sum = 0;
    for ($i = 0; $i < 9; $i++) {
        $sum += (int) $digits[$i] * $weights[$i];
    }

    return $sum % 11 % 10 === (int) $digits[9];
}

/**
 * СНИЛС: 9 цифр номера + двузначное контрольное число. Веса убывают с 9 до 1.
 * Суммы 100 и 101 дают контроль 00, большие — остаток от деления на 101
 * (и снова 00, если остаток равен 100 или 101).
 */
function validSnils(?string $snils): bool
{
    $digits = digitsOnly($snils);
    if (strlen($digits) !== 11) {
        return false;
    }

    $total = 0;
    for ($i = 0; $i < 9; $i++) {
        $total += (int) $digits[$i] * (9 - $i);
    }

    if ($total === 100 || $total === 101) {
        $control = 0;
    } elseif ($total > 101) {
        $remainder = $total % 101;
        $control = ($remainder === 100 || $remainder === 101) ? 0 : $remainder;
    } else {
        $control = $total;
    }

    return $control === (int) substr($digits, 9, 2);
}

/** ОГРН: 13 цифр. Контрольная — последняя цифра остатка от деления на 11. */
function validOgrn(?string $ogrn): bool
{
    $digits = digitsOnly($ogrn);
    if (strlen($digits) !== 13) {
        return false;
    }

    // Остаток считаем посимвольно: так разбор одинаково работает и для номеров,
    // которые не помещаются в целочисленный тип платформы.
    $remainder = 0;
    for ($i = 0; $i < 12; $i++) {
        $remainder = ($remainder * 10 + (int) $digits[$i]) % 11;
    }

    return $remainder % 10 === (int) $digits[12];
}

/**
 * Расчётный счёт проверяется ВМЕСТЕ с БИК — сам по себе он не проверяем.
 * К счёту слева приписывается префикс: для корсчетов (начинаются на 301) — «0»
 * и пятая-шестая цифры БИК, для остальных — три последние цифры БИК. Сумма
 * младших разрядов произведений на веса 7-1-3 должна оканчиваться нулём.
 */
function validAccount(?string $bic, ?string $account): bool
{
    $bicDigits = digitsOnly($bic);
    $accountDigits = digitsOnly($account);
    if (strlen($bicDigits) !== 9 || strlen($accountDigits) !== 20) {
        return false;
    }

    $prefix = str_starts_with($accountDigits, '301')
        ? '0' . substr($bicDigits, 4, 2)
        : substr($bicDigits, 6, 3);
    $probe = $prefix . $accountDigits;

    $weights = [7, 1, 3];
    $total = 0;
    for ($i = 0, $length = strlen($probe); $i < $length; $i++) {
        $total += (int) $probe[$i] * $weights[$i % 3] % 10;
    }

    return $total % 10 === 0;
}

/** Номер банковской карты по алгоритму Луна (ISO/IEC 7812-1). */
function validCard(?string $card): bool
{
    $digits = digitsOnly($card);
    $length = strlen($digits);
    if ($length < 12) {
        return false;
    }

    $total = 0;
    for ($index = 0; $index < $length; $index++) {
        $value = (int) $digits[$length - 1 - $index];
        if ($index % 2 === 1) {
            $value *= 2;
            if ($value > 9) {
                $value -= 9;
            }
        }
        $total += $value;
    }

    return $total % 10 === 0;
}

/** Дополняет строку пробелами до ширины В СИМВОЛАХ: str_pad считает байты. */
function padRightUtf8(string $text, int $width): string
{
    return $text . str_repeat(' ', max(0, $width - mb_strlen($text)));
}

/**
 * Печатает запись и проверяет контрольные суммы.
 *
 * @param array<string, string> $record
 * @return array{0: int, 1: int} проверено и не сошлось
 */
function verifyRecord(array $record): array
{
    // Поле → человеческое имя и проверка. Порядок задаёт порядок печати.
    $checks = [
        ['fullName', 'ФИО', null],
        ['innPerson', 'ИНН физлица', static fn(array $r): bool => validInnPerson($r['innPerson'])],
        ['snils', 'СНИЛС', static fn(array $r): bool => validSnils($r['snils'])],
        ['innCompany', 'ИНН юрлица', static fn(array $r): bool => validInnCompany($r['innCompany'])],
        ['kpp', 'КПП', null],
        ['ogrn', 'ОГРН', static fn(array $r): bool => validOgrn($r['ogrn'])],
        ['bic', 'БИК', null],
        ['bankName', 'Банк', null],
        ['bankAccount', 'Расчётный счёт', static fn(array $r): bool => validAccount($r['bic'], $r['bankAccount'])],
        ['cardNumber', 'Номер карты', static fn(array $r): bool => validCard($r['cardNumber'])],
    ];

    $checked = 0;
    $failed = 0;

    foreach ($checks as [$field, $title, $verify]) {
        if (!array_key_exists($field, $record)) {
            continue;
        }

        $value = $record[$field];

        if ($verify === null) {
            printf("  %s %s\n", padRightUtf8($title, 16), $value);
            continue;
        }

        $ok = $verify($record);
        $checked++;
        if (!$ok) {
            $failed++;
        }
        $mark = $ok ? '[OK] контрольная сумма сошлась' : '[!!] КОНТРОЛЬНАЯ СУММА НЕ СОШЛАСЬ';
        printf("  %s %s %s\n", padRightUtf8($title, 16), padRightUtf8($value, 22), $mark);
    }

    return [$checked, $failed];
}

// ── main ────────────────────────────────────────────────────────────────────

$client = new TestDataClient();

if ($client->isSandbox()) {
    echo "Демо-ключ. Этот сервис данные не ищет, а придумывает, поэтому демо-ответ\n";
    echo "ничем не отличается от боевого — моков здесь нет.\n\n";
}

$count = isset($argv[1]) ? (int) $argv[1] : 2;

try {
    $result = $client->generate($count, DEFAULT_FIELDS, DEFAULT_SEED);
} catch (AtloriumError $error) {
    fwrite(STDERR, 'Ошибка: ' . $error->getMessage() . "\n");
    exit(1);
}

printf("Записей: %d · seed %d\n\n", $result['count'], $result['seed']);

$totalChecked = 0;
$totalFailed = 0;
foreach ($result['records'] as $index => $record) {
    printf("Запись %d\n", $index + 1);
    [$checked, $failed] = verifyRecord($record);
    $totalChecked += $checked;
    $totalFailed += $failed;
    echo "\n";
}

printf("Проверено контрольных сумм: %d, не сошлось: %d\n", $totalChecked, $totalFailed);

// Воспроизводимость — не обещание в документации, а проверяемый факт.
// Повторяем тот же запрос и сверяем записи целиком.
try {
    $again = $client->generate($count, DEFAULT_FIELDS, DEFAULT_SEED);
} catch (AtloriumError $error) {
    fwrite(STDERR, 'Повторный запрос не удался: ' . $error->getMessage() . "\n");
    exit(0);
}

$same = $again['records'] === $result['records'];
printf("Воспроизводимость по seed %d: %s\n", DEFAULT_SEED, $same ? 'совпало' : 'РАСХОЖДЕНИЕ');

printf("\n%s\n", $result['disclaimer']);

exit($totalFailed === 0 ? 0 : 1);
