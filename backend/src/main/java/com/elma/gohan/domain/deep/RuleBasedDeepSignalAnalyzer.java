package com.elma.gohan.domain.deep;

import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.provider.deep.DeepEvidenceBatch;
import com.elma.gohan.provider.deep.DeepEvidenceSource;
import com.elma.gohan.provider.deep.WebEvidenceItem;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 使用透明短语规则提取正负面、运营和营销信号。 */
@Component
public class RuleBasedDeepSignalAnalyzer implements DeepSignalAnalyzer {

    private final DeepEvidenceProperties properties;

    public RuleBasedDeepSignalAnalyzer(DeepEvidenceProperties properties) {
        this.properties = properties;
    }

    @Override
    public DeepSignalAnalysis analyze(Map<DeepEvidenceSource, DeepEvidenceBatch> evidence,
                                      Instant now) {
        Map<String, Integer> positivePhrases = new HashMap<>();
        Map<String, Integer> negativePhrases = new HashMap<>();
        Map<String, Integer> operationalPhrases = new HashMap<>();
        Map<DeepEvidenceSource, SourceSignalStats> stats =
                new EnumMap<>(DeepEvidenceSource.class);
        int availableSources = 0;
        int relevant = 0;
        int published = 0;
        int marketingResults = 0;
        double matchConfidenceTotal = 0.0;

        for (DeepEvidenceSource source : DeepEvidenceSource.values()) {
            DeepEvidenceBatch batch = evidence.get(source);
            if (batch == null) batch = DeepEvidenceBatch.unavailable(source, now);
            if (batch.status() == EvidenceStatus.AVAILABLE) availableSources++;
            int positive = 0;
            int negative = 0;
            int operational = 0;
            int marketing = 0;
            for (WebEvidenceItem item : batch.items()) {
                relevant++;
                if (item.publishedAt() != null) published++;
                matchConfidenceTotal += item.entityMatchConfidence();
                String text = (item.title() + " "
                        + (item.snippet() == null ? "" : item.snippet())).toLowerCase();
                List<String> negativeHits = hits(text, properties.getNegativePhrases(), false);
                List<String> positiveHits = hits(text, properties.getPositivePhrases(), true);
                List<String> operationalHits = hits(text, properties.getOperationalPhrases(), false);
                List<String> marketingHits = hits(text, properties.getMarketingPhrases(), false);
                if (!positiveHits.isEmpty()) positive++;
                if (!negativeHits.isEmpty()) negative++;
                if (!operationalHits.isEmpty()) operational++;
                if (!marketingHits.isEmpty()) {
                    marketing++;
                    marketingResults++;
                }
                positiveHits.forEach(hit -> positivePhrases.merge(hit, 1, Integer::sum));
                negativeHits.forEach(hit -> negativePhrases.merge(hit, 1, Integer::sum));
                operationalHits.forEach(hit -> operationalPhrases.merge(hit, 1, Integer::sum));
            }
            double denominator = positive + negative + operational * 0.5;
            double balance = denominator == 0.0 ? 0.0
                    : (negative + operational * 0.5 - positive) / denominator;
            String direction = balance >= 0.2 ? "NEGATIVE"
                    : balance <= -0.2 ? "POSITIVE" : "NEUTRAL";
            stats.put(source, new SourceSignalStats(batch.items().size(), positive, negative,
                    operational, marketing, round(balance), direction));
        }

        List<SourceSignalStats> signaled = stats.values().stream()
                .filter(value -> value.positiveCount() + value.negativeCount()
                        + value.operationalCount() > 0)
                .toList();
        double globalBalance = signaled.stream().mapToDouble(SourceSignalStats::balance)
                .average().orElse(0.0);
        double coverage = Math.min(1.0, relevant / 9.0) * (availableSources / 3.0);
        double timeCoverage = relevant == 0 ? 0.0 : (double) published / relevant;
        double matchAverage = relevant == 0 ? 0.0 : matchConfidenceTotal / relevant;
        double marketingRatio = relevant == 0 ? 0.0 : (double) marketingResults / relevant;
        double webConfidence = (0.4 * availableSources / 3.0
                + 0.3 * Math.min(1.0, relevant / 9.0)
                + 0.2 * timeCoverage
                + 0.1 * matchAverage) * (1.0 - 0.3 * marketingRatio);

        Consistency consistency = consistency(stats, availableSources);
        List<String> cautions = new ArrayList<>(messages(operationalPhrases, 2, "公开结果提示"));
        if (marketingResults > 0 && cautions.size() < 2) {
            cautions.add("部分公开结果带有探店或推广式表达");
        }
        Instant expiresAt = now.plus(properties.getAnalysisCacheHours(), ChronoUnit.HOURS);
        return new DeepSignalAnalysis(
                messages(positivePhrases, 3, "公开结果提到"),
                messages(negativePhrases, 3, "公开结果提到"),
                List.copyOf(cautions.subList(0, Math.min(2, cautions.size()))),
                stats, round(webConfidence), consistency.level(), consistency.reason(),
                round(globalBalance), round(coverage), relevant, availableSources,
                now, expiresAt, properties.getAnalysisAlgorithmVersion());
    }

    private List<String> hits(String text, List<String> phrases, boolean positive) {
        return phrases.stream()
                .filter(phrase -> phrase != null && !phrase.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .filter(phrase -> positive ? containsPositive(text, phrase) : text.contains(phrase))
                .distinct().toList();
    }

    private boolean containsPositive(String text, String phrase) {
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf(phrase, from);
            if (index < 0) return false;
            if (index == 0 || "不没无非".indexOf(text.charAt(index - 1)) < 0) return true;
            from = index + phrase.length();
        }
        return false;
    }

    private List<String> messages(Map<String, Integer> counts, int max, String prefix) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(max)
                .map(entry -> prefix + (entry.getValue() >= 2 ? "多次" : "")
                        + "“" + entry.getKey() + "”")
                .toList();
    }

    private Consistency consistency(Map<DeepEvidenceSource, SourceSignalStats> stats,
                                    int availableSources) {
        long positive = stats.values().stream()
                .filter(value -> "POSITIVE".equals(value.direction())).count();
        long negative = stats.values().stream()
                .filter(value -> "NEGATIVE".equals(value.direction())).count();
        if (positive > 0 && negative > 0) {
            return new Consistency("LOW", "不同公开来源的正负线索存在分歧");
        }
        if (positive >= 2 || negative >= 2) {
            return new Consistency("HIGH", "至少两个公开来源的线索方向一致");
        }
        if (availableSources >= 2) {
            return new Consistency("MEDIUM", "已覆盖多个公开来源，但有效倾向仍有限");
        }
        return new Consistency("UNKNOWN", "公开结果不足，暂无法判断多来源一致度");
    }

    private double round(double value) {
        return Math.round(Math.max(-1.0, Math.min(1.0, value)) * 1000.0) / 1000.0;
    }

    private record Consistency(String level, String reason) { }
}
