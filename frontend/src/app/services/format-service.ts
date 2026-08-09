import { Injectable, inject } from '@angular/core';
import { INTL_TAGS, LanguageService } from './language-service';

/**
 * Formátování čísel/měny/data podle aktuálního jazyka — přes `Intl.*`, ne Angular pipy
 * (`CurrencyPipe`/`DatePipe`/`DecimalPipe` se v projektu nepoužívají, viz `LanguageService`
 * a CLAUDE.md). Formátovače jsou memoizované podle (jazyk, měna) — vytvořit `Intl.NumberFormat`
 * není zadarmo a volá se to na každý řádek tabulky hledání.
 */
@Injectable({ providedIn: 'root' })
export class FormatService {
  private readonly language = inject(LanguageService);
  private readonly moneyFormatters = new Map<string, Intl.NumberFormat>();
  private readonly dateFormatters = new Map<string, Intl.DateTimeFormat>();

  /** @param currency ISO-4217 kód z dat (docs/lokalizace.md) — chybí-li, CZK je jen nouzový fallback. */
  money(amount: number | null | undefined, currency: string | null | undefined): string {
    if (amount == null) return '—';
    return this.moneyFormatter(currency ?? 'CZK').format(amount);
  }

  /** Jen symbol/kód měny (např. "Kč", "€") — pro popisky typu "Cena ({{currency}})". */
  currencySymbol(currency: string | null | undefined): string {
    const code = currency ?? 'CZK';
    const part = this.moneyFormatter(code)
      .formatToParts(0)
      .find((p) => p.type === 'currency');
    return part?.value ?? code;
  }

  date(iso: string | null | undefined, style: 'short' | 'medium' | 'long' = 'medium'): string {
    if (!iso) return '—';
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return iso;
    return this.dateFormatter(style).format(date);
  }

  number(value: number, digits = 2): string {
    return new Intl.NumberFormat(this.tag(), {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(value);
  }

  private moneyFormatter(currency: string): Intl.NumberFormat {
    const key = `${this.language.lang()}|${currency}`;
    let formatter = this.moneyFormatters.get(key);
    if (!formatter) {
      formatter = new Intl.NumberFormat(this.tag(), { style: 'currency', currency });
      this.moneyFormatters.set(key, formatter);
    }
    return formatter;
  }

  private dateFormatter(style: 'short' | 'medium' | 'long'): Intl.DateTimeFormat {
    const key = `${this.language.lang()}|${style}`;
    let formatter = this.dateFormatters.get(key);
    if (!formatter) {
      formatter = new Intl.DateTimeFormat(this.tag(), { dateStyle: style });
      this.dateFormatters.set(key, formatter);
    }
    return formatter;
  }

  private tag(): string {
    return INTL_TAGS[this.language.lang()];
  }
}
