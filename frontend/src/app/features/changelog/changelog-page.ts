import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective, provideTranslocoScope } from '@jsverse/transloco';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { CHANGELOG } from '../../changelog.generated';
import { FormatService } from '../../services/format-service';
import { LanguageService } from '../../services/language-service';

/**
 * Seznam změn appky — data z `changelog.generated.ts` (generováno `tools/version/sync.mjs`
 * z kořenového `CHANGELOG.md`, needituj ručně). Text položek je záměrně jen česky, stejně
 * jako Podmínky/Zásady (vzor `features/terms/terms-page.ts`) — `t('changelog.czechOnlyNotice')`
 * to čtenáři v jiném jazyce appky řekne rovnou. Mobilní protějšek:
 * `mobile/.../ui/about/ChangelogScreen.kt`, čte stejná data z `assets/changelog.json`.
 */
@Component({
  selector: 'app-changelog-page',
  imports: [RouterLink, NzCardModule, NzTagModule, TranslocoDirective],
  providers: [provideTranslocoScope('changelog')],
  templateUrl: './changelog-page.html',
  styleUrl: '../../shared/document-page.css',
})
export class ChangelogPage {
  protected readonly language = inject(LanguageService);
  protected readonly format = inject(FormatService);
  protected readonly releases = CHANGELOG;
}
