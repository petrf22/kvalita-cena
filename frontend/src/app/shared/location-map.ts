import {
  Component,
  ElementRef,
  EventEmitter,
  Output,
  effect,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import type * as LeafletNs from 'leaflet';
import { MAP_TILE_ATTRIBUTION, MAP_TILE_MAX_ZOOM, MAP_TILE_URL } from './map-tiles';

const DEFAULT_CENTER: [number, number] = [49.8, 15.5]; // střed ČR — bez zadaného bodu
const DEFAULT_ZOOM = 7;
const POINT_ZOOM = 16;

/**
 * Mapa nad OpenStreetMap (Leaflet) — dva režimy: náhled (souřadnice se jen zobrazí) a výběr
 * bodu (klik do mapy nebo přetažení značky mění souřadnice). `import type` níž je jen typ pro
 * TypeScript, žádný kód se nebalí — skutečný modul se natáhne až uvnitř {@link show}, tedy
 * teprve po kliknutí na "Zobrazit mapu".
 *
 * Mapové dlaždice (tile.openstreetmap.org) se na rozdíl od geocodeAddress/reverseGeocode
 * stahují PŘÍMO Z PROHLÍŽEČE — vědomá výjimka z pravidla "jen ze serveru" (docs/soukromi.md):
 * appka to zmírňuje tím, že se dlaždice nenačtou automaticky, jen na výslovné přání
 * uživatele. Mobilní protějšek: mobile ui/common/LocationMap.kt (osmdroid).
 */
@Component({
  selector: 'app-location-map',
  imports: [NzButtonModule, NzIconModule, TranslocoPipe],
  templateUrl: './location-map.html',
  styleUrl: './location-map.css',
})
export class LocationMap {
  readonly lat = input<number | null>(null);
  readonly lon = input<number | null>(null);
  readonly editable = input(false);
  @Output() readonly pointChange = new EventEmitter<{ lat: number; lon: number }>();

  protected readonly mapContainer = viewChild<ElementRef<HTMLDivElement>>('mapContainer');
  protected readonly loaded = signal(false);
  protected readonly loading = signal(false);

  private map: LeafletNs.Map | null = null;
  private marker: LeafletNs.Marker | null = null;
  private leaflet: typeof LeafletNs | null = null;

  constructor() {
    // Mapu inicializuje, jakmile se v šabloně objeví kontejner (po show()) — signálová query
    // se sama přepočítá při změně DOM, není potřeba ručně čekat na vykreslení.
    effect(() => {
      const container = this.mapContainer();
      if (container && this.leaflet && !this.map) {
        this.initMap(container.nativeElement);
      }
    });

    // Souřadnice se po zobrazení mapy dál mohou měnit zvenčí (reverseGeocode, "Použít mou
    // polohu") — přesuň značku, samotnou mapu tím neinicializujeme znovu.
    effect(() => {
      const lat = this.lat();
      const lon = this.lon();
      if (this.map && this.leaflet && lat != null && lon != null) {
        this.setMarker(lat, lon, false);
      }
    });
  }

  async show(): Promise<void> {
    if (this.loaded()) return;
    this.loading.set(true);
    this.leaflet = await import('leaflet');
    this.loading.set(false);
    this.loaded.set(true);
  }

  private initMap(container: HTMLDivElement): void {
    const leaflet = this.leaflet;
    if (!leaflet || this.map) return;

    const lat = this.lat();
    const lon = this.lon();
    const hasPoint = lat != null && lon != null;
    const center: [number, number] = hasPoint ? [lat, lon] : DEFAULT_CENTER;

    this.map = leaflet.map(container).setView(center, hasPoint ? POINT_ZOOM : DEFAULT_ZOOM);
    leaflet
      .tileLayer(MAP_TILE_URL, {
        attribution: MAP_TILE_ATTRIBUTION,
        maxZoom: MAP_TILE_MAX_ZOOM,
      })
      .addTo(this.map);

    if (hasPoint) {
      this.setMarker(lat, lon, false);
    }

    if (this.editable()) {
      this.map.on('click', (event: LeafletNs.LeafletMouseEvent) => {
        this.setMarker(event.latlng.lat, event.latlng.lng, true);
        this.pointChange.emit({ lat: event.latlng.lat, lon: event.latlng.lng });
      });
    }
  }

  private setMarker(lat: number, lon: number, fromInteraction: boolean): void {
    const leaflet = this.leaflet;
    const map = this.map;
    if (!leaflet || !map) return;

    if (!this.marker) {
      const icon = leaflet.divIcon({ className: 'location-map-marker', iconSize: [18, 18] });
      this.marker = leaflet.marker([lat, lon], { icon, draggable: this.editable() }).addTo(map);
      if (this.editable()) {
        this.marker.on('dragend', () => {
          const position = this.marker!.getLatLng();
          this.pointChange.emit({ lat: position.lat, lon: position.lng });
        });
      }
    } else {
      this.marker.setLatLng([lat, lon]);
    }

    if (!fromInteraction) {
      map.setView([lat, lon], Math.max(map.getZoom(), POINT_ZOOM));
    }
  }
}
