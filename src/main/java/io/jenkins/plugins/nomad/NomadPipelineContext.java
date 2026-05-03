package io.jenkins.plugins.nomad;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

final class NomadPipelineContext {
    static final String TEMPLATE_LABEL_ENV = "NOMAD_TEMPLATE_LABEL";
    static final String CONTAINER_NAMES_ENV = "NOMAD_CONTAINER_NAMES";
    static final String ACTIVE_CONTAINER_ENV = "NOMAD_ACTIVE_CONTAINER";
    private static final Object SCOPED_CONTAINERS_LOCK = new Object();
    private static final Map<String, LinkedHashMap<String, List<NomadContainerTemplate>>> SCOPED_CONTAINERS_BY_LABEL =
            new HashMap<>();
        private static final LinkedHashMap<String, List<NomadContainerTemplate>> SCOPED_CONTAINERS_BY_SCOPE =
            new LinkedHashMap<>();

    private NomadPipelineContext() {}

    static String encodeContainerNames(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        return names.stream().sorted().collect(Collectors.joining(","));
    }

    static Set<String> decodeContainerNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    static String registerTemplateScope(String label, List<NomadContainerTemplate> containers) {
        String normalizedLabel = normalizeLabel(label);
        String scopeId = UUID.randomUUID().toString();
        List<NomadContainerTemplate> snapshot = cloneContainers(containers);

        synchronized (SCOPED_CONTAINERS_LOCK) {
            SCOPED_CONTAINERS_BY_LABEL
                    .computeIfAbsent(normalizedLabel, key -> new LinkedHashMap<>())
                    .put(scopeId, snapshot);
            SCOPED_CONTAINERS_BY_SCOPE.put(scopeId, snapshot);
        }
        return scopeId;
    }

    static void unregisterTemplateScope(String label, String scopeId) {
        if (scopeId == null || scopeId.isBlank()) {
            return;
        }
        String normalizedLabel = normalizeLabel(label);
        synchronized (SCOPED_CONTAINERS_LOCK) {
            LinkedHashMap<String, List<NomadContainerTemplate>> scopes = SCOPED_CONTAINERS_BY_LABEL.get(normalizedLabel);
            if (scopes == null) {
                return;
            }
            scopes.remove(scopeId);
            SCOPED_CONTAINERS_BY_SCOPE.remove(scopeId);
            if (scopes.isEmpty()) {
                SCOPED_CONTAINERS_BY_LABEL.remove(normalizedLabel);
            }
        }
    }

    static List<NomadContainerTemplate> getCurrentContainersForLabel(String label) {
        String normalizedLabel = normalizeLabel(label);
        synchronized (SCOPED_CONTAINERS_LOCK) {
            LinkedHashMap<String, List<NomadContainerTemplate>> scopes = SCOPED_CONTAINERS_BY_LABEL.get(normalizedLabel);
            if (scopes == null || scopes.isEmpty()) {
                return List.of();
            }
            List<NomadContainerTemplate> latest = null;
            for (List<NomadContainerTemplate> value : scopes.values()) {
                latest = value;
            }
            return latest == null ? List.of() : cloneContainers(latest);
        }
    }

    static List<NomadContainerTemplate> getMostRecentContainers() {
        synchronized (SCOPED_CONTAINERS_LOCK) {
            if (SCOPED_CONTAINERS_BY_SCOPE.isEmpty()) {
                return List.of();
            }
            List<NomadContainerTemplate> latest = null;
            for (List<NomadContainerTemplate> value : SCOPED_CONTAINERS_BY_SCOPE.values()) {
                latest = value;
            }
            return latest == null ? List.of() : cloneContainers(latest);
        }
    }

    static void clearTemplateScopesForTests() {
        synchronized (SCOPED_CONTAINERS_LOCK) {
            SCOPED_CONTAINERS_BY_LABEL.clear();
            SCOPED_CONTAINERS_BY_SCOPE.clear();
        }
    }

    private static String normalizeLabel(String label) {
        return label == null || label.isBlank() ? "nomad" : label.trim();
    }

    private static List<NomadContainerTemplate> cloneContainers(List<NomadContainerTemplate> containers) {
        if (containers == null || containers.isEmpty()) {
            return List.of();
        }
        List<NomadContainerTemplate> copy = new ArrayList<>();
        for (NomadContainerTemplate container : containers) {
            if (container == null) {
                continue;
            }
            NomadContainerTemplate cloned = new NomadContainerTemplate(container.getName(), container.getImage());
            cloned.setCommand(container.getCommand());
            cloned.setArgs(container.getArgs());
            cloned.setCpu(container.getCpu());
            cloned.setMemoryMb(container.getMemoryMb());
            copy.add(cloned);
        }
        return copy;
    }
}
