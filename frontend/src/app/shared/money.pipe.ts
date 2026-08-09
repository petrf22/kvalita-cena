import { Pipe, PipeTransform, inject } from '@angular/core';
import { FormatService } from '../services/format-service';

/**
 * `pure: false` schválně — měna přichází z dat (multi-currency, docs/lokalizace.md) a jazyk ze
 * signálu v `LanguageService`, žádnou z těch dvou vstupů Angular change detection nevidí jako
 * "input" pipu. Čistý pipe by po přepnutí jazyka zůstal viset ve starém formátu. Formátovače
 * jsou memoizované ve `FormatService`, takže časté přepočítání nic nestojí.
 */
@Pipe({ name: 'money', pure: false })
export class MoneyPipe implements PipeTransform {
  private readonly format = inject(FormatService);

  transform(amount: number | null | undefined, currency: string | null | undefined): string {
    return this.format.money(amount, currency);
  }
}
