package com.traverse.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mastery is computed from evidence, never authored and never stored as truth.
 *
 * The distinction that matters: answering correctly straight after reading the
 * explanation proves you can copy. Answering correctly weeks later, cold, proves you
 * learned it. Those two are weighted very differently, which is what stops the number
 * from being farmable.
 */
public class Mastery {

    private static final long DAY = 24L * 60 * 60 * 1000;

    // SM-2 style, with the constants kept blunt and readable
    private static final double EASE_START = 2.5;
    private static final double EASE_FLOOR = 1.3;
    private static final double EASE_UP = 0.10;
    private static final double EASE_DOWN = 0.22;

    /**
     * 0-100 for one topic. Weighted toward cold correctness and toward harder items,
     * so grinding the easy questions cannot carry the score.
     */
    public static int forTopic(Db db, String topicId) {
        int[] s = db.attemptStats(topicId);
        int correct = s[0], total = s[1], coldCorrect = s[2], coldTotal = s[3];
        int wCorrect = s[4], wTotal = s[5];
        if (total == 0) return 0;

        double overall = correct / (double) total;
        double weighted = wTotal == 0 ? overall : wCorrect / (double) wTotal;
        double cold = coldTotal == 0 ? 0 : coldCorrect / (double) coldTotal;

        // Coverage stops three lucky answers from reading as mastery of a whole topic.
        int items = db.itemCount(topicId);
        double coverage = items == 0 ? 0 : Math.min(1.0, total / (double) items);

        double score = 0.30 * overall + 0.25 * weighted + 0.45 * cold;
        score *= (0.55 + 0.45 * coverage);

        int out = (int) Math.round(score * 100);
        if (out < 0) out = 0;
        if (out > 100) out = 100;
        return out;
    }

    /** A topic opens once everything it depends on is genuinely underway. */
    public static final int UNLOCK_FLOOR = 40;

    public static void applyUnlocks(List<Model.Unit> units) {
        Map<String, Integer> mastery = new HashMap<String, Integer>();
        for (int i = 0; i < units.size(); i++) {
            for (int j = 0; j < units.get(i).topics.size(); j++) {
                Model.Topic t = units.get(i).topics.get(j);
                mastery.put(t.id, Integer.valueOf(t.mastery));
            }
        }
        for (int i = 0; i < units.size(); i++) {
            for (int j = 0; j < units.get(i).topics.size(); j++) {
                Model.Topic t = units.get(i).topics.get(j);
                boolean open = true;
                for (int p = 0; p < t.prereqs.size(); p++) {
                    Integer m = mastery.get(t.prereqs.get(p));
                    if (m == null || m.intValue() < UNLOCK_FLOOR) { open = false; break; }
                }
                t.unlocked = open;
            }
        }
    }

    /**
     * Reschedule one item after an answer.
     *
     * Right: the gap grows by the ease factor, so things you know stop interrupting you.
     * Wrong: it comes back today, and the ease drops so it will keep coming back sooner
     * than its neighbours until it sticks.
     */
    public static void reschedule(Db db, Model.Item it, boolean correct) {
        double[] s = db.sched(it.id);
        double ease = s == null ? EASE_START : s[0];
        int interval = s == null ? 0 : (int) s[1];
        int reps = s == null ? 0 : (int) s[3];
        int lapses = s == null ? 0 : (int) s[4];

        if (correct) {
            reps++;
            if (reps == 1) interval = 1;
            else if (reps == 2) interval = 3;
            else interval = (int) Math.round(Math.max(1, interval) * ease);
            ease = Math.min(3.0, ease + EASE_UP);
        } else {
            reps = 0;
            lapses++;
            interval = 0;                      // back in today's queue
            ease = Math.max(EASE_FLOOR, ease - EASE_DOWN);
        }

        long due = System.currentTimeMillis() + interval * DAY;
        db.putSched(it.id, it.topicId, ease, interval, due, reps, lapses);
    }

    /** Plain-language version of the schedule, so the app never looks arbitrary. */
    public static String explainSchedule(Db db, String itemId) {
        double[] s = db.sched(itemId);
        if (s == null) return "New. It will come back tomorrow.";
        int interval = (int) s[1];
        long due = (long) s[2];
        long now = System.currentTimeMillis();
        if (interval == 0) return "You missed this. It stays in today's queue.";
        if (due <= now) return "Due now.";
        long days = Math.max(1, Math.round((due - now) / (double) DAY));
        return "Solid for now. Back in " + days + (days == 1 ? " day." : " days.");
    }

    public static String band(int mastery) {
        if (mastery == 0) return "Not started";
        if (mastery < 35) return "Shaky";
        if (mastery < 60) return "Getting there";
        if (mastery < 80) return "Solid";
        return "Cold-solid";
    }
}
