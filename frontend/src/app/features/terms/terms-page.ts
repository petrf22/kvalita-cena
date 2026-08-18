import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective, provideTranslocoScope } from '@jsverse/transloco';
import { NzCardModule } from 'ng-zorro-antd/card';
import { LanguageService } from '../../services/language-service';

/**
 * Statický text zrcadlí docs/podminky-uziti.md (jeden zdroj pravdy pro znění) — při úpravě
 * znění se mění nejdřív tam, sem se jen přenese. Zatím jen česky, stejně jako zdrojový
 * dokument; t('legal.czechOnlyNotice') to čtenáři v jiném jazyce appky řekne rovnou.
 */
@Component({
  selector: 'app-terms-page',
  imports: [RouterLink, NzCardModule, TranslocoDirective],
  providers: [provideTranslocoScope('legal')],
  templateUrl: './terms-page.html',
  styleUrl: '../../shared/document-page.css',
})
export class TermsPage {
  protected readonly language = inject(LanguageService);
}
