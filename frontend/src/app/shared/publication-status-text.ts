import { PublicationState, PublicationStatus } from '../models/catalog';

/**
 * Kdy se vlastní záznam propaguje globálně (docs/datovy-model.md, "Uživatelská vrstva nad
 * globálními daty"; prahy v docs/reputace.md) — čistá funkce oddělená od `publication-status.ts`
 * (komponenta), ať se dá otestovat Vitestem bez renderu. Vrací i18n KLÍČ + parametry, ne
 * hotový text — překlad dělá volající komponenta přes `TranslocoService.translate`.
 *
 * "product"/"store" mají u AWAITING_CONFIRMATIONS jiný text (zboží s kódem najde kdokoli
 * skenem, PENDING obchod nenajde nikdo) — viz docs/reputace.md, "Práh důvěry pro
 * zveřejnění nového záznamu". Bezkódové zboží (`generic`) kód nemá, ostatní ho najdou jen
 * v nabídce obchodu při zápisu ceny (`productSuggestions`), proto vlastní text. MERGED
 * (sloučeno do jiného záznamu, typicky zboží z účtenky po dopárování) odkazuje na cíl. "observation" nese vlastní text pro PUBLIC/AWAITING, protože
 * cena sama žádný práh nemá, jen dědí stav od blokujícího zboží/obchodu. "edit" (úprava
 * cizího záznamu) je ve výpisu vždy PENDING_MERGE — ostatní stavy tam nikdy nepřijdou.
 */
export type PublicationRecordKind = 'product' | 'store' | 'observation' | 'edit';

export interface PublicationStatusText {
  key: string;
  params?: { received: number; required: number };
}

export function publicationStatusText(
  status: Pick<PublicationStatus, 'state' | 'confirmationsReceived' | 'confirmationsRequired'>,
  kind: PublicationRecordKind,
  options: { generic?: boolean } = {},
): PublicationStatusText {
  switch (status.state) {
    case PublicationState.AwaitingConfirmations:
      return {
        key: `my-contributions.status.awaitingConfirmations.${kind === 'product' && options.generic ? 'genericProduct' : kind}`,
        params: {
          received: status.confirmationsReceived ?? 0,
          required: status.confirmationsRequired ?? 0,
        },
      };
    case PublicationState.HiddenAfterFlags:
      return { key: `my-contributions.status.hiddenAfterFlags.${kind}` };
    case PublicationState.PendingMerge:
      return { key: `my-contributions.status.pendingMerge.${kind}` };
    case PublicationState.Merged:
      return { key: `my-contributions.status.merged.${kind}` };
    default:
      return { key: `my-contributions.status.public.${kind}` };
  }
}
