// Клиент API генератора тестовых данных РФ Atlorium — вымышленные, но формально
// корректные реквизиты: ФИО, ИНН, СНИЛС, ОГРН, расчётный счёт, номер карты.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//     dotnet run -- 2
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.

using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

// Публичный демо-ключ. Этот сервис — особый случай: он ничего не ищет во внешних
// источниках, а ПРИДУМЫВАЕТ данные по правилам. Поэтому подменять его ответ моком
// нечем и незачем — с демо-ключом приходит ровно то же, что придёт с боевым.
const string SandboxKey = "ak_sandbox_demo_mockdata_v1";

// Фиксированный seed делает пример воспроизводимым: одна и та же команда всегда
// печатает одни и те же реквизиты. На этом же строятся стабильные автотесты.
const int DefaultSeed = 20260823;

// Набор полей примера подобран так, чтобы каждое второе поле можно было ПРОВЕРИТЬ
// локально — в этом весь смысл сервиса.
const string DefaultFields = "fullName,innPerson,snils,innCompany,ogrn,kpp,bic,bankName,bankAccount,cardNumber";

var apiKey = Environment.GetEnvironmentVariable("ATLORIUM_API_KEY") ?? SandboxKey;
var baseUrl = Environment.GetEnvironmentVariable("ATLORIUM_BASE_URL") ?? "https://atlorium.com";

Console.OutputEncoding = Encoding.UTF8;

using var http = new HttpClient
{
    BaseAddress = new Uri(baseUrl),
    Timeout = TimeSpan.FromSeconds(30),
};
http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

var client = new TestDataClient(http);

if (apiKey == SandboxKey)
{
    Console.WriteLine("Демо-ключ. Этот сервис данные не ищет, а придумывает, поэтому демо-ответ");
    Console.WriteLine("ничем не отличается от боевого — моков здесь нет.\n");
}

var count = args.Length > 0 ? int.Parse(args[0]) : 2;

TestDataResponse result;
try
{
    result = await client.GenerateAsync(count, DefaultFields, DefaultSeed);
}
catch (AtloriumException error)
{
    Console.Error.WriteLine($"Ошибка: {error.Message}");
    return 1;
}

Console.WriteLine($"Записей: {result.Count} · seed {result.Seed}\n");

var totalChecked = 0;
var totalFailed = 0;
for (var index = 0; index < result.Records.Count; index++)
{
    Console.WriteLine($"Запись {index + 1}");
    var (checked_, failed) = Checksums.VerifyRecord(result.Records[index]);
    totalChecked += checked_;
    totalFailed += failed;
    Console.WriteLine();
}

Console.WriteLine($"Проверено контрольных сумм: {totalChecked}, не сошлось: {totalFailed}");

// Воспроизводимость — не обещание в документации, а проверяемый факт.
// Повторяем тот же запрос и сверяем записи целиком.
TestDataResponse again;
try
{
    again = await client.GenerateAsync(count, DefaultFields, DefaultSeed);
}
catch (AtloriumException error)
{
    Console.Error.WriteLine($"Повторный запрос не удался: {error.Message}");
    return 0;
}

var same = again.Records.Count == result.Records.Count
           && again.Records.Zip(result.Records).All(pair =>
               pair.First.Count == pair.Second.Count
               && pair.First.All(entry => pair.Second.TryGetValue(entry.Key, out var value) && value == entry.Value));

Console.WriteLine($"Воспроизводимость по seed {DefaultSeed}: {(same ? "совпало" : "РАСХОЖДЕНИЕ")}");

Console.WriteLine($"\n{result.Disclaimer}");
return totalFailed == 0 ? 0 : 1;

// ── Применение данных: проверка контрольных сумм на своей стороне ────────────
// Тестовые данные ценны ровно настолько, насколько они проходят ВАШУ валидацию.
// Ниже — те же алгоритмы, что стоят в приёмных формах: если сгенерированный
// реквизит их проходит, он пройдёт и в вашем коде. Заодно это готовые валидаторы,
// которые всё равно пришлось бы написать.

public static class Checksums
{
    private static readonly Regex NotDigit = new(@"[^0-9]", RegexOptions.Compiled);

    /// <summary>Убирает форматирование: пробелы и дефисы в СНИЛС и номере карты.</summary>
    private static string DigitsOf(string? value) => NotDigit.Replace(value ?? string.Empty, string.Empty);

    /// <summary>ИНН физлица: 12 цифр, два контрольных разряда.</summary>
    public static bool ValidInnPerson(string? inn)
    {
        var digits = DigitsOf(inn);
        if (digits.Length != 12) return false;

        int[] weights11 = [7, 2, 4, 10, 3, 5, 9, 4, 6, 8];
        int[] weights12 = [3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8];

        var sum11 = 0;
        for (var i = 0; i < 10; i++) sum11 += (digits[i] - '0') * weights11[i];
        var sum12 = 0;
        for (var i = 0; i < 11; i++) sum12 += (digits[i] - '0') * weights12[i];

        return sum11 % 11 % 10 == digits[10] - '0' && sum12 % 11 % 10 == digits[11] - '0';
    }

    /// <summary>ИНН юрлица: 10 цифр, один контрольный разряд.</summary>
    public static bool ValidInnCompany(string? inn)
    {
        var digits = DigitsOf(inn);
        if (digits.Length != 10) return false;

        int[] weights = [2, 4, 10, 3, 5, 9, 4, 6, 8];
        var sum = 0;
        for (var i = 0; i < 9; i++) sum += (digits[i] - '0') * weights[i];

        return sum % 11 % 10 == digits[9] - '0';
    }

    /// <summary>
    /// СНИЛС: 9 цифр номера + двузначное контрольное число. Веса убывают с 9 до 1.
    /// Суммы 100 и 101 дают контроль 00, большие — остаток от деления на 101
    /// (и снова 00, если остаток равен 100 или 101).
    /// </summary>
    public static bool ValidSnils(string? snils)
    {
        var digits = DigitsOf(snils);
        if (digits.Length != 11) return false;

        var total = 0;
        for (var i = 0; i < 9; i++) total += (digits[i] - '0') * (9 - i);

        int control;
        if (total is 100 or 101)
        {
            control = 0;
        }
        else if (total > 101)
        {
            var remainder = total % 101;
            control = remainder is 100 or 101 ? 0 : remainder;
        }
        else
        {
            control = total;
        }

        return control == int.Parse(digits[9..11]);
    }

    /// <summary>ОГРН: 13 цифр. Контрольная — последняя цифра остатка от деления на 11.</summary>
    public static bool ValidOgrn(string? ogrn)
    {
        var digits = DigitsOf(ogrn);
        if (digits.Length != 13) return false;

        // Остаток считаем посимвольно: двенадцатизначное число помещается в long,
        // но такой разбор одинаково работает и для более длинных номеров.
        var remainder = 0;
        for (var i = 0; i < 12; i++) remainder = (remainder * 10 + (digits[i] - '0')) % 11;

        return remainder % 10 == digits[12] - '0';
    }

    /// <summary>
    /// Расчётный счёт проверяется ВМЕСТЕ с БИК — сам по себе он не проверяем.
    /// К счёту слева приписывается префикс: для корсчетов (начинаются на 301) — «0»
    /// и пятая-шестая цифры БИК, для остальных — три последние цифры БИК. Сумма
    /// младших разрядов произведений на веса 7-1-3 должна оканчиваться нулём.
    /// </summary>
    public static bool ValidAccount(string? bic, string? account)
    {
        var bicDigits = DigitsOf(bic);
        var accountDigits = DigitsOf(account);
        if (bicDigits.Length != 9 || accountDigits.Length != 20) return false;

        var prefix = accountDigits.StartsWith("301", StringComparison.Ordinal)
            ? "0" + bicDigits[4..6]
            : bicDigits[6..9];
        var probe = prefix + accountDigits;

        int[] weights = [7, 1, 3];
        var total = 0;
        for (var i = 0; i < probe.Length; i++) total += (probe[i] - '0') * weights[i % 3] % 10;

        return total % 10 == 0;
    }

    /// <summary>Номер банковской карты по алгоритму Луна (ISO/IEC 7812-1).</summary>
    public static bool ValidCard(string? card)
    {
        var digits = DigitsOf(card);
        if (digits.Length < 12) return false;

        var total = 0;
        for (var index = 0; index < digits.Length; index++)
        {
            var value = digits[^(index + 1)] - '0';
            if (index % 2 == 1)
            {
                value *= 2;
                if (value > 9) value -= 9;
            }
            total += value;
        }
        return total % 10 == 0;
    }

    /// <summary>Поле записи, его человеческое имя и проверка (может отсутствовать).</summary>
    private sealed record Check(string Field, string Title, Func<IReadOnlyDictionary<string, string>, bool>? Verify);

    private static readonly Check[] Checks =
    [
        new("fullName", "ФИО", null),
        new("innPerson", "ИНН физлица", r => ValidInnPerson(r["innPerson"])),
        new("snils", "СНИЛС", r => ValidSnils(r["snils"])),
        new("innCompany", "ИНН юрлица", r => ValidInnCompany(r["innCompany"])),
        new("kpp", "КПП", null),
        new("ogrn", "ОГРН", r => ValidOgrn(r["ogrn"])),
        new("bic", "БИК", null),
        new("bankName", "Банк", null),
        new("bankAccount", "Расчётный счёт", r => ValidAccount(r["bic"], r["bankAccount"])),
        new("cardNumber", "Номер карты", r => ValidCard(r["cardNumber"])),
    ];

    /// <summary>Печатает запись и проверяет контрольные суммы.</summary>
    public static (int Checked, int Failed) VerifyRecord(IReadOnlyDictionary<string, string> record)
    {
        var checkedCount = 0;
        var failed = 0;

        foreach (var item in Checks)
        {
            if (!record.TryGetValue(item.Field, out var value)) continue;

            if (item.Verify is null)
            {
                Console.WriteLine($"  {item.Title.PadRight(16)} {value}");
                continue;
            }

            var ok = item.Verify(record);
            checkedCount++;
            if (!ok) failed++;
            var mark = ok ? "[OK] контрольная сумма сошлась" : "[!!] КОНТРОЛЬНАЯ СУММА НЕ СОШЛАСЬ";
            Console.WriteLine($"  {item.Title.PadRight(16)} {value.PadRight(22)} {mark}");
        }

        return (checkedCount, failed);
    }
}

// ── Клиент API ──────────────────────────────────────────────────────────────

/// <summary>Ошибка API: HTTP-код разложен в человекочитаемую причину.</summary>
public sealed class AtloriumException : Exception
{
    private static readonly Dictionary<int, string> Reasons = new()
    {
        [400] = "Недопустимое количество записей, неизвестное имя поля или неизвестный формат",
        [401] = "API-ключ отсутствует, просрочен или недействителен",
        [402] = "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        [429] = "Превышен лимит запросов — повторите позже",
        [503] = "Сервис временно недоступен (плановые работы) — повторите позже",
    };

    public AtloriumException(int status, string body)
        : base($"HTTP {status}: {(Reasons.TryGetValue(status, out var reason) ? reason : "Неизвестная ошибка")}. " +
               $"Ответ сервера: {body[..Math.Min(200, body.Length)]}")
    {
        Status = status;
    }

    public int Status { get; }
}

public sealed class TestDataClient(HttpClient http)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private async Task<HttpResponseMessage> GetAsync(string path)
    {
        var response = await http.GetAsync(path);
        if (!response.IsSuccessStatusCode)
        {
            throw new AtloriumException((int)response.StatusCode, await response.Content.ReadAsStringAsync());
        }
        return response;
    }

    /// <summary>
    /// Генерирует набор записей. Единица тарификации — ОДНА ЗАПИСЬ, а не один вызов:
    /// запрос на сто записей стоит сто единиц. Одинаковый seed при одинаковых
    /// остальных параметрах даёт в точности одинаковый результат.
    /// </summary>
    public async Task<TestDataResponse> GenerateAsync(
        int count = 1, string? fields = null, int? seed = null, string gender = "any")
    {
        var query = new List<string> { $"count={count}", $"gender={gender}" };
        if (fields is not null) query.Add($"fields={Uri.EscapeDataString(fields)}");
        if (seed is not null) query.Add($"seed={seed}");

        using var response = await GetAsync("/api/testdata?" + string.Join("&", query));
        var payload = await response.Content.ReadAsStringAsync();
        return JsonSerializer.Deserialize<TestDataResponse>(payload, Json)!;
    }

    /// <summary>
    /// То же самое плоской таблицей CSV. У CSV нет места под метаданные, поэтому
    /// использованный seed возвращается заголовком X-Atlorium-Seed.
    /// </summary>
    public async Task<(string Csv, string? Seed)> GenerateCsvAsync(
        int count = 1, string? fields = null, int? seed = null)
    {
        var query = new List<string> { $"count={count}", "format=csv" };
        if (fields is not null) query.Add($"fields={Uri.EscapeDataString(fields)}");
        if (seed is not null) query.Add($"seed={seed}");

        using var response = await GetAsync("/api/testdata?" + string.Join("&", query));
        var csv = await response.Content.ReadAsStringAsync();
        var seedHeader = response.Headers.TryGetValues("X-Atlorium-Seed", out var values)
            ? values.FirstOrDefault()
            : null;
        return (csv, seedHeader);
    }

    /// <summary>
    /// Справочник доступных полей: идентификатор, группа, пример значения.
    /// Запрос бесплатный: справочник описывает контракт, а не результат работы.
    /// </summary>
    public async Task<IReadOnlyList<FieldDescriptor>> GetFieldsAsync()
    {
        using var response = await GetAsync("/api/testdata/fields");
        var payload = await response.Content.ReadAsStringAsync();
        return JsonSerializer.Deserialize<List<FieldDescriptor>>(payload, Json)!;
    }
}

// ── Модель ответа ───────────────────────────────────────────────────────────

public sealed record TestDataResponse(
    // Фактически использованное начальное значение генератора.
    int Seed,
    int Count,
    // Поля в каноническом порядке — он может отличаться от порядка в запросе.
    IReadOnlyList<string> Fields,
    IReadOnlyList<Dictionary<string, string>> Records,
    string Disclaimer);

/// <summary>Описание поля из справочника.</summary>
public sealed record FieldDescriptor(
    string Id,
    string Title,
    string TitleEn,
    string Description,
    string Group,
    bool HasChecksum,
    string Sample);
