# Traverse

An offline tutor for one unit of data structures and algorithms. Miss a question and it comes back today. Get it right and the gap widens. A wrong option files the misconception the author wrote for that choice, and mastery trusts a later review more than an answer given with the lesson still on screen.

**v1 is Complexity and correctness.** Five topics, fifty questions, lessons, quizzes, spaced review, and prerequisite gating. Progress stays on the device. There is no account and no network.

## Not included

Code problems, the rest of a DSA course, LeetCode logging, charts, notifications, and search. Those are out of scope for this version on purpose.

## Build

`bash build.sh` checks `assets/curriculum.json` with `tools/validate.js`, then builds `Traverse.apk` (aapt2, javac, d8, zipalign, apksigner). No Gradle.

Set `JAVA_HOME` to a JDK and `ANDROID_HOME` to an Android SDK that contains build-tools and a platform `android.jar`. If those variables are missing or point at a directory that is not there, the script falls back to `C:/Program Files/Android/openjdk/jdk-21.0.8` and `C:/Users/hopel/AppData/Local/Android/Sdk`. Signing uses the debug keystore at `~/.android/debug.keystore` (password `android`, alias `androiddebugkey`). Node is required for the content check.

minSdk 24. Java source, compiled for the Android runtime. The APK is a build product and is not part of the git tree.

A bad answer index, a prerequisite that does not exist, or a cycle fails the build. That is deliberate: a wrong key would mark correct answers wrong.

## Source

```
assets/curriculum.json     the unit
tools/validate.js          content gate
src/com/traverse/
  PathActivity.java        what is due, and the path
  TopicActivity.java       mastery, next step, open gaps
  LessonActivity.java      the reading; Java or Python samples
  QuizActivity.java        one question, explanation either way
  SummaryActivity.java     what the run showed
  data/                    authored content and earned progress
```

## License

MIT. Copyright (c) 2026 Ashwin R.
