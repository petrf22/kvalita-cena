import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.accessibilityservice.AccessibilityServiceInfo;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * Dump VŠECH oken obrazovky, ne jen aktivního — `uiautomator dump` čte jen
 * `getRootInActiveWindow()`, takže nevidí nabídku editovatelného comboboxu (Compose
 * ExposedDropdownMenu s PrimaryEditable kotvou otevírá nefokusovatelné okno, fokus zůstává
 * v textovém poli) a nepozná, kde je klávesnice.
 *
 * Běží přes `app_process` stejně jako samotný `uiautomator` (UiAutomationShellWrapper):
 * skrytý konstruktor UiAutomation(Looper, IUiAutomationConnection) přes reflexi. Výstup je
 * XML ve tvaru `uiautomator dump`, jen s jedním `<window>` na okno:
 *
 *   <hierarchy><window type="application" layer="3" bounds="[..]"><node .../></window>...</hierarchy>
 *
 * Volá ho `tools/mobile/ui.py` (dex se sestaví a nahraje sám při prvním použití).
 */
public final class WindowDump {

  public static void main(String[] args) throws Exception {
    // app_process hlavní Looper nepřipraví a UiAutomation si nad ním staví Handler.
    Looper.prepareMainLooper();
    HandlerThread thread = new HandlerThread("WindowDump");
    thread.start();
    UiAutomation automation = connect(thread.getLooper());
    try {
      AccessibilityServiceInfo info = automation.getServiceInfo();
      info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
          | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
          | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
      automation.setServiceInfo(info);
      try {
        automation.waitForIdle(300, 3000);
      } catch (java.util.concurrent.TimeoutException e) {
        // Nekonečná animace (náhled kamery) — dump i tak, jen bez čekání na klid.
      }

      StringBuilder out = new StringBuilder("<hierarchy>");
      List<AccessibilityWindowInfo> windows = automation.getWindows();
      // Uzel pod klávesnicí hlásí isVisibleToUser() == false — vyhodit by ho znamenalo ztratit
      // i popisek tlačítka, na které chce ui.py po zavření klávesnice klepnout.
      Rect ime = new Rect();
      for (AccessibilityWindowInfo window : windows) {
        if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
          window.getBoundsInScreen(ime);
        }
      }
      for (AccessibilityWindowInfo window : windows) {
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        out.append("<window type=\"").append(typeName(window.getType()))
            .append("\" layer=\"").append(window.getLayer())
            .append("\" active=\"").append(window.isActive())
            .append("\" bounds=\"").append(rect(bounds)).append("\">");
        AccessibilityNodeInfo root = window.getRoot();
        if (root != null) {
          node(root, ime, out);
        }
        out.append("</window>");
      }
      out.append("</hierarchy>");
      System.out.println(out);
    } finally {
      disconnect(automation);
      thread.quitSafely();
    }
  }

  private static UiAutomation connect(Looper looper) throws Exception {
    Class<?> connectionClass = Class.forName("android.app.UiAutomationConnection");
    Class<?> connectionInterface = Class.forName("android.app.IUiAutomationConnection");
    Object connection = connectionClass.getDeclaredConstructor().newInstance();
    Constructor<UiAutomation> constructor =
        UiAutomation.class.getDeclaredConstructor(Looper.class, connectionInterface);
    constructor.setAccessible(true);
    UiAutomation automation = constructor.newInstance(looper, connection);
    UiAutomation.class.getDeclaredMethod("connect").invoke(automation);
    return automation;
  }

  private static void disconnect(UiAutomation automation) {
    try {
      UiAutomation.class.getDeclaredMethod("disconnect").invoke(automation);
    } catch (Exception ignored) {
      // Proces stejně končí — odpojení je jen úklid.
    }
  }

  private static void node(AccessibilityNodeInfo info, Rect ime, StringBuilder out) {
    Rect bounds = new Rect();
    info.getBoundsInScreen(bounds);
    out.append("<node");
    attr(out, "text", info.getText());
    attr(out, "content-desc", info.getContentDescription());
    attr(out, "resource-id", info.getViewIdResourceName());
    attr(out, "class", info.getClassName());
    attr(out, "clickable", String.valueOf(info.isClickable()));
    attr(out, "scrollable", String.valueOf(info.isScrollable()));
    attr(out, "checked", String.valueOf(info.isChecked()));
    attr(out, "selected", String.valueOf(info.isSelected()));
    attr(out, "focused", String.valueOf(info.isFocused()));
    attr(out, "enabled", String.valueOf(info.isEnabled()));
    attr(out, "bounds", rect(bounds));
    out.append(">");
    for (int i = 0; i < info.getChildCount(); i++) {
      AccessibilityNodeInfo child = info.getChild(i);
      if (child == null) {
        continue;
      }
      Rect childBounds = new Rect();
      child.getBoundsInScreen(childBounds);
      if (child.isVisibleToUser() || Rect.intersects(childBounds, ime)) {
        node(child, ime, out);
      }
    }
    out.append("</node>");
  }

  private static void attr(StringBuilder out, String name, CharSequence value) {
    if (value == null) {
      return;
    }
    out.append(' ').append(name).append("=\"").append(escape(value.toString())).append('"');
  }

  private static String escape(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      switch (c) {
        case '&': escaped.append("&amp;"); break;
        case '<': escaped.append("&lt;"); break;
        case '>': escaped.append("&gt;"); break;
        case '"': escaped.append("&quot;"); break;
        case '\n': escaped.append("&#10;"); break;
        default:
          if (c >= 0x20 || c == '\t') {
            escaped.append(c);
          }
      }
    }
    return escaped.toString();
  }

  private static String rect(Rect r) {
    return "[" + r.left + "," + r.top + "][" + r.right + "," + r.bottom + "]";
  }

  private static String typeName(int type) {
    switch (type) {
      case AccessibilityWindowInfo.TYPE_APPLICATION: return "application";
      case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "ime";
      case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
      case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "overlay";
      case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "divider";
      default: return "other";
    }
  }
}
