import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { of, Subject } from 'rxjs';
import { vi } from 'vitest';
import { StoreForm } from './store-form';
import { StoreService } from '../services/store-service';
import { CountryService } from '../services/country-service';

describe('StoreForm – propojení adresy a mapy', () => {
  const address = (city: string) => ({
    street: 'Hlavní 12',
    city,
    postalCode: '12345',
    country: 'CZ',
    attribution: 'OSM',
    osmRef: 'way/1',
  });
  let service: {
    reverseGeocode: ReturnType<typeof vi.fn>;
    geocode: ReturnType<typeof vi.fn>;
    search: ReturnType<typeof vi.fn>;
  };
  let form: StoreForm;

  beforeEach(() => {
    service = { reverseGeocode: vi.fn(), geocode: vi.fn(), search: vi.fn(() => of({ items: [] })) };
    TestBed.configureTestingModule({
      providers: [
        { provide: StoreService, useValue: service },
        { provide: CountryService, useValue: { country: signal('CZ'), countries: signal([]) } },
        { provide: TranslocoService, useValue: { translate: (key: string) => key } },
      ],
    });
    form = TestBed.runInInjectionContext(() => new StoreForm());
  });

  it('po změně bodu doplní adresu a ignoruje opožděnou odpověď původního bodu', () => {
    const first = new Subject<ReturnType<typeof address>>();
    const second = new Subject<ReturnType<typeof address>>();
    service.reverseGeocode.mockReturnValueOnce(first).mockReturnValueOnce(second);
    form.onMapPointSelected({ lat: 49, lon: 15 });
    form.onMapPointSelected({ lat: 50, lon: 16 });
    second.next(address('Nové město'));
    first.next(address('Staré město'));
    expect(form['city']()).toBe('Nové město');
    expect(form['currentLat']()).toBe(50);
    expect(form['geocodeAttribution']()).toBe('OSM');
  });

  it('nepřepíše ruční úpravu provedenou během dohledávání', () => {
    const response = new Subject<ReturnType<typeof address>>();
    service.reverseGeocode.mockReturnValue(response);
    form.onMapPointSelected({ lat: 49, lon: 15 });
    form['street'].set('Moje oprava 5');
    response.next(address('Brno'));
    expect(form['street']()).toBe('Moje oprava 5');
    expect(form['city']()).toBe('Brno');
  });

  it('při neúspěšném hledání zachová vybranou polohu a pošle zemi formuláře', () => {
    service.reverseGeocode.mockReturnValue(of(address('Brno')));
    form.onMapPointSelected({ lat: 49, lon: 15 });
    form['country'].set('SK');
    service.geocode.mockReturnValue(of({ candidates: [], attribution: 'OSM' }));
    form.geocode();
    expect(service.geocode).toHaveBeenCalledWith('Hlavní 12', 'Brno', '12345', 'SK');
    expect(form['currentLat']()).toBe(49);
    expect(form['locationMessage']()).toBeTruthy();
  });

  it('jediný výsledek vybere rovnou pro mapu', () => {
    const candidate = { lat: 49, lon: 15, displayName: 'Brno', osmRef: 'way/1' };
    service.geocode.mockReturnValue(of({ candidates: [candidate], attribution: 'OSM' }));
    service.reverseGeocode.mockReturnValue(of(address('Brno')));
    form['city'].set('Brno');
    form.geocode();
    expect(form['selectedCandidateRef']()).toEqual(candidate);
    expect(form['street']()).toBe('Hlavní 12');
  });
});
