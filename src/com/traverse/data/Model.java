package com.traverse.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Plain holders. Content comes from the pack; progress is computed from attempts. */
public class Model {

    public static class Unit {
        public String id, title, blurb;
        public int ord;
        public List<Topic> topics = new ArrayList<Topic>();
    }

    public static class Topic {
        public String id, unitId, title;
        public int ord;
        public List<String> prereqs = new ArrayList<String>();
        public JSONArray lesson;

        // derived, never stored as truth
        public int mastery;
        public int dueCount;
        public int itemCount;
        public boolean unlocked;
    }

    /** A quiz question. `payload` is the authored JSON. */
    public static class Item {
        public String id, topicId, kind;
        public int difficulty, ord;
        public JSONObject payload;

        public String stem() { return payload.optString("stem", ""); }
        public String type() { return payload.optString("type", "mcq"); }
        public String explain() { return payload.optString("explain", ""); }
        public int answer() { return payload.optInt("answer", 0); }
        public boolean isOrder() { return "order".equals(type()); }

        public List<String> options() {
            List<String> out = new ArrayList<String>();
            JSONArray a = payload.optJSONArray("options");
            for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
            return out;
        }

        /** Correct tap sequence for an order question. Empty when this is not one. */
        public int[] orderAnswer() {
            JSONArray a = payload.optJSONArray("orderAnswer");
            if (a == null) return new int[0];
            int[] out = new int[a.length()];
            for (int i = 0; i < a.length(); i++) out[i] = a.optInt(i, -1);
            return out;
        }

        /** Code for the learner's chosen language, or the other one if only that exists. */
        public String code(String lang) {
            JSONObject c = payload.optJSONObject("code");
            if (c == null) return "";
            String want = c.optString(lang, "");
            if (want.length() > 0) return want;
            return c.optString("java", c.optString("python", ""));
        }

        /** What picking this option means, as written by the author. Empty if none. */
        public String misconceptionFor(int option) {
            JSONObject m = payload.optJSONObject("misconception");
            return m == null ? "" : m.optString(String.valueOf(option), "");
        }
    }

    public static class Miscon {
        public long id;
        public String topicId, itemId, text;
        public boolean open;
        public int hits;
        public long firstSeen, lastSeen;
    }

    /** One row of the review queue. */
    public static class Due {
        public Item item;
        public String topicTitle;
        public long due;
        public int reps;
    }
}
