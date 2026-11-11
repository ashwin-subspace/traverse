package com.traverse;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.traverse.data.Db;
import com.traverse.data.Mastery;
import com.traverse.data.Model;

import java.util.List;

/** One topic: how solid you are, what to do next, and what you keep getting wrong. */
public class TopicActivity extends Activity {

    private String topicId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_topic);
        topicId = getIntent().getStringExtra("topicId");

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnLesson).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(TopicActivity.this, LessonActivity.class);
                i.putExtra("topicId", topicId);
                startActivity(i);
            }
        });
        findViewById(R.id.btnPractice).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { practise(); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void practise() {
        Intent i = new Intent(this, QuizActivity.class);
        i.putExtra("topicId", topicId);
        startActivity(i);
    }

    private void render() {
        Db db = Db.get(this);
        final Model.Topic t = db.topic(topicId);
        if (t == null) { finish(); return; }

        ((TextView) findViewById(R.id.kTitle)).setText(t.title);
        ((TextView) findViewById(R.id.kBand)).setText(
                Mastery.band(t.mastery) + (t.mastery > 0 ? "  ·  " + t.mastery + " of 100" : ""));

        final View bar = findViewById(R.id.kBar);
        final View track = (View) bar.getParent();
        final int pct = t.mastery;
        track.post(new Runnable() {
            public void run() {
                ViewGroup.LayoutParams lp = bar.getLayoutParams();
                lp.width = (int) (track.getWidth() * (pct / 100f));
                bar.setLayoutParams(lp);
            }
        });

        int[] s = db.attemptStats(topicId);
        String meta = t.itemCount + " questions";
        if (s[1] > 0) meta += "  ·  " + s[0] + " right of " + s[1] + " answered";
        if (t.dueCount > 0) meta += "\n" + t.dueCount + " due to come back now";
        if (s[3] > 0) meta += "\n" + s[2] + " of " + s[3] + " right when it came back cold";
        ((TextView) findViewById(R.id.kMeta)).setText(meta);

        boolean fresh = !db.unseen(topicId).isEmpty();
        String practice = "Practise";
        if (t.dueCount > 0) practice = "Review " + t.dueCount + " and practise";
        else if (!fresh && s[1] > 0) practice = "Practise again";
        ((TextView) findViewById(R.id.btnPractice)).setText(practice);

        List<Model.Miscon> gaps = db.misconceptions(topicId);
        LinearLayout host = (LinearLayout) findViewById(R.id.kGaps);
        host.removeAllViews();
        TextView gapsHead = (TextView) findViewById(R.id.kGapsHead);
        if (gaps.isEmpty()) {
            gapsHead.setVisibility(s[1] > 0 ? View.VISIBLE : View.GONE);
            gapsHead.setText("NO OPEN GAPS");
        } else {
            gapsHead.setVisibility(View.VISIBLE);
            gapsHead.setText("WHAT YOU KEEP GETTING WRONG");
        }
        for (int i = 0; i < gaps.size(); i++) {
            Model.Miscon m = gaps.get(i);
            View v = getLayoutInflater().inflate(R.layout.item_gap, host, false);
            ((TextView) v.findViewById(R.id.gText)).setText(m.text);
            ((TextView) v.findViewById(R.id.gHits)).setText(m.hits == 1
                    ? "Seen once. Get that question right and this clears."
                    : "Seen " + m.hits + " times.");
            host.addView(v);
        }
    }
}
