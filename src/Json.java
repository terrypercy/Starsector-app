package claudecaptain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer for state.txt (maps keep insertion order). Pure. */
public final class Json {

    private Json() {}

    public static Map<String, Object> obj() { return new LinkedHashMap<String, Object>(); }
    public static List<Object> arr() { return new ArrayList<Object>(); }

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(o, b, 0);
        return b.toString();
    }

    static void indent(StringBuilder b, int n) { b.append('\n'); for (int i = 0; i < n; i++) b.append("  "); }

    @SuppressWarnings("unchecked")
    static void write(Object o, StringBuilder b, int depth) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String) { str((String) o, b); return; }
        if (o instanceof Boolean) { b.append(o.toString()); return; }
        if (o instanceof Float || o instanceof Double) {
            double v = ((Number) o).doubleValue();
            if (Double.isNaN(v) || Double.isInfinite(v)) { b.append("null"); return; }
            if (v == Math.rint(v) && Math.abs(v) < 1e15) b.append((long) v);
            else b.append(String.format(java.util.Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", ""));
            return;
        }
        if (o instanceof Number) { b.append(o.toString()); return; }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                indent(b, depth + 1);
                str(e.getKey(), b);
                b.append(": ");
                write(e.getValue(), b, depth + 1);
            }
            indent(b, depth);
            b.append('}');
            return;
        }
        if (o instanceof Collection) {
            Collection<Object> c = (Collection<Object>) o;
            if (c.isEmpty()) { b.append("[]"); return; }
            boolean simple = true;
            for (Object x : c) if (x instanceof Map || x instanceof Collection) simple = false;
            b.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) b.append(simple ? ", " : ",");
                first = false;
                if (!simple) indent(b, depth + 1);
                write(x, b, depth + 1);
            }
            if (!simple) indent(b, depth);
            b.append(']');
            return;
        }
        str(o.toString(), b);
    }

    static void str(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }
}
