// Клиент API генератора тестовых данных РФ Atlorium — вымышленные, но формально
// корректные реквизиты: ФИО, ИНН, СНИЛС, ОГРН, расчётный счёт, номер карты.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//
//	go run . 2
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// SandboxKey — публичный демо-ключ. Этот сервис — особый случай: он ничего не ищет
// во внешних источниках, а ПРИДУМЫВАЕТ данные по правилам. Поэтому подменять его
// ответ моком нечем и незачем — с демо-ключом приходит ровно то же, что придёт
// с боевым.
const SandboxKey = "ak_sandbox_demo_mockdata_v1"

// DefaultSeed делает пример воспроизводимым: одна и та же команда всегда печатает
// одни и те же реквизиты. На этом же строятся стабильные автотесты.
const DefaultSeed = 20260823

// DefaultFields подобраны так, чтобы каждое второе поле можно было ПРОВЕРИТЬ
// локально — в этом весь смысл сервиса.
const DefaultFields = "fullName,innPerson,snils,innCompany,ogrn,kpp,bic,bankName,bankAccount,cardNumber"

var (
	apiKey  = envOr("ATLORIUM_API_KEY", SandboxKey)
	baseURL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com")
	client  = &http.Client{Timeout: 30 * time.Second}
)

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// TestDataResponse — ответ генератора.
type TestDataResponse struct {
	// Фактически использованное начальное значение генератора.
	Seed  int `json:"seed"`
	Count int `json:"count"`
	// Поля в каноническом порядке — он может отличаться от порядка в запросе.
	Fields     []string            `json:"fields"`
	Records    []map[string]string `json:"records"`
	Disclaimer string              `json:"disclaimer"`
}

// FieldDescriptor — описание поля из справочника.
type FieldDescriptor struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	TitleEn     string `json:"titleEn"`
	Description string `json:"description"`
	Group       string `json:"group"`
	HasChecksum bool   `json:"hasChecksum"`
	Sample      string `json:"sample"`
}

// APIError раскладывает HTTP-код в человекочитаемую причину.
type APIError struct {
	Status int
	Body   string
}

var errorReasons = map[int]string{
	400: "Недопустимое количество записей, неизвестное имя поля или неизвестный формат",
	401: "API-ключ отсутствует, просрочен или недействителен",
	402: "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
	429: "Превышен лимит запросов — повторите позже",
	503: "Сервис временно недоступен (плановые работы) — повторите позже",
}

func (e *APIError) Error() string {
	reason, ok := errorReasons[e.Status]
	if !ok {
		reason = "Неизвестная ошибка"
	}
	body := e.Body
	if len(body) > 200 {
		body = body[:200]
	}
	return fmt.Sprintf("HTTP %d: %s. Ответ сервера: %s", e.Status, reason, body)
}

// get возвращает тело ответа и его заголовки: seed приходит заголовком
// X-Atlorium-Seed, и в формате CSV это единственный способ его узнать.
func get(path string, query url.Values) ([]byte, http.Header, error) {
	request, err := http.NewRequest(http.MethodGet, baseURL+path+"?"+query.Encode(), nil)
	if err != nil {
		return nil, nil, err
	}
	request.Header.Set("Authorization", "Bearer "+apiKey)
	request.Header.Set("Accept", "application/json")

	response, err := client.Do(request)
	if err != nil {
		return nil, nil, err
	}
	defer response.Body.Close()

	payload, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, nil, err
	}
	if response.StatusCode != http.StatusOK {
		return nil, nil, &APIError{Status: response.StatusCode, Body: string(payload)}
	}
	return payload, response.Header, nil
}

// ── Вызовы API ──────────────────────────────────────────────────────────────

// Generate создаёт набор записей. Единица тарификации — ОДНА ЗАПИСЬ, а не один
// вызов: запрос на сто записей стоит сто единиц. Одинаковый seed при одинаковых
// остальных параметрах даёт в точности одинаковый результат.
func Generate(count int, fields string, seed int, gender string) (*TestDataResponse, error) {
	query := url.Values{
		"count":  {strconv.Itoa(count)},
		"gender": {gender},
	}
	if fields != "" {
		query.Set("fields", fields)
	}
	if seed != 0 {
		query.Set("seed", strconv.Itoa(seed))
	}

	payload, _, err := get("/api/testdata", query)
	if err != nil {
		return nil, err
	}

	var result TestDataResponse
	if err := json.Unmarshal(payload, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// GenerateCsv отдаёт то же самое плоской таблицей CSV. У CSV нет места под
// метаданные, поэтому использованный seed возвращается заголовком.
func GenerateCsv(count int, fields string, seed int) (string, string, error) {
	query := url.Values{"count": {strconv.Itoa(count)}, "format": {"csv"}}
	if fields != "" {
		query.Set("fields", fields)
	}
	if seed != 0 {
		query.Set("seed", strconv.Itoa(seed))
	}

	payload, headers, err := get("/api/testdata", query)
	if err != nil {
		return "", "", err
	}
	return string(payload), headers.Get("X-Atlorium-Seed"), nil
}

// GetFields возвращает справочник доступных полей. Запрос бесплатный: справочник
// описывает контракт, а не результат работы.
func GetFields() ([]FieldDescriptor, error) {
	payload, _, err := get("/api/testdata/fields", url.Values{})
	if err != nil {
		return nil, err
	}
	var result []FieldDescriptor
	if err := json.Unmarshal(payload, &result); err != nil {
		return nil, err
	}
	return result, nil
}

// ── Применение данных: проверка контрольных сумм на своей стороне ────────────
// Тестовые данные ценны ровно настолько, насколько они проходят ВАШУ валидацию.
// Ниже — те же алгоритмы, что стоят в приёмных формах: если сгенерированный
// реквизит их проходит, он пройдёт и в вашем коде. Заодно это готовые валидаторы,
// которые всё равно пришлось бы написать.

var notDigit = regexp.MustCompile(`\D`)

// digitsOf убирает форматирование: пробелы и дефисы в СНИЛС и номере карты.
func digitsOf(value string) string {
	return notDigit.ReplaceAllString(value, "")
}

// ValidInnPerson проверяет ИНН физлица: 12 цифр, два контрольных разряда.
func ValidInnPerson(inn string) bool {
	digits := digitsOf(inn)
	if len(digits) != 12 {
		return false
	}

	weights11 := []int{7, 2, 4, 10, 3, 5, 9, 4, 6, 8}
	weights12 := []int{3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8}

	sum11, sum12 := 0, 0
	for i := 0; i < 10; i++ {
		sum11 += int(digits[i]-'0') * weights11[i]
	}
	for i := 0; i < 11; i++ {
		sum12 += int(digits[i]-'0') * weights12[i]
	}

	return sum11%11%10 == int(digits[10]-'0') && sum12%11%10 == int(digits[11]-'0')
}

// ValidInnCompany проверяет ИНН юрлица: 10 цифр, один контрольный разряд.
func ValidInnCompany(inn string) bool {
	digits := digitsOf(inn)
	if len(digits) != 10 {
		return false
	}

	weights := []int{2, 4, 10, 3, 5, 9, 4, 6, 8}
	sum := 0
	for i := 0; i < 9; i++ {
		sum += int(digits[i]-'0') * weights[i]
	}
	return sum%11%10 == int(digits[9]-'0')
}

// ValidSnils проверяет СНИЛС: 9 цифр номера + двузначное контрольное число.
// Веса убывают с 9 до 1. Суммы 100 и 101 дают контроль 00, большие — остаток
// от деления на 101 (и снова 00, если остаток равен 100 или 101).
func ValidSnils(snils string) bool {
	digits := digitsOf(snils)
	if len(digits) != 11 {
		return false
	}

	total := 0
	for i := 0; i < 9; i++ {
		total += int(digits[i]-'0') * (9 - i)
	}

	control := total
	switch {
	case total == 100 || total == 101:
		control = 0
	case total > 101:
		remainder := total % 101
		if remainder == 100 || remainder == 101 {
			remainder = 0
		}
		control = remainder
	}

	stated, err := strconv.Atoi(digits[9:11])
	return err == nil && control == stated
}

// ValidOgrn проверяет ОГРН: 13 цифр, контрольная — последняя цифра остатка
// от деления двенадцатизначного числа на 11.
func ValidOgrn(ogrn string) bool {
	digits := digitsOf(ogrn)
	if len(digits) != 13 {
		return false
	}

	// Остаток считаем посимвольно: двенадцатизначное число помещается в int64,
	// но такой разбор одинаково работает и для более длинных номеров.
	remainder := 0
	for i := 0; i < 12; i++ {
		remainder = (remainder*10 + int(digits[i]-'0')) % 11
	}
	return remainder%10 == int(digits[12]-'0')
}

// ValidAccount проверяет расчётный счёт ВМЕСТЕ с БИК — сам по себе он не проверяем.
// К счёту слева приписывается префикс: для корсчетов (начинаются на 301) — «0»
// и пятая-шестая цифры БИК, для остальных — три последние цифры БИК. Сумма
// младших разрядов произведений на веса 7-1-3 должна оканчиваться нулём.
func ValidAccount(bic, account string) bool {
	bicDigits := digitsOf(bic)
	accountDigits := digitsOf(account)
	if len(bicDigits) != 9 || len(accountDigits) != 20 {
		return false
	}

	prefix := bicDigits[6:9]
	if strings.HasPrefix(accountDigits, "301") {
		prefix = "0" + bicDigits[4:6]
	}
	probe := prefix + accountDigits

	weights := []int{7, 1, 3}
	total := 0
	for i := 0; i < len(probe); i++ {
		total += int(probe[i]-'0') * weights[i%3] % 10
	}
	return total%10 == 0
}

// ValidCard проверяет номер банковской карты по алгоритму Луна (ISO/IEC 7812-1).
func ValidCard(card string) bool {
	digits := digitsOf(card)
	if len(digits) < 12 {
		return false
	}

	total := 0
	for index := 0; index < len(digits); index++ {
		value := int(digits[len(digits)-1-index] - '0')
		if index%2 == 1 {
			value *= 2
			if value > 9 {
				value -= 9
			}
		}
		total += value
	}
	return total%10 == 0
}

// check — поле записи, его человеческое имя и проверка (может отсутствовать).
type check struct {
	Field  string
	Title  string
	Verify func(record map[string]string) bool
}

var checks = []check{
	{Field: "fullName", Title: "ФИО"},
	{Field: "innPerson", Title: "ИНН физлица", Verify: func(r map[string]string) bool { return ValidInnPerson(r["innPerson"]) }},
	{Field: "snils", Title: "СНИЛС", Verify: func(r map[string]string) bool { return ValidSnils(r["snils"]) }},
	{Field: "innCompany", Title: "ИНН юрлица", Verify: func(r map[string]string) bool { return ValidInnCompany(r["innCompany"]) }},
	{Field: "kpp", Title: "КПП"},
	{Field: "ogrn", Title: "ОГРН", Verify: func(r map[string]string) bool { return ValidOgrn(r["ogrn"]) }},
	{Field: "bic", Title: "БИК"},
	{Field: "bankName", Title: "Банк"},
	{Field: "bankAccount", Title: "Расчётный счёт", Verify: func(r map[string]string) bool { return ValidAccount(r["bic"], r["bankAccount"]) }},
	{Field: "cardNumber", Title: "Номер карты", Verify: func(r map[string]string) bool { return ValidCard(r["cardNumber"]) }},
}

// padRight дополняет строку пробелами до нужной ширины В СИМВОЛАХ, а не в байтах:
// в кириллице символ занимает два байта, и обычный %-16s разъехался бы.
func padRight(text string, width int) string {
	runes := []rune(text)
	if len(runes) >= width {
		return text
	}
	return text + strings.Repeat(" ", width-len(runes))
}

// VerifyRecord печатает запись и проверяет контрольные суммы.
// Возвращает число проверок и число несошедшихся.
func VerifyRecord(record map[string]string) (int, int) {
	checked, failed := 0, 0

	for _, item := range checks {
		value, present := record[item.Field]
		if !present {
			continue
		}

		if item.Verify == nil {
			fmt.Printf("  %s %s\n", padRight(item.Title, 16), value)
			continue
		}

		ok := item.Verify(record)
		checked++
		mark := "[OK] контрольная сумма сошлась"
		if !ok {
			failed++
			mark = "[!!] КОНТРОЛЬНАЯ СУММА НЕ СОШЛАСЬ"
		}
		fmt.Printf("  %s %s %s\n", padRight(item.Title, 16), padRight(value, 22), mark)
	}

	return checked, failed
}

// ── main ────────────────────────────────────────────────────────────────────

func main() {
	if apiKey == SandboxKey {
		fmt.Println("Демо-ключ. Этот сервис данные не ищет, а придумывает, поэтому демо-ответ")
		fmt.Println("ничем не отличается от боевого — моков здесь нет.")
		fmt.Println()
	}

	count := 2
	if len(os.Args) > 1 {
		if parsed, err := strconv.Atoi(os.Args[1]); err == nil {
			count = parsed
		}
	}

	result, err := Generate(count, DefaultFields, DefaultSeed, "any")
	if err != nil {
		fmt.Fprintf(os.Stderr, "Ошибка: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Записей: %d · seed %d\n\n", result.Count, result.Seed)

	totalChecked, totalFailed := 0, 0
	for index, record := range result.Records {
		fmt.Printf("Запись %d\n", index+1)
		checked, failed := VerifyRecord(record)
		totalChecked += checked
		totalFailed += failed
		fmt.Println()
	}

	fmt.Printf("Проверено контрольных сумм: %d, не сошлось: %d\n", totalChecked, totalFailed)

	// Воспроизводимость — не обещание в документации, а проверяемый факт.
	// Повторяем тот же запрос и сверяем записи целиком.
	again, err := Generate(count, DefaultFields, DefaultSeed, "any")
	if err != nil {
		fmt.Fprintf(os.Stderr, "Повторный запрос не удался: %v\n", err)
		return
	}

	verdict := "совпало"
	if !sameRecords(result.Records, again.Records) {
		verdict = "РАСХОЖДЕНИЕ"
	}
	fmt.Printf("Воспроизводимость по seed %d: %s\n", DefaultSeed, verdict)

	fmt.Printf("\n%s\n", result.Disclaimer)

	if totalFailed != 0 {
		os.Exit(1)
	}
}

func sameRecords(left, right []map[string]string) bool {
	if len(left) != len(right) {
		return false
	}
	for i := range left {
		if len(left[i]) != len(right[i]) {
			return false
		}
		for key, value := range left[i] {
			if right[i][key] != value {
				return false
			}
		}
	}
	return true
}
