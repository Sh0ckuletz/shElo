package cc.shockuletz.shelo;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EloColorManager {
    private static final Pattern CONDITION = Pattern.compile("^(>=|<=|>|<|==|=)\\s*(-?\\d+)$");

    private final ShEloPlugin plugin;
    private List<ColorRule> rules = List.of();
    private String defaultFormat = "<gray>{elo}</gray>";

    public EloColorManager(ShEloPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "colors.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        defaultFormat = config.getString("default-format", "<gray>{elo}</gray>");

        List<ColorRule> loadedRules = new ArrayList<>();
        int ruleNumber = 0;
        for (Map<?, ?> entry : config.getMapList("rules")) {
            ruleNumber++;
            Object rawConditions = entry.get("conditions");
            Object rawFormat = entry.get("format");
            if (!(rawConditions instanceof List<?> conditions) || !(rawFormat instanceof String format)) {
                plugin.getLogger().warning("Rule " + ruleNumber + " in colors.yml is invalid.");
                continue;
            }

            List<IntPredicate> predicates = new ArrayList<>();
            boolean valid = true;
            for (Object rawCondition : conditions) {
                if (!(rawCondition instanceof String condition)) {
                    valid = false;
                    break;
                }
                IntPredicate predicate = parseCondition(condition);
                if (predicate == null) {
                    plugin.getLogger().warning("Invalid condition in colors.yml: " + condition);
                    valid = false;
                    break;
                }
                predicates.add(predicate);
            }

            if (valid && !predicates.isEmpty()) {
                loadedRules.add(new ColorRule(List.copyOf(predicates), format));
            }
        }
        rules = List.copyOf(loadedRules);
    }

    public String format(int elo) {
        for (ColorRule rule : rules) {
            if (rule.matches(elo)) {
                return rule.format().replace("{elo}", Integer.toString(elo));
            }
        }
        return defaultFormat.replace("{elo}", Integer.toString(elo));
    }

    private IntPredicate parseCondition(String condition) {
        Matcher matcher = CONDITION.matcher(condition.trim());
        if (!matcher.matches()) {
            return null;
        }

        String operator = matcher.group(1);
        int target;
        try {
            target = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException exception) {
            return null;
        }

        return switch (operator) {
            case ">=" -> elo -> elo >= target;
            case "<=" -> elo -> elo <= target;
            case ">" -> elo -> elo > target;
            case "<" -> elo -> elo < target;
            case "=", "==" -> elo -> elo == target;
            default -> null;
        };
    }

    private record ColorRule(List<IntPredicate> conditions, String format) {
        private boolean matches(int elo) {
            return conditions.stream().allMatch(condition -> condition.test(elo));
        }
    }
}
