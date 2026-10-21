import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { TranslocoDirective, TranslocoPipe } from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import type { ReaderOptions } from 'zxing-wasm/reader';

/**
 * WASM čtečky se servíruje z vlastního webu (angular.json, asset `zxing/`), ne z CDN, kam by
 * ho knihovna jinak sama šla stáhnout — appka nemá kvůli skenu prozrazovat návštěvu třetí
 * straně (docs/soukromi.md).
 */
const WASM_BASE = '/zxing/';

/** Jen kódy, které appka jako čárový kód zboží umí (EAN/UPC → GTIN-14, docs/datovy-model.md). */
const READER_OPTIONS: ReaderOptions = {
  formats: ['EAN13', 'EAN8', 'UPCA', 'UPCE'],
  tryHarder: true,
  maxNumberOfSymbols: 1,
};

/** Jak často se zkouší přečíst snímek z kamery — kompromis mezi odezvou a zátěží telefonu. */
const SCAN_INTERVAL_MS = 250;
/** Šířka snímku pro čtečku; větší rozlišení EAN nepotřebuje a jen zdržuje. */
const SCAN_WIDTH = 1280;

type ScannerState = 'starting' | 'scanning' | 'denied' | 'noCamera' | 'decoding' | 'notFound';

type ReaderModule = typeof import('zxing-wasm/reader');
let readerModule: Promise<ReaderModule> | null = null;

/** Knihovna se načítá až při prvním skenu — do úvodního bundlu appky nepatří (~1 MB WASM). */
function loadReader(): Promise<ReaderModule> {
  readerModule ??= import('zxing-wasm/reader').then((module) => {
    module.prepareZXingModule({
      overrides: {
        locateFile: (path: string, prefix: string) =>
          path.endsWith('.wasm') ? `${WASM_BASE}${path}` : prefix + path,
      },
    });
    return module;
  });
  return readerModule;
}

/**
 * Skener čárového kódu kamerou (zxing-wasm, MIT) s náhradní cestou přes fotku — kamera
 * v prohlížeči chce HTTPS a povolení, a na počítači často žádná není. Po přečtení vyšle kód
 * a kameru hned vypne. Mobilní protějšek je nativní skener (ZXing, CLAUDE.md).
 */
@Component({
  selector: 'app-barcode-scanner',
  imports: [
    TranslocoDirective,
    TranslocoPipe,
    NzAlertModule,
    NzButtonModule,
    NzIconModule,
    NzSpinModule,
  ],
  template: `
    <ng-container *transloco="let t; scope: 'product-form'">
      @if (state() === 'starting' || state() === 'scanning') {
        <div class="viewport">
          <video #video class="video" playsinline muted></video>
          @if (state() === 'starting') {
            <div class="overlay"><nz-spin nzSimple /></div>
          } @else {
            <div class="aim" aria-hidden="true"></div>
          }
        </div>
        <p class="hint">{{ t('product-form.scan.cameraHint') }}</p>
      } @else if (state() === 'decoding') {
        <div class="overlay-static"><nz-spin nzSimple /></div>
      } @else {
        <nz-alert
          [nzType]="state() === 'notFound' ? 'warning' : 'info'"
          nzShowIcon
          [nzMessage]="t('product-form.scan.' + state())"
        />
      }

      <input
        #fileInput
        type="file"
        accept="image/*"
        capture="environment"
        class="file-input"
        (change)="onFile($event)"
      />
      <div class="actions">
        <button nz-button type="button" (click)="fileInput.click()">
          <span nz-icon nzType="picture"></span>
          {{ t('product-form.scan.fromPhoto') }}
        </button>
        <button nz-button nzType="text" type="button" (click)="cancel()">
          {{ 'common.cancel' | transloco }}
        </button>
      </div>
    </ng-container>
  `,
  styles: `
    .viewport {
      position: relative;
      overflow: hidden;
    }
    .video {
      display: block;
      width: 100%;
      height: auto;
    }
    .overlay,
    .overlay-static {
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--kc-space-6);
    }
    .overlay {
      position: absolute;
      inset: 0;
    }
    /* Vodítko, kam kód namířit — široký nízký pruh odpovídá tvaru EAN. */
    .aim {
      position: absolute;
      left: 10%;
      right: 10%;
      top: 35%;
      bottom: 35%;
      border: 2px solid var(--kc-brand);
      border-radius: var(--kc-space-1);
    }
    .hint {
      margin: var(--kc-space-2) 0 0;
      font-size: var(--kc-font-xs);
      color: var(--kc-text-muted);
    }
    .file-input {
      display: none;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--kc-space-2);
      margin-top: var(--kc-space-3);
    }
    .actions button {
      min-height: 44px;
    }
  `,
})
export class BarcodeScanner {
  readonly detected = output<string>();
  readonly cancelled = output<void>();

  protected readonly state = signal<ScannerState>('starting');
  private readonly video = viewChild<ElementRef<HTMLVideoElement>>('video');
  private stream: MediaStream | null = null;
  private timer?: ReturnType<typeof setTimeout>;
  private canvas: HTMLCanvasElement | null = null;
  private stopped = false;

  constructor() {
    afterNextRender(() => void this.startCamera());
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  private async startCamera(): Promise<void> {
    if (!navigator.mediaDevices?.getUserMedia) {
      this.state.set('noCamera');
      return;
    }
    try {
      const [stream] = await Promise.all([
        navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' } },
          audio: false,
        }),
        loadReader(),
      ]);
      if (this.stopped) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      this.stream = stream;
      const video = this.video()?.nativeElement;
      if (!video) return;
      video.srcObject = stream;
      await video.play();
      this.state.set('scanning');
      this.scheduleScan();
    } catch (err) {
      this.state.set(
        err instanceof DOMException && err.name === 'NotAllowedError' ? 'denied' : 'noCamera',
      );
    }
  }

  private scheduleScan(): void {
    if (this.stopped) return;
    this.timer = setTimeout(() => void this.scanFrame(), SCAN_INTERVAL_MS);
  }

  private async scanFrame(): Promise<void> {
    const video = this.video()?.nativeElement;
    if (!video || this.stopped || video.videoWidth === 0) {
      this.scheduleScan();
      return;
    }
    const scale = Math.min(1, SCAN_WIDTH / video.videoWidth);
    const width = Math.round(video.videoWidth * scale);
    const height = Math.round(video.videoHeight * scale);
    this.canvas ??= document.createElement('canvas');
    this.canvas.width = width;
    this.canvas.height = height;
    const context = this.canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return;
    context.drawImage(video, 0, 0, width, height);
    const reader = await loadReader();
    const results = await reader.readBarcodes(
      context.getImageData(0, 0, width, height),
      READER_OPTIONS,
    );
    const code = results.find((result) => result.isValid && result.text)?.text;
    if (code) {
      this.finish(code);
      return;
    }
    this.scheduleScan();
  }

  /** Záloha bez kamery: vyfocená nebo uložená fotka kódu. */
  protected async onFile(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.stop();
    this.state.set('decoding');
    try {
      const reader = await loadReader();
      const results = await reader.readBarcodes(file, READER_OPTIONS);
      const code = results.find((result) => result.isValid && result.text)?.text;
      if (code) {
        this.detected.emit(code);
        return;
      }
    } catch {
      // Nečitelný soubor i chybějící kód dopadnou pro uživatele stejně.
    }
    this.state.set('notFound');
  }

  protected cancel(): void {
    this.stop();
    this.cancelled.emit();
  }

  private finish(code: string): void {
    this.stop();
    this.detected.emit(code);
  }

  private stop(): void {
    this.stopped = true;
    clearTimeout(this.timer);
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
  }
}
