import { TranslocoService } from '@jsverse/transloco';
import { GraphQlAppError } from '../services/graphql-service';

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
      const translated = transloco.translate(key, paramsObject(err.params));
      if (translated !== key) return translated;
    }
    if (err.serverMessage) return err.serverMessage;
  }
  if (err instanceof Error && err.message) return err.message;
  return transloco.translate('errors.generic');
}

function paramsObject(params: readonly unknown[]): Record<string, unknown> {
  return Object.fromEntries(params.map((value, index) => [`p${index}`, value]));
}
