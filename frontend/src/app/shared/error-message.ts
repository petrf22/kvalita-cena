import { HttpErrorResponse } from '@angular/common/http';
import { TranslocoService } from '@jsverse/transloco';
import { ErrorCode } from '../models/generated/enums';
import { GraphQlAppError } from '../services/graphql-service';
import { PRICE_KIND_KEYS } from './enum-labels';

/** Tvar {@code ProblemDetail} těla REST chyby (GlobalExceptionHandler.java) — vlastní `code`/
 *  `params`/`detail` navíc oproti RFC7807 základu, stejný kontrakt jako GraphQL extensions. */
interface ProblemDetailBody {
  code?: string;
  params?: readonly unknown[];
  detail?: string;
}

/**
 * Pro pár chybových kódů je `{0}` symbolické jméno enumu (`docs/lokalizace.md` — server posílá
 * jméno, ne hezký popisek), takže syrová interpolace by ukázala "CLUB_CARD" místo "Klubová
 * karta". Mapa kód → překladový klíč pro parametr; kód bez záznamu se interpoluje syrově.
 */
const PARAM_ENUM_KEYS: Partial<Record<ErrorCode, Record<string, string>>> = {
  [ErrorCode.ObservationDuplicatePriceKind]: PRICE_KIND_KEYS,
  [ErrorCode.ObservationPriceKindAlreadySubmittedToday]: PRICE_KIND_KEYS,
  [ErrorCode.ObservationPriceIncomplete]: PRICE_KIND_KEYS,
};

/**
 * Priorita: vlastní překlad podle strojového kódu → lokalizovaná zpráva ze serveru (ta už
 * respektovala `Accept-Language`) → obecný fallback (docs/lokalizace.md). Prostřední krok je
 * důvod, proč klient nespadne na "Něco se pokazilo" jen proto, že backend přidal `ErrorCode`
 * dřív, než se vydala nová verze webu — server pošle text rovnou ve správném jazyce.
 *
 * REST volání (`/api/me/*`, `/api/auth/email/*` — vlastní tok mimo GraphQL) chybu nese jako
 * {@link HttpErrorResponse} s `ProblemDetail` tělem, ne jako {@link GraphQlAppError} — stejný
 * kontrakt (`code`/`params`/lokalizovaný `detail`), jen jiný obal.
 */
export function translateError(err: unknown, transloco: TranslocoService): string {
  if (err instanceof GraphQlAppError) {
    if (err.code) {
      const key = `errors.${err.code}`;
      const translated = transloco.translate(key, paramsObject(err.code, err.params, transloco));
      if (translated !== key) return translated;
    }
    if (err.serverMessage) return err.serverMessage;
  }
  if (err instanceof HttpErrorResponse) {
    const body = err.error as ProblemDetailBody | null;
    if (body?.code) {
      const key = `errors.${body.code}`;
      const translated = transloco.translate(key, paramsObject(body.code, body.params ?? [], transloco));
      if (translated !== key) return translated;
    }
    if (body?.detail) return body.detail;
  }
  if (err instanceof Error && err.message) return err.message;
  return transloco.translate('errors.generic');
}

function paramsObject(
  code: string,
  params: readonly unknown[],
  transloco: TranslocoService,
): Record<string, unknown> {
  const enumKeys = PARAM_ENUM_KEYS[code as ErrorCode];
  return Object.fromEntries(
    params.map((value, index) => {
      const enumKey = typeof value === 'string' ? enumKeys?.[value] : undefined;
      return [`p${index}`, enumKey ? transloco.translate(enumKey) : value];
    }),
  );
}
