━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 0 — INGEST
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: PDF | DOCX

  → File type detection (magic bytes, not extension)
  → PDF path:
       PDFMiner → raw text extraction
       If extraction empty → OCR fallback (Tesseract)
       If scanned PDF → flag: SCANNED_DOCUMENT
  → DOCX path:
       python-docx → raw text extraction
       Preserve section order

  → RawDocument {
      text: string,
      pageCount: int,
      extractionMethod: "PDFMINER | OCR | DOCX",
      confidence: "HIGH | MEDIUM | LOW",
      flags: ["SCANNED", "MULTI_COLUMN", "IMAGE_HEAVY"]
    }

  Failure modes:
    → Password protected → reject, return error
    → Corrupt file → reject, return error
    → Empty extraction → flag LOW confidence, continue


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 0a — JD RESOLVE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: URL | raw text

  URL path:
    → HTTP GET with browser-like headers
    → Workday/Greenhouse/Lever/Ashby detection (separate classes to extract each response properly)
       Known ATS → use platform-specific extractor
    → Generic fallback → juni (main content extraction)
    → Strip: nav, footer, cookie banners, benefits boilerplate
    → If blocked (403/JS-rendered) → return error:
       "JD URL could not be fetched. Paste the JD text directly."

  Raw text path:
    → Direct use, minimal cleaning

  → RawJD {
      text: string,
      source: "URL | PASTE",
      platform: "WORKDAY | GREENHOUSE | LEVER | ASHBY | UNKNOWN",
      fetchSuccess: boolean
    }


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 0b — JD PARSE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: RawJD

  Step 1 — Section identification (regex + heuristics)
    → Detect: responsibilities, requirements, nice-to-haves,
               about us, benefits, tech stack
    → Strip: about us, benefits, equal opportunity boilerplate
    → Preserve: responsibilities, requirements, tech stack

  Step 2 — Requirement classification (linguistic cues)
    → Required signals:
       "must", "essential", "required", "you have",
       "you will need", "minimum", "proven experience in"
    → Preferred signals:
       "nice to have", "ideally", "bonus", "advantageous",
       "familiarity with", "exposure to", "plus"
    → Ambiguous → classify as PREFERRED, flag low confidence

  Step 3 — YOE extraction
    → Regex: "X+ years", "X-Y years", "minimum X years"
    → If not found → UNSPECIFIED

  Step 4 — Role metadata
    → Title extraction
    → Seniority signal detection:
       "junior", "mid", "senior", "lead", "principal",
       "staff", "architect" in title or requirements
    → Remote/hybrid/onsite detection
    → Location extraction

  Step 5 — Skill extraction (SkillNer NER)
    → Run on requirements + tech stack sections only
    → Extract raw skill mentions

  Step 6 — MIND normalisation
    → For each extracted skill:
       MIND synonym lookup → canonical name
       "React.js" → "React"
       "Postgres SQL" → "PostgreSQL"
       "k8s" → "Kubernetes"
    → Skills not in MIND → keep raw, flag as UNRESOLVED

  Step 7 — MIND concept mapping
    → For each normalised skill:
       MIND.solvesApplicationTasks → application task concepts
       MIND.architecturalPatterns → architectural concepts
       MIND.technicalDomains → domain tags
    → Build concept set for this JD

  Step 8 — MIND implied skill expansion
    → For each required skill:
       Traverse impliesKnowingSkills graph
       Add implied skills to inferred set
       "Next.js" required → React, JavaScript inferred

  Step 9 — O*NET lookup (cached)
    → Match role title to O*NET occupation code
    → Fetch: skills, work activities, technology skills
    → Add to implicitExpectations
    → Cache key: normalised_title, TTL: 90 days

  Step 10 — jdClarity scoring
    → Score 0–10 based on:
       Has explicit tech stack? (+2)
       Has YOE range? (+1)
       Has clear required vs preferred split? (+2)
       Requirements section exists? (+2)
       No contradictions detected? (+2)
       Seniority clear? (+1)
    → jdClarity: HIGH (7–10) | MEDIUM (4–6) | LOW (0–3)
    → jdClarity propagates as confidence modifier downstream:
       LOW → all signals get -1 confidence band

  → ParsedJD {
      title: string,
      seniority: "JUNIOR | MID | SENIOR | LEAD | PRINCIPAL | UNSPECIFIED",
      yoeMin: int | null,
      yoeMax: int | null,
      location: string,
      remote: "REMOTE | HYBRID | ONSITE | UNSPECIFIED",
      skills: {
        required: [{ name: string, raw: string, source: "MIND | UNRESOLVED" }],
        preferred: [{ name: string, raw: string, source: "MIND | UNRESOLVED" }],
        inferred: [string]
      },
      concepts: {
        applicationTasks: [string],
        architecturalPatterns: [string],
        domains: [string]
      },
      implicitExpectations: [string],
      jdClarity: "HIGH | MEDIUM | LOW",
      jdClarityScore: int,
      trimmedText: string  ← stripped JD for AI prompt, ~150 tokens
    }

  Cache: hash(rawJD.text) → ParsedJD, TTL: indefinite


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 1 — RESUME STRUCTURE EXTRACTION
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: RawDocument

  Step 1 — Section detection (regex + layout heuristics)
    → Detect standard sections:
       SUMMARY | EXPERIENCE | EDUCATION | SKILLS |
       PROJECTS | CERTIFICATIONS | AWARDS | PUBLICATIONS
    → Flag missing critical sections:
       No SUMMARY → flag
       No SKILLS → flag
       No EXPERIENCE → flag (unless graduate)

  Step 2 — Header extraction
    → Name (first non-section line, title case heuristic)
    → Current title (line below name or in summary first sentence)
    → Location
    → Email, phone, LinkedIn, GitHub URLs
    → Flag: no LinkedIn → minor gap for tech roles

  Step 3 — Format signals
    → Page count
    → Word count
    → Has tables → ATS risk flag
    → Has columns → ATS risk flag
    → Has graphics/logos → ATS risk flag
    → Font variety (DOCX only) → complexity flag
    → Bullet density per section

  → ResumeStructure {
      header: { name, title, location, email, linkedin, github },
      sections: Map<SectionType, rawText>,
      formatFlags: [string],
      pageCount: int,
      wordCount: int,
      missingCriticalSections: [string]
    }


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 2 — RESUME DEEP PARSE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: ResumeStructure

  EXPERIENCE PARSE
    → Per role:
       Company name
       Role title
       Start date, end date (or "Present")
       Duration in months (computed)
       Location
       Bullets (ordered list, preserve sequence)
    → Date parsing:
       Support formats: "Jan 2021", "2021-01", "January 2021"
       Ambiguous dates → flag
       Future dates → flag as anomaly
    → Gap detection:
       > 3 months between roles → employment gap
       Flag with duration

  EDUCATION PARSE
    → Per entry:
       Institution name
       Degree type: BSc | MSc | PhD | Diploma | Bootcamp | Other
       Field of study
       Graduation year
       Grade/GPA if present

  SKILLS PARSE
    → Extract skills list from SKILLS section
    → Preserve groupings if present
       "Languages: Java, Python" → group: Languages

  SUMMARY PARSE
    → Raw text preserved for analysis


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 2b — RESUME ENRICHMENT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: ResumeStructure + parsed experience + parsed skills

  SKILL ENRICHMENT
    → SkillNer NER across all sections
       Skills section + experience bullets + summary + projects
    → MIND normalisation per extracted skill
    → MIND implied graph expansion
       For each found skill → traverse impliesKnowingSkills
       Add implied skills with IMPLIED flag
    → MIND concept mapping
       Map each skill to application task concepts
    → Recency weight per skill:
       Skill mentioned in role ending < 12 months ago → 1.0
       Skill mentioned in role ending 1–3 years ago → 0.7
       Skill mentioned in role ending 3+ years ago → 0.4
       Skill only in skills section, no role evidence → 0.5
    → Deduplicate across sections, keep highest recency weight

  BULLET ENRICHMENT (per bullet, per role)
    → Metric detection:
       Has number? Has percentage? Has scale indicator?
       (millions, thousands, daily, monthly)
    → Action verb quality:
       Strong: architected, engineered, led, reduced, eliminated
       Medium: developed, built, implemented, created
       Weak: helped, assisted, worked on, involved in
       Missing: flag
    → Impact direction:
       IMPROVEMENT (reduced, improved, increased, accelerated)
       PREVENTION (eliminated, avoided, prevented)
       SCALE (handles, processes, supports)
       AMBIGUOUS
    → Scope signal:
       INDIVIDUAL: "I built", "developed"
       TEAM: "led team", "collaborated", "worked with"
       ORG: "org-wide", "company-wide", "all teams"
    → Specificity score 0–10:
       Vague → low score ("worked on backend systems")
       Specific → high score ("reduced P99 latency by 75%
                               for service handling 5M daily transactions")
    → Claim credibility check:
       Cross-reference scope claims vs YOE
       "Led 50 engineers" at 2 YOE → credibility flag
    → Duplicate intent detection:
       Embed each bullet
       Cosine similarity > 0.90 between bullets in same role
       → flag as duplicate

  IMPLICIT SKILL INFERENCE FROM BULLETS
    → Embed each bullet
    → Compare against O*NET work activity embeddings
    → Match to MIND application task concepts
    → Skills implied by bullet content added with
       IMPLIED_FROM_BULLET flag + evidence string

  TOP BULLETS SELECTION (for AI prompt)
    → Score each bullet:
       metricScore * 0.3
       + actionVerbScore * 0.2
       + specificityScore * 0.3
       + jdRelevanceScore * 0.2
    → Select top 5 across all roles
    → Preserve which role each came from

  → EnrichedResume {
      skills: [{
        name: string,
        source: "EXPLICIT | IMPLIED | IMPLIED_FROM_BULLET",
        via: string | null,
        recencyWeight: float,
        mentionedIn: [sectionType]
      }],
      concepts: {
        applicationTasks: [string],
        architecturalPatterns: [string],
        domains: [string]
      },
      bullets: [{
        text: string,
        roleTitle: string,
        company: string,
        metricDetected: boolean,
        actionVerbQuality: "STRONG | MEDIUM | WEAK | MISSING",
        impactDirection: "IMPROVEMENT | PREVENTION | SCALE | AMBIGUOUS",
        scopeSignal: "INDIVIDUAL | TEAM | ORG",
        specificityScore: float,
        credibilityFlag: boolean,
        duplicateFlag: boolean,
        jdRelevanceScore: float
      }],
      topBullets: [string],
      employmentGaps: [{ durationMonths: int, between: [string] }],
      totalYOE: float,
      effectiveYOE: float  ← recency-weighted
    }


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 3 — SKILL MATCHING ENGINE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: ParsedJD + EnrichedResume

  For each required JD skill:

    Step 1 — Exact match (post-MIND normalisation)
      "React.js" on JD → "React" → "React" on resume
      → EXACT, confidence: 1.0

    Step 2 — MIND synonym match
      Caught by normalisation in step 1
      Both sides normalised before comparison

    Step 3 — MIND implied graph match
      JD requires "JavaScript"
      Resume has "React" → MIND: React impliesKnowingSkills JavaScript
      → MIND_IMPLIED, confidence: 0.95
      Evidence: "React implies JavaScript per MIND ontology"

    Step 4 — MIND concept match
      JD concept: "Streaming and Real-time Processing"
      Resume skill: "Kafka" → MIND: solvesApplicationTasks includes this
      → CONCEPT_MATCH, confidence: 0.90

    Step 5 — ESCO equivalence (cached)
      JD requires "C#"
      Resume has "Java"
      → ESCO: both map to "object-oriented programming languages"
      → ESCO_EQUIVALENT, confidence: 0.90
      Cache key: esco:equiv:{skillA}:{skillB}, TTL: 30 days

    Step 6 — Embedding similarity (MiniLM, local)
      Only for skills not resolved by steps 1–5
      Embed JD skill term
      Compare against all resume skill embeddings
      Calibrated thresholds:
        0.92+ → SEMANTIC_HIGH, confidence: 0.85
        0.85–0.92 → SEMANTIC_MEDIUM, confidence: 0.70
        0.75–0.85 → SEMANTIC_LOW, confidence: 0.55, flag for AI
        < 0.75 → no match

    Step 7 — IMPLIED_FROM_BULLET
      Skill inferred from bullet content
      → IMPLIED_FROM_BULLET, confidence: 0.80
      Evidence: bullet text preserved

    Step 8 — MISSING
      No match at any layer
      → AbsenceReason classification:
         Check if skill exists in resume with different name
           → UNLABELLED
         Check if skill is implied but evidence too weak
           → IMPLIED (low confidence)
         Check if skill appears only in skills section
           with no bullet evidence → OMITTED
         Otherwise → GENUINE_GAP

  Apply recency weight to all matched skills:
    final_confidence = match_confidence * recency_weight

  Apply jdClarity modifier:
    LOW jdClarity → cap all confidence at MEDIUM

  → SkillMatchResult {
      matched: [{
        jdSkill: string,
        resumeSkill: string,
        matchType: "EXACT | MIND_IMPLIED | CONCEPT_MATCH |
                    ESCO_EQUIVALENT | SEMANTIC_HIGH |
                    SEMANTIC_MEDIUM | SEMANTIC_LOW |
                    IMPLIED_FROM_BULLET",
        confidence: float,
        recencyWeight: float,
        finalConfidence: float,
        evidence: string | null
      }],
      missing: [{
        jdSkill: string,
        absenceReason: "GENUINE_GAP | UNLABELLED | IMPLIED | OMITTED",
        confidence: float
      }],
      preferred: {
        matched: [string],
        missing: [string]
      },
      conceptOverlap: {
        matched: [string],
        missing: [string]
      }
    }


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 4 — SIGNAL COMPUTATION
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: ParsedJD + EnrichedResume + SkillMatchResult

  title_match
    → ISCO normalise both resume title and JD title
    → EXACT | CLOSE | PARTIAL | MISMATCH
    → confidence: HIGH

  years_of_experience
    → Compare totalYOE + effectiveYOE vs JD yoeMin/yoeMax
    → EXCEEDS | MEETS | BELOW | UNSPECIFIED
    → If EXCEEDS significantly → flag potential overqualification

  company_pedigree
    → Lookup each company in local table:
       tier: TOP_TIER | MID_TIER | STARTUP | UNKNOWN
       scale: HYPERSCALE | ENTERPRISE | MID | SMALL
       domain: FINTECH | HEALTHTECH | ECOMMERCE | GAMING |
               CONSULTING | OTHER
       engineeringCulture: HIGH_BAR | PROCESS | BREADTH | UNKNOWN
    → domainAdjacency vs JD domain:
       DIRECT | ADJACENT | DISTANT | UNKNOWN

  resume_readability
    → formatFlags from Layer 1
    → pageCount: 1 = ideal, 2 = acceptable, 3+ = flag
    → wordCount: 400–700 = ideal for mid-level
    → bulletDensity: avg bullets per role
    → GOOD | ACCEPTABLE | POOR

  skills_visibility
    → Required skills found / total required skills
    → Preferred skills found / total preferred
    → Visibility score 0–100

  current_role_relevance
    → Most recent role title vs JD title
    → Most recent role domain vs JD domain
    → Most recent role tech vs JD required skills
    → HIGHLY_RELEVANT | RELEVANT | PARTIAL | DISTANT

  seniority_signal
    → Resume seniority inferred from:
       Title language, YOE, bullet scope signals,
       team size mentions, leadership language
    → vs JD seniority expectation
    → MATCHED | REACHING_UP | OVER_QUALIFIED | UNCLEAR

  impact_evidence
    → % of bullets with metric detected
    → Average specificity score across top bullets
    → STRONG | MODERATE | WEAK | ABSENT

  career_trajectory
    → Role progression across positions:
       Titles trending upward? → ASCENDING
       Same title repeated? → LATERAL
       Title stepped down? → DESCENDING
       Single role → INSUFFICIENT_DATA
    → Duration per role:
       < 12 months → short tenure flag
       12–24 months → acceptable
       24+ months → stable signal

  domain_relevance
    → JD domain vs resume company domains
    → JD concept set vs resume concept set overlap
    → Score 0–100
    → DIRECT | ADJACENT | DISTANT | NONE

  education_fit
    → Degree level vs role expectation
       (inferred from O*NET occupation profile)
    → Field relevance to role
    → EXCEEDS | MEETS | PARTIAL | IRRELEVANT | NOT_REQUIRED

  employment_gaps
    → From Layer 2b gap detection
    → Flag if gap > 3 months
    → Severity: gap > 12 months → HIGH, 6–12 → MEDIUM, 3–6 → LOW
    → Context: gap near graduation, gap near current date,
                gap mid-career

  → ComputedSignals {
      [signalId]: {
        status: "PASS | WARN | FAIL",
        value: any,
        confidence: "HIGH | MEDIUM | LOW",
        detail: any
      }
    }


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 5 — COHERENCE ENGINE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: ComputedSignals + EnrichedResume + SkillMatchResult

  Cross-signal contradiction checks:

    SENIORITY_VS_YOE
      seniority_signal = SENIOR but YOE < 3 → flag

    LEADERSHIP_VS_EVIDENCE
      Summary claims "led teams" but
      no TEAM/ORG scope bullets anywhere → flag

    SKILLS_INFLATION
      Skills section has 30+ items but
      experience bullets reference < 8 → flag

    POSITIONING_VS_EXPERIENCE
      Summary says "AI-focused engineer" but
      no AI work in any role → positioning mismatch flag

    TITLE_VS_BULLETS
      Current title = "Senior Engineer" but
      bullet scope = all INDIVIDUAL, no TEAM/ORG → flag

    METRIC_CREDIBILITY
      Metric claims outliers:
      "reduced costs by 99%" → credibility flag
      "led team of 100" at 2 YOE → credibility flag

  Role transition detection:
    → Compare resume domain set vs JD domain
    → If DISTANT:
       Compute transferableSkillScore:
         matched concepts / total JD concepts
         (concepts transfer even when stack doesn't)
       pivotType: DIRECT | ADJACENT | CAREER_CHANGE

  → CoherenceReport {
      flags: [{
        type: string,
        severity: "HIGH | MEDIUM | LOW",
        detail: string
      }],
      transferableSkillScore: float | null,
      pivotType: "DIRECT | ADJACENT | CAREER_CHANGE | null"
    }


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 6 — CLASSIFICATION ENGINE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: ComputedSignals + SkillMatchResult + CoherenceReport

  Verdict computation (pure deterministic function):

    requiredSkillCoverage =
      matched_required / total_required (weighted by finalConfidence)

    preferredSkillCoverage =
      matched_preferred / total_preferred

    signalScore = weighted sum:
      title_match         * 0.15
      skills_visibility   * 0.25
      current_role        * 0.20
      impact_evidence     * 0.15
      domain_relevance    * 0.15
      yoe_fit             * 0.10

    coherencePenalty:
      HIGH severity flag   → -0.15 per flag
      MEDIUM severity flag → -0.08 per flag
      LOW severity flag    → -0.03 per flag

    finalScore = signalScore - coherencePenalty

    Verdict:
      finalScore >= 0.75 → STRONG_FIT
      finalScore >= 0.55 → POSSIBLE_FIT
      finalScore >= 0.35 → WEAK_FIT
      finalScore < 0.35  → NO_FIT

  Confidence:
    jdClarity HIGH + all signals HIGH → HIGH
    any jdClarity MEDIUM or signals MEDIUM → MEDIUM
    jdClarity LOW or multiple LOW signals → LOW

  interviewLikelihood:
    STRONG_FIT + HIGH confidence → VERY_LIKELY
    STRONG_FIT + MEDIUM → LIKELY
    POSSIBLE_FIT + HIGH → LIKELY
    POSSIBLE_FIT + MEDIUM → POSSIBLE
    WEAK_FIT any → UNLIKELY
    NO_FIT → VERY_UNLIKELY

  scanDuration estimate:
    NO_FIT → 8
    WEAK_FIT → 15
    POSSIBLE_FIT → 30
    STRONG_FIT → 60

  seniorityCalibration → from seniority_signal
  tailoringScore → from skills_visibility + domain + title alignment
  competitiveContext → from O*NET occupation demand data
  recruiterType → inferred from company size + role type

  → ClassificationResult {
      verdict, confidence, interviewLikelihood,
      scanDuration, finalScore,
      seniorityCalibration, tailoringScore,
      competitiveContext, recruiterType
    }


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 7 — AI PROMPT ASSEMBLY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: all previous layer outputs

  Assemble compressed AI payload:

  SYSTEM PROMPT (~200 tokens):
    You are simulating the mental experience of an
    experienced technical recruiter reading a resume
    against a job description.

    You receive pre-computed signals from a deterministic
    pipeline. Your job is to interpret these signals —
    not recompute them. Every signal has a confidence level.
    HIGH confidence = treat as fact.
    MEDIUM confidence = treat as probable.
    LOW confidence = hedge your language, do not assert.

    You must output valid JSON matching this exact schema:
    [schema here]

    Rules:
    - narrative: 3–5 sentences, past tense, warm but honest
    - never mention specific scores or confidence numbers
    - never use the words "hard stop" or "decisive"
    - fixes: maximum 3, ranked by impact
    - beforeAfter: use actual bullet text from resume
    - if jdClarity is LOW, acknowledge uncertainty in narrative

  USER PROMPT (~750 tokens):
    JD (trimmed requirements only):
    [ParsedJD.trimmedText]

    Role: [title] at [company]

    Candidate:
    [header.title] at [header.company]
    Top bullets:
    [topBullets]

    Pre-computed signals:
    [ComputedSignals — key signals only]

    Skill matching:
    [SkillMatchResult — matched with evidence + missing with absenceReason]

    Coherence flags:
    [CoherenceReport.flags]

    Classification:
    verdict: [verdict]
    confidence: [confidence]
    scanDuration: [n] seconds
    seniorityCalibration: [value]
    tailoringScore: [value]
    jdClarity: [value]
    recruiterType: [value]
    competitiveContext: [value]
    transferableSkillScore: [value | null]

  Total tokens in: ~950
  Total tokens out: ~600
  Total per call: ~1550


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 8 — AI CALL
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Model: Gemini Flash 2.0
Mode: JSON output enforced
Temperature: 0.3 (low — consistency over creativity)
Max tokens: 1000

  Output: raw JSON string

  Validation:
    → Parse JSON
    → Schema validation (Zod or Pydantic)
    → Required fields present?
    → Enum values valid?
    → If validation fails → retry once with error appended to prompt
    → If retry fails → return partial result with flag:
       AI_OUTPUT_INVALID, fallback to deterministic summaryLine


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 9 — ASSEMBLY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Input: ClassificationResult + AI output + all signal data

  Merge:
    → Top-level fields from ClassificationResult
    → narrative, momentOfDecision, narrativeTone,
       recruiterGutFeel, summaryLine from AI output
    → signals: AI interpretation text
               merged with deterministic status/confidence
    → differentiators from AI output
    → redFlags: coherence flags + AI-identified flags merged
    → fixes from AI output, capped at 3

  Final validation:
    → No field missing
    → No null where value required
    → Signal count ≤ 6 (first glance cap)
    → Fix count ≤ 3

  → FirstGlanceReport (final JSON schema)


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
LAYER 10 — OUTCOME TRACKER (async, non-blocking)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  Fire and forget — does not block response

  Store:
    → analysisId
    → verdict + finalScore
    → signal payload (compressed)
    → skill match results
    → timestamp

  Future hooks (when user reports outcome):
    → interview received → positive signal
    → rejection received → negative signal
    → resume edited + resubmitted → delta tracking

  Use over time:
    → Calibrate embedding similarity thresholds per role type
    → Weight signals differently by actual outcome correlation
    → Improve MIND equivalence confidence scores
    → Fine-tune prompt on real verdict vs outcome pairs


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FULL STACK SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Extraction:     PDFMiner, python-docx, Tesseract (OCR fallback)
Web fetch:      Trafilatura (JD URL extraction)
NER:            SkillNer (local, no API)
Ontology:       MIND (local JSON, loaded at startup)
Occupation:     O*NET API (cached 90 days, ~50 calls total ever)
Equivalence:    ESCO API (cached 30 days, cold start only)
Embeddings:     MiniLM all-MiniLM-L6-v2 (local, no API)
Classification: Pure deterministic Python
AI:             Gemini Flash 2.0 (1 call, ~1550 tokens)
Cache:          Redis
Validation:     Pydantic (Python) or Zod (Node)
Storage:        PostgreSQL (outcome tracking)

Cost per analysis:    ~$0.001
External API calls:   0–2 (cache hits after warmup)
AI calls:             1
Total latency:        2–4 seconds end to end