import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/**
 * Je prohlížeč online? Offline je u téhle appky věcný stav, ne chyba (docs/design.md, „Čtyři
 * stavy každé obrazovky") — obrazovka má říct, že se akce povede po připojení, ne ukázat
 * „něco se pokazilo".
 *
 * `navigator.onLine` umí lhát jen jedním směrem (hlásí online i v síti bez internetu), proto
 * se za offline bere i požadavek, který skončil bez odpovědi (`isNetworkError`).
 */
@Injectable({ providedIn: 'root' })
export class ConnectivityService {
  private readonly onlineSignal = signal(typeof navigator === 'undefined' || navigator.onLine);
  private readonly reconnectedSubject = new Subject<void>();

  readonly online = this.onlineSignal.asReadonly();
  /** Vyšle pokaždé, když se prohlížeč znovu připojí — podnět k automatickému zopakování. */
  readonly reconnected$: Observable<void> = this.reconnectedSubject.asObservable();

  constructor() {
    if (typeof window === 'undefined') return;
    const onOnline = () => {
      this.onlineSignal.set(true);
      this.reconnectedSubject.next();
    };
    const onOffline = () => this.onlineSignal.set(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    });
  }
}

/** Požadavek nedostal žádnou odpověď (status 0) — výpadek sítě, ne chyba serveru. */
export function isNetworkError(err: unknown): boolean {
  return err instanceof HttpErrorResponse && err.status === 0;
}
