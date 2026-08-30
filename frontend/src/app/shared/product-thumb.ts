import { Component, computed, input } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';

/** Užší tvar, než jaký vrací codegen (`ProductSummaryFieldsFragment`) — jen pole, která
 *  komponenta/pomocná funkce skutečně potřebují, ať se dá použít i pro `Product` (detail). */
export interface ProductThumbSource {
  photos?: readonly { thumbnailUrl: string }[] | null;
  externalImage?: { thumbnailUrl: string; attribution: string } | null;
}

/**
 * Stejné pravidlo jako {@link usesExternalImageFallback} — jestli produkt vlastní fotku nemá,
 * ale komunitní zápis o něm existuje, ukáže se aspoň externí zdroj (Open Food Facts). Atribuci
 * zdroje do řádku seznamu nevejde, proto ji volající strana shrne pod celý seznam jednou
 * (`usesExternalImageFallback` napříč `results()`), stejně jako na detailu zboží
 * (product-detail-page.html), jen s hromadnou poznámkou místo textu u každého řádku.
 */
export function usesExternalImageFallback(source: ProductThumbSource): boolean {
  return !(source.photos && source.photos.length > 0) && !!source.externalImage;
}

/** Miniatura zboží pro řádek výsledků (hledání, zápis ceny) — priorita zdroje: vlastní fotka >
 *  obrázek z Open Food Facts > zástupná ikona. Mobilní protějšek: ui/common/ProductThumb.kt. */
@Component({
  selector: 'app-product-thumb',
  imports: [NzIconModule],
  template: `
    @if (thumbnailUrl(); as url) {
      <img [src]="url" [alt]="name()" [title]="externalAttribution() ?? name()" class="thumb" />
    } @else {
      <span class="thumb thumb-placeholder" aria-hidden="true">
        <span nz-icon nzType="picture"></span>
      </span>
    }
  `,
  styles: `
    .thumb {
      width: 36px;
      height: 36px;
      border-radius: 4px;
      object-fit: cover;
      flex-shrink: 0;
    }
    .thumb-placeholder {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      background: rgba(0, 0, 0, 0.04);
      color: rgba(0, 0, 0, 0.25);
      font-size: 16px;
    }
  `,
})
export class ProductThumb {
  readonly name = input<string>('');
  readonly photos = input<ProductThumbSource['photos']>(null);
  readonly externalImage = input<ProductThumbSource['externalImage']>(null);

  protected readonly thumbnailUrl = computed<string | null>(() => {
    const photos = this.photos();
    if (photos && photos.length > 0) return photos[0].thumbnailUrl;
    return this.externalImage()?.thumbnailUrl ?? null;
  });

  protected readonly externalAttribution = computed<string | null>(() => {
    const photos = this.photos();
    if (photos && photos.length > 0) return null;
    return this.externalImage()?.attribution ?? null;
  });
}
