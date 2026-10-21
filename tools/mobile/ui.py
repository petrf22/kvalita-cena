#!/usr/bin/env python3
"""Textové ovládání appky v Android emulátoru — místo screenshotů.

Proč to existuje: ověřovat stav obrazovky obrázkem je drahé a pomalé. `uiautomator` umí
vypsat celý strom prvků jako XML, jenže SYROVÝ dump je ještě horší než screenshot (desítky
kilobajtů na jednu obrazovku). Úspora vzniká až filtrací na jeden řádek per viditelný prvek
— to je hlavní přidaná hodnota tohoto skriptu, ne obalení `adb`.

Tohle NEJSOU automatizované testy. Konvence appky zůstává „ViewModely a obrazovky se
automatizovaně netestují, testuje se ručně checklistem" (`mobile/CLAUDE.md`) — skript je
pomůcka pro to ruční ověřování, aby šlo dělat textově.

Použití (emulátor musí běžet, appka být nainstalovaná — viz `./start-dev.sh`):

    python3 tools/mobile/ui.py setup                   # jednorázově: vypnout animace
    python3 tools/mobile/ui.py dump                    # co je na obrazovce
    python3 tools/mobile/ui.py tap "Zapsat cenu"       # klepnutí podle popisku
    python3 tools/mobile/ui.py tap-xy 540 1820         # klepnutí podle souřadnic z dumpu (ne na klávesnici)
    python3 tools/mobile/ui.py text 8594001020102      # psaní do zaostřeného pole
    python3 tools/mobile/ui.py key back
    python3 tools/mobile/ui.py wait "Uložit" --timeout 15
    python3 tools/mobile/ui.py open "product/<uuid>"   # skok rovnou na obrazovku
    python3 tools/mobile/ui.py log --clear
    python3 tools/mobile/ui.py screenshot /tmp/x.png   # vypíše JEN cestu, pro oko uživatele

Omezení, na která se naráží v praxi:

  * `adb shell input text` NEUMÍ diakritiku — pošle místo znaku nesmysl. Skript na ne-ASCII
    vstup upozorní; testovací data volit v ASCII (e-maily, EANy, ceny jimi jsou beztak).
  * `uiautomator dump` umí selhat hláškou „could not get idle state" tam, kde běží nekonečná
    animace (náhled kamery na ScanScreen, průběžné indikátory). Skript proto dump opakuje;
    hlavní pojistka je ale `ui.py setup` — po vynulování animation scales prošel i sken.
  * `resource-id` je u Compose skoro vždy prázdný (musel by se zapnout `testTagsAsResourceId`
    a rozdat `testTag`). Proto se prvky hledají podle textu a `contentDescription`, kterých má
    appka dost.
  * `uiautomator dump` čte jen AKTIVNÍ okno: nevidí nabídku editovatelného comboboxu (Compose
    ExposedDropdownMenu s `PrimaryEditable` kotvou otevírá nefokusovatelné okno, fokus zůstává
    v poli) a neví, kde je klávesnice — prvky pod ní vypíše, jako by šly klepnout. Skript proto
    obrazovku čte vlastním dumperem všech oken (`windowdump/WindowDump.java`, přes
    `app_process` stejně jako `uiautomator`; dex se sestaví a nahraje sám). Prvky zakryté
    klávesnicí nebo nabídkou mají v dumpu příznak a `tap` na prvek pod klávesnicí ji nejdřív
    zavře. Bez `javac`/Android SDK se vrací k `uiautomator dump` a klávesnici zjistí z `dumpsys`.

`adb` se hledá stejně jako v `start-dev.sh`: `sdk.dir` z `mobile/local.properties`, pak
`$ANDROID_HOME`/`$ANDROID_SDK_ROOT`, pak `~/Android/Sdk`, nakonec `adb` v PATH.
"""
import argparse
import hashlib
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import unicodedata
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PACKAGE = "cz.kvalitacena"
ACTIVITY = PACKAGE + "/.MainActivity"
# Extra, kterou MainActivity čte jen v debug buildu (viz DebugRouteIntent tamtéž).
ROUTE_EXTRA = "route"
DUMP_REMOTE_PATH = "/sdcard/kvalitacena-ui.xml"
WINDOWDUMP_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "windowdump")
WINDOWDUMP_SOURCE = os.path.join(WINDOWDUMP_DIR, "WindowDump.java")
# Strop na jedno volání adb: zamrzlý emulátor nebo zaseknutý uiautomator jinak skript pověsí
# navždy (viděno u systémového výběru fotek).
ADB_TIMEOUT = 45

# Zkratky tříd do výpisu — plné android.widget.* názvy by byly jen šum.
CLASS_SHORT = {
    "android.widget.TextView": "txt",
    "android.widget.EditText": "edit",
    "android.widget.Button": "btn",
    "android.widget.ImageView": "img",
    "android.widget.ImageButton": "btn",
    "android.widget.CheckBox": "chk",
    "android.widget.Switch": "sw",
    "android.widget.RadioButton": "radio",
    "android.widget.ScrollView": "scroll",
    "android.widget.HorizontalScrollView": "scroll",
    "android.view.View": "view",
    "android.widget.FrameLayout": "box",
    "android.widget.LinearLayout": "box",
    "androidx.compose.ui.platform.ComposeView": "compose",
}


def die(message):
    print(message, file=sys.stderr)
    sys.exit(1)


# ---- adb ----------------------------------------------------------------------------------


def sdk_dir():
    found = None
    properties = os.path.join(ROOT, "mobile", "local.properties")
    if os.path.isfile(properties):
        with open(properties, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("sdk.dir="):
                    found = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
                    break
    return found or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") \
        or os.path.expanduser("~/Android/Sdk")


def adb_binary():
    candidate = os.path.join(sdk_dir(), "platform-tools", "adb")
    if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
        return candidate
    found = shutil.which("adb")
    if found:
        return found
    die("adb nenalezeno (hledáno v %s a v PATH) — oprav sdk.dir v mobile/local.properties." % candidate)


def adb(args, device=None, check=True):
    """Spustí adb a vrátí (returncode, stdout jako text)."""
    command = [adb_binary()]
    if device:
        command += ["-s", device]
    command += args
    try:
        result = subprocess.run(command, capture_output=True, timeout=ADB_TIMEOUT)
    except subprocess.TimeoutExpired:
        die("adb neodpověděl do %d s: %s\nZamrzl emulátor? Zkus `adb devices`, případně emulátor "
            "restartovat." % (ADB_TIMEOUT, " ".join(command)))
    text = (result.stdout + result.stderr).decode("utf-8", errors="replace")
    if check and result.returncode != 0:
        die("Selhalo: %s\n%s" % (" ".join(command), text.strip()))
    return result.returncode, text


def adb_shell(parts, device=None, check=True):
    """`adb shell` skládá argumenty do jednoho příkazu pro vzdálený sh — proto shlex.quote."""
    return adb(["shell", " ".join(shlex.quote(part) for part in parts)], device, check)


# ---- dump ---------------------------------------------------------------------------------


class Node:
    def __init__(self, attrib):
        self.text = (attrib.get("text") or "").strip()
        self.desc = (attrib.get("content-desc") or "").strip()
        self.resource_id = (attrib.get("resource-id") or "").split("/")[-1]
        self.cls = attrib.get("class") or ""
        self.clickable = attrib.get("clickable") == "true"
        self.scrollable = attrib.get("scrollable") == "true"
        self.checked = attrib.get("checked") == "true"
        self.selected = attrib.get("selected") == "true"
        self.focused = attrib.get("focused") == "true"
        self.enabled = attrib.get("enabled") != "false"
        bounds = re.findall(r"-?\d+", attrib.get("bounds") or "")
        self.bounds = tuple(int(value) for value in bounds) if len(bounds) == 4 else None
        self.children = []
        # Kam klepnout: u popisku vyzvednutého z klikatelného rodiče je to střed RODIČE
        # (tlačítko), ne středu textu — viz flatten().
        self.tap = None
        # Vrstva okna (vyšší = navrch) a co bod klepnutí zakrývá — viz Screen.
        self.layer = 0
        self.covered = None

    @property
    def label(self):
        return self.text or self.desc

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    @property
    def point(self):
        return self.tap or self.center

    @property
    def area(self):
        x1, y1, x2, y2 = self.bounds
        return (x2 - x1) * (y2 - y1)

    def render(self):
        flags = []
        if self.clickable:
            flags.append("clickable")
        if self.scrollable:
            flags.append("scrollable")
        if not self.enabled:
            flags.append("disabled")
        if self.checked:
            flags.append("checked")
        if self.selected:
            flags.append("selected")
        if self.focused:
            flags.append("focused")
        if self.covered:
            flags.append("POD-" + self.covered.upper())
        label = self.label or "—"
        if self.text and self.desc and self.text != self.desc:
            label = "%s «%s»" % (self.text, self.desc)
        if self.resource_id:
            label += " #" + self.resource_id
        line = "%s  %s  @(%d,%d)" % (label, CLASS_SHORT.get(self.cls, self.cls.split(".")[-1].lower()),
                                     self.point[0], self.point[1])
        return line + ("  " + " ".join(flags) if flags else "")


def extract_xml(output):
    start = output.find("<hierarchy")
    end = output.rfind(">")
    if start == -1 or end == -1 or end < start:
        return None
    return output[start:end + 1]


def dump_xml(device=None, retries=3):
    """XML hierarchie obrazovky. `/dev/tty` na některých obrazech nefunguje — fallback přes soubor."""
    last_output = ""
    for attempt in range(retries):
        _, output = adb(["exec-out", "uiautomator", "dump", "/dev/tty"], device, check=False)
        xml = extract_xml(output)
        if xml:
            return xml
        last_output = output
        adb_shell(["uiautomator", "dump", DUMP_REMOTE_PATH], device, check=False)
        _, output = adb(["exec-out", "cat", DUMP_REMOTE_PATH], device, check=False)
        xml = extract_xml(output)
        if xml:
            return xml
        last_output = output or last_output
        if attempt + 1 < retries:
            time.sleep(0.8)
    die("uiautomator dump se nepovedl ani na %d. pokus:\n%s\n\nBěží na obrazovce nekonečná "
        "animace (ScanScreen/průběžný indikátor)? Zkus `ui.py setup`." % (retries, last_output.strip()))


def build_tree(xml):
    def walk(element):
        node = Node(element.attrib)
        node.children = [walk(child) for child in element if child.tag == "node"]
        return node
    return [walk(element) for element in ET.fromstring(xml) if element.tag == "node"]


def descendants(node):
    for child in node.children:
        yield child
        for grandchild in descendants(child):
            yield grandchild


# Kolik popisků smí klikatelný kontejner "vyzvednout". Řádek seznamu (název + cena + obchod)
# ano, obal celé obrazovky ne — jinak by se klikatelnost rozlila po všem, co je pod ním.
HOIST_LIMIT = 3


def flatten(node, out, tap=None, clickable=False):
    """Uzly k vypsání. Compose dává klikatelný kontejner a jeho text zvlášť — tady se spojí
    do jednoho řádku (popisek z textu, souřadnice z kontejneru), a kontejnery, které po
    spojení nic nenesou, vypadnou. Právě tím vzniká úspora oproti syrovému XML."""
    if not node.bounds or node.area <= 0:
        return
    if node.label:
        node.tap = tap or node.center
        node.clickable = node.clickable or clickable
        out.append(node)
        for child in node.children:
            flatten(child, out)
        return
    if node.clickable:
        labeled = [item for item in descendants(node) if item.label]
        if 1 <= len(labeled) <= HOIST_LIMIT:
            for child in node.children:
                flatten(child, out, tap=node.center, clickable=True)
            return
        out.append(node)  # bez popisku, ale souřadnice se hodí (ikona, prázdné pole)
        for child in node.children:
            flatten(child, out)
        return
    if node.scrollable:
        out.append(node)
    for child in node.children:
        flatten(child, out, tap, clickable)


def parse_nodes(xml):
    out = []
    for root in build_tree(xml):
        flatten(root, out)
    return out


def all_nodes(xml):
    nodes = []
    for element in ET.fromstring(xml).iter("node"):
        node = Node(element.attrib)
        if node.bounds and node.area > 0:
            nodes.append(node)
    return nodes


def dump_lines(nodes):
    lines = []
    seen = set()
    layer = None
    for node in nodes:
        if layer is not None and node.layer != layer:
            lines.append("── nabídka v samostatném okně (navrch) ──")
        layer = node.layer
        line = node.render()
        if line not in seen:
            seen.add(line)
            lines.append(line)
    return lines


# ---- všechna okna (WindowDump) ------------------------------------------------------------


def _newest(directory, check):
    """Nejnovější podadresář SDK (`platforms/android-36`, `build-tools/36.0.0`), který projde `check`."""
    if not os.path.isdir(directory):
        return None
    def version(name):
        return [int(part) if part.isdigit() else -1 for part in re.split(r"[.-]", name)]
    for name in sorted(os.listdir(directory), key=version, reverse=True):
        path = os.path.join(directory, name)
        if check(path):
            return path
    return None


_windowdump_warned = False


def _windowdump_unavailable(reason):
    global _windowdump_warned
    if not _windowdump_warned:
        print("Pozor: dump všech oken nejde (%s) — čtu jen aktivní okno přes uiautomator, "
              "nabídky comboboxů nebudou vidět." % reason, file=sys.stderr)
        _windowdump_warned = True
    return None


def windowdump_dex():
    """Lokální dex dumperu — sestaví se z WindowDump.java, kdykoli je zdroj novější."""
    build_dir = os.path.join(WINDOWDUMP_DIR, "build")
    dex = os.path.join(build_dir, "classes.dex")
    if os.path.isfile(dex) and os.path.getmtime(dex) >= os.path.getmtime(WINDOWDUMP_SOURCE):
        return dex
    javac = shutil.which("javac")
    platform = _newest(os.path.join(sdk_dir(), "platforms"),
                       lambda path: os.path.isfile(os.path.join(path, "android.jar")))
    build_tools = _newest(os.path.join(sdk_dir(), "build-tools"),
                          lambda path: os.path.isfile(os.path.join(path, "d8")))
    if not javac or not platform or not build_tools:
        return _windowdump_unavailable("chybí javac, android.jar nebo d8 v Android SDK")
    os.makedirs(build_dir, exist_ok=True)
    with tempfile.TemporaryDirectory() as classes:
        result = subprocess.run([javac, "--release", "11", "-nowarn", "-cp", os.path.join(platform, "android.jar"),
                                 "-d", classes, WINDOWDUMP_SOURCE], capture_output=True)
        if result.returncode != 0:
            return _windowdump_unavailable("javac: " + result.stderr.decode("utf-8", "replace").strip()[:300])
        class_files = [os.path.join(classes, name) for name in os.listdir(classes) if name.endswith(".class")]
        result = subprocess.run([os.path.join(build_tools, "d8"), "--min-api", "26", "--output", build_dir]
                                + class_files, capture_output=True)
        if result.returncode != 0:
            return _windowdump_unavailable("d8: " + result.stderr.decode("utf-8", "replace").strip()[:300])
    return dex


def windowdump_xml(device):
    """XML všech oken, nebo None (dumper nejde sestavit/spustit — volající spadne na uiautomator)."""
    dex = windowdump_dex()
    if not dex:
        return None
    with open(dex, "rb") as handle:
        digest = hashlib.sha256(handle.read()).hexdigest()[:12]
    remote = "/data/local/tmp/kvalitacena-windowdump-%s.dex" % digest
    code, _ = adb_shell(["ls", remote], device, check=False)
    if code != 0:
        adb(["push", dex, remote], device)
    for _ in range(2):
        _, output = adb_shell(["CLASSPATH=" + remote, "app_process", "/system/bin", "WindowDump"],
                              device, check=False)
        start, end = output.find("<hierarchy"), output.rfind("</hierarchy>")
        if start != -1 and end != -1:
            return output[start:end + len("</hierarchy>")]
        time.sleep(0.5)
    return _windowdump_unavailable("app_process: " + output.strip()[:300])


def _bounds(text):
    values = [int(value) for value in re.findall(r"-?\d+", text or "")]
    return tuple(values) if len(values) == 4 else None


def _inside(point, bounds):
    return bounds is not None and bounds[0] <= point[0] < bounds[2] and bounds[1] <= point[1] < bounds[3]


def ime_bounds_dumpsys(device):
    """Kde je klávesnice podle `dumpsys` — pro tap-xy a záložní cestu přes uiautomator.

    Rámec okna InputMethod je přes celou obrazovku; skutečnou plochu klávesnice nese až
    IME inset (`InsetsSource ... type=ime frame=[..] visible=true`)."""
    _, methods = adb_shell(["dumpsys", "input_method"], device, check=False)
    if "mInputShown=true" not in methods:
        return None
    _, windows = adb_shell(["dumpsys", "window"], device, check=False)
    match = re.search(r"type=ime frame=(\[\d+,\d+\]\[\d+,\d+\])[^\n]*visible=true", windows)
    return _bounds(match.group(1)) if match else None


class Screen:
    """Obrazovka jako seznam prvků napříč okny appky + kde je klávesnice.

    Prvek, jehož bod klepnutí leží pod oknem z vyšší vrstvy (klávesnice, nabídka comboboxu),
    dostane `covered` — klepnutí by trefilo to okno, ne jeho."""

    def __init__(self, device):
        self.xml = windowdump_xml(device)
        self.nodes, self.all, self.ime = [], [], None
        if self.xml is not None:
            self._from_windows()
        else:
            self.xml = dump_xml(device)
            self.nodes, self.all = parse_nodes(self.xml), all_nodes(self.xml)
            self.ime = ime_bounds_dumpsys(device)
            self._mark([(1, "klávesnicí", self.ime)] if self.ime else [])

    def _from_windows(self):
        covers = []
        app_windows = []
        for window in ET.fromstring(self.xml).iter("window"):
            kind, layer = window.get("type"), int(window.get("layer") or 0)
            bounds = _bounds(window.get("bounds"))
            if kind == "ime":
                self.ime = bounds
                covers.append((layer, "klávesnicí", bounds))
            elif kind == "application":
                app_windows.append((layer, window))
                covers.append((layer, "nabídkou", bounds))
        # Okna appky odspodu nahoru: hlavní obrazovka první, nabídka za ní (dump_lines je oddělí).
        for layer, window in sorted(app_windows, key=lambda item: item[0]):
            for element in window:
                if element.tag != "node":
                    continue
                flat = []
                flatten(self._tree(element), flat)
                for node in flat:
                    node.layer = layer
                self.nodes += flat
                for item in element.iter("node"):
                    node = Node(item.attrib)
                    if node.bounds and node.area > 0:
                        node.layer = layer
                        self.all.append(node)
        self._mark(covers)

    @staticmethod
    def _tree(element):
        node = Node(element.attrib)
        node.children = [Screen._tree(child) for child in element if child.tag == "node"]
        return node

    def _mark(self, covers):
        for node in self.nodes + self.all:
            if not node.bounds:
                continue
            for layer, what, bounds in covers:
                if layer > node.layer and _inside(node.point, bounds):
                    node.covered = what
                    break


# ---- hledání prvku ------------------------------------------------------------------------


def fold(value):
    """Porovnání bez ohledu na velikost písmen a diakritiku — ať `tap "ulozit"` taky sedne."""
    stripped = unicodedata.normalize("NFKD", value)
    return "".join(char for char in stripped if not unicodedata.combining(char)).casefold()


def find_nodes(nodes, query):
    """Přesný text → přesný popis → podřetězec. Nikdy netipovat: tichý tap vedle je horší než chyba."""
    candidates = [node for node in nodes if node.label]
    for match in (lambda node: node.text == query,
                  lambda node: node.desc == query,
                  lambda node: fold(node.label) == fold(query),
                  lambda node: fold(query) in fold(node.label)):
        found = [node for node in candidates if match(node)]
        if found:
            return found
    return []


def resolve_target(nodes, query, nth=None):
    found = find_nodes(nodes, query)
    if not found:
        print("Nenalezeno: %r. Na obrazovce je:" % query, file=sys.stderr)
        for line in dump_lines(nodes):
            print("  " + line, file=sys.stderr)
        sys.exit(1)
    # Nezakrytý prvek má přednost — stejný text pod otevřenou nabídkou a v ní je typicky
    # položka nabídky, na kterou se má klepnout.
    found = [node for node in found if not node.covered] or found
    # Compose vrací zanořené uzly se stejným textem (klikatelný rodič + textový potomek) —
    # pro klepnutí je správně ten klikatelný, jinak nejmenší (nejkonkrétnější).
    clickable = [node for node in found if node.clickable]
    found = clickable or found
    found.sort(key=lambda node: node.area)
    unique = []
    for node in found:
        if not any(abs(node.point[0] - other.point[0]) < 8 and abs(node.point[1] - other.point[1]) < 8
                   for other in unique):
            unique.append(node)
    if len(unique) > 1 and nth is None:
        print("Víc shod pro %r — vyber přes --nth:" % query, file=sys.stderr)
        for index, node in enumerate(unique, start=1):
            print("  --nth %d  %s" % (index, node.render()), file=sys.stderr)
        sys.exit(1)
    return unique[(nth or 1) - 1]


# ---- podpříkazy ---------------------------------------------------------------------------


def cmd_dump(args):
    screen = Screen(args.device)
    if args.raw:
        print(screen.xml)
        return
    lines = dump_lines(screen.all if args.all else screen.nodes)
    if screen.ime:
        lines.insert(0, "[klávesnice otevřená: y %d–%d — prvky POD-KLÁVESNICÍ klepnutí netrefí, "
                        "`tap` ji sám zavře]" % (screen.ime[1], screen.ime[3]))
    print("\n".join(lines) if lines else "(prázdná obrazovka)")


def hide_keyboard(device):
    """Zavře klávesnici (Zpět ji zavře, dokud je otevřená — nenaviguje) a počká, až zmizí."""
    adb_shell(["input", "keyevent", "KEYCODE_BACK"], device)
    deadline = time.monotonic() + 3
    while ime_bounds_dumpsys(device) and time.monotonic() < deadline:
        time.sleep(0.2)


def cmd_tap(args):
    screen = Screen(args.device)
    node = resolve_target(screen.nodes, args.text, args.nth)
    if node.covered == "klávesnicí":
        # Po zavření klávesnice se rozvržení posune — souřadnice se musí načíst znovu.
        hide_keyboard(args.device)
        print("klávesnice zakrývala %r — zavřena" % args.text)
        node = resolve_target(Screen(args.device).nodes, args.text, args.nth)
    if node.covered:
        die("%r je pod otevřenou nabídkou — klepni na její položku, nebo ji zavři `key back`.\n  %s"
            % (args.text, node.render()))
    x, y = node.point
    adb_shell(["input", "tap", str(x), str(y)], args.device)
    print("tap @(%d,%d) — %s" % (x, y, node.render()))


def cmd_tap_xy(args):
    ime = None if args.force else ime_bounds_dumpsys(args.device)
    if _inside((args.x, args.y), ime):
        die("@(%d,%d) je na klávesnici (y %d–%d) — zavři ji `key back` a vezmi souřadnice z nového "
            "`dump` (rozvržení se posune), nebo pošli --force." % (args.x, args.y, ime[1], ime[3]))
    adb_shell(["input", "tap", str(args.x), str(args.y)], args.device)
    print("tap @(%d,%d)" % (args.x, args.y))


def focused_field(device):
    for node in Screen(device).nodes:
        if node.focused:
            return node
    return None


def cmd_text(args):
    if any(ord(char) > 127 for char in args.value):
        print("Pozor: `input text` neumí diakritiku, text dorazí zkomolený. Zvol ASCII data.",
              file=sys.stderr)
    if args.clear:
        current = focused_field(args.device)
        length = len(current.text) if current else 0
        if length:
            adb_shell(["input", "keyevent", "KEYCODE_MOVE_END"], args.device)
            adb_shell(["input", "keyevent"] + ["KEYCODE_DEL"] * length, args.device)
    adb_shell(["input", "text", args.value.replace(" ", "%s")], args.device)
    # Ověřit, co v poli doopravdy skončilo: `input text` občas závodí se zaostřením pole a
    # zdvojí první znak. Tiše zkomolený vstup je přesně ta chyba, kterou dřív odhalil až
    # screenshot — tady musí být hlasitá.
    if not args.no_verify:
        field = focused_field(args.device)
        if field is None:
            print("Pozor: zapsaná hodnota nešla ověřit (žádné zaostřené pole).", file=sys.stderr)
        elif args.value not in field.text:
            die("V poli je %r, ne %r — zkus `text --clear` (nebo `--no-verify`, jde-li "
                "o diakritiku či maskované pole)." % (field.text, args.value))
    print("napsáno: %s" % args.value)


KEYCODES = {
    "back": "KEYCODE_BACK",
    "home": "KEYCODE_HOME",
    "enter": "KEYCODE_ENTER",
    "tab": "KEYCODE_TAB",
    "del": "KEYCODE_DEL",
    "search": "KEYCODE_SEARCH",
    "up": "KEYCODE_DPAD_UP",
    "down": "KEYCODE_DPAD_DOWN",
    "left": "KEYCODE_DPAD_LEFT",
    "right": "KEYCODE_DPAD_RIGHT",
    "menu": "KEYCODE_MENU",
    "escape": "KEYCODE_ESCAPE",
}


def cmd_key(args):
    keycode = KEYCODES.get(args.name.lower(), args.name.upper())
    adb_shell(["input", "keyevent", keycode], args.device)
    print("keyevent %s" % keycode)


def cmd_wait(args):
    deadline = time.monotonic() + args.timeout
    while True:
        nodes = Screen(args.device).nodes
        found = find_nodes(nodes, args.text)
        if args.gone and not found:
            print("zmizelo: %r" % args.text)
            return
        if not args.gone and found:
            print("nalezeno: %s" % found[0].render())
            return
        if time.monotonic() >= deadline:
            print("Vypršelo %.0f s a %r se %s. Na obrazovce je:"
                  % (args.timeout, args.text, "pořád zobrazuje" if args.gone else "neobjevilo"),
                  file=sys.stderr)
            for line in dump_lines(nodes):
                print("  " + line, file=sys.stderr)
            sys.exit(1)
        time.sleep(0.5)


def cmd_open(args):
    # --activity-single-top: MainActivity je jediná aktivita appky, takže běžící instance
    # dostane intent přes onNewIntent a nepřijde o stav; když appka neběží, prostě nastartuje.
    adb_shell(["am", "start", "-n", ACTIVITY, "--activity-single-top", "-e", ROUTE_EXTRA, args.route],
              args.device)
    print("otevřeno: %s" % args.route)


def cmd_log(args):
    if args.clear:
        adb(["logcat", "-c"], args.device)
        print("logcat vyprázdněn")
        if not args.follow_up:
            return
    _, pid = adb_shell(["pidof", "-s", PACKAGE], args.device, check=False)
    pid = pid.strip()
    if not pid.isdigit():
        die("Appka %s neběží (pidof nic nevrátil) — spusť ji přes `ui.py open search`." % PACKAGE)
    _, output = adb(["logcat", "-d", "-t", str(args.lines), "--pid=" + pid, "*:" + args.level], args.device)
    print(output.strip() or "(žádné řádky)")


def cmd_setup(args):
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb_shell(["settings", "put", "global", setting, "0"], args.device)
    print("Animace v emulátoru vypnuté (rychlejší a spolehlivější `dump`).")


def cmd_screenshot(args):
    # Záměrně vypisuje jen cestu: screenshot je pro OKO UŽIVATELE, ne pro čtení modelem
    # (mobile/CLAUDE.md, "Ladění v emulátoru").
    path = os.path.abspath(args.path)
    command = [adb_binary()] + (["-s", args.device] if args.device else []) + ["exec-out", "screencap", "-p"]
    with open(path, "wb") as handle:
        result = subprocess.run(command, stdout=handle, stderr=subprocess.PIPE)
    if result.returncode != 0:
        die(result.stderr.decode("utf-8", errors="replace").strip())
    print(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--device", "-s", help="ID zařízení, když jich běží víc (`adb devices`)")
    subparsers = parser.add_subparsers(dest="command", required=True)

    dump = subparsers.add_parser("dump", help="vypíše viditelné prvky obrazovky")
    dump.add_argument("--all", action="store_true", help="všechny uzly, bez slučování a filtrace")
    dump.add_argument("--raw", action="store_true", help="původní XML (velké!)")
    dump.set_defaults(func=cmd_dump)

    tap = subparsers.add_parser("tap", help="klepne na prvek podle textu nebo popisu")
    tap.add_argument("text")
    tap.add_argument("--nth", type=int, help="která shoda, když je jich víc")
    tap.set_defaults(func=cmd_tap)

    tap_xy = subparsers.add_parser("tap-xy", help="klepne na souřadnice z dumpu")
    tap_xy.add_argument("x", type=int)
    tap_xy.add_argument("y", type=int)
    tap_xy.add_argument("--force", action="store_true", help="klepnout i na klávesnici")
    tap_xy.set_defaults(func=cmd_tap_xy)

    text = subparsers.add_parser("text", help="napíše text do zaostřeného pole (jen ASCII)")
    text.add_argument("value")
    text.add_argument("--clear", action="store_true", help="nejdřív vyprázdnit pole")
    text.add_argument("--no-verify", action="store_true", help="nekontrolovat, co v poli skončilo")
    text.set_defaults(func=cmd_text)

    key = subparsers.add_parser("key", help="pošle klávesu (back/enter/del/…)")
    key.add_argument("name")
    key.set_defaults(func=cmd_key)

    wait = subparsers.add_parser("wait", help="čeká, až se text objeví (náhrada za sleep)")
    wait.add_argument("text")
    wait.add_argument("--timeout", type=float, default=10.0)
    wait.add_argument("--gone", action="store_true", help="čekat naopak na zmizení")
    wait.set_defaults(func=cmd_wait)

    open_route = subparsers.add_parser("open", help="skok na obrazovku podle trasy (jen debug build)")
    open_route.add_argument("route", help="např. search, product/<uuid>, 'price_entry?barcode=…'")
    open_route.set_defaults(func=cmd_open)

    log = subparsers.add_parser("log", help="logcat filtrovaný na PID appky")
    log.add_argument("--clear", action="store_true", help="vyprázdnit buffer")
    log.add_argument("--follow-up", action="store_true", help="po --clear rovnou vypsat")
    log.add_argument("--lines", type=int, default=100)
    log.add_argument("--level", default="W", choices=["V", "D", "I", "W", "E"])
    log.set_defaults(func=cmd_log)

    setup = subparsers.add_parser("setup", help="vypne animace v emulátoru")
    setup.set_defaults(func=cmd_setup)

    screenshot = subparsers.add_parser("screenshot", help="uloží PNG a vypíše jen cestu")
    screenshot.add_argument("path")
    screenshot.set_defaults(func=cmd_screenshot)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
