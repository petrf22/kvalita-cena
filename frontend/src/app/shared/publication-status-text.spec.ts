import { describe, expect, it } from 'vitest';
import { PublicationState } from '../models/catalog';
import { publicationStatusText } from './publication-status-text';

describe('publicationStatusText', () => {
  it('returns confirmation counts for a product awaiting confirmations', () => {
    const status = {
      state: PublicationState.AwaitingConfirmations,
      confirmationsReceived: 1,
      confirmationsRequired: 3,
      verified: false,
    };
    expect(publicationStatusText(status, 'product')).toEqual({
      key: 'my-contributions.status.awaitingConfirmations.product',
      params: { received: 1, required: 3 },
    });
  });

  it('uses a different key for a store awaiting confirmations', () => {
    const status = {
      state: PublicationState.AwaitingConfirmations,
      confirmationsReceived: 0,
      confirmationsRequired: 3,
      verified: false,
    };
    expect(publicationStatusText(status, 'store').key).toBe(
      'my-contributions.status.awaitingConfirmations.store',
    );
  });

  it('defaults missing confirmation numbers to zero', () => {
    const status = {
      state: PublicationState.AwaitingConfirmations,
      confirmationsReceived: null,
      confirmationsRequired: null,
      verified: false,
    };
    expect(publicationStatusText(status, 'observation').params).toEqual({
      received: 0,
      required: 0,
    });
  });

  it('has no params for a hidden record', () => {
    const status = {
      state: PublicationState.HiddenAfterFlags,
      confirmationsReceived: null,
      confirmationsRequired: null,
      verified: false,
    };
    expect(publicationStatusText(status, 'product')).toEqual({
      key: 'my-contributions.status.hiddenAfterFlags.product',
    });
  });

  it('falls back to the public key for PUBLIC state', () => {
    const status = {
      state: PublicationState.Public,
      confirmationsReceived: null,
      confirmationsRequired: null,
      verified: true,
    };
    expect(publicationStatusText(status, 'store')).toEqual({
      key: 'my-contributions.status.public.store',
    });
  });

  it('edit status is always the pending-merge key for the "edit" kind', () => {
    const status = {
      state: PublicationState.PendingMerge,
      confirmationsReceived: null,
      confirmationsRequired: null,
      verified: false,
    };
    expect(publicationStatusText(status, 'edit')).toEqual({
      key: 'my-contributions.status.pendingMerge.edit',
    });
  });
});
