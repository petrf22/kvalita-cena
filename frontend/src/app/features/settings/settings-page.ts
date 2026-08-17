import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoDirective, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzSelectModule } from 'ng-zorro-antd/select';
import {
  DISPLAY_CURRENCIES,
  DisplayCurrency,
  DisplayCurrencyService,
} from '../../services/display-currency-service';
import { FxInfo } from '../../models/catalog';
import { CountryService } from '../../services/country-service';
import { FxService } from '../../services/fx-service';
import { AppLang, LanguageService } from '../../services/language-service';
import { KNOWN_COUNTRIES } from '../../shared/known-countries';

/** Endonyma — jazyk se vždy zobrazuje svým vlastním jménem, nikdy se nepřekládá (docs/lokalizace.md). */
const LANGUAGE_OPTIONS: { value: AppLang; label: string }[] = [
  { value: 'cs', label: 'Čeština' },
  { value: 'sk', label: 'Slovenčina' },
  { value: 'en', label: 'English' },
  { value: 'pl', label: 'Polski' },
  { value: 'de', label: 'Deutsch' },
];

/**
 * Stránka "Nastavení" — karta Zdroje dat plní ODbL požadavek "UI vždy uvede zdroj" centrálně,
 * ne jen u jednotlivého odkazu v detailu produktu (docs/datovy-model.md). Přepínač jazyka
 * (docs/lokalizace.md) je jediné místo v appce, kde jde volba jazyka nastavit ručně — jinak se
 * odvozuje z localStorage/navigator.languages (LanguageService.readInitialLang).
 *
 * Přepínač zobrazovací měny (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna") je
 * druhá, nezávislá volba — `null` (měna obchodu) je výchozí, appka pak vůbec neposílá
 * X-Display-Currency a nic nepřepočítává (DisplayCurrencyService).
 * Mobilní protějšek: mobile ui/settings/SettingsScreen.kt.
 */
@Component({
  selector: 'app-settings-page',
  imports: [
    FormsModule,
    RouterLink,
    NzCardModule,
    NzFormModule,
    NzSelectModule,
    TranslocoDirective,
  ],
  providers: [provideTranslocoScope('settings')],
  templateUrl: './settings-page.html',
  styleUrl: './settings-page.css',
})
export class SettingsPage {
  private readonly fxService = inject(FxService);
  private readonly transloco = inject(TranslocoService);

  protected readonly language = inject(LanguageService);
  protected readonly languageOptions = LANGUAGE_OPTIONS;
  protected readonly countryService = inject(CountryService);
  protected readonly countryOptions = computed(() => {
    const fromServer = this.countryService.countries().map((c) => c.code);
    return fromServer.length > 0 ? fromServer : KNOWN_COUNTRIES;
  });
  protected readonly displayCurrency = inject(DisplayCurrencyService);
  protected readonly currencyOptions = DISPLAY_CURRENCIES;
  protected readonly fxInfo = signal<FxInfo | null>(null);
  protected readonly appVersion = '0.1.0';

  constructor() {
    // Atribuce zdroje kurzu (docs/lokalizace.md) — jen k zobrazení na kartě, nic z toho
    // nerozhoduje, jaké měny appka nabídne (to je DISPLAY_CURRENCIES, lehká kopie app.fx).
    this.fxService.fxInfo().subscribe({ next: (info) => this.fxInfo.set(info) });
  }

  onLanguageChange(lang: AppLang): void {
    void this.language.setLang(lang);
  }

  onCountryChange(country: string): void {
    this.countryService.setCountry(country);
  }

  /** Appka zatím zná jména jen pro CZ/SK/PL — chybějící překlad zobrazí rovnou kód země. */
  protected countryOptionLabel(code: string): string {
    const key = `settings.country.${code}`;
    const translated = this.transloco.translate(key);
    return translated === key ? code : translated;
  }

  onCurrencyChange(currency: DisplayCurrency | null): void {
    this.displayCurrency.setCurrency(currency);
  }
}
