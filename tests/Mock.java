import java.lang.reflect.*;
import java.util.*;

/** Tiny proxy mocks for the game's interfaces: a map from method name to a value or a Fn. */
public final class Mock {
    public interface Fn { Object call(Object[] a); }
    @SuppressWarnings("unchecked")
    public static <T> T of(Class<T> k, final Map<String, Object> o) {
        return (T) Proxy.newProxyInstance(k.getClassLoader(), new Class<?>[]{k}, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (o.containsKey(m.getName())) {
                    Object v = o.get(m.getName());
                    if (v instanceof Fn) return ((Fn) v).call(a);
                    return v;
                }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "mock " + k.getSimpleName();
                return def(m.getReturnType());
            }
        });
    }
    static Object def(Class<?> r) {
        if (r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == String.class) return "";
        if (r == List.class || r == Collection.class) return new ArrayList<Object>();
        if (r == Map.class) return new HashMap<Object, Object>();
        if (r == Set.class) return new HashSet<Object>();
        if (r.isInterface() && !r.getName().startsWith("java.")) return of(r, new HashMap<String, Object>());
        return null;
    }
    public static Map<String, Object> m(Object... kv) {
        Map<String, Object> x = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) x.put((String) kv[i], kv[i + 1]);
        return x;
    }

    /** A cargo that really holds commodities (enough for trades and transactions). */
    public static com.fs.starfarer.api.campaign.CargoAPI cargo(final Map<String, Float> held, Map<String, Object> extra) {
        Map<String, Object> o = m(
            "getCommodityQuantity", (Fn) a -> { Float v = held.get((String) a[0]); return v == null ? 0f : v; },
            "addCommodity", (Fn) a -> { String id = (String) a[0]; float q = ((Number) a[1]).floatValue(); held.put(id, (held.containsKey(id) ? held.get(id) : 0f) + q); return null; },
            "removeCommodity", (Fn) a -> { String id = (String) a[0]; float q = ((Number) a[1]).floatValue(); held.put(id, (held.containsKey(id) ? held.get(id) : 0f) - q); return null; });
        if (extra != null) o.putAll(extra);
        return of(com.fs.starfarer.api.campaign.CargoAPI.class, o);
    }
}
