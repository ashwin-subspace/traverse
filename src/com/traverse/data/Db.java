package com.traverse.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Content is authored and read-only; progress is earned and never overwritten by a
 * content update. Those are two different lifetimes in one file, so every content
 * refresh replaces unit/topic/item and leaves attempt/sched/miscon strictly alone.
 */
public class Db extends SQLiteOpenHelper {

    static final long DAY = 24L * 60 * 60 * 1000;
    private static final int VERSION = 1;
    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) { super(c, "traverse.db", null, VERSION); }

    @Override public void onCreate(SQLiteDatabase d) {
        // ---- authored content, replaceable ----
        d.execSQL("CREATE TABLE unit (id TEXT PRIMARY KEY, title TEXT, blurb TEXT, ord INTEGER)");
        d.execSQL("CREATE TABLE topic (id TEXT PRIMARY KEY, unit_id TEXT, title TEXT, "
                + "ord INTEGER, prereqs TEXT, lesson TEXT)");
        d.execSQL("CREATE TABLE item (id TEXT PRIMARY KEY, topic_id TEXT, kind TEXT, "
                + "payload TEXT, difficulty INTEGER, ord INTEGER)");

        // ---- earned progress, never replaced ----
        d.execSQL("CREATE TABLE attempt (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id TEXT, "
                + "topic_id TEXT, at INTEGER, correct INTEGER, hints INTEGER, ms INTEGER, "
                + "chosen TEXT, cold INTEGER)");
        d.execSQL("CREATE TABLE sched (item_id TEXT PRIMARY KEY, topic_id TEXT, ease REAL, "
                + "interval_days INTEGER, due INTEGER, reps INTEGER, lapses INTEGER)");
        d.execSQL("CREATE TABLE miscon (id INTEGER PRIMARY KEY AUTOINCREMENT, topic_id TEXT, "
                + "item_id TEXT, text TEXT, open INTEGER, hits INTEGER, "
                + "first_seen INTEGER, last_seen INTEGER)");
        d.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");

        d.execSQL("CREATE INDEX idx_item_topic ON item (topic_id)");
        d.execSQL("CREATE INDEX idx_attempt_item ON attempt (item_id)");
        d.execSQL("CREATE INDEX idx_attempt_topic ON attempt (topic_id)");
        d.execSQL("CREATE INDEX idx_sched_due ON sched (due)");
    }

    /**
     * Versioned migrations, fall-through on purpose so an old install catches up one
     * step at a time. Empty in v1, but the shape is here because adding it later is
     * how you lose people's data.
     */
    @Override public void onUpgrade(SQLiteDatabase d, int from, int to) {
        switch (from) {
            default: break;
        }
    }

    void addColumn(SQLiteDatabase d, String table, String col, String spec) {
        try {
            d.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " " + spec);
        } catch (Exception alreadyThere) { }
    }

    // ---- meta -------------------------------------------------------------

    public String meta(String k, String fallback) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k=?",
                new String[]{k});
        String v = c.moveToFirst() ? c.getString(0) : fallback;
        c.close();
        return v;
    }

    public void setMeta(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("meta", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---- content ----------------------------------------------------------

    /** Called by Content after parsing the pack. Progress tables are untouched. */
    void replaceContent(List<Model.Unit> units) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("item", null, null);
            d.delete("topic", null, null);
            d.delete("unit", null, null);

            for (int u = 0; u < units.size(); u++) {
                Model.Unit unit = units.get(u);
                ContentValues uv = new ContentValues();
                uv.put("id", unit.id);
                uv.put("title", unit.title);
                uv.put("blurb", unit.blurb);
                uv.put("ord", unit.ord);
                d.insert("unit", null, uv);

                for (int t = 0; t < unit.topics.size(); t++) {
                    Model.Topic topic = unit.topics.get(t);
                    ContentValues tv = new ContentValues();
                    tv.put("id", topic.id);
                    tv.put("unit_id", unit.id);
                    tv.put("title", topic.title);
                    tv.put("ord", topic.ord);
                    tv.put("prereqs", join(topic.prereqs));
                    tv.put("lesson", topic.lesson == null ? "[]" : topic.lesson.toString());
                    d.insert("topic", null, tv);
                }
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    void insertItem(SQLiteDatabase d, String topicId, String kind, JSONObject payload,
                    int difficulty, int ord) {
        ContentValues v = new ContentValues();
        v.put("id", payload.optString("id"));
        v.put("topic_id", topicId);
        v.put("kind", kind);
        v.put("payload", payload.toString());
        v.put("difficulty", difficulty);
        v.put("ord", ord);
        d.insertWithOnConflict("item", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static String join(List<String> xs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) b.append(",");
            b.append(xs.get(i));
        }
        return b.toString();
    }

    // ---- reading the path -------------------------------------------------

    public List<Model.Unit> path() {
        List<Model.Unit> units = new ArrayList<Model.Unit>();
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = d.rawQuery("SELECT id,title,blurb,ord FROM unit ORDER BY ord", null);
        while (c.moveToNext()) {
            Model.Unit u = new Model.Unit();
            u.id = c.getString(0); u.title = c.getString(1);
            u.blurb = c.getString(2); u.ord = c.getInt(3);
            units.add(u);
        }
        c.close();

        for (int i = 0; i < units.size(); i++) {
            Model.Unit u = units.get(i);
            Cursor t = d.rawQuery("SELECT id,title,ord,prereqs FROM topic WHERE unit_id=? "
                    + "ORDER BY ord", new String[]{u.id});
            while (t.moveToNext()) {
                Model.Topic k = new Model.Topic();
                k.id = t.getString(0); k.unitId = u.id; k.title = t.getString(1);
                k.ord = t.getInt(2);
                String pr = t.getString(3);
                if (pr != null && pr.length() > 0) {
                    String[] parts = pr.split(",");
                    for (int j = 0; j < parts.length; j++) k.prereqs.add(parts[j]);
                }
                u.topics.add(k);
            }
            t.close();
        }

        // derived fields, computed rather than stored
        for (int i = 0; i < units.size(); i++) {
            for (int j = 0; j < units.get(i).topics.size(); j++) {
                Model.Topic k = units.get(i).topics.get(j);
                k.mastery = Mastery.forTopic(this, k.id);
                k.dueCount = dueCount(k.id);
                k.itemCount = itemCount(k.id);
            }
        }
        Mastery.applyUnlocks(units);
        return units;
    }

    public Model.Topic topic(String id) {
        List<Model.Unit> units = path();
        for (int i = 0; i < units.size(); i++) {
            for (int j = 0; j < units.get(i).topics.size(); j++) {
                Model.Topic k = units.get(i).topics.get(j);
                if (k.id.equals(id)) {
                    Cursor c = getReadableDatabase().rawQuery(
                            "SELECT lesson FROM topic WHERE id=?", new String[]{id});
                    if (c.moveToFirst()) {
                        try { k.lesson = new JSONArray(c.getString(0)); }
                        catch (Exception e) { k.lesson = new JSONArray(); }
                    }
                    c.close();
                    return k;
                }
            }
        }
        return null;
    }

    public List<Model.Item> items(String topicId, String kind) {
        List<Model.Item> out = new ArrayList<Model.Item>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,topic_id,kind,payload,difficulty,ord FROM item "
                        + "WHERE topic_id=? AND kind=? ORDER BY ord",
                new String[]{topicId, kind});
        while (c.moveToNext()) out.add(read(c));
        c.close();
        return out;
    }

    public Model.Item item(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,topic_id,kind,payload,difficulty,ord FROM item WHERE id=?",
                new String[]{id});
        Model.Item it = c.moveToFirst() ? read(c) : null;
        c.close();
        return it;
    }

    private Model.Item read(Cursor c) {
        Model.Item it = new Model.Item();
        it.id = c.getString(0); it.topicId = c.getString(1); it.kind = c.getString(2);
        try { it.payload = new JSONObject(c.getString(3)); }
        catch (Exception e) { it.payload = new JSONObject(); }
        it.difficulty = c.getInt(4); it.ord = c.getInt(5);
        return it;
    }

    public int itemCount(String topicId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM item WHERE topic_id=?", new String[]{topicId});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int topicCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM topic", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    // ---- progress: append-only evidence -----------------------------------

    /**
     * One answered question. Never updated, only appended -- mastery is always
     * recomputed from this table, so changing the scoring cannot corrupt history.
     *
     * @param cold answered with no hints and not straight after reading the explanation.
     *             Cold evidence is what mastery weights most heavily.
     */
    public void recordAttempt(Model.Item it, boolean correct, int hints, long ms,
                              String chosen, boolean cold) {
        ContentValues v = new ContentValues();
        v.put("item_id", it.id);
        v.put("topic_id", it.topicId);
        v.put("at", System.currentTimeMillis());
        v.put("correct", correct ? 1 : 0);
        v.put("hints", hints);
        v.put("ms", ms);
        v.put("chosen", chosen);
        v.put("cold", cold ? 1 : 0);
        getWritableDatabase().insert("attempt", null, v);
        Mastery.reschedule(this, it, correct);
    }

    /** {correct, total, coldCorrect, coldTotal, weightedCorrect, weightedTotal}. */
    public int[] attemptStats(String topicId) {
        int[] s = new int[6];
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT a.correct, a.cold, i.difficulty FROM attempt a "
                        + "JOIN item i ON i.id = a.item_id WHERE a.topic_id=?",
                new String[]{topicId});
        while (c.moveToNext()) {
            int correct = c.getInt(0), cold = c.getInt(1), diff = Math.max(1, c.getInt(2));
            s[1]++;
            if (correct == 1) s[0]++;
            if (cold == 1) { s[3]++; if (correct == 1) s[2]++; }
            s[5] += diff;
            if (correct == 1) s[4] += diff;
        }
        c.close();
        return s;
    }

    public boolean hasAttempt(String itemId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM attempt WHERE item_id=? LIMIT 1", new String[]{itemId});
        boolean any = c.moveToFirst();
        c.close();
        return any;
    }

    // ---- scheduling -------------------------------------------------------

    /** {ease, intervalDays, due, reps, lapses}, or null if never scheduled. */
    public double[] sched(String itemId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ease, interval_days, due, reps, lapses FROM sched WHERE item_id=?",
                new String[]{itemId});
        double[] s = null;
        if (c.moveToFirst()) {
            s = new double[]{c.getDouble(0), c.getInt(1), c.getLong(2),
                             c.getInt(3), c.getInt(4)};
        }
        c.close();
        return s;
    }

    void putSched(String itemId, String topicId, double ease, int intervalDays,
                  long due, int reps, int lapses) {
        ContentValues v = new ContentValues();
        v.put("item_id", itemId);
        v.put("topic_id", topicId);
        v.put("ease", ease);
        v.put("interval_days", intervalDays);
        v.put("due", due);
        v.put("reps", reps);
        v.put("lapses", lapses);
        getWritableDatabase().insertWithOnConflict("sched", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int dueCount(String topicId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM sched s JOIN item i ON i.id = s.item_id "
                        + "WHERE s.topic_id=? AND i.kind='quiz' AND s.due<=?",
                new String[]{topicId, String.valueOf(System.currentTimeMillis())});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int dueTotal() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM sched s JOIN item i ON i.id = s.item_id "
                        + "WHERE i.kind='quiz' AND s.due<=?",
                new String[]{String.valueOf(System.currentTimeMillis())});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /**
     * True when a previous successful gap has elapsed. A first look, an early
     * revisit, or the immediate retry of a miss is not cold: those can be copied.
     * Cold is what mastery refuses to let you farm.
     */
    public boolean isColdDue(String itemId) {
        double[] s = sched(itemId);
        if (s == null) return false;
        int interval = (int) s[1];
        long due = (long) s[2];
        return interval > 0 && due <= System.currentTimeMillis();
    }

    public int unseenQuizCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM item WHERE kind='quiz' AND id NOT IN "
                        + "(SELECT item_id FROM attempt)", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** Answered questions, shakiest first, for a topic with nothing new and nothing due. */
    public List<Model.Item> weakest(String topicId, int limit) {
        List<Model.Item> out = new ArrayList<Model.Item>();
        int n = limit < 1 ? 1 : limit;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT i.id,i.topic_id,i.kind,i.payload,i.difficulty,i.ord FROM item i "
                        + "LEFT JOIN sched s ON s.item_id = i.id "
                        + "WHERE i.topic_id=? AND i.kind='quiz' "
                        + "ORDER BY COALESCE(s.ease, 2.5) ASC, COALESCE(s.lapses, 0) DESC, "
                        + "i.ord ASC LIMIT " + n,
                new String[]{topicId});
        while (c.moveToNext()) out.add(read(c));
        c.close();
        return out;
    }

    /** The review queue: most overdue first, so nothing rots at the bottom. */
    public List<Model.Due> dueQueue(int limit) {
        List<Model.Due> out = new ArrayList<Model.Due>();
        int n = limit < 1 ? 1 : limit;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT s.item_id, s.due, s.reps, t.title FROM sched s "
                        + "JOIN topic t ON t.id = s.topic_id "
                        + "JOIN item i ON i.id = s.item_id "
                        + "WHERE i.kind='quiz' AND s.due<=? ORDER BY s.due ASC LIMIT " + n,
                new String[]{String.valueOf(System.currentTimeMillis())});
        while (c.moveToNext()) {
            Model.Due d = new Model.Due();
            d.item = item(c.getString(0));
            d.due = c.getLong(1);
            d.reps = c.getInt(2);
            d.topicTitle = c.getString(3);
            if (d.item != null) out.add(d);
        }
        c.close();
        return out;
    }

    /** Never-attempted questions, easiest first, so practice starts somewhere winnable. */
    public List<Model.Item> unseen(String topicId) {
        List<Model.Item> out = new ArrayList<Model.Item>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT i.id,i.topic_id,i.kind,i.payload,i.difficulty,i.ord FROM item i "
                        + "WHERE i.topic_id=? AND i.kind='quiz' AND i.id NOT IN "
                        + "(SELECT item_id FROM attempt) ORDER BY i.difficulty, i.ord",
                new String[]{topicId});
        while (c.moveToNext()) out.add(read(c));
        c.close();
        return out;
    }

    // ---- misconceptions: authored, not inferred ---------------------------

    /** Picking a distractor files exactly what the author said that choice means. */
    public void fileMisconception(Model.Item it, int chosenOption) {
        String text = it.misconceptionFor(chosenOption);
        if (text == null || text.trim().length() == 0) return;
        SQLiteDatabase d = getWritableDatabase();
        long now = System.currentTimeMillis();
        Cursor c = d.rawQuery("SELECT id,hits FROM miscon WHERE item_id=? AND text=?",
                new String[]{it.id, text});
        if (c.moveToFirst()) {
            long id = c.getLong(0);
            int hits = c.getInt(1) + 1;
            c.close();
            ContentValues v = new ContentValues();
            v.put("hits", hits);
            v.put("open", 1);
            v.put("last_seen", now);
            d.update("miscon", v, "id=?", new String[]{String.valueOf(id)});
            return;
        }
        c.close();
        ContentValues v = new ContentValues();
        v.put("topic_id", it.topicId);
        v.put("item_id", it.id);
        v.put("text", text);
        v.put("open", 1);
        v.put("hits", 1);
        v.put("first_seen", now);
        v.put("last_seen", now);
        d.insert("miscon", null, v);
    }

    /** Answering the same item correctly closes what its distractor opened. */
    public void clearMisconceptions(String itemId) {
        ContentValues v = new ContentValues();
        v.put("open", 0);
        getWritableDatabase().update("miscon", v, "item_id=? AND open=1",
                new String[]{itemId});
    }

    /** Open misconceptions, most repeated first. Pass null for every topic. */
    public List<Model.Miscon> misconceptions(String topicId) {
        List<Model.Miscon> out = new ArrayList<Model.Miscon>();
        String sql = "SELECT id,topic_id,item_id,text,open,hits,first_seen,last_seen "
                + "FROM miscon WHERE open=1";
        String[] args = null;
        if (topicId != null) { sql += " AND topic_id=?"; args = new String[]{topicId}; }
        sql += " ORDER BY hits DESC, last_seen DESC";
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        while (c.moveToNext()) {
            Model.Miscon m = new Model.Miscon();
            m.id = c.getLong(0); m.topicId = c.getString(1); m.itemId = c.getString(2);
            m.text = c.getString(3); m.open = c.getInt(4) == 1; m.hits = c.getInt(5);
            m.firstSeen = c.getLong(6); m.lastSeen = c.getLong(7);
            out.add(m);
        }
        c.close();
        return out;
    }

    // ---- habit ------------------------------------------------------------

    /** Consecutive days with at least one attempt, ending today or yesterday. */
    public int streak() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT date(at/1000,'unixepoch','localtime') FROM attempt "
                        + "ORDER BY 1 DESC LIMIT 400", null);
        java.util.HashSet<String> days = new java.util.HashSet<String>();
        while (c.moveToNext()) days.add(c.getString(0));
        c.close();
        if (days.isEmpty()) return 0;
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (!days.contains(f.format(cal.getTime()))) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1);
            if (!days.contains(f.format(cal.getTime()))) return 0;
        }
        int n = 0;
        while (days.contains(f.format(cal.getTime()))) {
            n++;
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1);
        }
        return n;
    }

    public int answeredToday() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM attempt WHERE date(at/1000,'unixepoch','localtime') "
                        + "= date('now','localtime')", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int attemptTotal() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM attempt", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public void wipeProgress() {
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("DELETE FROM attempt");
        d.execSQL("DELETE FROM sched");
        d.execSQL("DELETE FROM miscon");
    }
}
