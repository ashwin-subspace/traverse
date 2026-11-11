package com.traverse;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.traverse.data.Db;
import com.traverse.data.Model;
import com.traverse.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The material. Blocks are rendered rather than dumped as one string, so code keeps a
 * monospace face and its own horizontal scroll, and a callout reads as a callout.
 *
 * Code blocks are filtered by the learner's chosen language: showing Java to someone
 * revising in Python is noise, not thoroughness.
 */
public class LessonActivity extends Activity {

    private LinearLayout host;
    private String topicId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_lesson);

        topicId = getIntent().getStringExtra("topicId");
        host = (LinearLayout) findViewById(R.id.lessonHost);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnCheck).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(LessonActivity.this, QuizActivity.class);
                i.putExtra("topicId", topicId);
                startActivity(i);
                finish();
            }
        });
        findViewById(R.id.langChip).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String next = "python".equals(Prefs.lang(LessonActivity.this)) ? "java" : "python";
                Prefs.setLang(LessonActivity.this, next);
                render();
            }
        });

        render();
    }

    private void render() {
        Model.Topic t = Db.get(this).topic(topicId);
        if (t == null) { finish(); return; }

        while (host.getChildCount() > 1) host.removeViewAt(host.getChildCount() - 1);

        String lang = Prefs.lang(this);
        String label = "python".equals(lang) ? "Python" : "Java";
        ((TextView) findViewById(R.id.lTitle)).setText(t.title);
        TextView chip = (TextView) findViewById(R.id.langChip);
        chip.setText(label);
        chip.setContentDescription("Language, " + label + ". Tap to switch.");

        JSONArray blocks = t.lesson == null ? new JSONArray() : t.lesson;
        boolean hasChosen = hasLangVariant(blocks, lang);
        boolean anyCode = hasLangVariant(blocks, "java") || hasLangVariant(blocks, "python");
        if (!hasChosen && anyCode) {
            String other = "python".equals(lang) ? "Java" : "Python";
            host.addView(callout("This lesson has no " + label + " samples, so the "
                    + other + " ones are shown.", "info"));
        }
        int added = 0;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) continue;
            String type = b.optString("type");

            if ("code".equals(type)) {
                String blockLang = b.optString("lang", "java");
                if (hasChosen && !blockLang.equals(lang)) continue;
                host.addView(code(b.optString("src")));
                added++;
            } else if ("callout".equals(type)) {
                host.addView(callout(b.optString("md"), b.optString("tone", "info")));
                added++;
            } else {
                host.addView(text(b.optString("md")));
                added++;
            }
        }
        if (added == 0) host.addView(text("This lesson has no written material."));
    }

    /** Only filter code blocks when the lesson actually offers the chosen language. */
    private boolean hasLangVariant(JSONArray blocks, String lang) {
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b != null && "code".equals(b.optString("type"))
                    && lang.equals(b.optString("lang", "java"))) return true;
        }
        return false;
    }

    private TextView text(String md) {
        TextView v = new TextView(this);
        v.setText(strip(md));
        v.setTextSize(16.5f);
        v.setLineSpacing(dp(6), 1f);
        v.setTextColor(getResources().getColor(R.color.text));
        v.setPadding(0, dp(6), 0, dp(14));
        return v;
    }

    private View code(String src) {
        TextView v = new TextView(this);
        v.setText(src);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(13f);
        v.setTextColor(getResources().getColor(R.color.text));
        v.setPadding(dp(16), dp(16), dp(16), dp(16));
        v.setLineSpacing(dp(3), 1f);
        v.setHorizontallyScrolling(true);

        // Wide code scrolls inside its own container rather than pushing the page sideways.
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setBackgroundResource(R.drawable.card);
        sc.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        sc.setLayoutParams(lp);
        return sc;
    }

    private View callout(String md, String tone) {
        TextView v = new TextView(this);
        v.setText(strip(md));
        v.setTextSize(15f);
        v.setLineSpacing(dp(5), 1f);
        v.setTextColor(getResources().getColor(
                "warn".equals(tone) ? R.color.warm : R.color.accent));
        v.setBackgroundResource(R.drawable.card);
        v.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        v.setLayoutParams(lp);
        v.setGravity(Gravity.START);
        return v;
    }

    /** A TextView shows asterisks literally, so emphasis markers are removed, not rendered. */
    private String strip(String md) {
        if (md == null) return "";
        return md.replace("**", "").replace("*", "");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
