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
import com.traverse.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * One question at a time. The explanation is shown whether the answer was right or
 * wrong, and a wrong choice files the misconception the author attached to that option.
 */
public class QuizActivity extends Activity {

    private static final int PRACTICE_LIMIT = 10;
    private static final int REVIEW_LIMIT = 20;

    private final List<Model.Item> queue = new ArrayList<Model.Item>();
    private final List<String> topicTitles = new ArrayList<String>();
    private final List<Integer> picks = new ArrayList<Integer>();

    private int index;
    private boolean answered;
    private boolean review;
    private long shownAt;
    private int right, wrong;
    private int lastChoice = -1;
    private boolean holdRestore;
    private boolean restoreAnswered;
    private int restoreChoice = -1;

    private LinearLayout options;
    private TextView stem, code, topicLabel, progress, next, verdict, explain, miscon, sched;
    private TextView hint, undo, emptyTitle, emptyBody;
    private View feedback, bar, codeScroll, questionScroll, emptyPanel, track;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_quiz);

        options = (LinearLayout) findViewById(R.id.qOptions);
        stem = (TextView) findViewById(R.id.qStem);
        code = (TextView) findViewById(R.id.qCode);
        codeScroll = findViewById(R.id.qCodeScroll);
        topicLabel = (TextView) findViewById(R.id.qTopic);
        progress = (TextView) findViewById(R.id.qProgress);
        next = (TextView) findViewById(R.id.btnNext);
        feedback = findViewById(R.id.qFeedback);
        verdict = (TextView) findViewById(R.id.fVerdict);
        explain = (TextView) findViewById(R.id.fExplain);
        miscon = (TextView) findViewById(R.id.fMiscon);
        sched = (TextView) findViewById(R.id.fSched);
        bar = findViewById(R.id.qBar);
        track = findViewById(R.id.qTrack);
        questionScroll = findViewById(R.id.qScroll);
        hint = (TextView) findViewById(R.id.qHint);
        undo = (TextView) findViewById(R.id.btnUndo);
        emptyPanel = findViewById(R.id.qEmpty);
        emptyTitle = (TextView) findViewById(R.id.qEmptyTitle);
        emptyBody = (TextView) findViewById(R.id.qEmptyBody);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { advance(); }
        });
        undo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { undoPick(); }
        });

        if (b != null && b.containsKey("qIds")) restore(b);
        else build();
        show();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        ArrayList<String> ids = new ArrayList<String>();
        for (int i = 0; i < queue.size(); i++) ids.add(queue.get(i).id);
        out.putStringArrayList("qIds", ids);
        out.putStringArrayList("qTitles", new ArrayList<String>(topicTitles));
        out.putInt("qIndex", index);
        out.putInt("qRight", right);
        out.putInt("qWrong", wrong);
        out.putBoolean("qAnswered", answered);
        out.putBoolean("qReview", review);
        out.putInt("qChoice", lastChoice);
        int[] p = new int[picks.size()];
        for (int i = 0; i < picks.size(); i++) p[i] = picks.get(i).intValue();
        out.putIntArray("qPicks", p);
    }

    private void restore(Bundle b) {
        review = b.getBoolean("qReview");
        ArrayList<String> ids = b.getStringArrayList("qIds");
        ArrayList<String> titles = b.getStringArrayList("qTitles");
        Db db = Db.get(this);
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                Model.Item it = db.item(ids.get(i));
                if (!usable(it)) continue;
                queue.add(it);
                String title = "";
                if (titles != null && i < titles.size() && titles.get(i) != null) title = titles.get(i);
                topicTitles.add(title);
            }
        }
        index = b.getInt("qIndex", 0);
        if (index < 0) index = 0;
        if (index > queue.size()) index = queue.size();
        right = b.getInt("qRight", 0);
        wrong = b.getInt("qWrong", 0);
        restoreAnswered = b.getBoolean("qAnswered", false);
        restoreChoice = b.getInt("qChoice", -1);
        int[] p = b.getIntArray("qPicks");
        if (p != null) {
            for (int i = 0; i < p.length; i++) picks.add(Integer.valueOf(p[i]));
        }
        holdRestore = true;
    }

    private void build() {
        Db db = Db.get(this);
        review = "review".equals(getIntent().getStringExtra("mode"));

        if (review) {
            List<Model.Due> due = db.dueQueue(REVIEW_LIMIT);
            for (int i = 0; i < due.size(); i++) {
                add(due.get(i).item, due.get(i).topicTitle, REVIEW_LIMIT);
            }
            return;
        }

        String topicId = getIntent().getStringExtra("topicId");
        if (topicId == null || topicId.length() == 0) return;
        Model.Topic t = db.topic(topicId);
        String title = t == null ? "" : t.title;

        List<Model.Due> due = db.dueQueue(50);
        for (int i = 0; i < due.size() && queue.size() < PRACTICE_LIMIT; i++) {
            Model.Item it = due.get(i).item;
            if (it != null && topicId.equals(it.topicId)) add(it, title, PRACTICE_LIMIT);
        }
        List<Model.Item> fresh = db.unseen(topicId);
        for (int i = 0; i < fresh.size() && queue.size() < PRACTICE_LIMIT; i++) {
            add(fresh.get(i), title, PRACTICE_LIMIT);
        }
        if (queue.isEmpty()) {
            List<Model.Item> weak = db.weakest(topicId, PRACTICE_LIMIT);
            for (int i = 0; i < weak.size() && queue.size() < PRACTICE_LIMIT; i++) {
                add(weak.get(i), title, PRACTICE_LIMIT);
            }
        }
    }

    private void add(Model.Item it, String title, int limit) {
        if (!usable(it) || queue.size() >= limit) return;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id.equals(it.id)) return;
        }
        queue.add(it);
        topicTitles.add(title == null ? "" : title);
    }

    /** A question we can actually grade. Anything else is skipped, not marked wrong. */
    private boolean usable(Model.Item it) {
        if (it == null || it.payload == null) return false;
        if (it.stem().trim().length() == 0) return false;
        List<String> opts = it.options();
        if (opts.size() < 2) return false;
        if (it.isOrder()) {
            int[] order = it.orderAnswer();
            if (order.length != opts.size()) return false;
            for (int i = 0; i < order.length; i++) {
                if (order[i] < 0 || order[i] >= opts.size()) return false;
            }
            return true;
        }
        int answer = it.answer();
        return answer >= 0 && answer < opts.size();
    }

    private void show() {
        if (queue.isEmpty() || index > queue.size()) {
            holdRestore = false;
            showEmpty();
            return;
        }
        if (index == queue.size()) {
            holdRestore = false;
            done();
            return;
        }

        boolean keepAnswered = holdRestore && restoreAnswered;
        boolean keepPicks = holdRestore;
        int keptChoice = restoreChoice;
        holdRestore = false;
        if (!keepPicks) picks.clear();

        Model.Item it = queue.get(index);
        answered = false;
        if (!keepAnswered) {
            shownAt = System.currentTimeMillis();
            lastChoice = -1;
        }

        emptyPanel.setVisibility(View.GONE);
        questionScroll.setVisibility(View.VISIBLE);
        track.setVisibility(View.VISIBLE);
        next.setVisibility(View.GONE);
        feedback.setVisibility(View.GONE);
        miscon.setVisibility(View.GONE);

        topicLabel.setText(topicTitles.get(index).toUpperCase());
        stem.setText(it.stem());
        progress.setText((index + 1) + " of " + queue.size());
        setBar(index / (float) queue.size());

        String src = it.code(Prefs.lang(this));
        if (src.length() > 0) {
            code.setText(src);
            codeScroll.setVisibility(View.VISIBLE);
        } else {
            codeScroll.setVisibility(View.GONE);
        }

        options.removeAllViews();
        if (it.isOrder()) bindOrder(it, keepAnswered);
        else bindChoice(it, keepAnswered, keptChoice);
    }

    private void showEmpty() {
        questionScroll.setVisibility(View.GONE);
        track.setVisibility(View.GONE);
        next.setVisibility(View.GONE);
        progress.setText("");
        setBar(0f);
        emptyPanel.setVisibility(View.VISIBLE);
        if (review) {
            emptyTitle.setText("Nothing to review");
            emptyBody.setText("Nothing is due right now. Work a topic first, or come back when "
                    + "a question is scheduled.");
        } else {
            emptyTitle.setText("No questions right now");
            emptyBody.setText("This topic has nothing to practise. Read the lesson, or return "
                    + "when a review is due.");
        }
    }

    private void bindChoice(final Model.Item it, boolean keepAnswered, int keptChoice) {
        hint.setVisibility(View.GONE);
        undo.setVisibility(View.GONE);
        List<String> opts = it.options();
        for (int i = 0; i < opts.size(); i++) {
            final int choice = i;
            TextView v = optionView(opts.get(i));
            if (keepAnswered) {
                v.setEnabled(false);
                paintChoice(v, i, it.answer(), keptChoice);
            } else {
                v.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View x) { answer(choice); }
                });
            }
            options.addView(v);
        }
        if (keepAnswered) {
            answered = true;
            lastChoice = keptChoice;
            showFeedback(it, keptChoice == it.answer(), diagnosis(it, keptChoice, false));
        }
    }

    private void bindOrder(final Model.Item it, boolean keepAnswered) {
        hint.setVisibility(View.VISIBLE);
        hint.setText("Tap them in that order.");
        if (keepAnswered) answered = true;
        paintOrder(it);
        if (keepAnswered) {
            showFeedback(it, orderCorrect(it), diagnosis(it, -1, true));
        }
    }

    private TextView optionView(String label) {
        TextView v = (TextView) getLayoutInflater().inflate(R.layout.item_option, options, false);
        v.setText(label);
        return v;
    }

    private void paintOrder(final Model.Item it) {
        options.removeAllViews();
        List<String> opts = it.options();
        for (int i = 0; i < opts.size(); i++) {
            final int choice = i;
            int at = picks.indexOf(Integer.valueOf(choice));
            TextView v = optionView(at >= 0 ? (at + 1) + "   " + opts.get(i) : opts.get(i));
            if (answered || at >= 0) {
                v.setEnabled(false);
                if (at >= 0) v.setAlpha(0.72f);
            } else {
                v.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View x) { pick(it, choice); }
                });
            }
            options.addView(v);
        }
        undo.setVisibility(!answered && picks.size() > 0 ? View.VISIBLE : View.GONE);
    }

    private void pick(Model.Item it, int choice) {
        if (answered || picks.contains(Integer.valueOf(choice))) return;
        picks.add(Integer.valueOf(choice));
        if (picks.size() >= it.options().size()) {
            lastChoice = picks.get(0).intValue();
            boolean correct = orderCorrect(it);
            paintOrder(it);
            grade(it, correct, joinPicks(), -1, true);
            paintOrder(it);
        } else {
            paintOrder(it);
        }
    }

    private void undoPick() {
        if (answered || picks.isEmpty() || index >= queue.size()) return;
        picks.remove(picks.size() - 1);
        paintOrder(queue.get(index));
    }

    private void answer(int choice) {
        if (answered || index >= queue.size()) return;
        Model.Item it = queue.get(index);
        lastChoice = choice;
        boolean correct = choice == it.answer();
        for (int i = 0; i < options.getChildCount(); i++) {
            TextView v = (TextView) options.getChildAt(i);
            v.setEnabled(false);
            paintChoice(v, i, it.answer(), choice);
        }
        grade(it, correct, String.valueOf(choice), choice, false);
    }

    private void paintChoice(TextView v, int i, int answer, int choice) {
        if (i == answer) v.setBackgroundResource(R.drawable.opt_right);
        else if (i == choice) v.setBackgroundResource(R.drawable.opt_wrong);
        else v.setAlpha(0.55f);
    }

    private void grade(Model.Item it, boolean correct, String chosen, int misconOption,
                       boolean order) {
        if (answered) return;
        answered = true;
        long ms = Math.max(0L, System.currentTimeMillis() - shownAt);
        Db db = Db.get(this);
        boolean cold = db.isColdDue(it.id);
        db.recordAttempt(it, correct, 0, ms, chosen, cold);
        if (correct) {
            right++;
            db.clearMisconceptions(it.id);
        } else {
            wrong++;
            if (misconOption >= 0) db.fileMisconception(it, misconOption);
        }
        for (int i = 0; i < options.getChildCount(); i++) options.getChildAt(i).setEnabled(false);
        undo.setVisibility(View.GONE);
        showFeedback(it, correct, diagnosis(it, misconOption, order));
        next.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private String diagnosis(Model.Item it, int choice, boolean order) {
        if (order) {
            return orderCorrect(it) ? "" : correctOrderText(it);
        }
        if (choice < 0 || choice == it.answer()) return "";
        String m = it.misconceptionFor(choice);
        if (m.length() == 0) return "";
        return "What that answer says you believe: " + m;
    }

    private boolean orderCorrect(Model.Item it) {
        int[] want = it.orderAnswer();
        if (want.length != picks.size()) return false;
        for (int i = 0; i < want.length; i++) {
            if (want[i] != picks.get(i).intValue()) return false;
        }
        return true;
    }

    private String correctOrderText(Model.Item it) {
        int[] want = it.orderAnswer();
        List<String> opts = it.options();
        StringBuilder b = new StringBuilder("Correct order: ");
        for (int i = 0; i < want.length; i++) {
            if (i > 0) b.append(", then ");
            int idx = want[i];
            if (idx >= 0 && idx < opts.size()) b.append(opts.get(idx));
        }
        b.append(".");
        return b.toString();
    }

    private String joinPicks() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < picks.size(); i++) {
            if (i > 0) b.append(',');
            b.append(picks.get(i).intValue());
        }
        return b.toString();
    }

    private void showFeedback(Model.Item it, boolean correct, String extra) {
        verdict.setText(correct ? "Right" : "Not quite");
        verdict.setTextColor(getResources().getColor(correct ? R.color.good : R.color.bad));
        explain.setText(it.explain());
        if (extra != null && extra.length() > 0) {
            miscon.setText(extra);
            miscon.setVisibility(View.VISIBLE);
        } else {
            miscon.setVisibility(View.GONE);
        }
        sched.setText(Mastery.explainSchedule(Db.get(this), it.id));
        feedback.setVisibility(View.VISIBLE);
        next.setText(index + 1 >= queue.size() ? "Finish" : "Next");
        next.setVisibility(View.VISIBLE);
    }

    private void advance() {
        if (!answered) return;
        index++;
        picks.clear();
        show();
    }

    private void done() {
        setBar(1f);
        Intent i = new Intent(this, SummaryActivity.class);
        i.putExtra("right", right);
        i.putExtra("wrong", wrong);
        i.putExtra("review", review);
        startActivity(i);
        finish();
    }

    private void setBar(final float p) {
        if (track == null || bar == null) return;
        track.post(new Runnable() {
            public void run() {
                ViewGroup.LayoutParams lp = bar.getLayoutParams();
                lp.width = (int) (track.getWidth() * Math.max(0f, Math.min(1f, p)));
                bar.setLayoutParams(lp);
            }
        });
    }
}
