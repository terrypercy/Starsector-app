package claudecaptain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 1.0.9 (Nov 2): which ships the second-in-command sends after a fleeing enemy (the player's rule: post-battle pursuits
 * go to the second-in-command, with only the fastest, most combat-ready ships). Pure logic, unit-tested.
 *
 * Ships under minCr are left behind. The rest go fastest first (ties: higher CR first) until their deployment points
 * reach strength x the enemy's fleet points; when even all of them fall short, all of them go.
 */
public final class PursuitMath {

    private PursuitMath() {}

    /** One ship that could pursue. */
    public static final class Cand {
        public final String name;
        public final float speed, cr, dp;
        public Cand(String name, float speed, float cr, float dp) { this.name = name; this.speed = speed; this.cr = cr; this.dp = dp; }
    }

    /** Indexes into cands of the ships to send (empty when none qualifies). */
    public static List<Integer> pick(final List<Cand> cands, float enemyFP, float minCr, float strength) {
        List<Integer> ok = new ArrayList<Integer>();
        for (int i = 0; i < cands.size(); i++) if (cands.get(i).cr >= minCr) ok.add(i);
        Collections.sort(ok, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                Cand x = cands.get(a), y = cands.get(b);
                if (x.speed != y.speed) return Float.compare(y.speed, x.speed);
                return Float.compare(y.cr, x.cr);
            }
        });
        float need = Math.max(1f, enemyFP) * Math.max(0f, strength);
        List<Integer> out = new ArrayList<Integer>();
        float dp = 0f;
        for (Integer i : ok) {
            if (dp >= need && !out.isEmpty()) break;
            out.add(i);
            dp += cands.get(i).dp;
        }
        return out;
    }
}
