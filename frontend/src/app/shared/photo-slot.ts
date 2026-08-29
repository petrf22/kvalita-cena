import { Component, EventEmitter, OnDestroy, Output, inject, input, signal } from '@angular/core';
import { TranslocoDirective, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { AuthService } from '../services/auth-service';
import { MAX_PHOTOS_PER_RECORD, photoValidationError } from './photo-upload-validation';

/**
 * Jeden slot na fotku PŘED uložením záznamu — appka soubor jen podrží v paměti (File), nahrání
 * zajistí volající komponenta až po vzniku záznamu (docs/datovy-model.md, "fotky se nahrávají
 * výhradně na existující záznam"). Na rozdíl od `PhotoGallery` (`shared/photo-gallery.ts`),
 * která je vázaná na existující `recordId` a nahrává rovnou. Použití: založení nového zboží
 * (`features/product-form/product-form.ts`).
 *
 * Dva skryté file inputy místo jednoho — `capture="environment"` je atribut inputu, ne tlačítka,
 * takže "Vyfotit" (rovnou fotoaparát na mobilu) a "Vybrat soubor" (galerie/souborový dialog)
 * potřebují každý svůj. Na desktopu se oba chovají stejně jako obyčejný výběr souboru.
 */
@Component({
  selector: 'app-photo-slot',
  imports: [NzAlertModule, NzButtonModule, NzIconModule, TranslocoDirective],
  providers: [provideTranslocoScope('photos')],
  templateUrl: './photo-slot.html',
  styleUrl: './photo-slot.css',
})
export class PhotoSlot implements OnDestroy {
  protected readonly auth = inject(AuthService);
  private readonly transloco = inject(TranslocoService);

  /** Přeložený popisek slotu (např. "Fotka zboží") — dodává volající komponenta. */
  readonly label = input.required<string>();

  @Output() readonly fileChange = new EventEmitter<File | null>();

  protected readonly previewUrl = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  private objectUrl: string | null = null;

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = ''; // stejný soubor jde vybrat znovu (např. po chybě nebo po odebrání)
    if (!file) return;

    this.error.set(null);
    // Limit počtu fotek na záznam se tu netýká — slot drží nejvýš jednu fotku, appka volá
    // existujícím počtem 0, ať zbylé kódy (formát/velikost) zůstanou stejné jako v PhotoGallery.
    const errorCode = photoValidationError(file, 0);
    if (errorCode) {
      this.error.set(
        this.transloco.translate(`errors.${errorCode}`, { p0: MAX_PHOTOS_PER_RECORD }),
      );
      return;
    }
    this.setFile(file);
  }

  remove(): void {
    this.setFile(null);
  }

  ngOnDestroy(): void {
    this.revokeObjectUrl();
  }

  private setFile(file: File | null): void {
    this.revokeObjectUrl();
    if (file) {
      this.objectUrl = URL.createObjectURL(file);
      this.previewUrl.set(this.objectUrl);
    } else {
      this.previewUrl.set(null);
    }
    this.fileChange.emit(file);
  }

  private revokeObjectUrl(): void {
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
      this.objectUrl = null;
    }
  }
}
