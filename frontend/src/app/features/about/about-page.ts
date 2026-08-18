import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective, provideTranslocoScope } from '@jsverse/transloco';
import { NzCardModule } from 'ng-zorro-antd/card';
import { FxInfo } from '../../models/catalog';
import { FxService } from '../../services/fx-service';

/**
 * Stránka "O aplikaci" — co appka dělá, odkud bere data (dřív karta "Zdroje dat" v Nastavení,
 * i s appVersion sem přesunuto, viz CLAUDE.md), otevřený kód a kontakt. Vzor terms-page.ts/
 * privacy-page.ts, ale text se (na rozdíl od právních dokumentů) plně překládá do všech pěti
 * jazyků — není to právní text s rizikem nepřesného strojového překladu.
 */
@Component({
  selector: 'app-about-page',
  imports: [RouterLink, NzCardModule, TranslocoDirective],
  providers: [provideTranslocoScope('about')],
  templateUrl: './about-page.html',
  styleUrl: '../../shared/document-page.css',
})
export class AboutPage {
  private readonly fxService = inject(FxService);

  protected readonly fxInfo = signal<FxInfo | null>(null);
  protected readonly appVersion = '0.1.0';

  constructor() {
    // Atribuce zdroje kurzu (docs/lokalizace.md) — jen k zobrazení, nic z toho nerozhoduje,
    // jaké měny appka nabídne (to je DISPLAY_CURRENCIES v Nastavení).
    this.fxService.fxInfo().subscribe({ next: (info) => this.fxInfo.set(info) });
  }
}
