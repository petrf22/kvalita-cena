import { TranslocoService } from '@jsverse/transloco';
import { ErrorCode } from '../models/generated/enums';
import { GraphQlAppError } from '../services/graphql-service';
import { PRICE_KIND_KEYS } from './enum-labels';

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
