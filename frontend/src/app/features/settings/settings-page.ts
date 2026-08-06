import { Component } from '@angular/core';
import { NzCardModule } from 'ng-zorro-antd/card';

/**
 * Stránka "Nastavení" — placeholder podle zadání ("doplním později"), ale ne prázdný: karta
 * Zdroje dat plní ODbL požadavek "UI vždy uvede zdroj" centrálně, ne jen u jednotlivého odkazu
 * v detailu produktu (docs/datovy-model.md). Mobilní protějšek: mobile ui/settings/SettingsScreen.kt.
 */
@Component({
  selector: 'app-settings-page',
  imports: [NzCardModule],
  templateUrl: './settings-page.html',
  styleUrl: './settings-page.css',
})
export class SettingsPage {
  protected readonly appVersion = '0.1.0';
}
