/*
 * Клиент API генератора тестовых данных РФ Atlorium — вымышленные, но формально
 * корректные реквизиты: ФИО, ИНН, СНИЛС, ОГРН, расчётный счёт, номер карты.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе).
 * Начиная с Java 11 файл запускается напрямую, без компиляции и без зависимостей:
 *
 *     java Main.java 2
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    /**
     * Публичный демо-ключ. Этот сервис — особый случай: он ничего не ищет во внешних
     * источниках, а ПРИДУМЫВАЕТ данные по правилам. Поэтому подменять его ответ моком
     * нечем и незачем — с демо-ключом приходит ровно то же, что придёт с боевым.
     */
    static final String SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1";

    static final String API_KEY = envOr("ATLORIUM_API_KEY", SANDBOX_KEY);
    static final String BASE_URL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com");

    /**
     * Фиксированный seed делает пример воспроизводимым: одна и та же команда всегда
     * печатает одни и те же реквизиты. На этом же строятся стабильные автотесты.
     */
    static final int DEFAULT_SEED = 20260823;

    /**
     * Набор полей примера подобран так, чтобы каждое второе поле можно было ПРОВЕРИТЬ
     * локально — в этом весь смысл сервиса.
     */
    static final String DEFAULT_FIELDS =
            "fullName,innPerson,snils,innCompany,ogrn,kpp,bic,bankName,bankAccount,cardNumber";

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    /** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
    static class AtloriumException extends RuntimeException {
        private static final Map<Integer, String> REASONS = new HashMap<Integer, String>();

        static {
            REASONS.put(400, "Недопустимое количество записей, неизвестное имя поля или неизвестный формат");
            REASONS.put(401, "API-ключ отсутствует, просрочен или недействителен");
            REASONS.put(402, "Недостаточно кредитов на балансе — пополните на https://atlorium.com");
            REASONS.put(429, "Превышен лимит запросов — повторите позже");
            REASONS.put(503, "Сервис временно недоступен (плановые работы) — повторите позже");
        }

        final int status;

        AtloriumException(int status, String body) {
            super("HTTP " + status + ": "
                    + REASONS.getOrDefault(status, "Неизвестная ошибка")
                    + ". Ответ сервера: " + body.substring(0, Math.min(200, body.length())));
            this.status = status;
        }
    }

    static HttpResponse<byte[]> get(String path, String query) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path + "?" + query))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new AtloriumException(response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8));
        }
        return response;
    }

    // ── Вызовы API ───────────────────────────────────────────────────────────

    /**
     * Генерирует набор записей. Единица тарификации — ОДНА ЗАПИСЬ, а не один вызов:
     * запрос на сто записей стоит сто единиц. Одинаковый seed при одинаковых
     * остальных параметрах даёт в точности одинаковый результат.
     */
    static String generate(int count, String fields, int seed, String gender)
            throws IOException, InterruptedException {
        String query = "count=" + count + "&gender=" + gender
                + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8)
                + "&seed=" + seed;
        return new String(get("/api/testdata", query).body(), StandardCharsets.UTF_8);
    }

    /**
     * То же самое плоской таблицей CSV. У CSV нет места под метаданные, поэтому
     * использованный seed возвращается заголовком X-Atlorium-Seed.
     */
    static String generateCsv(int count, String fields, int seed) throws IOException, InterruptedException {
        String query = "count=" + count + "&format=csv"
                + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8)
                + "&seed=" + seed;
        return new String(get("/api/testdata", query).body(), StandardCharsets.UTF_8);
    }

    /**
     * Справочник доступных полей: идентификатор, группа, пример значения.
     * Запрос бесплатный: справочник описывает контракт, а не результат работы.
     */
    static String getFields() throws IOException, InterruptedException {
        return new String(get("/api/testdata/fields", "").body(), StandardCharsets.UTF_8);
    }

    // ── Разбор JSON ──────────────────────────────────────────────────────────
    // Пример намеренно оставлен без внешних зависимостей, чтобы запускаться одной
    // командой `java Main.java`. В рабочем проекте берите Jackson или Gson.

    static String str(String json, String field) {
        if (json == null) return null;
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"") : null;
    }

    static int intOf(String json, String field) {
        if (json == null) return 0;
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** Возвращает содержимое массива по имени поля, считая скобки. */
    static String arrayBody(String json, String field) {
        int key = json.indexOf("\"" + field + "\"");
        if (key < 0) return null;

        int start = json.indexOf('[', key);
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < json.length(); i++) {
            char symbol = json.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (symbol == '\\') {
                escaped = true;
                continue;
            }
            if (symbol == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (symbol == '[') depth++;
            else if (symbol == ']' && --depth == 0) return json.substring(start + 1, i);
        }
        return null;
    }

    /** Разбивает содержимое массива на объекты верхнего уровня. */
    static List<String> objects(String body) {
        List<String> result = new ArrayList<String>();
        if (body == null) return result;

        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < body.length(); i++) {
            char symbol = body.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (symbol == '\\') {
                escaped = true;
                continue;
            }
            if (symbol == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (symbol == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (symbol == '}' && --depth == 0 && start >= 0) {
                result.add(body.substring(start, i + 1));
            }
        }
        return result;
    }

    /** Разбирает плоский объект «строка → строка» в карту. */
    static Map<String, String> flatObject(String json) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        Matcher matcher = Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2).replace("\\\"", "\""));
        }
        return result;
    }

    // ── Применение данных: проверка контрольных сумм на своей стороне ────────
    // Тестовые данные ценны ровно настолько, насколько они проходят ВАШУ валидацию.
    // Ниже — те же алгоритмы, что стоят в приёмных формах: если сгенерированный
    // реквизит их проходит, он пройдёт и в вашем коде. Заодно это готовые
    // валидаторы, которые всё равно пришлось бы написать.

    /** Убирает форматирование: пробелы и дефисы в СНИЛС и номере карты. */
    static String digitsOf(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    /** ИНН физлица: 12 цифр, два контрольных разряда. */
    static boolean validInnPerson(String inn) {
        String digits = digitsOf(inn);
        if (digits.length() != 12) return false;

        int[] weights11 = { 7, 2, 4, 10, 3, 5, 9, 4, 6, 8 };
        int[] weights12 = { 3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8 };

        int sum11 = 0;
        for (int i = 0; i < 10; i++) sum11 += (digits.charAt(i) - '0') * weights11[i];
        int sum12 = 0;
        for (int i = 0; i < 11; i++) sum12 += (digits.charAt(i) - '0') * weights12[i];

        return sum11 % 11 % 10 == digits.charAt(10) - '0'
                && sum12 % 11 % 10 == digits.charAt(11) - '0';
    }

    /** ИНН юрлица: 10 цифр, один контрольный разряд. */
    static boolean validInnCompany(String inn) {
        String digits = digitsOf(inn);
        if (digits.length() != 10) return false;

        int[] weights = { 2, 4, 10, 3, 5, 9, 4, 6, 8 };
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (digits.charAt(i) - '0') * weights[i];

        return sum % 11 % 10 == digits.charAt(9) - '0';
    }

    /**
     * СНИЛС: 9 цифр номера + двузначное контрольное число. Веса убывают с 9 до 1.
     * Суммы 100 и 101 дают контроль 00, большие — остаток от деления на 101
     * (и снова 00, если остаток равен 100 или 101).
     */
    static boolean validSnils(String snils) {
        String digits = digitsOf(snils);
        if (digits.length() != 11) return false;

        int total = 0;
        for (int i = 0; i < 9; i++) total += (digits.charAt(i) - '0') * (9 - i);

        int control;
        if (total == 100 || total == 101) {
            control = 0;
        } else if (total > 101) {
            int remainder = total % 101;
            control = (remainder == 100 || remainder == 101) ? 0 : remainder;
        } else {
            control = total;
        }

        return control == Integer.parseInt(digits.substring(9, 11));
    }

    /** ОГРН: 13 цифр. Контрольная — последняя цифра остатка от деления на 11. */
    static boolean validOgrn(String ogrn) {
        String digits = digitsOf(ogrn);
        if (digits.length() != 13) return false;

        // Остаток считаем посимвольно: двенадцатизначное число помещается в long,
        // но такой разбор одинаково работает и для более длинных номеров.
        int remainder = 0;
        for (int i = 0; i < 12; i++) {
            remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 11;
        }
        return remainder % 10 == digits.charAt(12) - '0';
    }

    /**
     * Расчётный счёт проверяется ВМЕСТЕ с БИК — сам по себе он не проверяем.
     * К счёту слева приписывается префикс: для корсчетов (начинаются на 301) — «0»
     * и пятая-шестая цифры БИК, для остальных — три последние цифры БИК. Сумма
     * младших разрядов произведений на веса 7-1-3 должна оканчиваться нулём.
     */
    static boolean validAccount(String bic, String account) {
        String bicDigits = digitsOf(bic);
        String accountDigits = digitsOf(account);
        if (bicDigits.length() != 9 || accountDigits.length() != 20) return false;

        String prefix = accountDigits.startsWith("301")
                ? "0" + bicDigits.substring(4, 6)
                : bicDigits.substring(6, 9);
        String probe = prefix + accountDigits;

        int[] weights = { 7, 1, 3 };
        int total = 0;
        for (int i = 0; i < probe.length(); i++) {
            total += (probe.charAt(i) - '0') * weights[i % 3] % 10;
        }
        return total % 10 == 0;
    }

    /** Номер банковской карты по алгоритму Луна (ISO/IEC 7812-1). */
    static boolean validCard(String card) {
        String digits = digitsOf(card);
        if (digits.length() < 12) return false;

        int total = 0;
        for (int index = 0; index < digits.length(); index++) {
            int value = digits.charAt(digits.length() - 1 - index) - '0';
            if (index % 2 == 1) {
                value *= 2;
                if (value > 9) value -= 9;
            }
            total += value;
        }
        return total % 10 == 0;
    }

    /** Поле записи, его человеческое имя и проверка (может отсутствовать). */
    static class Check {
        final String field;
        final String title;
        final Predicate<Map<String, String>> verify;

        Check(String field, String title, Predicate<Map<String, String>> verify) {
            this.field = field;
            this.title = title;
            this.verify = verify;
        }
    }

    static final List<Check> CHECKS = new ArrayList<Check>();

    static {
        CHECKS.add(new Check("fullName", "ФИО", null));
        CHECKS.add(new Check("innPerson", "ИНН физлица", r -> validInnPerson(r.get("innPerson"))));
        CHECKS.add(new Check("snils", "СНИЛС", r -> validSnils(r.get("snils"))));
        CHECKS.add(new Check("innCompany", "ИНН юрлица", r -> validInnCompany(r.get("innCompany"))));
        CHECKS.add(new Check("kpp", "КПП", null));
        CHECKS.add(new Check("ogrn", "ОГРН", r -> validOgrn(r.get("ogrn"))));
        CHECKS.add(new Check("bic", "БИК", null));
        CHECKS.add(new Check("bankName", "Банк", null));
        CHECKS.add(new Check("bankAccount", "Расчётный счёт", r -> validAccount(r.get("bic"), r.get("bankAccount"))));
        CHECKS.add(new Check("cardNumber", "Номер карты", r -> validCard(r.get("cardNumber"))));
    }

    static String padRight(String text, int width) {
        StringBuilder result = new StringBuilder(text);
        while (result.length() < width) result.append(' ');
        return result.toString();
    }

    /** Печатает запись и проверяет контрольные суммы: [проверено, не сошлось]. */
    static int[] verifyRecord(Map<String, String> record) {
        int checked = 0;
        int failed = 0;

        for (Check item : CHECKS) {
            String value = record.get(item.field);
            if (value == null) continue;

            if (item.verify == null) {
                System.out.println("  " + padRight(item.title, 16) + " " + value);
                continue;
            }

            boolean ok = item.verify.test(record);
            checked++;
            if (!ok) failed++;
            String mark = ok ? "[OK] контрольная сумма сошлась" : "[!!] КОНТРОЛЬНАЯ СУММА НЕ СОШЛАСЬ";
            System.out.println("  " + padRight(item.title, 16) + " " + padRight(value, 22) + " " + mark);
        }

        return new int[] { checked, failed };
    }

    // ── main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("Демо-ключ. Этот сервис данные не ищет, а придумывает, поэтому демо-ответ");
            System.out.println("ничем не отличается от боевого — моков здесь нет.\n");
        }

        int count = args.length > 0 ? Integer.parseInt(args[0]) : 2;

        String result;
        try {
            result = generate(count, DEFAULT_FIELDS, DEFAULT_SEED, "any");
        } catch (AtloriumException error) {
            System.err.println("Ошибка: " + error.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Записей: " + intOf(result, "count") + " · seed " + intOf(result, "seed") + "\n");

        List<Map<String, String>> records = new ArrayList<Map<String, String>>();
        for (String item : objects(arrayBody(result, "records"))) {
            records.add(flatObject(item));
        }

        int totalChecked = 0;
        int totalFailed = 0;
        for (int i = 0; i < records.size(); i++) {
            System.out.println("Запись " + (i + 1));
            int[] counters = verifyRecord(records.get(i));
            totalChecked += counters[0];
            totalFailed += counters[1];
            System.out.println();
        }

        System.out.println("Проверено контрольных сумм: " + totalChecked + ", не сошлось: " + totalFailed);

        // Воспроизводимость — не обещание в документации, а проверяемый факт.
        // Повторяем тот же запрос и сверяем записи целиком.
        String again;
        try {
            again = generate(count, DEFAULT_FIELDS, DEFAULT_SEED, "any");
        } catch (AtloriumException error) {
            System.err.println("Повторный запрос не удался: " + error.getMessage());
            return;
        }

        List<Map<String, String>> repeated = new ArrayList<Map<String, String>>();
        for (String item : objects(arrayBody(again, "records"))) {
            repeated.add(flatObject(item));
        }

        boolean same = repeated.equals(records);
        System.out.println("Воспроизводимость по seed " + DEFAULT_SEED + ": "
                + (same ? "совпало" : "РАСХОЖДЕНИЕ"));

        System.out.println("\n" + str(result, "disclaimer"));

        if (totalFailed != 0) System.exit(1);
    }
}
