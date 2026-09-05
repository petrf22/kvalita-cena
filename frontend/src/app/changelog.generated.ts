// Generováno tools/version/sync.mjs z kořenového CHANGELOG.md — needituj ručně.
// Zobrazuje features/changelog/changelog-page.ts, text je záměrně jen česky (CLAUDE.md,
// docs/lokalizace.md — legal.czechOnlyNotice vzor), UI okolo je přeložené.

export interface ChangelogItem {
  text: string;
  parts: string[];
}

export interface ChangelogSection {
  title: string;
  items: ChangelogItem[];
}

export interface ChangelogRelease {
  version: string;
  date: string;
  sections: ChangelogSection[];
}

// Nejnovější vydání první.
export const CHANGELOG: ChangelogRelease[] = [
  { version: "0.7.0", date: "2026-09-05", sections: [
      { title: "Přidáno", items: [
        { text: "Varianty názvů bezkódového zboží se učí z úspěšných cenových zápisů a po potvrzení dvěma různými registrovanými uživateli pomáhají s našeptáváním i překlepy", parts: ["server", "web", "mobil"] },
        { text: "Moderátor může sloučit nahlášené duplicitní bezkódové produkty včetně jejich cen, recenzí, fotek a aliasů; původní odkaz se přesměruje na kanonický produkt", parts: ["server", "web"] },
        { text: "Po výběru obchodu je hned vidět, jaké bezkódové zboží už v něm máme, seřazené podle toho, jak často se v něm zapisuje cena — bez psaní", parts: ["server", "web", "mobil"] },
        { text: "Moderace má záložku Duplicity s dvojicemi podezřele podobného bezkódového zboží ve stejném obchodě a kategorii, bez čekání na nahlášení", parts: ["server", "web"] },
        { text: "Moderátor může opravit název bezkódového zboží pro všechny; původní název zůstane dohledatelný jako alias", parts: ["server", "web"] },
        { text: "Tlačítko „Zapsat další zboží v tomhle obchodě\" po úspěšném zápisu ceny", parts: ["web"] },
        { text: "Zboží má název ve víc jazycích: appka ukáže název v jazyce, ve kterém běží, a když v něm název nikdo nemá, sáhne po jiném jazyce a označí jej štítkem", parts: ["server", "web", "mobil"] },
        { text: "Formulář zboží upozorní, když má zboží zatím název jen cizojazyčně, a nabídne sbalenou sekci „Názvy v jiných jazycích\" — pole „Název\" zůstává vždy v jazyce appky", parts: ["web", "mobil"] },
        { text: "Fotky zboží se ukládají s jazykem obalu, ať se u složení nenabídne cizojazyčná etiketa", parts: ["server", "web", "mobil"] },
      ] },
      { title: "Změněno", items: [
        { text: "Bezkódové zboží se už nespojuje globálně jen podle volného názvu: patří konkrétnímu řetězci, nebo nezávislé provozovně, a formulář proto vybírá obchod před zbožím", parts: ["server", "web", "mobil"] },
        { text: "Poslední obchod použitý pro zadání ceny se na zařízení pamatuje 30 dní, aby jej nebylo nutné vybírat při každé další položce", parts: ["web", "mobil"] },
        { text: "Našeptávání zboží najde položku i podle jednoho slova z dlouhého názvu („polévku\" najde „Dršťkovou polévku s kroupami\"), dřív takovou shodu délka názvu utopila", parts: ["server"] },
        { text: "Zboží na webu našeptává průběžně při psaní, ne až po odeslání hledání", parts: ["web"] },
        { text: "Zatím nepotvrzené zboží se v nabídce řadí až za potvrzené a je označené, ať nepotvrzený název nestojí nad zavedeným", parts: ["server", "web", "mobil"] },
        { text: "Cíl sloučení duplicit vybírá moderátor z našeptávače ve stejném obchodním rozsahu místo opisování číselného ID", parts: ["web"] },
      ] },
      { title: "Zabezpečeno", items: [
        { text: "Zakládání bezkódového zboží má strop na jednu provozovnu za den a na počet nepotvrzených položek otevřených jedním účtem naráz, aby zaplevelení katalogu nešlo škálovat časem", parts: ["server"] },
      ] },
      { title: "Opraveno", items: [
        { text: "Po naskenování nebo zadání kódu se u nalezeného zboží na obrazovce zápisu ceny zobrazí obrázek zboží", parts: ["web", "mobil"] },
        { text: "Detail zboží z Open Food Facts, jehož kategorie neodpovídá našemu stromu, se otevře místo pádu", parts: ["server"] },
      ] },
  ] },
  { version: "0.6.3", date: "2026-09-03", sections: [
      { title: "Přidáno", items: [
        { text: "Tlačítko zpět v hlavičce mobilní appky na vnořených obrazovkách", parts: ["mobil"] },
        { text: "Appka se před opuštěním rozepsaného formuláře nebo obrazovky zeptá, jestli zahodit neuložené změny", parts: ["mobil"] },
        { text: "Ruční zadání kódu a baterka ve skeneru", parts: ["mobil"] },
        { text: "Vedlejší akce detailu zboží (upravit, nahlásit) sjednoceny do jedné nabídky", parts: ["web", "mobil"] },
      ] },
      { title: "Změněno", items: [
        { text: "Cena je na detailu zboží nejvýš, před grafem, kvalitou a recenzemi", parts: ["web", "mobil"] },
        { text: "Panel filtrů hledání na webu je na malých displejích sbalený za tlačítkem, výsledky se zobrazují jako karty", parts: ["web"] },
      ] },
  ] },
  { version: "0.6.2", date: "2026-09-03", sections: [
      { title: "Změněno", items: [
        { text: "Výsledek hledání v mobilu se zobrazuje jako karta s větší fotkou a cenou jako hlavním údajem místo řádku", parts: ["mobil"] },
        { text: "Filtry obchodu, města, kategorie a řazení v mobilním hledání jsou sbalené za tlačítkem „Filtry a řazení“ s počtem aktivních filtrů", parts: ["mobil"] },
      ] },
  ] },
  { version: "0.6.1", date: "2026-09-02", sections: [
      { title: "Opraveno", items: [
        { text: "Dlouhý název obchodu už nelámal cenu do svislého sloupce jednotlivých znaků", parts: ["mobil"] },
      ] },
      { title: "Přidáno", items: [
        { text: "Moderace v záložce Ceny ukazuje u zboží čekajícího na zveřejnění počet chybějících komunitních potvrzení", parts: ["web"] },
      ] },
  ] },
  { version: "0.6.0", date: "2026-09-02", sections: [
      { title: "Zabezpečeno", items: [
        { text: "Formulář zpětné vazby má proof-of-work výzvu místo CAPTCHY, vrstvené limity na IP/podsíť/celkový denní počet anonymních odeslání a odkládá podezřelé zprávy do karantény místo skrytí", parts: ["server", "web", "mobil"] },
        { text: "Klientská IP se čte odolně proti podvržení hlavičky `X-Forwarded-For` (server), oprava se týká i limitu na přihlašovací kódy", parts: [] },
      ] },
      { title: "Změněno", items: [
        { text: "Anonymní zápisy se do prahu potvrzení bezkódového zboží/nového obchodu už nepočítají vůbec, dřív se všechny anonymní zápisy jednoho dne počítaly jako jedno potvrzení", parts: ["server"] },
        { text: "Poloha uživatele se před odesláním na OpenStreetMap Nominatim (reverzní geokódování) a do vyhledání obchodů v okolí zaokrouhlí na méně přesných desetinných míst, ať appka třetí straně neposílá falešně přesnou polohu", parts: ["server"] },
        { text: "Poskytovatel mapových dlaždic je konfigurovatelný, ne natvrdo v kódu", parts: ["web", "mobil"] },
      ] },
      { title: "Přidáno", items: [
        { text: "Atribuce poskytovatele mapových dlaždic přímo v mapě (dřív jen ve „O aplikaci\")", parts: ["mobil"] },
        { text: "Publikační sestavení mobilní appky teď bez podpisového klíče rovnou selže a po sestavení ověří podpis, místo aby tiše vyrobilo nepodepsaný soubor", parts: ["mobil"] },
        { text: "Záložka „Podezřelé\" v moderaci pro zprávy zpětné vazby odložené kvůli podezření na spam, s možností vrátit falešný poplach zpět do fronty", parts: ["web"] },
        { text: "K hodnocení zboží hvězdičkami lze doplnit textovou recenzi (max 1000 znaků) — zobrazuje se pod zbožím podepsaná veřejným jménem autora, jde upravit, smazat i nahlásit", parts: ["server", "web", "mobil"] },
        { text: "Výpis vlastních recenzí v „Moje příspěvky\"", parts: ["server", "web", "mobil"] },
      ] },
  ] },
  { version: "0.5.2", date: "2026-08-31", sections: [
      { title: "Opraveno", items: [
        { text: "Hledání zboží a Moje příspěvky (Zboží, Ceny, Úpravy) u položek s fotkou opravdu fungují — oprava ve verzi 0.5.1 chybu ještě neodstranila", parts: ["mobil"] },
      ] },
  ] },
  { version: "0.5.1", date: "2026-08-30", sections: [
      { title: "Opraveno", items: [
        { text: "Hledání zboží a záložky Zboží/Ceny v Mých příspěvcích už nekončí chybou u položek s fotkou nebo obrázkem z Open Food Facts", parts: ["mobil"] },
      ] },
  ] },
  { version: "0.5.0", date: "2026-08-30", sections: [
      { title: "Přidáno", items: [
        { text: "Miniatura zboží (vlastní fotka, jinak Open Food Facts) ve výsledcích hledání a při zápisu ceny", parts: ["server", "web", "mobil"] },
        { text: "Zpětné datum ceny („Kdy jsi cenu viděl(a)\") při zápisu ceny", parts: ["mobil"] },
        { text: "Založení zboží bez čárového kódu a rovnou zápis ceny ze záložky Hledat", parts: ["mobil"] },
      ] },
      { title: "Změněno", items: [
        { text: "Hodnocení kvality zboží je hvězdičky 1–5 (5 nejlepší) místo školní známky 1–5", parts: ["server", "web", "mobil"] },
      ] },
      { title: "Opraveno", items: [
        { text: "Přihlášení přepíná na zadání kódu hned po odeslání, ne až po odpovědi serveru, a odeslání e-mailu už neblokuje request", parts: ["server", "web", "mobil"] },
        { text: "Kalendář při zápisu ceny respektuje zvolený jazyk appky (dny, měsíce, první den týdne) místo pevné angličtiny", parts: ["web"] },
        { text: "Zpětně zapsaná cena a platnost akční ceny se už neposouvají o den kvůli časovému pásmu", parts: ["web"] },
        { text: "Datum platnosti akční ceny a relativní datum („před 3 dny\") na mobilu respektují zvolený jazyk appky", parts: ["mobil"] },
      ] },
  ] },
  { version: "0.4.0", date: "2026-08-29", sections: [
      { title: "Přidáno", items: [
        { text: "Fotka zboží a fotka etikety rovnou při zakládání nového zboží, obě nepovinné", parts: ["server", "web", "mobil"] },
        { text: "Odkazy na aditiva (E-čka) konkrétního zboží z Open Food Facts na detailu zboží", parts: ["server"] },
        { text: "Našeptávání řetězce podle názvu při zakládání/úpravě obchodu, výběr předvyplní název obchodu", parts: ["server", "web", "mobil"] },
      ] },
  ] },
  { version: "0.3.0", date: "2026-08-28", sections: [
      { title: "Přidáno", items: [
        { text: "Předvyplnění zboží z Open Food Facts při skenu/zadání čárového kódu, který appka ještě nezná, s atribucí zdroje na detailu zboží", parts: ["server", "web", "mobil"] },
        { text: "Verze appky viditelná pod titulkem na záložce Nastavení", parts: ["mobil"] },
      ] },
  ] },
  { version: "0.2.1", date: "2026-08-28", sections: [

  ] },
  { version: "0.2.0", date: "2026-08-27", sections: [
      { title: "Přidáno", items: [
        { text: "Nepovinná platnost akční ceny od–do — po vypršení zmizí z aktuálních cen, historie v grafu zůstává beze změny", parts: ["server", "web", "mobil"] },
        { text: "Nepovinná URL obchodu na jeho stránku u řetězce", parts: ["server", "web", "mobil"] },
        { text: "Hledání zboží podle čárového kódu, ne jen podle názvu", parts: ["server", "mobil"] },
        { text: "Verze appky viditelná v patičce webu a v „O aplikaci\" v mobilu, s odkazem na seznam změn — web na `/changelog`, appka na „Novinky\"", parts: ["server", "web", "mobil"] },
      ] },
      { title: "Změněno", items: [
        { text: "Zápis ceny je v appce schovaný za tlačítkem — appka slouží i lidem, co jen hledají ceny poblíž", parts: ["mobil"] },
        { text: "Filtry hledání (obchod/město/řazení) přežijí přepnutí záložky", parts: ["mobil"] },
        { text: "Zvětšená ikona v hlavičce webu, srovnaná tlačítka na stránce přihlášení pod sebe", parts: ["web"] },
      ] },
      { title: "Opraveno", items: [
        { text: "Mapa obchodu jde posunout prstem a umí vybrat obchod ze značek", parts: ["mobil"] },
        { text: "Pád appky bez FINE oprávnění při zjišťování polohy", parts: ["mobil"] },
        { text: "Vykreslení mapy na starém místě po skrytí/zobrazení klávesnice", parts: ["mobil"] },
      ] },
  ] },
  { version: "0.1.0", date: "2026-08-24", sections: [
      { title: "Přidáno", items: [
        { text: "První testovací nasazení (interní testování, ne veřejné spuštění) — hledání zboží a obchodů, zápis a přehled cen, vývoj v čase a srovnání napříč obchody, OTP přihlášení, samoobslužné smazání účtu", parts: ["server", "web", "mobil"] },
      ] },
  ] },
];
