// Content integrity gate. A malformed curriculum must break the build, never the user:
// a question whose answer index is wrong would silently mark correct answers wrong,
// which is worse than having no app at all.
//
//   node tools/validate.js assets/curriculum.json

const fs = require('fs');

const path = process.argv[2] || 'assets/curriculum.json';
const errors = [];
const warnings = [];
let raw;

try {
  raw = fs.readFileSync(path, 'utf8');
} catch (e) {
  console.error('FATAL: cannot read ' + path);
  process.exit(1);
}

let pack;
try {
  pack = JSON.parse(raw);
} catch (e) {
  console.error('FATAL: ' + path + ' is not valid JSON\n  ' + e.message);
  process.exit(1);
}

const err = (where, msg) => errors.push(where + ': ' + msg);
const warn = (where, msg) => warnings.push(where + ': ' + msg);

const ORDERED_TYPES = new Set(['order']);
const KNOWN_TYPES = new Set(
  ['mcq', 'multi', 'output', 'complexity', 'order', 'blank', 'truefalse']);

const topicIds = new Set();
const itemIds = new Set();
const prereqEdges = {};
let topicCount = 0, quizCount = 0, problemCount = 0, misconCount = 0;

if (!Array.isArray(pack.units) || pack.units.length === 0) {
  err('pack', 'no units');
}

for (const unit of pack.units || []) {
  const uw = 'unit ' + (unit.id || '?');
  if (!unit.id) err(uw, 'missing id');
  if (!unit.title) err(uw, 'missing title');
  if (!Array.isArray(unit.topics) || unit.topics.length === 0) {
    err(uw, 'no topics');
  }

  for (const topic of unit.topics || []) {
    const tw = uw + ' / topic ' + (topic.id || '?');
    topicCount++;
    if (!topic.id) err(tw, 'missing id');
    if (topicIds.has(topic.id)) err(tw, 'duplicate topic id');
    topicIds.add(topic.id);
    if (!topic.title) err(tw, 'missing title');
    if (!Array.isArray(topic.lesson) || topic.lesson.length === 0) {
      err(tw, 'no lesson blocks');
    }
    prereqEdges[topic.id] = topic.prereqs || [];

    for (const block of topic.lesson || []) {
      if (!block.type) err(tw, 'lesson block with no type');
      if (block.type === 'text' || block.type === 'callout') {
        if (!block.md) err(tw, block.type + ' block with no md');
      }
      if (block.type === 'code' && !block.src) err(tw, 'code block with no src');
    }

    for (const q of topic.quiz || []) {
      const qw = tw + ' / ' + (q.id || '?');
      quizCount++;
      if (!q.id) err(qw, 'missing id');
      if (itemIds.has(q.id)) err(qw, 'duplicate item id');
      itemIds.add(q.id);
      if (!q.stem) err(qw, 'missing stem');
      if (!KNOWN_TYPES.has(q.type)) err(qw, 'unknown type "' + q.type + '"');
      if (!Array.isArray(q.options) || q.options.length < 2) {
        err(qw, 'needs at least 2 options');
        continue;
      }
      if (typeof q.answer !== 'number' || q.answer < 0 || q.answer >= q.options.length) {
        err(qw, 'answer index ' + q.answer + ' out of range for '
                + q.options.length + ' options');
      }
      if (!q.explain) err(qw, 'missing explain -- every answer must teach, right or wrong');
      if (typeof q.difficulty !== 'number' || q.difficulty < 1 || q.difficulty > 5) {
        err(qw, 'difficulty must be 1-5');
      }
      if (ORDERED_TYPES.has(q.type)) {
        if (!Array.isArray(q.orderAnswer) || q.orderAnswer.length !== q.options.length) {
          err(qw, 'order question needs orderAnswer covering every option');
        }
      }

      // The misconception map is the point of the app: a wrong answer must name
      // what the learner actually believes.
      const m = q.misconception || {};
      for (const k of Object.keys(m)) {
        const idx = Number(k);
        if (!Number.isInteger(idx) || idx < 0 || idx >= q.options.length) {
          err(qw, 'misconception key "' + k + '" is not a valid option index');
        }
        if (idx === q.answer) {
          err(qw, 'misconception attached to the CORRECT option ' + idx);
        }
        if (!m[k] || String(m[k]).trim().length < 8) {
          err(qw, 'misconception for option ' + idx + ' is empty or too vague');
        }
        misconCount++;
      }
      if (q.type !== 'order') {
        const distractors = q.options.length - 1;
        const covered = Object.keys(m).length;
        if (covered < distractors) {
          warn(qw, covered + ' of ' + distractors + ' distractors carry a misconception');
        }
      }
    }

    for (const p of topic.problems || []) {
      const pw = tw + ' / ' + (p.id || '?');
      problemCount++;
      if (!p.id) err(pw, 'missing id');
      if (itemIds.has(p.id)) err(pw, 'duplicate item id');
      itemIds.add(p.id);
      if (!p.statement) err(pw, 'missing statement');
      if (!Array.isArray(p.tests) || p.tests.length < 3) {
        err(pw, 'needs at least 3 test cases');
      }
      for (const t of p.tests || []) {
        if (!t.id) err(pw, 'a test case has no id');
        if (t.expected === undefined || t.expected === null) {
          err(pw, 'test ' + t.id + ' has no expected output -- grading would be impossible');
        }
      }
      if (!p.signature || (!p.signature.java && !p.signature.python)) {
        err(pw, 'needs a signature for at least one language');
      }
      if (!Array.isArray(p.hints) || p.hints.length === 0) {
        warn(pw, 'no hints');
      }
    }
  }
}

// Prerequisites must resolve, and must not form a cycle, or the path can deadlock.
for (const [id, prereqs] of Object.entries(prereqEdges)) {
  for (const p of prereqs) {
    if (!topicIds.has(p)) err('topic ' + id, 'prereq "' + p + '" does not exist');
  }
}
const state = {};
function cyclic(node, trail) {
  if (state[node] === 'done') return false;
  if (state[node] === 'open') {
    err('prereqs', 'cycle: ' + trail.concat(node).join(' -> '));
    return true;
  }
  state[node] = 'open';
  for (const p of prereqEdges[node] || []) {
    if (topicIds.has(p) && cyclic(p, trail.concat(node))) return true;
  }
  state[node] = 'done';
  return false;
}
for (const id of topicIds) cyclic(id, []);

for (const w of warnings) console.log('  warn  ' + w);
for (const e of errors) console.error('  ERROR ' + e);

console.log('\n  ' + topicCount + ' topics, ' + quizCount + ' questions, '
            + problemCount + ' problems, ' + misconCount + ' misconceptions mapped');

if (errors.length) {
  console.error('\nCONTENT INVALID: ' + errors.length + ' error(s). Build stopped.');
  process.exit(1);
}
console.log('  content OK' + (warnings.length ? ' (' + warnings.length + ' warning(s))' : ''));
