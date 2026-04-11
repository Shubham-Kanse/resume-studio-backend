# Recruiter Agreement Eval Harness

This folder contains the blind-label evaluation dataset used to track first-glance recruiter agreement quality.

## Dataset

- File: `recruiter_agreement_cases.json`
- Schema per case:
  - `id`
  - `split` (`train` or `blind`)
  - `resumeText`
  - `jdText`
  - `recruiterVerdict` (`STRONG_FIT`, `POSSIBLE_FIT`, `WEAK_FIT`)

## Calibration + Validation Flow

Implemented in:
- `src/test/java/com/resumestudio/reviewer/eval/RecruiterAgreementEvaluationTest.java`

Process:
1. Grid-search semantic threshold on `train` split.
2. Lock best threshold.
3. Evaluate on `blind` split.
4. Enforce quality gates on blind set accuracy, macro-F1, and Cohen's kappa (agreement beyond chance).

## Run

From repo root:

```bash
mvn -pl resume-studio-reviewer -Dtest=RecruiterAgreementEvaluationTest test
```

This provides a repeatable metric guardrail for recruiter-like first-glance behavior.
