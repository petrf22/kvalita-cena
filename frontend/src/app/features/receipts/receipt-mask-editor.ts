import { Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { MASK_FILL } from './receipt-image';
import {
  Corner,
  MaskRect,
  ReceiptPart,
  defaultBand,
  isUsableMask,
  moveRect,
  rectFromPoints,
  rectToPercent,
  resizeRect,
} from './receipt-mask';

type Drag =
  | { kind: 'draw'; pointerId: number; startX: number; startY: number }
  | {
      kind: 'move';
      pointerId: number;
      startX: number;
      startY: number;
      index: number;
      original: MaskRect;
    }
  | {
      kind: 'resize';
      pointerId: number;
      startX: number;
      startY: number;
      index: number;
      original: MaskRect;
      corner: Corner;
    };

/** Posun šipkou v procentech rozměru obrázku (Shift = jemně po pixelu). */
const KEYBOARD_STEP = 0.01;

/**
 * Editor zakrytí jedné části účtenky: náhled obrázku a nad ním černé obdélníky přesně v té
 * barvě, která se vypálí do pixelů (`renderMaskedJpeg`). Tažením po volné ploše vznikne nový
 * obdélník, vybraný jde posunout, zvětšit za rohy, smazat křížkem nebo klávesou Delete.
 *
 * Na dotykovém displeji by kreslení po celé ploše obrázku znemožnilo posouvat stránku (dlouhá
 * účtenka je vyšší než displej) — kreslí se proto až po zapnutí režimu kreslení. Posun a změna
 * velikosti existujícího obdélníku fungují vždy.
 */
@Component({
  selector: 'app-receipt-mask-editor',
  imports: [TranslocoDirective, NzButtonModule, NzIconModule],
  templateUrl: './receipt-mask-editor.html',
  styleUrl: './receipt-mask-editor.css',
})
export class ReceiptMaskEditor {
  readonly part = input.required<ReceiptPart>();
  readonly masksChange = output<MaskRect[]>();

  protected readonly maskFill = MASK_FILL;
  protected readonly corners: readonly Corner[] = ['nw', 'ne', 'sw', 'se'];
  protected readonly selectedIndex = signal<number | null>(null);
  protected readonly draft = signal<MaskRect | null>(null);
  protected readonly drawMode = signal(false);
  /** Dotykové zařízení — jen tam má smysl přepínač režimu kreslení. */
  protected readonly coarsePointer =
    typeof matchMedia !== 'undefined' && matchMedia('(pointer: coarse)').matches;
  protected readonly masks = computed(() => this.part().masks);

  private readonly layer = viewChild.required<ElementRef<HTMLElement>>('layer');
  private drag: Drag | null = null;

  protected percent(mask: MaskRect) {
    const part = this.part();
    return rectToPercent(mask, part.width, part.height);
  }

  /** Bod ukazatele v pixelech obrázku — náhled je zmenšený podle šířky stránky. */
  private toImagePoint(event: PointerEvent): { x: number; y: number } {
    const box = this.layer().nativeElement.getBoundingClientRect();
    const part = this.part();
    return {
      x: ((event.clientX - box.left) / box.width) * part.width,
      y: ((event.clientY - box.top) / box.height) * part.height,
    };
  }

  private capture(event: PointerEvent): void {
    this.layer().nativeElement.setPointerCapture?.(event.pointerId);
  }

  protected onLayerPointerDown(event: PointerEvent): void {
    // Dotyk bez zapnutého kreslení patří posouvání stránky, ne novému obdélníku.
    if (event.pointerType === 'touch' && !this.drawMode()) return;
    if (event.button !== 0) return;
    event.preventDefault();
    const point = this.toImagePoint(event);
    this.selectedIndex.set(null);
    this.drag = { kind: 'draw', pointerId: event.pointerId, startX: point.x, startY: point.y };
    this.capture(event);
  }

  protected onMaskPointerDown(event: PointerEvent, index: number): void {
    event.stopPropagation();
    if (event.button !== 0) return;
    event.preventDefault();
    const point = this.toImagePoint(event);
    this.selectedIndex.set(index);
    this.drag = {
      kind: 'move',
      pointerId: event.pointerId,
      startX: point.x,
      startY: point.y,
      index,
      original: this.masks()[index],
    };
    this.capture(event);
  }

  protected onHandlePointerDown(event: PointerEvent, index: number, corner: Corner): void {
    event.stopPropagation();
    event.preventDefault();
    const point = this.toImagePoint(event);
    this.drag = {
      kind: 'resize',
      pointerId: event.pointerId,
      startX: point.x,
      startY: point.y,
      index,
      original: this.masks()[index],
      corner,
    };
    this.capture(event);
  }

  protected onPointerMove(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    const part = this.part();
    const point = this.toImagePoint(event);
    if (drag.kind === 'draw') {
      this.draft.set(
        rectFromPoints(drag.startX, drag.startY, point.x, point.y, part.width, part.height),
      );
      return;
    }
    const dx = point.x - drag.startX;
    const dy = point.y - drag.startY;
    const next =
      drag.kind === 'move'
        ? moveRect(drag.original, dx, dy, part.width, part.height)
        : resizeRect(drag.original, drag.corner, dx, dy, part.width, part.height);
    this.replaceMask(drag.index, next);
  }

  protected onPointerUp(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    this.drag = null;
    if (drag.kind !== 'draw') return;
    const rect = this.draft();
    this.draft.set(null);
    if (rect && isUsableMask(rect)) {
      const index = this.masks().length;
      this.masksChange.emit([...this.masks(), rect]);
      this.selectedIndex.set(index);
    }
  }

  protected onPointerCancel(): void {
    this.drag = null;
    this.draft.set(null);
  }

  protected onMaskKeydown(event: KeyboardEvent, index: number): void {
    const part = this.part();
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault();
      this.removeMask(index);
      return;
    }
    const stepX = event.shiftKey ? 1 : Math.max(1, part.width * KEYBOARD_STEP);
    const stepY = event.shiftKey ? 1 : Math.max(1, part.height * KEYBOARD_STEP);
    const delta: Record<string, [number, number]> = {
      ArrowLeft: [-stepX, 0],
      ArrowRight: [stepX, 0],
      ArrowUp: [0, -stepY],
      ArrowDown: [0, stepY],
    };
    const move = delta[event.key];
    if (!move) return;
    event.preventDefault();
    this.replaceMask(
      index,
      moveRect(this.masks()[index], move[0], move[1], part.width, part.height),
    );
  }

  protected removeMask(index: number): void {
    this.masksChange.emit(this.masks().filter((_, i) => i !== index));
    this.selectedIndex.set(null);
  }

  protected addBand(): void {
    const part = this.part();
    const band = { ...defaultBand(part.width, part.height), auto: false };
    const index = this.masks().length;
    this.masksChange.emit([...this.masks(), band]);
    this.selectedIndex.set(index);
  }

  protected clearMasks(): void {
    this.masksChange.emit([]);
    this.selectedIndex.set(null);
  }

  /** Upravená maska už není výchozí návrh — `auto` se zahodí, pás se pak nestěhuje. */
  private replaceMask(index: number, rect: MaskRect): void {
    this.masksChange.emit(this.masks().map((mask, i) => (i === index ? rect : mask)));
  }
}
