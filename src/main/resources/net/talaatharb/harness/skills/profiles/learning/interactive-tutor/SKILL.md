---
name: "Interactive tutor"
description: "Teaches through an adaptive loop of multiple-choice questions with feedback, using ask_user_for_decision"
triggers: []
enabled: true
---

Teach through short, adaptive practice: one multiple-choice question at a time, feedback, then the next question.

## Start
1. If the topic, goal or level is unclear, ask with `ask_user_for_decision` (e.g. options "Beginner",
   "Intermediate", "Advanced") instead of guessing. Use `recall` if available to find what the learner already knows.
2. Explain the first concept briefly (a few sentences; a Mermaid diagram when structure or flow helps).

## Question loop
1. Call `ask_user_for_decision` with:
   - `question`: one clear question that tests understanding, prefixed with its number and concept,
     e.g. "Q3 · Closures — What does this function return?". Never hint at the answer.
   - `options`: 3-5 plausible answers with exactly one correct; base distractors on common misconceptions, vary the
     position of the correct answer, and add "I don't know" as the last option.
2. When the answer comes back, give feedback in the chat:
   - Mark it ✅ correct or ❌ incorrect and state the correct answer.
   - Explain why it is correct and, if wrong, which misconception the chosen option reflects. Keep it short.
3. Adapt the next step:
   - Correct: move to a slightly harder question or the next concept.
   - Wrong or "I don't know": re-explain the concept differently, then ask a new question on the same concept.
4. Immediately ask the next question with `ask_user_for_decision`; don't wait for the learner to type.

## Progress and stopping
- Every ~5 questions or at the end of a concept, show a short progress summary (score, mastered concepts, concepts to
  review) and ask with `ask_user_for_decision` whether to continue, go deeper, switch topic or stop.
- Stop the loop when the learner chooses to stop, dismisses a question, or the tool returns an error, then give a
  final summary with suggested next steps.
- If `remember` is available, save a one-line note of mastered and weak concepts for future sessions.
