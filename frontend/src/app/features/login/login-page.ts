import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { AuthService } from '../../services/auth-service';

/**
 * Passwordless přihlášení — e-mail → OTP kód. V etapě 1 backend kód loguje do konzole
 * místo posílání mailem (app.auth.otp.mail-enabled=false), viz backend/docs/soukromi.md.
 */
@Component({
  selector: 'app-login-page',
  imports: [FormsModule, NzCardModule, NzFormModule, NzInputModule, NzButtonModule, NzAlertModule],
  templateUrl: './login-page.html',
  styleUrl: './login-page.css',
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly step = signal<'email' | 'code'>('email');
  protected readonly email = signal('');
  protected readonly code = signal('');
  protected readonly challengeUid = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  requestCode(): void {
    if (!this.email().trim()) return;

    this.loading.set(true);
    this.errorMessage.set(null);
    this.auth.requestOtp(this.email().trim()).subscribe({
      next: (response) => {
        this.challengeUid.set(response.challengeUid);
        this.step.set('code');
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('Nepodařilo se odeslat kód. Zkus to prosím znovu za chvíli.');
        this.loading.set(false);
      },
    });
  }

  verifyCode(): void {
    const challengeUid = this.challengeUid();
    if (!challengeUid || !this.code().trim()) return;

    this.loading.set(true);
    this.errorMessage.set(null);
    this.auth.verifyOtp(challengeUid, this.code().trim(), this.email().trim()).subscribe({
      next: () => {
        this.loading.set(false);
        this.router.navigateByUrl('/');
      },
      error: () => {
        this.errorMessage.set('Kód je neplatný nebo vypršel. Zkus to prosím znovu.');
        this.loading.set(false);
      },
    });
  }

  backToEmail(): void {
    this.step.set('email');
    this.code.set('');
    this.errorMessage.set(null);
  }
}
