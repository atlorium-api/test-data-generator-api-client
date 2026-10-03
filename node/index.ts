/**
 * Клиент API генератора тестовых данных РФ Atlorium — вымышленные, но формально
 * корректные реквизиты: ФИО, ИНН, СНИЛС, ОГРН, расчётный счёт, номер карты.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   npm install
 *   npm start
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

/**
 * Публичный демо-ключ. Этот сервис — особый случай: он ничего не ищет во внешних
 * источниках, а ПРИДУМЫВАЕТ данные по правилам. Поэтому подменять его ответ моком
 * нечем и незачем — с демо-ключом приходит ровно то же, что придёт с боевым.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const API_KEY = process.env.ATLORIUM_API_KEY ?? SANDBOX_KEY;
const BASE_URL = process.env.ATLORIUM_BASE_URL ?? 'https://atlorium.com';

const TIMEOUT_MS = 30_000;

/**
 * Фиксированный seed делает пример воспроизводимым: одна и та же команда всегда
 * печатает одни и те же реквизиты. На этом же строятся стабильные автотесты.
 */
const DEFAULT_SEED = 20_260_823;

/**
 * Набор полей примера подобран так, чтобы каждое второе поле можно было ПРОВЕРИТЬ
 * локально — в этом весь смысл сервиса.
 */
const DEFAULT_FIELDS = 'fullName,innPerson,snils,innCompany,ogrn,kpp,bic,bankName,bankAccount,cardNumber';

/** Одна сгенерированная запись: имя поля → значение. */
export type TestRecord = Record<string, string>;

export interface TestDataResponse {
  /** Фактически использованное начальное значение генератора. */
  seed: number;
  count: number;
  /** Поля в каноническом порядке — он может отличаться от порядка в запросе. */
  fields: string[];
  records: TestRecord[];
  disclaimer: string;
}

/** Описание поля из справочника. */
export interface FieldDescriptor {
  id: string;
  title: string;
  titleEn: string;
  description: string;
  group: string;
  hasChecksum: boolean;
  sample: string;
}

const ERROR_REASONS: Record<number, string> = {
  400: 'Недопустимое количество записей, неизвестное имя поля или неизвестный формат',
  401: 'API-ключ отсутствует, просрочен или недействителен',
  402: 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
  429: 'Превышен лимит запросов — повторите позже',
  503: 'Сервис временно недоступен (плановые работы) — повторите позже',
};

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
export class AtloriumError extends Error {
  constructor(readonly status: number, body: string) {
    const reason = ERROR_REASONS[status] ?? 'Неизвестная ошибка';
    super(`HTTP ${status}: ${reason}. Ответ сервера: ${body.slice(0, 200)}`);
    this.name = 'AtloriumError';
  }
}

async function request(path: string, params: Record<string, string>): Promise<Response> {
  const url = new URL(path, BASE_URL);
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }

  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${API_KEY}`,
      Accept: 'application/json',
    },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });

  if (!response.ok) {
    throw new AtloriumError(response.status, await response.text());
  }
  return response;
}

// ── Вызовы API ──────────────────────────────────────────────────────────────

/**
 * Генерирует набор записей. Единица тарификации — ОДНА ЗАПИСЬ, а не один вызов:
 * запрос на сто записей стоит сто единиц. Одинаковый seed при одинаковых
 * остальных параметрах даёт в точности одинаковый результат.
 */
export async function generate(options: {
  count?: number;
  fields?: string;
  seed?: number;
  gender?: 'any' | 'male' | 'female';
} = {}): Promise<TestDataResponse> {
  const params: Record<string, string> = {
    count: String(options.count ?? 1),
    gender: options.gender ?? 'any',
  };
  if (options.fields) params.fields = options.fields;
  if (options.seed !== undefined) params.seed = String(options.seed);

  const response = await request('/api/testdata', params);
  return response.json() as Promise<TestDataResponse>;
}

/**
 * То же самое плоской таблицей CSV. У CSV нет места под метаданные, поэтому
 * использованный seed возвращается заголовком X-Atlorium-Seed — он ставится
 * в обоих форматах.
 */
export async function generateCsv(options: {
  count?: number;
  fields?: string;
  seed?: number;
} = {}): Promise<{ csv: string; seed: string | null }> {
  const params: Record<string, string> = { count: String(options.count ?? 1), format: 'csv' };
  if (options.fields) params.fields = options.fields;
  if (options.seed !== undefined) params.seed = String(options.seed);

  const response = await request('/api/testdata', params);
  return { csv: await response.text(), seed: response.headers.get('X-Atlorium-Seed') };
}

/**
 * Справочник доступных полей: идентификатор, группа, пример значения.
 * Запрос бесплатный: справочник описывает контракт, а не результат работы.
 */
export async function getFields(): Promise<FieldDescriptor[]> {
  const response = await request('/api/testdata/fields', {});
  return response.json() as Promise<FieldDescriptor[]>;
}

// ── Применение данных: проверка контрольных сумм на своей стороне ────────────
// Тестовые данные ценны ровно настолько, насколько они проходят ВАШУ валидацию.
// Ниже — те же алгоритмы, что стоят в приёмных формах: если сгенерированный
// реквизит их проходит, он пройдёт и в вашем коде. Заодно это готовые валидаторы,
// которые всё равно пришлось бы написать.

/** Убирает форматирование: пробелы и дефисы в СНИЛС и номере карты. */
function digitsOf(value: string): string {
  return (value ?? '').replace(/\D/g, '');
}

/** ИНН физлица: 12 цифр, два контрольных разряда. */
export function validInnPerson(inn: string): boolean {
  const digits = digitsOf(inn);
  if (digits.length !== 12) return false;

  const weights11 = [7, 2, 4, 10, 3, 5, 9, 4, 6, 8];
  const weights12 = [3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8];

  let sum11 = 0;
  for (let i = 0; i < 10; i++) sum11 += Number(digits[i]) * weights11[i]!;
  let sum12 = 0;
  for (let i = 0; i < 11; i++) sum12 += Number(digits[i]) * weights12[i]!;

  return (sum11 % 11) % 10 === Number(digits[10]) && (sum12 % 11) % 10 === Number(digits[11]);
}

/** ИНН юрлица: 10 цифр, один контрольный разряд. */
export function validInnCompany(inn: string): boolean {
  const digits = digitsOf(inn);
  if (digits.length !== 10) return false;

  const weights = [2, 4, 10, 3, 5, 9, 4, 6, 8];
  let sum = 0;
  for (let i = 0; i < 9; i++) sum += Number(digits[i]) * weights[i]!;

  return (sum % 11) % 10 === Number(digits[9]);
}

/**
 * СНИЛС: 9 цифр номера + двузначное контрольное число. Веса убывают с 9 до 1.
 * Суммы 100 и 101 дают контроль 00, большие — остаток от деления на 101
 * (и снова 00, если остаток равен 100 или 101).
 */
export function validSnils(snils: string): boolean {
  const digits = digitsOf(snils);
  if (digits.length !== 11) return false;

  let total = 0;
  for (let i = 0; i < 9; i++) total += Number(digits[i]) * (9 - i);

  let control: number;
  if (total === 100 || total === 101) {
    control = 0;
  } else if (total > 101) {
    const remainder = total % 101;
    control = remainder === 100 || remainder === 101 ? 0 : remainder;
  } else {
    control = total;
  }

  return control === Number(digits.slice(9, 11));
}

/** ОГРН: 13 цифр. Контрольная — последняя цифра остатка от деления на 11. */
export function validOgrn(ogrn: string): boolean {
  const digits = digitsOf(ogrn);
  if (digits.length !== 13) return false;
  // BigInt, потому что двенадцатизначное число уже не помещается в точный
  // диапазон обычного number без риска потери младших разрядов.
  return Number((BigInt(digits.slice(0, 12)) % 11n) % 10n) === Number(digits[12]);
}

/**
 * Расчётный счёт проверяется ВМЕСТЕ с БИК — сам по себе он не проверяем.
 * К счёту слева приписывается префикс: для корсчетов (начинаются на 301) — «0»
 * и пятая-шестая цифры БИК, для остальных — три последние цифры БИК. Сумма
 * младших разрядов произведений на веса 7-1-3 должна оканчиваться нулём.
 */
export function validAccount(bic: string, account: string): boolean {
  const bicDigits = digitsOf(bic);
  const accountDigits = digitsOf(account);
  if (bicDigits.length !== 9 || accountDigits.length !== 20) return false;

  const prefix = accountDigits.startsWith('301')
    ? '0' + bicDigits.slice(4, 6)
    : bicDigits.slice(6, 9);
  const probe = prefix + accountDigits;

  const weights = [7, 1, 3];
  let total = 0;
  for (let i = 0; i < probe.length; i++) {
    total += (Number(probe[i]) * weights[i % 3]!) % 10;
  }
  return total % 10 === 0;
}

/** Номер банковской карты по алгоритму Луна (ISO/IEC 7812-1). */
export function validCard(card: string): boolean {
  const digits = digitsOf(card);
  if (digits.length < 12) return false;

  let total = 0;
  for (let index = 0; index < digits.length; index++) {
    let value = Number(digits[digits.length - 1 - index]);
    if (index % 2 === 1) {
      value *= 2;
      if (value > 9) value -= 9;
    }
    total += value;
  }
  return total % 10 === 0;
}

/** Поле → человеческое имя и проверка. Порядок задаёт порядок печати. */
const CHECKS: Array<{ field: string; title: string; check?: (record: TestRecord) => boolean }> = [
  { field: 'fullName', title: 'ФИО' },
  { field: 'innPerson', title: 'ИНН физлица', check: (r) => validInnPerson(r.innPerson!) },
  { field: 'snils', title: 'СНИЛС', check: (r) => validSnils(r.snils!) },
  { field: 'innCompany', title: 'ИНН юрлица', check: (r) => validInnCompany(r.innCompany!) },
  { field: 'kpp', title: 'КПП' },
  { field: 'ogrn', title: 'ОГРН', check: (r) => validOgrn(r.ogrn!) },
  { field: 'bic', title: 'БИК' },
  { field: 'bankName', title: 'Банк' },
  { field: 'bankAccount', title: 'Расчётный счёт', check: (r) => validAccount(r.bic!, r.bankAccount!) },
  { field: 'cardNumber', title: 'Номер карты', check: (r) => validCard(r.cardNumber!) },
];

/** Печатает запись и проверяет контрольные суммы. Возвращает [проверено, ошибок]. */
export function verifyRecord(record: TestRecord): [number, number] {
  let checked = 0;
  let failed = 0;

  for (const { field, title, check } of CHECKS) {
    const value = record[field];
    if (value === undefined) continue;

    if (!check) {
      console.log(`  ${title.padEnd(16)} ${value}`);
      continue;
    }

    const ok = check(record);
    checked++;
    if (!ok) failed++;
    const mark = ok ? '[OK] контрольная сумма сошлась' : '[!!] КОНТРОЛЬНАЯ СУММА НЕ СОШЛАСЬ';
    console.log(`  ${title.padEnd(16)} ${value.padEnd(22)} ${mark}`);
  }

  return [checked, failed];
}

// ── main ────────────────────────────────────────────────────────────────────

async function main(): Promise<number> {
  if (API_KEY === SANDBOX_KEY) {
    console.log('Демо-ключ. Этот сервис данные не ищет, а придумывает, поэтому демо-ответ');
    console.log('ничем не отличается от боевого — моков здесь нет.\n');
  }

  const count = Number(process.argv[2] ?? 2);

  let result: TestDataResponse;
  try {
    result = await generate({ count, fields: DEFAULT_FIELDS, seed: DEFAULT_SEED });
  } catch (error) {
    console.error(`Ошибка: ${(error as Error).message}`);
    return 1;
  }

  console.log(`Записей: ${result.count} · seed ${result.seed}\n`);

  let totalChecked = 0;
  let totalFailed = 0;
  result.records.forEach((record, index) => {
    console.log(`Запись ${index + 1}`);
    const [checked, failed] = verifyRecord(record);
    totalChecked += checked;
    totalFailed += failed;
    console.log();
  });

  console.log(`Проверено контрольных сумм: ${totalChecked}, не сошлось: ${totalFailed}`);

  // Воспроизводимость — не обещание в документации, а проверяемый факт.
  // Повторяем тот же запрос и сверяем записи целиком.
  let again: TestDataResponse;
  try {
    again = await generate({ count, fields: DEFAULT_FIELDS, seed: DEFAULT_SEED });
  } catch (error) {
    console.error(`Повторный запрос не удался: ${(error as Error).message}`);
    return 0;
  }

  const same = JSON.stringify(again.records) === JSON.stringify(result.records);
  console.log(`Воспроизводимость по seed ${DEFAULT_SEED}: ${same ? 'совпало' : 'РАСХОЖДЕНИЕ'}`);

  console.log(`\n${result.disclaimer}`);
  return totalFailed === 0 ? 0 : 1;
}

process.exitCode = await main();
