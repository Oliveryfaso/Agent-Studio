package dev.agentconfig.workbench.skill;

import dev.agentconfig.workbench.skill.CodexSkillInventory.PackageState;
import dev.agentconfig.workbench.skill.SkillTaxonomyReport.Category;
import dev.agentconfig.workbench.skill.SkillTaxonomyReport.Classification;
import dev.agentconfig.workbench.skill.SkillTaxonomyReport.Confidence;
import dev.agentconfig.workbench.skill.SkillTaxonomyReport.EvidenceSource;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded rules-first Skill classifier. It never executes Skill content or calls an LLM. */
public final class SkillTaxonomyService {
    private static final Map<Category, List<String>> KEYWORDS = keywords();

    public SkillTaxonomyReport classify(Path authorizedRoot) throws IOException {
        Path root = authorizedRoot.toAbsolutePath().normalize().toRealPath();
        CodexSkillInventory inventory = new CodexSkillInventoryService().inspect(root);
        List<CodexSkillInventory.SkillPackage> eligible = inventory.packages();
        boolean partial = inventory.status() == CodexSkillInventory.Status.PARTIAL;
        List<Classification> classifications = new ArrayList<>();
        CodexSkillContentService contentService = new CodexSkillContentService();
        for (CodexSkillInventory.SkillPackage skill : eligible) {
            if (skill.state() != PackageState.MINIMAL_METADATA_VALID) {
                classifications.add(unclassified(
                        skill.directoryName(), skill.logicalPath(), skill.sha256()));
                continue;
            }
            try {
                CodexSkillContent content = contentService.readSelected(root, skill);
                classifications.add(classifyOne(skill.directoryName(), skill.logicalPath(),
                        skill.sha256(), content.content()));
            } catch (IOException exception) {
                partial = true;
                classifications.add(unclassified(
                        skill.directoryName(), skill.logicalPath(), skill.sha256()));
            }
        }
        classifications.sort(Comparator.comparing(Classification::logicalPath));
        int unclassified = (int) classifications.stream()
                .filter(skill -> skill.category() == null).count();
        return new SkillTaxonomyReport(SkillTaxonomyReport.CURRENT_SCHEMA_VERSION,
                SkillTaxonomyReport.CLASSIFIER_PROFILE_ID,
                partial ? SkillTaxonomyReport.Status.PARTIAL
                        : SkillTaxonomyReport.Status.COMPLETE,
                false, false, false, Category.buckets(), classifications, unclassified);
    }

    private static Classification classifyOne(
            String name, String logicalPath, String sourceSha256, String content) {
        String normalizedName = normalize(name.replace('-', ' '));
        String description = normalize(description(content));
        EnumMap<Category, Integer> scores = new EnumMap<>(Category.class);
        EnumMap<Category, EnumSet<EvidenceSource>> sources = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            int score = 0;
            EnumSet<EvidenceSource> evidence = EnumSet.noneOf(EvidenceSource.class);
            for (String keyword : KEYWORDS.get(category)) {
                if (matchesPhrase(normalizedName, keyword)) {
                    score += 4;
                    evidence.add(EvidenceSource.NAME);
                }
                if (matchesPhrase(description, keyword)) {
                    score += 6;
                    evidence.add(EvidenceSource.DESCRIPTION);
                }
            }
            scores.put(category, score);
            sources.put(category, evidence);
        }
        List<Map.Entry<Category, Integer>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<Category, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().ordinal())).toList();
        int best = ranked.getFirst().getValue();
        int second = ranked.get(1).getValue();
        int margin = best - second;
        if (best < 6 || margin < 3) return unclassified(
                name, logicalPath, sourceSha256, best, margin,
                sources.get(ranked.getFirst().getKey()));
        Confidence confidence = best >= 10 && margin >= 5
                && sources.get(ranked.getFirst().getKey()).size() >= 2
                ? Confidence.HIGH : Confidence.MEDIUM;
        Category category = ranked.getFirst().getKey();
        return new Classification(name, logicalPath, sourceSha256,
                category, confidence, best, margin,
                sources.get(category).stream().toList());
    }

    private static Classification unclassified(
            String name, String logicalPath, String sourceSha256) {
        return unclassified(name, logicalPath, sourceSha256, 0, 0, Set.of());
    }

    private static Classification unclassified(String name, String logicalPath,
            String sourceSha256,
            int score, int margin, Set<EvidenceSource> evidence) {
        return new Classification(name, logicalPath, sourceSha256,
                null, Confidence.UNCLASSIFIED,
                score, margin, evidence.stream().sorted().toList());
    }

    private static String description(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\\n", 130);
        if (lines.length < 3 || !"---".equals(stripBom(lines[0]).strip())) return "";
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].strip();
            if ("---".equals(line)) break;
            int colon = line.indexOf(':');
            if (colon > 0 && "description".equals(line.substring(0, colon).strip()))
                return line.substring(colon + 1).strip();
        }
        return "";
    }

    private static String stripBom(String value) {
        return value.startsWith("\ufeff") ? value.substring(1) : value;
    }

    private static boolean matchesPhrase(String text, String phrase) {
        int offset = 0;
        while (offset <= text.length() - phrase.length()) {
            int found = text.indexOf(phrase, offset);
            if (found < 0) return false;
            int end = found + phrase.length();
            boolean ascii = phrase.chars().allMatch(character -> character < 128);
            boolean bounded = !ascii
                    || ((found == 0 || !Character.isLetterOrDigit(text.charAt(found - 1)))
                    && (end == text.length() || !Character.isLetterOrDigit(text.charAt(end))));
            int windowStart = Math.max(0, found - 28);
            String prefix = text.substring(windowStart, found);
            boolean negated = List.of("do not", "don't", "never", "not for", "avoid",
                    "不要", "不用于", "不适用", "避免").stream().anyMatch(prefix::contains);
            if (bounded && !negated) return true;
            offset = found + 1;
        }
        return false;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Map<Category, List<String>> keywords() {
        EnumMap<Category, List<String>> result = new EnumMap<>(Category.class);
        result.put(Category.LIBRARY_API_REFERENCE, List.of(
                "api reference", "sdk usage", "library usage", "cli usage", "库/api 参考", "sdk 使用", "接口参考"));
        result.put(Category.PRODUCT_VALIDATION, List.of(
                "end-to-end test", "end to end test", "acceptance test", "browser journey", "registration flow", "端到端测试", "验收测试", "注册流程"));
        result.put(Category.DATA_QUERY_ANALYSIS, List.of(
                "sql query", "database query", "conversion funnel", "metric analysis", "数据查询", "转化漏斗", "指标分析"));
        result.put(Category.BUSINESS_WORKFLOW_AUTOMATION, List.of(
                "ticket triage", "generate status report", "aggregate work items", "business workflow automation", "工单聚合", "生成站会日报", "业务流程自动化"));
        result.put(Category.CODE_SCAFFOLDING, List.of(
                "scaffold service", "generate boilerplate", "project template", "代码脚手架", "模板代码", "项目模板"));
        result.put(Category.CODE_QUALITY_REVIEW, List.of(
                "code review", "security audit", "static analysis", "api backward compatibility", "代码审查", "安全审计", "静态分析"));
        result.put(Category.CI_CD_DEPLOYMENT, List.of(
                "ci pipeline", "release pipeline", "deploy release", "pull request checks", "ci/cd", "持续集成", "发布部署"));
        result.put(Category.RUNBOOK_TROUBLESHOOTING, List.of(
                "incident response", "alert investigation", "root cause analysis", "on-call runbook", "告警排障", "事故响应", "根因分析"));
        result.put(Category.INFRASTRUCTURE_OPERATIONS, List.of(
                "cluster maintenance", "orphan resource cleanup", "terraform operation", "infrastructure maintenance", "基础设施运维", "孤儿资源清理", "集群维护"));
        return Map.copyOf(result);
    }
}
