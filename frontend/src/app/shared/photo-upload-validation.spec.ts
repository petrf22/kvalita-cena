import { describe, expect, it } from 'vitest';
import {
  MAX_PHOTOS_PER_RECORD,
  MAX_PHOTO_BYTES,
  photoValidationError,
  remainingPhotoSlots,
} from './photo-upload-validation';

function file(type: string, size: number): File {
  return new File([new Uint8Array(size)], 'test.jpg', { type });
}

describe('photoValidationError', () => {
  it('accepts a normal JPEG under the size limit', () => {
    expect(photoValidationError(file('image/jpeg', 1024), 0)).toBeNull();
  });

  it('accepts PNG too', () => {
    expect(photoValidationError(file('image/png', 1024), 0)).toBeNull();
  });

  it('rejects unsupported types', () => {
    expect(photoValidationError(file('image/gif', 1024), 0)).toBe('PHOTO_UNSUPPORTED_FORMAT');
  });

  it('rejects files over the size limit', () => {
    expect(photoValidationError(file('image/jpeg', MAX_PHOTO_BYTES + 1), 0)).toBe(
      'PHOTO_TOO_LARGE',
    );
  });

  it('rejects once the per-record limit is reached', () => {
    expect(photoValidationError(file('image/jpeg', 1024), MAX_PHOTOS_PER_RECORD)).toBe(
      'PHOTO_LIMIT_REACHED',
    );
  });
});

describe('remainingPhotoSlots', () => {
  it('counts down from the per-record limit', () => {
    expect(remainingPhotoSlots(0)).toBe(MAX_PHOTOS_PER_RECORD);
    expect(remainingPhotoSlots(MAX_PHOTOS_PER_RECORD - 1)).toBe(1);
  });

  it('never goes negative', () => {
    expect(remainingPhotoSlots(MAX_PHOTOS_PER_RECORD + 3)).toBe(0);
  });
});
