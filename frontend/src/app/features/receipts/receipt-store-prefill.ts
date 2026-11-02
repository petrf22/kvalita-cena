import type { ReceiptDetail } from '../../models/catalog';
import type { StorePrefill } from '../../shared/store-form';

/** PSČ a město — stejný regex, kterým je z účtenky čte worker (tools/uctenky/profiles). */
const POSTAL_CITY = /^(\d{3}\s?\d{2})\s+(\D.*)$/;

export interface ReceiptAddress {
  street: string | null;
  postalCode: string | null;
  city: string | null;
}

/**
 * Rozloží `storeAddressRaw` zpátky na části. Server dostává od workeru jeden řetězec ve tvaru
 * `"[ulice, ]PSČ Město"`, případně jen `"ulice"`, když parser PSČ nenašel
 * (tools/uctenky/result.py) — PSČ a město tam chodí vždy spolu. Mobilní protějšek:
 * mobile ui/receipt/ReceiptStorePrefill.kt.
 */
export function splitReceiptAddress(raw: string | null | undefined): ReceiptAddress {
  const text = raw?.trim();
  if (!text) return { street: null, postalCode: null, city: null };
  const comma = text.lastIndexOf(', ');
  const head = comma >= 0 ? text.slice(0, comma).trim() : null;
  const tail = comma >= 0 ? text.slice(comma + 2).trim() : text;
  const match = POSTAL_CITY.exec(tail);
  if (!match) return { street: text, postalCode: null, city: null };
  return { street: head || null, postalCode: match[1], city: match[2].trim() };
}

/**
 * Údaje z hlavičky účtenky pro nový obchod, ať uživatel neopisuje, co už stroj přečetl.
 * `null`, když hlavička nenese nic, čím by šlo formulář předvyplnit.
 */
export function receiptStorePrefill(
  receipt: Pick<ReceiptDetail, 'storeNameRaw' | 'storeAddressRaw' | 'storeIcoRaw'>,
): StorePrefill | null {
  const prefill: StorePrefill = {
    name: receipt.storeNameRaw?.trim() || null,
    ...splitReceiptAddress(receipt.storeAddressRaw),
    ico: receipt.storeIcoRaw?.trim() || null,
  };
  return Object.values(prefill).some((part) => part != null) ? prefill : null;
}
