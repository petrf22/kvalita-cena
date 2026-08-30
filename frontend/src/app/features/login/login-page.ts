import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoDirective, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { Observable, first, shareReplay, switchMap } from 'rxjs';
import { OtpRequestResponse, Viewer } from '../../models/auth';
import { AuthService } from '../../services/auth-service';
import { ViewerService } from '../../services/viewer-service';
import { translateError } from '../../shared/error-message';

/**
 * Stránka "Účet"/"Přihlášení" (menu je má sloučené do jedné položky, jako mobile
 * ui/account/AccountScreen.kt) — passwordless přihlášení (e-mail → OTP kód) pro nepřihlášené,
 * veřejná identita a odhlášení pro přihlášené. V etapě 1 backend kód loguje do konzole místo
 * posílání mailem (app.auth.otp.mail-enabled=false), viz backend/docs/soukromi.md.
 *
 * Krok se na "zadání kódu" přepíná HNED po odeslání requestu, ne až po odpovědi serveru —
 * odeslání e-mailu umí trvat (SMTP, viz backend OtpService/SmtpOtpMailSender), a čekání na
 * formuláři vypadalo appce jako zaseknuté, takže uživatel odesílal znovu a narazil na cooldown
 * (OtpRateLimiter, 1 request/60s). Request na kód se drží jako sdílený Observable
 * ({@link otpRequest$}) — verifyCode na něj počká, i kdyby uživatel opsal kód dřív, než
 * odpověď dorazí. Při chybě se appka vrátí zpět na zadání e-mailu.
 */
@Component({
  selector: 'app-login-page',
  imports: [
    FormsModule,
    RouterLink,
    NzCardModule,
    NzFormModule,
    NzInputModule,
    NzButtonModule,
    NzAlertModule,
    NzIconModule,
    NzCheckboxModule,
    TranslocoDirective,
  ],
  providers: [provideTranslocoScope('login')],
  templateUrl: './login-page.html',
  styleUrl: './login-page.css',
})
export class LoginPage implements OnDestroy {
  protected readonly auth = inject(AuthService);
  private readonly viewerService = inject(ViewerService);
  private readonly transloco = inject(TranslocoService);

  protected readonly step = signal<'email' | 'code'>('email');
  protected readonly email = signal('');
  protected readonly code = signal('');
  // Souhlas s Podmínkami užití a Zásadami ochrany osobních údajů (docs/podminky-uziti.md,
  // docs/zasady-ochrany-osobnich-udaju.md) — vyžaduje se už tady, ne až u verifyCode(), protože
  // i requestOtp zpracovává e-mail (docs/soukromi.md, "Passwordless auth"), i když účet vzniká
  // JIT až při úspěšném ověření kódu.
  protected readonly consentGiven = signal(false);
  protected readonly sendingCode = signal(false);
  protected readonly verifying = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  /** Sekundy do dalšího možného odeslání — server ho vrací v resendAfterSec (OtpRateLimiter,
   *  1 request/60s na e-mail); 0 = tlačítko "Poslat znovu" je aktivní. */
  protected readonly resendCooldown = signal(0);

  protected readonly viewer = signal<Viewer | null>(null);
  protected readonly viewerLoading = signal(false);

  private otpRequest$: Observable<OtpRequestResponse> | null = null;
  private resendTimerId: ReturnType<typeof setInterval> | null = null;

  constructor() {
    if (this.auth.isLoggedIn()) this.loadViewer();
  }

  ngOnDestroy(): void {
    this.clearResendTimer();
  }

  private loadViewer(): void {
    this.viewerLoading.set(true);
    this.viewerService.me().subscribe({
      next: (viewer) => {
        this.viewer.set(viewer);
        this.viewerLoading.set(false);
      },
      error: () => this.viewerLoading.set(false),
    });
  }

  logout(): void {
    this.auth.logout().subscribe(() => this.viewer.set(null));
  }

  requestCode(): void {
    if (!this.email().trim() || !this.consentGiven() || this.sendingCode()) return;

    this.sendingCode.set(true);
    this.errorMessage.set(null);
    // Přepnutí kroku NEČEKÁ na odpověď — viz komentář u třídy.
    this.step.set('code');

    const request$ = this.auth.requestOtp(this.email().trim()).pipe(shareReplay(1));
    this.otpRequest$ = request$;
    request$.subscribe({
      next: (response) => {
        this.sendingCode.set(false);
        this.startResendCooldown(response.resendAfterSec);
      },
      error: (err) => {
        this.sendingCode.set(false);
        this.otpRequest$ = null;
        this.clearResendTimer();
        this.resendCooldown.set(0);
        // Zpět na zadání e-mailu — server odeslání odmítl (rate limit, pozastavený účet, …),
        // krok "zadej kód" by tu neměl co dělat.
        this.step.set('email');
        this.errorMessage.set(translateError(err, this.transloco));
      },
    });
  }

  verifyCode(): void {
    const request$ = this.otpRequest$;
    if (!request$ || !this.code().trim() || this.verifying()) return;

    this.verifying.set(true);
    this.errorMessage.set(null);
    request$
      .pipe(
        first(),
        switchMap((response) =>
          this.auth.verifyOtp(
            response.challengeUid,
            this.code().trim(),
            this.email().trim(),
            this.consentGiven(),
          ),
        ),
      )
      .subscribe({
        next: () => {
          this.verifying.set(false);
          // Žádná navigace pryč — stránka se sama překreslí na účet (auth.isLoggedIn() se
          // změní), stejný princip jako mobile AccountScreen po přihlášení.
          this.loadViewer();
        },
        error: (err) => {
          this.verifying.set(false);
          this.errorMessage.set(translateError(err, this.transloco));
        },
      });
  }

  backToEmail(): void {
    this.step.set('email');
    this.code.set('');
    this.errorMessage.set(null);
    this.otpRequest$ = null;
    this.clearResendTimer();
    this.resendCooldown.set(0);
  }

  private startResendCooldown(seconds: number): void {
    this.clearResendTimer();
    this.resendCooldown.set(seconds);
    this.resendTimerId = setInterval(() => {
      const next = this.resendCooldown() - 1;
      if (next <= 0) {
        this.resendCooldown.set(0);
        this.clearResendTimer();
      } else {
        this.resendCooldown.set(next);
      }
    }, 1000);
  }

  private clearResendTimer(): void {
    if (this.resendTimerId != null) clearInterval(this.resendTimerId);
    this.resendTimerId = null;
  }
}
