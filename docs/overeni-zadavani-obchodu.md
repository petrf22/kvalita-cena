# Ověření zadávání obchodu

Úprava webu i Androidu: výběr obchodu se zalamuje, má tlačítko pro vymazání a dál
předvyplňuje poslední obchod. Vymazání ruší i uloženou volbu; na webu vedle něj stojí
„Změnit obchod", které jen vrátí seznam (volbu ani paměť nemaže). Výpadek načtení na webu
už zapamatovaný obchod nemaže a opožděné načtení nepřepisuje ruční výběr.

Výběr bodu na mapě doplní dostupnou adresu přes existující serverové `reverseGeocode`.
Uživatel má zkontrolovat výsledek: reverzní geokódování vrací nejbližší známou adresu,
nikoli záruku, že se jedná o konkrétní prodejnu. Název a řetězec zůstávají ruční.
Hledání adresy posílá zemi formuláře, jediný výsledek rovnou vybere a otevře mapu.
Neúspěšné hledání zachová dosavadní bod. Atribuce OSM je viditelná i u reverzního hledání.

Používá se stávající serverová cache, omezení provozu a zaokrouhlování GPS; klient
Nominatim přímo nevolá. Nejde o průběžné OSM našeptávání, které veřejná služba zakazuje.
Pravidla: https://operations.osmfoundation.org/policies/nominatim/

## Ruční checklist (web, Android ve světlém a tmavém režimu)

- Vybrat obchod s dlouhým názvem a adresou: celý text je čitelný i na úzkém displeji.
- Otevřít zadání dalšího produktu/ceny: poslední obchod je předvyplněný.
- Vymazat obchod jedním stiskem: nejde uložit cenu pod předchozím obchodem; po návratu
  se vymazaná volba znovu nepředvyplní.
- Změnit obchod během načítání poslední volby: pozdní odpověď novou volbu nepřepíše.
- V novém obchodě vyplnit adresu včetně jiné země a hledat: jediný nález otevře mapu,
  více výsledků nabídne výběr. Vybraný bod se uloží spolu s formulářem.
- Kliknout na mapu nebo přetáhnout značku: dostupná ulice, město a PSČ se doplní.
- Vybrat kandidáta, pak přepsat město a hledat znovu: uloží se nově vybraný bod, nikdy
  souřadnice ani `osm_ref` kandidáta k původní adrese. Neúspěšné hledání bod zachová.
- Ručně přepnout zemi a pak posunout značku: volba země (a s ní měna zápisu) vydrží.
- Kliknout na místo, které nemá ulici: ulice z předchozího bodu zmizí, nezůstane smíchaná
  adresa. Místo se samotným PSČ se za nenalezenou adresu nepovažuje.
- „Najít v okolí" s víc nálezy: zbylé obchody jsou dosažitelné přes „Změnit obchod",
  které nemaže zapamatovanou volbu.
- Stisknout „Použít mou polohu" a během čekání na GPS „Najít souřadnice": platí hledání,
  opožděná poloha ho nepřepíše.
- Rychle zvolit dva body: platí poslední. Během dotazu ručně opravit ulici: oprava zůstane.
- Vypnout síť / vybrat místo bez známé adresy: zobrazí se zpráva, formulář lze doplnit
  ručně a dříve zvolená poloha zůstane zachovaná.
- Použít mou polohu: doplní se jen prázdná adresní pole, zobrazí se zdroj OSM.

Automatické regresní scénáře webu jsou v `store-form.spec.ts`; Android podle konvencí
projektu ověřuje ViewModely a obrazovky ručně. Samotná kompilace není vizuální kontrola.
