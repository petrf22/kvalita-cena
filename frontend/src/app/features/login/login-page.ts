import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { Viewer } from '../../models/auth';
import { AuthService } from '../../services/auth-service';
import { ViewerService } from '../../services/viewer-service';

/**
 * Stránka "Účet"/"Přihlášení" (menu je má sloučené do jedné položky, jako mobile
 * ui/account/AccountScreen.kt) — passwordless přihlášení (e-mail → OTP kód) pro nepřihlášené,
 * veřejná identita a odhlášení pro přihlášené. V etapě 1 backend kód loguje do konzole místo
 * posílání mailem (app.auth.otp.mail-enabled=false), viz backend/docs/soukromi.md.
 */
@Component({
  selector: 'app-login-page',
  imports: [FormsModule, NzCardModule, NzFormModule, NzInputModule, NzButtonModule, NzAlertModule, NzIconModule],
  templateUrl: './login-page.html',
  styleUrl: './login-page.css',
})
export class LoginPage {
  protected readonly auth = inject(AuthService);
  private readonly viewerService = inject(ViewerService);

  protected readonly step = signal<'email' | 'code'>('email');
  protected readonly email = signal('');
  protected readonly code = signal('');
  protected readonly challengeUid = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly viewer = signal<Viewer | null>(null);
  protected readonly viewerLoading = signal(false);

  constructor() {
    if (this.auth.isLoggedIn()) this.loadViewer();
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
        // Žádná navigace pryč — stránka se sama překreslí na účet (auth.isLoggedIn() se
        // změní), stejný princip jako mobile AccountScreen po přihlášení.
        this.loadViewer();
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
