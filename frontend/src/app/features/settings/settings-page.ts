import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, provideTranslocoScope } from '@jsverse/transloco';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { AppLang, LanguageService } from '../../services/language-service';

/** Endonyma — jazyk se vždy zobrazuje svým vlastním jménem, nikdy se nepřekládá (docs/lokalizace.md). */
const LANGUAGE_OPTIONS: { value: AppLang; label: string }[] = [
  { value: 'cs', label: 'Čeština' },
  { value: 'sk', label: 'Slovenčina' },
  { value: 'en', label: 'English' },
  { value: 'pl', label: 'Polski' },
];

/**
 * Stránka "Nastavení" — karta Zdroje dat plní ODbL požadavek "UI vždy uvede zdroj" centrálně,
 * ne jen u jednotlivého odkazu v detailu produktu (docs/datovy-model.md). Přepínač jazyka
 * (docs/lokalizace.md) je jediné místo v appce, kde jde volba jazyka nastavit ručně — jinak se
 * odvozuje z localStorage/navigator.languages (LanguageService.readInitialLang).
 * Mobilní protějšek: mobile ui/settings/SettingsScreen.kt.
 */
@Component({
  selector: 'app-settings-page',
  imports: [FormsModule, NzCardModule, NzFormModule, NzSelectModule, TranslocoDirective],
  providers: [provideTranslocoScope('settings')],
  templateUrl: './settings-page.html',
  styleUrl: './settings-page.css',
})
export class SettingsPage {
  protected readonly language = inject(LanguageService);
  protected readonly languageOptions = LANGUAGE_OPTIONS;
  protected readonly appVersion = '0.1.0';

  onLanguageChange(lang: AppLang): void {
    void this.language.setLang(lang);
  }
}
