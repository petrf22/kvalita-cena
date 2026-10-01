import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { NzBadgeModule } from 'ng-zorro-antd/badge';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { NzMenuModule } from 'ng-zorro-antd/menu';
import { AuthService } from './services/auth-service';
import { ReceiptService } from './services/receipt-service';
import { APP_VERSION } from './version';

/**
 * Menu (Hledání / Zadat cenu / Účtenky / Nastavení / Účet; Účtenky jen pro přihlášené) — na
 * mobilním prohlížeči se přes app.css přepne na spodní lištu, stejný princip jako bottom
 * navigation v mobile appce (mobile/.../ui/navigation/AppDestinations.kt). Přihlášení/odhlášení řeší features/login,
 * ne hlavička (dřív tam bylo tlačítko dvakrát — v hlavičce i na stránce).
 *
 * Patička (`nz-footer`) nese odkazy „O aplikaci"/„Podmínky užití"/„Zásady ochrany osobních
 * údajů" na každé stránce — dřív byly schované jen v Nastavení. Přepínače jazyk/země/měna na
 * `/settings` zůstávají (parita s mobilní SettingsScreen.kt), do patičky nejdou. Verze appky
 * v patičce je zároveň odkaz na `/changelog` (features/changelog) — zdroj pravdy pro obojí je
 * kořenový `CHANGELOG.md`/`VERSION`, viz `docs/vydani.md`.
 */
@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    NzLayoutModule,
    NzMenuModule,
    NzIconModule,
    NzBadgeModule,
    TranslocoPipe,
  ],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly auth = inject(AuthService);
  /** Odznak u „Účtenky" — kolik účtenek má pro vlastníka novinku (receiptSummary.unseenCount). */
  protected readonly receipts = inject(ReceiptService);
  protected readonly appVersion = APP_VERSION;
}
