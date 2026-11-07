import { describe, expect, it } from 'vitest';
import type { OsmStoreCandidate } from '../models/catalog';
import { DataOrigin, GeoSource } from '../models/generated/enums';
import {
  isOsmPoint,
  osmCandidateAddress,
  osmCandidateFormValues,
  storeGeoFields,
} from './osm-store-candidate';

const candidate: OsmStoreCandidate = {
  name: ' Albert ',
  street: 'Nádražní 12',
  city: 'Vzorov',
  postalCode: '100 00',
  country: 'CZ',
  lat: 50.1,
  lon: 14.4,
  osmRef: 'node/42',
  brand: 'Albert',
};
const receiptAddress = { street: 'Masarykova 1', postalCode: '100 01', city: 'Vzorov-Město' };
const countries = ['CZ', 'SK', 'PL'];

describe('osmCandidateFormValues', () => {
  it('převezme název, adresu, zemi a bod z OSM a adresu z účtenky nemíchá', () => {
    expect(osmCandidateFormValues(candidate, receiptAddress, countries)).toEqual({
      name: 'Albert',
      street: 'Nádražní 12',
      city: 'Vzorov',
      postalCode: '100 00',
      country: 'CZ',
      lat: 50.1,
      lon: 14.4,
    });
  });

  it('chybějící ulici a PSČ z OSM nedoplní z účtenky, dokud OSM nese město', () => {
    const values = osmCandidateFormValues(
      { ...candidate, street: null, postalCode: null },
      receiptAddress,
      countries,
    );
    expect(values.street).toBe('');
    expect(values.postalCode).toBe('');
    expect(values.city).toBe('Vzorov');
  });

  it('bez města v OSM vezme celou adresu, kolem které se hledalo', () => {
    const values = osmCandidateFormValues(
      { ...candidate, city: null, street: 'Nádražní 12' },
      receiptAddress,
      countries,
    );
    expect(values).toMatchObject(receiptAddress);
    expect(values.name).toBe('Albert');
  });

  it('bez města v OSM i bez záložní adresy nechá pole prázdná', () => {
    const values = osmCandidateFormValues(
      { ...candidate, city: null, street: null, postalCode: null },
      null,
      countries,
    );
    expect(values).toMatchObject({ street: '', city: '', postalCode: '' });
  });

  it('zemi, kterou appka nezná nebo OSM nenese, nepřevezme', () => {
    expect(osmCandidateFormValues({ ...candidate, country: 'US' }, null, countries).country).toBe(
      null,
    );
    expect(osmCandidateFormValues({ ...candidate, country: null }, null, countries).country).toBe(
      null,
    );
    expect(osmCandidateFormValues({ ...candidate, country: 'sk' }, null, countries).country).toBe(
      'SK',
    );
  });
});

describe('storeGeoFields', () => {
  const osm = { lat: 50.1, lon: 14.4, osmRef: 'node/42' };

  it('převzatá provozovna s nepohnutým bodem: OSM_DERIVED, bod i odkaz z OSM', () => {
    expect(
      storeGeoFields({
        geocodeCandidate: null,
        osmCandidate: osm,
        manualLat: 50.1,
        manualLon: 14.4,
      }),
    ).toEqual({
      lat: 50.1,
      lon: 14.4,
      geoSource: GeoSource.Osm,
      osmRef: 'node/42',
      dataOrigin: DataOrigin.OsmDerived,
    });
  });

  it('posunutý bod je uživatelův, text a odkaz zůstávají z OSM', () => {
    expect(
      storeGeoFields({
        geocodeCandidate: null,
        osmCandidate: osm,
        manualLat: 50.2,
        manualLon: 14.4,
      }),
    ).toEqual({
      lat: 50.2,
      lon: 14.4,
      geoSource: GeoSource.Community,
      osmRef: 'node/42',
      dataOrigin: DataOrigin.OsmDerived,
    });
  });

  it('geokódovaný bod u převzaté provozovny: odkaz dál ukazuje na obchod, ne na bod', () => {
    const geocoded = { lat: 50.3, lon: 14.5, osmRef: 'way/7' };
    expect(
      storeGeoFields({
        geocodeCandidate: geocoded,
        osmCandidate: osm,
        manualLat: null,
        manualLon: null,
      }),
    ).toEqual({
      lat: 50.3,
      lon: 14.5,
      geoSource: GeoSource.Osm,
      osmRef: 'node/42',
      dataOrigin: DataOrigin.OsmDerived,
    });
  });

  it('samotné geokódování adresy není OSM_DERIVED', () => {
    expect(
      storeGeoFields({
        geocodeCandidate: { lat: 50.3, lon: 14.5, osmRef: 'way/7' },
        osmCandidate: null,
        manualLat: null,
        manualLon: null,
      }),
    ).toEqual({
      lat: 50.3,
      lon: 14.5,
      geoSource: GeoSource.Osm,
      osmRef: 'way/7',
      dataOrigin: null,
    });
  });

  it('ruční bod bez OSM je COMMUNITY, bez bodu nic', () => {
    expect(
      storeGeoFields({ geocodeCandidate: null, osmCandidate: null, manualLat: 49, manualLon: 15 }),
    ).toMatchObject({ geoSource: GeoSource.Community, osmRef: null, dataOrigin: null });
    expect(
      storeGeoFields({
        geocodeCandidate: null,
        osmCandidate: null,
        manualLat: null,
        manualLon: null,
      }),
    ).toEqual({ lat: null, lon: null, geoSource: null, osmRef: null, dataOrigin: null });
  });
});

describe('isOsmPoint a osmCandidateAddress', () => {
  it('pozná nepohnutý bod kandidáta', () => {
    expect(isOsmPoint(candidate, 50.1, 14.4)).toBe(true);
    expect(isOsmPoint(candidate, 50.1, 14.5)).toBe(false);
    expect(isOsmPoint(null, 50.1, 14.4)).toBe(false);
  });

  it('adresu skládá z ulice a města a prázdné části vynechá', () => {
    expect(osmCandidateAddress(candidate)).toBe('Nádražní 12 · Vzorov');
    expect(osmCandidateAddress({ ...candidate, street: null })).toBe('Vzorov');
    expect(osmCandidateAddress({ ...candidate, street: ' ', city: null })).toBe('');
  });
});
