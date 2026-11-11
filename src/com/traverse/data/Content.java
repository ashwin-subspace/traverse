package com.traverse.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the authored curriculum from assets into SQLite.
 *
 * The pack is validated at build time (tools/validate.js), so this is deliberately
 * trusting about shape and careful about one thing only: a content update must never
 * touch attempts, schedules or misconceptions. Content is replaceable; what the learner
 * earned is not.
 */
public class Content {

    private static final String ASSET = "curriculum.json";
    private static final String META_VERSION = "content_version";

    /** Import if the packaged content is newer than what is in the database. */
    public static void ensureLoaded(Context ctx) {
        Db db = Db.get(ctx);
        try {
            String raw = read(ctx);
            JSONObject root = new JSONObject(raw);
            String packed = String.valueOf(root.optInt("version", 1));
            String have = db.meta(META_VERSION, "");

            if (packed.equals(have) && db.topicCount() > 0) return;

            List<Model.Unit> units = parse(root);
            db.replaceContent(units);
            insertItems(db, root);
            db.setMeta(META_VERSION, packed);
        } catch (Exception e) {
            // A broken pack must not take the app down. The path will simply be empty,
            // and the build-time validator exists so this cannot ship.
            android.util.Log.e("Traverse", "content load failed", e);
        }
    }

    /** Force a reimport, keeping progress. Used by Setup when content looks wrong. */
    public static void reload(Context ctx) {
        Db.get(ctx).setMeta(META_VERSION, "");
        ensureLoaded(ctx);
    }

    private static String read(Context ctx) throws Exception {
        InputStream in = ctx.getAssets().open(ASSET);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        r.close();
        return b.toString();
    }

    private static List<Model.Unit> parse(JSONObject root) {
        List<Model.Unit> units = new ArrayList<Model.Unit>();
        JSONArray us = root.optJSONArray("units");
        for (int i = 0; us != null && i < us.length(); i++) {
            JSONObject uo = us.optJSONObject(i);
            if (uo == null) continue;
            Model.Unit u = new Model.Unit();
            u.id = uo.optString("id");
            u.title = uo.optString("title");
            u.blurb = uo.optString("blurb", "");
            u.ord = uo.optInt("ord", i + 1);

            JSONArray ts = uo.optJSONArray("topics");
            for (int j = 0; ts != null && j < ts.length(); j++) {
                JSONObject to = ts.optJSONObject(j);
                if (to == null) continue;
                Model.Topic t = new Model.Topic();
                t.id = to.optString("id");
                t.unitId = u.id;
                t.title = to.optString("title");
                t.ord = to.optInt("ord", j + 1);
                t.lesson = to.optJSONArray("lesson");
                JSONArray pr = to.optJSONArray("prereqs");
                for (int k = 0; pr != null && k < pr.length(); k++) t.prereqs.add(pr.optString(k));
                u.topics.add(t);
            }
            units.add(u);
        }
        return units;
    }

    private static void insertItems(Db db, JSONObject root) {
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            JSONArray us = root.optJSONArray("units");
            for (int i = 0; us != null && i < us.length(); i++) {
                JSONArray ts = us.optJSONObject(i).optJSONArray("topics");
                for (int j = 0; ts != null && j < ts.length(); j++) {
                    JSONObject to = ts.optJSONObject(j);
                    String topicId = to.optString("id");

                    JSONArray qs = to.optJSONArray("quiz");
                    for (int k = 0; qs != null && k < qs.length(); k++) {
                        JSONObject q = qs.optJSONObject(k);
                        db.insertItem(d, topicId, "quiz", q, q.optInt("difficulty", 1), k);
                    }
                    JSONArray ps = to.optJSONArray("problems");
                    for (int k = 0; ps != null && k < ps.length(); k++) {
                        JSONObject p = ps.optJSONObject(k);
                        db.insertItem(d, topicId, "problem", p, p.optInt("difficulty", 1), k);
                    }
                }
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }
}
