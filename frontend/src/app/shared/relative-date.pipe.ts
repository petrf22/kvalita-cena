import { Pipe, PipeTransform, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { INTL_TAGS, LanguageService } from '../services/language-service';
import { formatRelativeDate } from './relative-date';

/** `pure: false` — čte aktuální jazyk ze signálu, stejný důvod jako MoneyPipe. */
@Pipe({ name: 'relativeDate', pure: false })
export class RelativeDatePipe implements PipeTransform {
  private readonly language = inject(LanguageService);
  private readonly transloco = inject(TranslocoService);

  transform(iso: string | null | undefined): string {
    const formatted = formatRelativeDate(iso, INTL_TAGS[this.language.lang()]);
    return formatted ?? this.transloco.translate('common.never');
  }
}
