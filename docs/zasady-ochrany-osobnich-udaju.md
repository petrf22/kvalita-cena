# Zásady ochrany osobních údajů

Platí od 24. srpna 2026. Toto je aktuálně platné znění; předchozí verze viz
historie tohoto souboru.

## 1. Kdo je správce

Správcem osobních údajů zpracovávaných appkou Kvalita a cena je **Petr Franta**, soukromá
osoba (dále jen „my" nebo „provozovatel").

Kontakt ve věci ochrany osobních údajů: **kontakt@kvalitacena.cz**

## 2. Základní princip: appka tě nesleduje

Cílem appky je, aby co nejvíc lidí mělo přehled o cenách — ne sledovat, kdo kde nakupuje.
Tenhle princip je promítnutý přímo do toho, jak appka ukládá data, ne jen do tohoto textu:

- appka nikdy neukládá tvoji GPS polohu,
- appka nepoužívá žádnou analytiku ani sledovací nástroje třetích stran, žádnou reklamu,
  žádné externí fonty,
- appka nemá funkci sledovat jiné uživatele ani vidět jejich historii nákupů,
- appka nepoužívá cookies pro sledování — jediná cookie, kterou appka nastavuje (na webu),
  slouží výhradně k udržení přihlášení a nikam se z ní nic nevyčítá.

## 3. Jaké údaje zpracováváme

Při registraci ti appka automaticky vygeneruje **veřejné jméno** — např. „Modrý čáp #4271" —
složené z náhodného přídavného jména, zvířete a čísla. Tohle jméno appka zobrazuje ostatním
uživatelům všude, kde se tvůj příspěvek podepisuje (recenze, viz 3.3); neprozrazuje o tobě nic
skutečného a nejde z něj nic zjistit. Kdykoli si ho můžeš v profilu (3.2) přepsat na vlastní
přezdívku — na skutečné jméno appka přepsání nedovolí.

### 3.1 Přihlašovací e-mail

E-mail, kterým se přihlašuješ, se v databázi neukládá v čitelné podobě. Ukládá se jednak
jako nevratný otisk (slouží jen k tomu, aby appka při přihlášení poznala, že jde o stejný
e-mail), jednak zašifrovaný (aby ti appka mohla poslat přihlašovací kód nebo pozdější
oznámení) — čitelný je jen appce se šifrovacím klíčem, který se v databázi nenachází.

### 3.2 Volitelný profil

Pokud se rozhodneš appce sdělit jméno, příjmení, telefon, kontaktní e-mail (jiný než
přihlašovací) nebo nahrát profilovou fotku, jsou to čistě dobrovolné údaje — appka po nich
nikde nežádá a bez nich appka plně funguje. Textové údaje profilu jsou zašifrované stejným
způsobem jako přihlašovací e-mail. Výchozí viditelnost profilu je nastavená tak, že ho vidíš
jen ty; pokud se rozhodneš profil zveřejnit, appka ti dá kontrolu nad tím, které konkrétní
pole uvidí kdo.

### 3.3 Cenové záznamy a hodnocení, které vložíš

Cena, kterou zapíšeš, se propojí s tvým účtem — díky tomu appka dokáže rozpoznat spolehlivé
přispěvatele a bránit se zneužití. Tahle vazba ale **není trvalá**: po 180 dnech appka
automaticky vazbu na tvůj konkrétní účet zruší, záznam zůstane v datech jen jako
anonymizovaná statistika (číslo do grafu vývoje ceny), bez možnosti dohledat, kdo ho zapsal.
180 dní je nejdelší doba, kterou appka potřebuje na to, aby dokázala odhalit podezřelé
vzorce chování nebo vyřešit spor o cenu.

Z tohoto pravidla existují dvě záměrné výjimky, protože bez trvalé vazby by appka ztratila
funkci, kterou od nich uživatelé očekávají:

- **Hodnocení kvality zboží** (hvězdičky 1–5, volitelně s textem recenze) zůstává navázané na
  tvůj účet po celou dobu trvání účtu — jinak by šlo systém obejít opakovaným hodnocením
  stejné věci. Samotné hvězdičky navenek appka zobrazuje jen jako průměr a počet hodnocení,
  nikdy jako seznam „kdo jak ohodnotil". **Napíšeš-li k hodnocení text, appka ho zveřejní
  podepsaný tvým veřejným jménem** (viz začátek kapitoly 3 výš) — nikdy tvoje skutečné jméno
  ani e-mail — protože nepodepsaná recenze by nebyla důvěryhodná. Text i podpis zmizí, když
  text smažeš nebo účet zrušíš.
- **Tvoje vlastní úpravy existujícího zboží/obchodu** (např. oprava názvu nebo adresy)
  zůstávají navázané na tvůj účet, dokud účet existuje — jinak by ti po půl roce tiše zmizely
  vlastní opravy. I tady appka navenek ukazuje jen výslednou opravenou hodnotu, ne kdo ji
  udělal.

Obě výjimky se smažou při smazání účtu (viz „Tvá práva" níže).

### 3.4 Poloha

Appka **nikdy neukládá tvoji GPS polohu**. Když appce dovolíš najít obchody v okolí, appka
polohu použije jen k dotazu „co je nablízku" a hned zapomene — do databáze ani do logů se
nezapisuje. Souřadnice obchodů (veřejný fakt, ne osobní údaj — totéž, co je na ceduli u
vchodu) appka získává buď od tebe při založení obchodu, nebo z OpenStreetMap; dotaz na
OpenStreetMap přitom vždy posílá appka sama ze svého serveru, ne tvůj telefon nebo prohlížeč
— tvoje IP adresa se tak k OpenStreetMap nikdy nedostane.

Totéž platí i naopak: tlačítko „Použít mou polohu" při zakládání/editaci obchodu (appka ti
předvyplní adresu podle toho, kde stojíš) pošle tvoji polohu OpenStreetMap taky výhradně ze
serveru, nikdy přímo z telefonu/prohlížeče — a než ji pošle, zaokrouhlí ji na přibližně
11 metrů, ať k OpenStreetMap nejde přesnější poloha, než appka pro najití adresy potřebuje.
Ani tahle poloha se nikam neukládá. Jedinou vědomou výjimkou z pravidla „jen ze serveru" je
mapa samotná (viz bod 3.6 níže).

### 3.5 Fotky zboží a provozoven

Fotka z mobilu běžně v sobě nese skrytá data — mimo jiné i přesné GPS souřadnice, kde
vznikla. Appka každou nahranou fotku před uložením kompletně překreslí z jednotlivých
obrazových bodů do nového souboru — výsledek tak nikdy nenese žádná skrytá data z originálu,
protože fotku negeneruje kopírováním originálu, ale úplně nově z jeho obsahu.

Fotky se dají kdykoli smazat (vlastní) nebo nahlásit (cizí, pokud jsou nevhodné nebo
porušují cizí práva) — nahlášení fotky appka bere obzvlášť vážně, stačí k dočasnému skrytí
jediné nahlášení, než ho někdo přezkoumá.

### 3.6 Mapa (vědomá výjimka)

Pokud si na detailu obchodu otevřeš mapu, appka mapové podklady (dlaždice) stahuje přímo
z tvého telefonu/prohlížeče od poskytovatele dlaždic (dnes OpenStreetMap, appka umí
poskytovatele vyměnit) — na rozdíl od geokódování adres výš tahle jedna věc jde přímo
z klienta, ne přes náš server. Server by dlaždice mohl proxovat, appka to zatím nedělá — bylo
by to za cenu vlastní cache infrastruktury navíc, kterou dnešní appka nemá. Znamená to, že
poskytovatel dlaždic při otevření mapy uvidí tvoji IP adresu — appka tomu předchází tím, že se
mapa nenačte, dokud si o ni výslovně neřekneš (není součástí běžného zobrazení stránky).

### 3.7 Technická data

Appka krátkodobě loguje technická data (např. IP adresu u přihlašovacích pokusů a u odesílání
formuláře zpětné vazby) kvůli ochraně proti zneužití (např. hromadnému zkoušení přihlašovacích
kódů nebo hromadnému odesílání spamu) — tahle data neslouží k profilování ani analytice. Žijí
jen v paměti serveru, ne v databázi, nejvýš 1 hodinu u přihlašovacích pokusů a nejvýš 1 den
u odesílání zpětné vazby — a v obou případech zmizí i dřív, restartem appky.

### 3.8 Zpětná vazba appce

Formulář zpětné vazby (dostupný i bez přihlášení) uloží text zprávy, kategorii, volitelný
kontaktní e-mail (jen pokud ho sám/sama vyplníš) a technické údaje o appce, ze které hlášení
přišlo (verze appky, platforma, jazyk, obrazovka). Na Androidu si můžeš navíc dobrovolně
přiložit záznam o posledním pádu appky — appka ho bez tvého výslovného zaškrtnutí nikdy
neodešle, standardně zůstává jen v appce na tvém telefonu. Hlášení vidí jen provozovatel,
slouží výhradně ke zlepšování appky.

## 4. Proč údaje zpracováváme (právní základ)

- **Plnění smlouvy** (čl. 6 odst. 1 písm. b) GDPR) — přihlašovací e-mail a vazba cenových
  záznamů na účet, bez kterých by appka nemohla fungovat tak, jak ji používáš.
- **Oprávněný zájem** (čl. 6 odst. 1 písm. f) GDPR) — ochrana appky před zneužitím
  (podezřelé vzorce chování, rate limiting, dočasná vazba záznam→účet do 180 dnů).
- **Souhlas** (čl. 6 odst. 1 písm. a) GDPR) — volitelný profil (jméno, telefon, kontaktní
  e-mail, avatar) a jeho případné zveřejnění; souhlas můžeš kdykoli odvolat smazáním
  příslušného údaje v appce.

## 5. Jak dlouho údaje uchováváme

| Údaj | Doba uchování |
|---|---|
| Vazba cenového záznamu na tvůj účet | 180 dní od data ceny, pak automaticky anonymizováno |
| Samotný cenový záznam (bez vazby na účet) | trvale, jako anonymizovaná statistika ve veřejném zájmu |
| Hodnocení kvality zboží, vlastní úpravy zboží/obchodu | po dobu trvání účtu, smazáno s účtem |
| Volitelný profil (jméno, telefon, kontaktní e-mail, avatar) | po dobu trvání účtu, smazáno s účtem |
| Přihlašovací e-mail (hash a šifrovaná podoba) | po dobu trvání účtu |
| Technická data (IP u přihlašovacích pokusů a odesílání zpětné vazby) | jen v paměti serveru, nejvýš 1 hodina (přihlašovací pokusy) / 1 den (zpětná vazba), zmizí i dřív restartem |
| Zpětná vazba appce | po dobu, než ji provozovatel vyřídí a přestane být potřebná |

## 6. Komu údaje předáváme

Osobní údaje nikomu neprodáváme ani je nepoužíváme k reklamě. Appka pracuje s těmito
externími službami, u kterých se — mimo výjimku mapových dlaždic v bodě 3.6 — appka vždy
staví mezi tebe a danou službu jako prostředník, takže se k nim tvoje IP adresa ani jiný
identifikátor nedostane:

- **OpenStreetMap (Nominatim)** — vyhledání/ověření adresy provozovny, i opačným směrem
  (tvoje poloha → adresa u tlačítka „Použít mou polohu", zaokrouhlená, viz bod 3.4); dotaz
  vždy posílá appka ze svého serveru.
- **Open Food Facts** — veřejné údaje o zboží (název, kategorie), žádná osobní data appka
  tomuhle zdroji neposílá.
- **ARES** (veřejný rejstřík ekonomických subjektů) — jen volitelné ověření IČO provozovny
  při zakládání obchodu; to je údaj o firmě, ne o tobě.
- **Česká národní banka** — kurzovní lístek pro přepočet zobrazovací měny; appka mu nic
  neposílá, jen si stahuje veřejně dostupná data.
- **Poskytovatel e-mailových služeb**, přes kterého appka posílá přihlašovací kódy — zpracovává
  tvůj e-mail v roli zpracovatele, výhradně k doručení zprávy.

Appka neběží na cizí analytické platformě a nemá vestavěné žádné sledovací pixely.

## 7. Cookies

Appka na webu používá jedinou cookie: `httpOnly` cookie s přihlašovacím tokenem, nutnou
k tomu, aby ses po přihlášení nemusel(a) znovu přihlašovat při každé stránce. Tahle cookie
neslouží k analytice ani reklamě, appka z ní nic nevyčítá — je to čistě technická nutnost
provozu, appka proto nezobrazuje cookie lištu.

Mobilní appka žádné cookies nepoužívá; přihlašovací token ukládá zašifrovaný přímo v
zabezpečeném úložišti telefonu.

## 8. Zabezpečení

- Přihlašovací e-mail i volitelné údaje profilu appka šifruje algoritmem AES-256-GCM;
  šifrovací klíč se nenachází v databázi.
- Přihlašovací kódy appka neukládá v čitelné podobě, jen jejich kryptografický otisk.
- Fotky appka před uložením kompletně přegeneruje, aby nikdy nenesly skrytá metadata
  z originálu (viz bod 3.5).
- Přístup appky ke zdrojovému kódu, databázi a serveru má výhradně provozovatel.

Žádný systém není stoprocentně bezpečný, ale appka je navržená tak, aby v případě úniku dat
(např. zálohy databáze) uniklá data sama o sobě neobsahovala čitelné e-maily ani jiné osobní
údaje bez šifrovacího klíče, který je uložený odděleně.

## 9. Tvá práva

Podle GDPR máš právo:

- **na přístup** — vědět, jaké údaje o tobě appka má,
- **na opravu** — nepřesné údaje profilu si opravíš přímo v appce (Nastavení → Účet),
- **na výmaz** — smazat účet přímo v appce (Účet → Profil → Smazat účet, ověřeno kódem
  z e-mailu); tvoje cenové záznamy přitom zůstanou jako anonymizovaná statistika bez vazby na
  tebe — appka je nemaže, jde o sdílená komunitní data, ne o tvůj osobní údaj,
- **na přenositelnost** — vyžádat si vlastní data ve strojově čitelné podobě,
- **vznést námitku** proti zpracování založenému na oprávněném zájmu,
- **podat stížnost** u Úřadu pro ochranu osobních údajů (www.uoou.cz), pokud máš pocit, že
  appka s tvými údaji nezachází správně.

Samoobslužný export dat v appce se teprve připravuje. Do té doby (i pro přístup nebo opravu,
kterou appka sama nenabízí) si napiš na **kontakt@kvalitacena.cz** — vyřídíme to ručně, obvykle
do několika dní.

## 10. Věk

Appka je určená lidem starším 15 let (viz Podmínky užití). Pokud zjistíme, že appku
používá dítě mladší 15 let bez souhlasu zákonného zástupce, účet na žádost zákonného
zástupce smažeme.

## 11. Budoucí strojové zpracování fotek

Appka do budoucna plánuje automatizovanou předběžnou kontrolu nahraných fotek kvůli
moderaci (např. odhalení zjevně nevhodného obsahu, než ho uvidí člověk). Tohle zpracování
poběží na počítači provozovatele, ne přes cloudovou službu třetí strany — fotky by tak
neopustily appku o nic víc, než dnes. Tenhle text aktualizujeme, až se to skutečně zapne.

## 12. Změny těchto zásad

Zásady se mohou měnit, zejména s tím, jak appka přibývá funkcemi. O podstatné změně
(zejména o rozšíření zpracovávaných údajů) budeme přihlášené uživatele informovat
e-mailem nebo oznámením v appce s přiměřeným předstihem. Datum aktuálního znění je na
začátku tohoto dokumentu.

## 13. Kontakt

E-mail: **kontakt@kvalitacena.cz**
