package com.traverse;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.traverse.data.Db;
import com.traverse.data.Model;

import java.util.List;

/** End of a run. Honest about what it means, rather than confetti. */
public class SummaryActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_summary);

        int right = getIntent().getIntExtra("right", 0);
        int wrong = getIntent().getIntExtra("wrong", 0);
        boolean review = getIntent().getBooleanExtra("review", false);
        int total = right + wrong;

        TextView kicker = (TextView) findViewById(R.id.sKicker);
        kicker.setText(review ? "REVIEW DONE" : "PRACTICE DONE");
        kicker.setTextColor(getResources().getColor(
                wrong == 0 && total > 0 ? R.color.good : R.color.warm));
        ((TextView) findViewById(R.id.sScore)).setText(
                total == 0 ? "\u2014" : Math.round(right * 100f / total) + "%");
        ((TextView) findViewById(R.id.sTitle)).setText(
                total == 0 ? "Nothing answered" : right + " of " + total + " right");

        Db db = Db.get(this);
        int due = db.dueTotal();
        String body;
        if (total == 0) {
            body = "Nothing was saved from this run.";
        } else if (review) {
            body = wrong == 0
                    ? "All of these came back on schedule. That is the check that it stuck."
                    : (wrong == 1 ? "1 slipped" : wrong + " slipped")
                        + ". Those come back sooner now, and they stay due until they hold.";
        } else if (wrong == 0) {
            body = "Clean run. These return in a day or so, to check it was not just the lesson.";
        } else {
            body = "The ones you missed are back in today's queue. Fixing them now is the "
                    + "point of the review, not a second copy of the lesson.";
        }
        if (due > 0) {
            body += "\n\n" + due + (due == 1 ? " question is" : " questions are") + " still due.";
        } else if (total > 0 && db.unseenQuizCount() == 0) {
            String name = "This unit";
            List<Model.Unit> units = db.path();
            if (units.size() == 1 && units.get(0).title != null) name = units.get(0).title;
            body += "\n\n" + name + " is the only unit in this version, and every question "
                    + "in it has been answered. Nothing is due today.";
        }
        ((TextView) findViewById(R.id.sBody)).setText(body);

        findViewById(R.id.sDone).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
    }
}
