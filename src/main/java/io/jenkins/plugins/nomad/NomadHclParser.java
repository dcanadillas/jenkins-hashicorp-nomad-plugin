package io.jenkins.plugins.nomad;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NomadHclParser {
    private static final Pattern TASK_HEADER_PATTERN = Pattern.compile("\\btask\\s+\"([^\"]+)\"\\s*\\{");
    private static final Pattern IMAGE_PATTERN = Pattern.compile("\\bimage\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern COMMAND_PATTERN = Pattern.compile("\\bcommand\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ARGS_PATTERN = Pattern.compile("\\bargs\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern ARG_ITEM_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern CPU_PATTERN = Pattern.compile("\\bcpu\\s*=\\s*(\\d+)");
    private static final Pattern MEMORY_PATTERN = Pattern.compile("\\bmemory(?:_mb)?\\s*=\\s*(\\d+)");

    private NomadHclParser() {}

    static List<NomadContainerTemplate> parseContainersFromJobHcl(String hcl) {
        if (hcl == null || hcl.isBlank()) {
            return List.of();
        }

        List<NomadContainerTemplate> containers = new ArrayList<>();
        Matcher matcher = TASK_HEADER_PATTERN.matcher(hcl);
        while (matcher.find()) {
            String taskName = matcher.group(1) == null ? "" : matcher.group(1).trim();
            int openingBrace = hcl.indexOf('{', matcher.end() - 1);
            if (openingBrace < 0) {
                continue;
            }
            int closingBrace = findMatchingBrace(hcl, openingBrace);
            if (closingBrace < 0) {
                continue;
            }
            String body = hcl.substring(openingBrace + 1, closingBrace);

            if (taskName.isBlank() || "jnlp".equals(taskName)) {
                continue;
            }

            String image = matchGroup(IMAGE_PATTERN, body);
            if (image == null || image.isBlank()) {
                continue;
            }

            NomadContainerTemplate container = new NomadContainerTemplate(taskName, image.trim());

            String command = matchGroup(COMMAND_PATTERN, body);
            if (command != null && !command.isBlank()) {
                container.setCommand(command.trim());
            }

            List<String> args = parseArgs(body);
            if (!args.isEmpty()) {
                container.setArgs(String.join(" ", args));
            }

            Integer cpu = parseInt(CPU_PATTERN, body);
            if (cpu != null) {
                container.setCpu(cpu);
            }

            Integer memoryMb = parseInt(MEMORY_PATTERN, body);
            if (memoryMb != null) {
                container.setMemoryMb(memoryMb);
            }

            containers.add(container);
        }

        return containers;
    }

    private static List<String> parseArgs(String body) {
        String rawList = matchGroup(ARGS_PATTERN, body);
        if (rawList == null || rawList.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        Matcher itemMatcher = ARG_ITEM_PATTERN.matcher(rawList);
        while (itemMatcher.find()) {
            args.add(unescape(itemMatcher.group(1)));
        }
        return args;
    }

    private static Integer parseInt(Pattern pattern, String text) {
        String value = matchGroup(pattern, text);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String matchGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String unescape(String value) {
        return value
                .replace("\\\\\"", "\"")
                .replace("\\\\n", "\n")
                .replace("\\\\r", "\r")
                .replace("\\\\t", "\t")
                .replace("\\\\\\\\", "\\");
    }

    private static int findMatchingBrace(String text, int openingBraceIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;

        for (int index = openingBraceIndex; index < text.length(); index++) {
            char current = text.charAt(index);

            if (escaping) {
                escaping = false;
                continue;
            }

            if (current == '\\') {
                if (inString) {
                    escaping = true;
                }
                continue;
            }

            if (current == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }

        return -1;
    }
}
