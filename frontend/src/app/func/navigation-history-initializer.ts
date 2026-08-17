import { inject } from '@angular/core';
import { NavigationHistoryService } from '../services/navigation-history-service';

/**
 * Vynutí vytvoření {@link NavigationHistoryService} PŘED první (initial) navigací routeru —
 * jinak by se služba poprvé vytvořila až uvnitř nějaké komponenty (typicky detail produktu/
 * obchodu), a o navigace před tímhle okamžikem by nevěděla vůbec (canGoBack() by tak vždy
 * spadl na false, i když uživatel do appky přišel přes několik stránek).
 */
export function navigationHistoryInitializer(): void {
  inject(NavigationHistoryService);
}
