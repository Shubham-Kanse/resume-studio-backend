package com.resumestudio.reviewer.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.model.FeedbackReport;
import com.resumestudio.reviewer.model.enums.Verdict;
import com.resumestudio.reviewer.skills.SemanticSkillMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "logging.level.com.resumestudio.reviewer.extraction.JdParserService=WARN",
    "logging.level.com.resumestudio.reviewer.skills.SkillMatchEngine=WARN"
})
class RecruiterAgreementEvaluationTest {

    private static final float MIN_THRESHOLD = 0.70f;
    private static final float MAX_THRESHOLD = 0.95f;
    private static final float STEP = 0.01f;

    private static final double BLIND_MIN_ACCURACY = 0.90;
    private static final double BLIND_MIN_MACRO_F1 = 0.90;
    private static final double BLIND_MIN_KAPPA = 0.80;

    @Autowired
    private ReviewerPipeline pipeline;

    @Autowired
    private SemanticSkillMatcher semanticSkillMatcher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recruiterAgreement_blindSet_meetsQualityGate_withCalibration() throws Exception {
        List<EvalCase> allCases = loadCases();
        List<EvalCase> train = allCases.stream().filter(c -> "train".equalsIgnoreCase(c.split())).toList();
        List<EvalCase> blind = allCases.stream().filter(c -> "blind".equalsIgnoreCase(c.split())).toList();

        CalibrationResult best = calibrateOnTrainSet(train);
        semanticSkillMatcher.setSemanticMatchThreshold(best.threshold());

        EvalReport blindReport = evaluate(blind);

        assertTrue(
            blindReport.accuracy() >= BLIND_MIN_ACCURACY,
            "Blind accuracy too low: " + blindReport.accuracy() + " < " + BLIND_MIN_ACCURACY + " | " + blindReport
        );
        assertTrue(
            blindReport.macroF1() >= BLIND_MIN_MACRO_F1,
            "Blind macro F1 too low: " + blindReport.macroF1() + " < " + BLIND_MIN_MACRO_F1 + " | " + blindReport
        );
        assertTrue(
            blindReport.kappa() >= BLIND_MIN_KAPPA,
            "Blind Cohen's kappa too low: " + blindReport.kappa() + " < " + BLIND_MIN_KAPPA + " | " + blindReport
        );
    }

    private CalibrationResult calibrateOnTrainSet(List<EvalCase> trainCases) {
        float original = semanticSkillMatcher.getSemanticMatchThreshold();
        try {
            CalibrationResult best = new CalibrationResult(original, -1.0, -1.0);
            for (float threshold = MIN_THRESHOLD; threshold <= MAX_THRESHOLD + 0.0001f; threshold += STEP) {
                semanticSkillMatcher.setSemanticMatchThreshold(threshold);
                EvalReport report = evaluate(trainCases);
                if (report.macroF1() > best.macroF1()
                    || (report.macroF1() == best.macroF1() && report.accuracy() > best.accuracy())) {
                    best = new CalibrationResult(threshold, report.accuracy(), report.macroF1());
                }
            }
            return best;
        } finally {
            semanticSkillMatcher.setSemanticMatchThreshold(original);
        }
    }

    private EvalReport evaluate(List<EvalCase> cases) {
        List<Prediction> predictions = new ArrayList<>();
        for (EvalCase c : cases) {
            FeedbackReport report = pipeline.reviewRawText(c.resumeText(), c.jdText());
            predictions.add(new Prediction(c.id(), c.recruiterVerdict(), report.getVerdict()));
        }
        return buildReport(predictions);
    }

    private EvalReport buildReport(List<Prediction> predictions) {
        int correct = 0;
        int total = predictions.size();
        Map<Verdict, Map<Verdict, Integer>> confusion = new EnumMap<>(Verdict.class);
        for (Verdict expected : Verdict.values()) {
            confusion.put(expected, new EnumMap<>(Verdict.class));
            for (Verdict predicted : Verdict.values()) {
                confusion.get(expected).put(predicted, 0);
            }
        }

        for (Prediction p : predictions) {
            if (p.expected() == p.predicted()) correct++;
            confusion.get(p.expected()).merge(p.predicted(), 1, Integer::sum);
        }

        double accuracy = total == 0 ? 0.0 : (double) correct / total;
        double macroF1 = macroF1(confusion);
        double kappa = cohenKappa(confusion, total);
        return new EvalReport(accuracy, macroF1, kappa, predictions, confusion);
    }

    private double macroF1(Map<Verdict, Map<Verdict, Integer>> confusion) {
        double sum = 0.0;
        int classes = Verdict.values().length;
        for (Verdict label : Verdict.values()) {
            int tp = confusion.get(label).get(label);
            int fp = 0;
            int fn = 0;

            for (Verdict other : Verdict.values()) {
                if (other != label) {
                    fp += confusion.get(other).get(label);
                    fn += confusion.get(label).get(other);
                }
            }

            double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
            double f1 = (precision + recall) == 0 ? 0.0 : (2 * precision * recall) / (precision + recall);
            sum += f1;
        }
        return sum / classes;
    }

    private List<EvalCase> loadCases() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("eval/recruiter_agreement_cases.json")) {
            return objectMapper.readValue(is, new TypeReference<List<EvalCase>>() {});
        }
    }

    private double cohenKappa(Map<Verdict, Map<Verdict, Integer>> confusion, int total) {
        if (total == 0) return 0.0;

        double po = 0.0; // observed agreement
        for (Verdict v : Verdict.values()) {
            po += confusion.get(v).get(v);
        }
        po /= total;

        double pe = 0.0; // expected agreement by chance
        for (Verdict v : Verdict.values()) {
            int row = 0;
            int col = 0;
            for (Verdict p : Verdict.values()) {
                row += confusion.get(v).get(p);
                col += confusion.get(p).get(v);
            }
            pe += ((double) row / total) * ((double) col / total);
        }

        if (pe >= 1.0) return 0.0;
        return (po - pe) / (1.0 - pe);
    }

    private record EvalCase(
        String id,
        String split,
        String resumeText,
        String jdText,
        Verdict recruiterVerdict
    ) {}

    private record Prediction(String id, Verdict expected, Verdict predicted) {}

    @SuppressWarnings("unused")
    private record EvalReport(
        double accuracy,
        double macroF1,
        double kappa,
        List<Prediction> predictions,
        Map<Verdict, Map<Verdict, Integer>> confusion
    ) {
        @Override
        public String toString() {
            String byCase = predictions.stream()
                .map(p -> p.id() + ":" + p.expected() + "->" + p.predicted())
                .collect(Collectors.joining(", "));
            return "EvalReport{accuracy=" + accuracy + ", macroF1=" + macroF1 + ", kappa=" + kappa + ", cases=[" + byCase + "]}";
        }
    }

    private record CalibrationResult(float threshold, double accuracy, double macroF1) {}
}
