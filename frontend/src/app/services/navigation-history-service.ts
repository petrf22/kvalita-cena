import { Injectable, inject, signal } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/**
 * Počítá dokončené in-app navigace (`NavigationEnd`) v aktuální relaci prohlížeče — jediné, co
 * detail stránky (produkt/obchod) potřebují vědět, aby šlo bezpečně nabídnout `Location.back()`
 * místo pevného odkazu "Zpět na hledání". Je-li aktuální stránka PRVNÍ v appce (přímý odkaz,
 * obnovení stránky, sdílený link), historie prohlížeče před ní appce nemusí vůbec patřit — proto
 * `canGoBack()` vrátí false a stránka radši spadne na pevný cíl.
 *
 * Řeší obecně "vrátit se tam, odkud jsem přišel/a" (`/my` → produkt/obchod → zpátky na `/my`,
 * detail produktu → nejlevnější/vlastní obchod → zpátky na produkt, hledání → produkt → zpátky
 * na hledání), aniž by si každá cílová stránka musela pamatovat konkrétní zdrojovou routu.
 *
 * {@link previousUrl} slouží stránce zpětné vazby (`features/feedback`) jako `pageRef` —
 * odkud uživatel na formulář přišel, jen orientační (core.feedback.page_ref), appka na tom nic
 * nerozhoduje.
 */
@Injectable({ providedIn: 'root' })
export class NavigationHistoryService {
  private readonly router = inject(Router);
  private navigationCount = 0;

  readonly canGoBack = signal(false);
  readonly previousUrl = signal<string | null>(null);
  private currentUrl: string | null = null;

  constructor() {
    this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe((event) => {
        this.navigationCount += 1;
        this.canGoBack.set(this.navigationCount > 1);
        this.previousUrl.set(this.currentUrl);
        this.currentUrl = (event as NavigationEnd).urlAfterRedirects;
      });
  }
}
