import { Component, EventEmitter, Output, effect, inject, input, signal } from '@angular/core';
import {
  TranslocoDirective,
  TranslocoPipe,
  TranslocoService,
  provideTranslocoScope,
} from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { Photo, RecordType } from '../models/catalog';
import { AuthService } from '../services/auth-service';
import { MediaService } from '../services/media-service';
import { translateError } from './error-message';
import {
  MAX_PHOTOS_PER_RECORD,
  photoValidationError,
  remainingPhotoSlots,
} from './photo-upload-validation';

/**
 * Galerie fotek zboží/obchodu (core.media) — mřížka náhledů, po kliknutí zvětšení s
 * prev/next navigací (ručně, bez další knihovny — stejný duch jako ruční SVG graf
 * price-chart.ts). Vlastní scope 'photos' přímo na komponentě (ne na stránce, která ji
 * vkládá) — galerie se používá jak z detailu zboží, tak z detailu obchodu (docs/lokalizace.md).
 * Mobilní protějšek: mobile ui/common/PhotoGallery.kt.
 */
@Component({
  selector: 'app-photo-gallery',
  imports: [
    NzButtonModule,
    NzIconModule,
    NzModalModule,
    NzAlertModule,
    TranslocoDirective,
    TranslocoPipe,
  ],
  providers: [provideTranslocoScope('photos')],
  templateUrl: './photo-gallery.html',
  styleUrl: './photo-gallery.css',
})
export class PhotoGallery {
  private readonly mediaService = inject(MediaService);
  private readonly transloco = inject(TranslocoService);
  protected readonly auth = inject(AuthService);

  readonly recordType = input.required<RecordType>();
  readonly recordId = input.required<string>();
  readonly photosInput = input<Photo[]>([], { alias: 'photos' });
  @Output() readonly photosChange = new EventEmitter<Photo[]>();

  protected readonly photos = signal<Photo[]>([]);
  protected readonly remainingSlots = signal(0);

  protected readonly uploading = signal(false);
  protected readonly uploadError = signal<string | null>(null);

  protected readonly viewerIndex = signal<number | null>(null);
  protected readonly flaggingId = signal<string | null>(null);
  protected readonly flagMessage = signal<string | null>(null);

  constructor() {
    // Resynchronizace jen když se reference vstupu skutečně změní (typicky po znovunačtení
    // záznamu) — jinak by přepisovala optimistické lokální úpravy (upload/delete) mezitím.
    effect(() => {
      const incoming = this.photosInput();
      this.photos.set(incoming);
      this.remainingSlots.set(remainingPhotoSlots(incoming.length));
    });
  }

  protected currentPhoto(): Photo | null {
    const index = this.viewerIndex();
    return index == null ? null : (this.photos()[index] ?? null);
  }

  openViewer(index: number): void {
    this.viewerIndex.set(index);
    this.flagMessage.set(null);
  }

  closeViewer(): void {
    this.viewerIndex.set(null);
  }

  prev(): void {
    const index = this.viewerIndex();
    if (index != null && index > 0) this.viewerIndex.set(index - 1);
  }

  next(): void {
    const index = this.viewerIndex();
    if (index != null && index < this.photos().length - 1) this.viewerIndex.set(index + 1);
  }

  onFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    input.value = ''; // stejný soubor jde vybrat znovu (např. po chybě)
    if (files.length === 0) return;

    this.uploadError.set(null);
    for (const file of files) {
      const errorCode = photoValidationError(file, this.photos().length);
      if (errorCode) {
        // Stejné klíče jako ErrorCode na backendu (kořenový bundle errors.*) — appka tu jen
        // ušetří zbytečný upload, text hlášky je ale ten samý jako po chybě ze serveru.
        this.uploadError.set(
          this.transloco.translate(`errors.${errorCode}`, { p0: MAX_PHOTOS_PER_RECORD }),
        );
        continue;
      }
      this.uploadOne(file);
    }
  }

  private uploadOne(file: File): void {
    this.uploading.set(true);
    this.mediaService.upload(this.recordType(), this.recordId(), file).subscribe({
      next: (photo) => {
        this.uploading.set(false);
        const updated = [...this.photos(), photo];
        this.photos.set(updated);
        this.remainingSlots.set(remainingPhotoSlots(updated.length));
        this.photosChange.emit(updated);
      },
      // Upload jde přes REST multipart, ne GraphQL (viz MediaService) — translateError tu
      // proto nejde použít, chyba nenese extensions.code.
      error: () => {
        this.uploading.set(false);
        this.uploadError.set(this.transloco.translate('photos.uploadFailed'));
      },
    });
  }

  deletePhoto(photo: Photo): void {
    this.mediaService.remove(photo.id).subscribe({
      next: () => {
        const updated = this.photos().filter((p) => p.id !== photo.id);
        this.photos.set(updated);
        this.remainingSlots.set(remainingPhotoSlots(updated.length));
        this.photosChange.emit(updated);
        this.closeViewer();
      },
      error: (err) => this.uploadError.set(translateError(err, this.transloco)),
    });
  }

  /**
   * Zjednodušené "nastav jako hlavní" — prohodí sortOrder s dosavadní první fotkou. Přesné
   * pořadí ostatních fotek appka dál nesleduje, jen kontrakt "první v poli = hlavní fotka".
   */
  setAsMain(photo: Photo): void {
    const list = this.photos();
    const currentFirst = list[0];
    if (!currentFirst || currentFirst.id === photo.id) return;

    this.mediaService.update(photo.id, null, 0).subscribe({
      next: (updatedPhoto) => {
        this.mediaService.update(currentFirst.id, null, 1).subscribe({
          next: (updatedFirst) => {
            const rest = list.filter((p) => p.id !== photo.id && p.id !== currentFirst.id);
            const updated = [updatedPhoto, updatedFirst, ...rest];
            this.photos.set(updated);
            this.photosChange.emit(updated);
            this.viewerIndex.set(0);
          },
        });
      },
      error: (err) => this.uploadError.set(translateError(err, this.transloco)),
    });
  }

  flagPhoto(photo: Photo): void {
    this.flaggingId.set(photo.id);
    this.mediaService.flag(photo.id).subscribe({
      next: (result) => {
        this.flaggingId.set(null);
        this.flagMessage.set(
          this.transloco.translate(result.hidden ? 'photos.flagHidden' : 'photos.flagAcknowledged'),
        );
      },
      error: (err) => {
        this.flaggingId.set(null);
        this.flagMessage.set(translateError(err, this.transloco));
      },
    });
  }
}
