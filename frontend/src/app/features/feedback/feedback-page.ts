import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  TranslocoDirective,
  TranslocoPipe,
  TranslocoService,
  provideTranslocoScope,
} from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { FeedbackCategory } from '../../models/catalog';
import { FeedbackService } from '../../services/feedback-service';
import { NavigationHistoryService } from '../../services/navigation-history-service';
import { FEEDBACK_CATEGORY_KEYS } from '../../shared/enum-labels';
import { translateError } from '../../shared/error-message';

/**
 * Jediný first-party kanál zpětné vazby (core.feedback) — funguje i BEZ přihlášení, na rozdíl
 * od nahlašování záznamů (docs/nasazeni.md, "Než pozvat první lidi"; docs/soukromi.md, vědomá
 * odchylka od record_flag). Přístupná z patičky, "O aplikaci" i Nastavení, aby ji tester našel
 * odkudkoli, ne jen v jedné konkrétní situaci.
 */
@Component({
  selector: 'app-feedback-page',
  imports: [
    FormsModule,
    RouterLink,
    NzCardModule,
    NzFormModule,
    NzInputModule,
    NzSelectModule,
    NzButtonModule,
    NzAlertModule,
    TranslocoDirective,
    TranslocoPipe,
  ],
  providers: [provideTranslocoScope('feedback')],
  templateUrl: './feedback-page.html',
  styleUrl: './feedback-page.css',
})
export class FeedbackPage implements OnDestroy {
  private readonly feedbackService = inject(FeedbackService);
  private readonly navigationHistory = inject(NavigationHistoryService);
  private readonly transloco = inject(TranslocoService);

  protected readonly categoryKeys = FEEDBACK_CATEGORY_KEYS;
  protected readonly categoryOptions = Object.values(FeedbackCategory);

  protected readonly category = signal<FeedbackCategory>(FeedbackCategory.Bug);
  protected readonly message = signal('');
  protected readonly contactEmail = signal('');
  // Honeypot (docs/nasazeni.md, obrana proti spamu) — appka tohle pole nikdy nevyplní, jen ho
  // v šabloně schová CSS (ne "hidden" atributem, který by bot přeskočil). Zůstává prázdné.
  protected readonly website = signal('');

  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly submitted = signal(false);

  // Zachyceno PŘI VSTUPU na stránku, ne až při odeslání — router.url v tu chvíli už ukazuje
  // na /feedback samotnou.
  private readonly pageRef = this.navigationHistory.previousUrl();

  // Proof-of-work (docs/nasazeni.md, obrana proti spamu) — výzva se vyžádá a řeší na pozadí
  // hned při vstupu na stránku, ať je nonce hotový dřív, než uživatel dopíše zprávu. Worker se
  // spouští jen tady na webu (mobil má vlastní ProofOfWork.kt), appka bez PoW klidně funguje
  // dál — required gating je na serveru (application-prod.yml vs. application-beta.yml).
  private challengeToken: string | null = null;
  private challengeReady: Promise<{ token: string; nonce: string } | null>;
  private worker: Worker | null = null;

  constructor() {
    this.challengeReady = new Promise((resolve) => {
      this.feedbackService.feedbackChallenge().subscribe({
        next: (challenge) => {
          this.challengeToken = challenge.token;
          this.worker = new Worker(new URL('../../shared/proof-of-work.worker', import.meta.url), {
            type: 'module',
          });
          this.worker.onmessage = ({ data }: MessageEvent<{ nonce: string }>) => {
            resolve({ token: challenge.token, nonce: data.nonce });
            this.worker?.terminate();
            this.worker = null;
          };
          this.worker.postMessage({ salt: challenge.salt, difficulty: challenge.difficulty });
        },
        // Appka pokračuje bez PoW — server rozhodne (required v prod, jen skóre v beta), ne
        // klient. Formulář zůstává použitelný, i když se appce nepovede výzvu vyžádat.
        error: () => resolve(null),
      });
    });
  }

  ngOnDestroy(): void {
    this.worker?.terminate();
  }

  submit(): void {
    if (!this.message().trim()) return;

    this.loading.set(true);
    this.errorMessage.set(null);
    this.challengeReady.then((solved) => {
      this.feedbackService
        .submitFeedback({
          category: this.category(),
          message: this.message().trim(),
          contactEmail: this.contactEmail().trim() || null,
          pageRef: this.pageRef,
          challenge: solved?.token ?? this.challengeToken,
          nonce: solved?.nonce ?? null,
          website: this.website().trim() || null,
        })
        .subscribe({
          next: () => {
            this.loading.set(false);
            this.submitted.set(true);
          },
          error: (err) => {
            this.loading.set(false);
            this.errorMessage.set(translateError(err, this.transloco));
          },
        });
    });
  }

  submitAnother(): void {
    this.submitted.set(false);
    this.message.set('');
    this.category.set(FeedbackCategory.Bug);
  }
}
