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
    python3 tools/mobile/ui.py tap-xy 540 1820         # klepnutí podle souřadnic z dumpu
    python3 tools/mobile/ui.py text 8594001020102      # psaní do zaostřeného pole
    python3 tools/mobile/ui.py key back
    python3 tools/mobile/ui.py wait "Uložit" --timeout 15
    python3 tools/mobile/ui.py open "product/<uuid>"   # skok rovnou na obrazovku
    python3 tools/mobile/ui.py log --clear
    python3 tools/mobile/ui.py screenshot /tmp/x.png   # vypíše JEN cestu, pro oko uživatele

Omezení, na která se naráží v praxi:

  * `adb shell input text` NEUMÍ diakritiku — pošle místo znaku nesmysl. Skript na ne-ASCII
    vstup upozorní; testovací data volit v ASCII (e-maily, EANy, ceny jimi jsou beztak).
  * `uiautomator dump` selhává hláškou „could not get idle state" tam, kde běží nekonečná
    animace — u téhle appky hlavně ScanScreen (náhled kamery) a průběžné indikátory. Skript
    dump opakuje; když ani pak neprojde, pomůže `ui.py setup` (vynuluje animation scales).
  * `resource-id` je u Compose skoro vždy prázdný (musel by se zapnout `testTagsAsResourceId`
    a rozdat `testTag`). Proto se prvky hledají podle textu a `contentDescription`, kterých má
    appka dost.

`adb` se hledá stejně jako v `start-dev.sh`: `sdk.dir` z `mobile/local.properties`, pak
`$ANDROID_HOME`/`$ANDROID_SDK_ROOT`, pak `~/Android/Sdk`, nakonec `adb` v PATH.
"""
import argparse
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import unicodedata
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PACKAGE = "cz.kvalitacena"
ACTIVITY = PACKAGE + "/.MainActivity"
# Extra, kterou MainActivity čte jen v debug buildu (viz DebugRouteIntent tamtéž).
ROUTE_EXTRA = "route"
DUMP_REMOTE_PATH = "/sdcard/kvalitacena-ui.xml"

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


def adb_binary():
    sdk_dir = None
    properties = os.path.join(ROOT, "mobile", "local.properties")
    if os.path.isfile(properties):
        with open(properties, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("sdk.dir="):
                    sdk_dir = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
                    break
    sdk_dir = sdk_dir or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") \
        or os.path.expanduser("~/Android/Sdk")
    candidate = os.path.join(sdk_dir, "platform-tools", "adb")
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
    result = subprocess.run(command, capture_output=True)
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

    @property
    def label(self):
        return self.text or self.desc

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

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
        label = self.label or "—"
        if self.text and self.desc and self.text != self.desc:
            label = "%s «%s»" % (self.text, self.desc)
        if self.resource_id:
            label += " #" + self.resource_id
        line = "%s  %s  @(%d,%d)" % (label, CLASS_SHORT.get(self.cls, self.cls.split(".")[-1].lower()),
                                     self.center[0], self.center[1])
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


def parse_nodes(xml):
    nodes = []
    for element in ET.fromstring(xml).iter("node"):
        node = Node(element.attrib)
        if node.bounds and node.area > 0:
            nodes.append(node)
    return nodes


def interesting(node):
    """Kontejnery bez popisku jsou šum — právě jejich vyhozením vzniká úspora oproti XML."""
    return bool(node.label) or bool(node.resource_id) or node.clickable or node.scrollable


def dump_lines(nodes, show_all=False):
    lines = []
    seen = set()
    for node in nodes:
        if not show_all and not interesting(node):
            continue
        line = node.render()
        if line in seen:
            continue
        seen.add(line)
        lines.append(line)
    return lines


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
    # Compose vrací zanořené uzly se stejným textem (klikatelný rodič + textový potomek) —
    # pro klepnutí je správně ten klikatelný, jinak nejmenší (nejkonkrétnější).
    clickable = [node for node in found if node.clickable]
    found = clickable or found
    found.sort(key=lambda node: node.area)
    unique = []
    for node in found:
        if not any(abs(node.center[0] - other.center[0]) < 8 and abs(node.center[1] - other.center[1]) < 8
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
    xml = dump_xml(args.device)
    if args.raw:
        print(xml)
        return
    lines = dump_lines(parse_nodes(xml), show_all=args.all)
    print("\n".join(lines) if lines else "(prázdná obrazovka)")


def cmd_tap(args):
    node = resolve_target(parse_nodes(dump_xml(args.device)), args.text, args.nth)
    x, y = node.center
    adb_shell(["input", "tap", str(x), str(y)], args.device)
    print("tap @(%d,%d) — %s" % (x, y, node.render()))


def cmd_tap_xy(args):
    adb_shell(["input", "tap", str(args.x), str(args.y)], args.device)
    print("tap @(%d,%d)" % (args.x, args.y))


def cmd_text(args):
    if any(ord(char) > 127 for char in args.value):
        print("Pozor: `input text` neumí diakritiku, text dorazí zkomolený. Zvol ASCII data.",
              file=sys.stderr)
    adb_shell(["input", "text", args.value.replace(" ", "%s")], args.device)
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
        nodes = parse_nodes(dump_xml(args.device))
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
    dump.add_argument("--all", action="store_true", help="i kontejnery bez popisku")
    dump.add_argument("--raw", action="store_true", help="původní XML (velké!)")
    dump.set_defaults(func=cmd_dump)

    tap = subparsers.add_parser("tap", help="klepne na prvek podle textu nebo popisu")
    tap.add_argument("text")
    tap.add_argument("--nth", type=int, help="která shoda, když je jich víc")
    tap.set_defaults(func=cmd_tap)

    tap_xy = subparsers.add_parser("tap-xy", help="klepne na souřadnice z dumpu")
    tap_xy.add_argument("x", type=int)
    tap_xy.add_argument("y", type=int)
    tap_xy.set_defaults(func=cmd_tap_xy)

    text = subparsers.add_parser("text", help="napíše text do zaostřeného pole (jen ASCII)")
    text.add_argument("value")
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
