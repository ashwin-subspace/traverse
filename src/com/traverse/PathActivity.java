package com.traverse;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.traverse.data.Content;
import com.traverse.data.Db;
import com.traverse.data.Mastery;
import com.traverse.data.Model;

import java.util.List;

/**
 * The path. Units as sections, topics as nodes, and above them the one thing that
 * separates this from a course: what is due to come back today.
 */
public class PathActivity extends Activity {

    private LinearLayout host, statRow;
    private TextView streak;
    private ScrollView scroller;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_path);

        host = (LinearLayout) findViewById(R.id.pathHost);
        statRow = (LinearLayout) findViewById(R.id.statRow);
        streak = (TextView) findViewById(R.id.streak);
        scroller = (ScrollView) findViewById(R.id.pathScroll);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        Content.ensureLoaded(this);
        Db db = Db.get(this);
        List<Model.Unit> units = db.path();

        host.removeAllViews();
        statRow.removeAllViews();
        streak.setVisibility(View.GONE);

        if (units.isEmpty()) {
            statRow.setVisibility(View.GONE);
            showBroken();
            return;
        }

        statRow.setVisibility(View.VISIBLE);
        renderReview(db, units);
        renderStats(db);

        for (int i = 0; i < units.size(); i++) {
            Model.Unit u = units.get(i);
            View head = getLayoutInflater().inflate(R.layout.item_unit, host, false);
            ((TextView) head.findViewById(R.id.uTitle)).setText(u.title.toUpperCase());
            ((TextView) head.findViewById(R.id.uBlurb)).setText(u.blurb);
            host.addView(head);

            for (int j = 0; j < u.topics.size(); j++) {
                host.addView(topicRow(u.topics.get(j)));
            }
        }
    }

    private void showBroken() {
        TextView kicker = (TextView) findViewById(R.id.reviewKicker);
        TextView title = (TextView) findViewById(R.id.reviewTitle);
        TextView body = (TextView) findViewById(R.id.reviewBody);
        TextView btn = (TextView) findViewById(R.id.btnReview);
        kicker.setText("CONTENT");
        title.setText("Questions could not be loaded");
        body.setText("The packaged unit is missing or unreadable, so there is nothing to "
                + "practise. Try again, and reinstall Traverse if it keeps happening.");
        btn.setText("Try again");
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Content.reload(PathActivity.this);
                render();
            }
        });
    }

    private View topicRow(final Model.Topic t) {
        View v = getLayoutInflater().inflate(R.layout.item_topic, host, false);
        ((TextView) v.findViewById(R.id.tTitle)).setText(t.title);
        ((TextView) v.findViewById(R.id.tPct)).setText(
                t.mastery == 0 ? "" : String.valueOf(t.mastery));
        v.findViewById(R.id.tRing).setBackgroundResource(
                t.mastery >= Mastery.UNLOCK_FLOOR ? R.drawable.ring_done : R.drawable.ring);

        String meta = Mastery.band(t.mastery) + "  ·  " + t.itemCount
                + (t.itemCount == 1 ? " question" : " questions");
        ((TextView) v.findViewById(R.id.tMeta)).setText(meta);

        TextView due = (TextView) v.findViewById(R.id.tDue);
        if (t.dueCount > 0) {
            due.setText(t.dueCount + " due");
            due.setVisibility(View.VISIBLE);
        } else {
            due.setVisibility(View.GONE);
        }

        if (!t.unlocked) {
            v.setAlpha(0.72f);
            ((TextView) v.findViewById(R.id.tMeta)).setText(
                    "Locked until the topics before this reach " + Mastery.UNLOCK_FLOOR);
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    android.widget.Toast.makeText(PathActivity.this,
                            "Get the topics before this to " + Mastery.UNLOCK_FLOOR + " first.",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            return v;
        }

        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { openTopic(t.id); }
        });
        return v;
    }

    private void renderReview(Db db, List<Model.Unit> units) {
        int due = db.dueTotal();
        TextView title = (TextView) findViewById(R.id.reviewTitle);
        TextView body = (TextView) findViewById(R.id.reviewBody);
        TextView kicker = (TextView) findViewById(R.id.reviewKicker);
        TextView btn = (TextView) findViewById(R.id.btnReview);

        if (due > 0) {
            kicker.setText("DUE FOR REVIEW");
            title.setText(due + (due == 1 ? " question" : " questions") + " to bring back");
            body.setText("These are the ones you are about to forget. Answering them now "
                    + "matters more than starting something new.");
            btn.setText("Review " + due);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Intent i = new Intent(PathActivity.this, QuizActivity.class);
                    i.putExtra("mode", "review");
                    startActivity(i);
                }
            });
            return;
        }

        if (db.attemptTotal() == 0) {
            final Model.Topic first = firstUnlocked(units);
            kicker.setText("START HERE");
            title.setText("Nothing learned yet");
            body.setText("This version is one unit. Work the first topic. After you answer "
                    + "something, Traverse brings it back before you forget it.");
            if (first != null) {
                btn.setText("Start the first topic");
                btn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { openTopic(first.id); }
                });
            } else {
                btn.setText("Browse the path");
                btn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { scrollToPath(); }
                });
            }
            return;
        }

        boolean finished = db.unseenQuizCount() == 0;
        if (finished) {
            String name = units.size() == 1 ? units.get(0).title : "The packaged units";
            kicker.setText("UNIT COMPLETE");
            title.setText("Nothing due today");
            body.setText(name + " is the only material in this version, and every question "
                    + "has been answered. Nothing is scheduled for today. What comes back "
                    + "later is a review, not a new unit.");
            btn.setText("Browse topics");
        } else {
            kicker.setText("ALL CAUGHT UP");
            title.setText("Nothing due today");
            body.setText("Everything you have learned is still fresh. The rest of the unit "
                    + "is on the path below.");
            btn.setText("Continue");
        }
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { scrollToPath(); }
        });
    }

    private Model.Topic firstUnlocked(List<Model.Unit> units) {
        for (int i = 0; i < units.size(); i++) {
            for (int j = 0; j < units.get(i).topics.size(); j++) {
                Model.Topic t = units.get(i).topics.get(j);
                if (t.unlocked) return t;
            }
        }
        return null;
    }

    private void openTopic(String id) {
        Intent i = new Intent(this, TopicActivity.class);
        i.putExtra("topicId", id);
        startActivity(i);
    }

    private void scrollToPath() {
        scroller.post(new Runnable() {
            public void run() { scroller.smoothScrollTo(0, host.getTop()); }
        });
    }

    private void renderStats(Db db) {
        int s = db.streak();
        if (s >= 2) {
            streak.setText(s + " day streak");
            streak.setVisibility(View.VISIBLE);
        } else {
            streak.setVisibility(View.GONE);
        }

        addStat(String.valueOf(db.answeredToday()), "today");
        addStat(String.valueOf(db.attemptTotal()), "answered");
        addStat(String.valueOf(db.misconceptions(null).size()), "gaps");
    }

    private void addStat(String value, String label) {
        View v = getLayoutInflater().inflate(R.layout.part_stat, statRow, false);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (statRow.getChildCount() > 0) {
            lp.leftMargin = (int) (10 * getResources().getDisplayMetrics().density);
        }
        v.setLayoutParams(lp);
        ((TextView) v.findViewById(R.id.statVal)).setText(value);
        ((TextView) v.findViewById(R.id.statLbl)).setText(label);
        statRow.addView(v);
    }
}
