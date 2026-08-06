import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { NzMenuModule } from 'ng-zorro-antd/menu';
import { AuthService } from './services/auth-service';

/**
 * Menu se třemi položkami (Hledání / Nastavení / Účet) — na mobilním prohlížeči se přes
 * app.css přepne na spodní lištu, stejný princip jako bottom navigation v mobile appce
 * (mobile/.../ui/navigation/AppDestinations.kt). Přihlášení/odhlášení řeší features/login,
 * ne hlavička (dřív tam bylo tlačítko dvakrát — v hlavičce i na stránce).
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NzLayoutModule, NzMenuModule, NzIconModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly auth = inject(AuthService);
}
