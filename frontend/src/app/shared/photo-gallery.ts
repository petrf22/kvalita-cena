import { Component, EventEmitter, Output, effect, inject, input, signal } from '@angular/core';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { Photo, RecordType } from '../models/catalog';
import { AuthService } from '../services/auth-service';
import { MediaService } from '../services/media-service';
import { photoValidationError, remainingPhotoSlots } from './photo-upload-validation';

/**
 * Galerie fotek zboží/obchodu (core.media) — mřížka náhledů, po kliknutí zvětšení s
 * prev/next navigací (ručně, bez další knihovny — stejný duch jako ruční SVG graf
 * price-chart.ts). Mobilní protějšek: mobile ui/common/PhotoGallery.kt.
 */
@Component({
  selector: 'app-photo-gallery',
  imports: [NzButtonModule, NzIconModule, NzModalModule, NzAlertModule],
  templateUrl: './photo-gallery.html',
  styleUrl: './photo-gallery.css',
})
export class PhotoGallery {
  private readonly mediaService = inject(MediaService);
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
      const error = photoValidationError(file, this.photos().length);
      if (error) {
        this.uploadError.set(error);
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
      error: () => {
        this.uploading.set(false);
        this.uploadError.set('Nahrání fotky se nepovedlo, zkus to prosím znovu.');
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
      error: () => this.uploadError.set('Smazání fotky se nepovedlo, zkus to prosím znovu.'),
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
      error: () => this.uploadError.set('Nastavení hlavní fotky se nepovedlo, zkus to prosím znovu.'),
    });
  }

  flagPhoto(photo: Photo): void {
    this.flaggingId.set(photo.id);
    this.mediaService.flag(photo.id).subscribe({
      next: (result) => {
        this.flaggingId.set(null);
        this.flagMessage.set(
          result.hidden
            ? 'Díky za nahlášení — fotka je teď skrytá a čeká na přezkum.'
            : 'Díky za nahlášení, zaznamenali jsme ho.',
        );
      },
      error: () => {
        this.flaggingId.set(null);
        this.flagMessage.set('Nahlášení se nepovedlo, zkus to prosím znovu.');
      },
    });
  }
}
