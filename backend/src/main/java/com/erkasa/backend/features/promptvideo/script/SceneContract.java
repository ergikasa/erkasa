package com.erkasa.backend.features.promptvideo.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.erkasa.backend.jobs.VideoOptions.Template;

/**
 * The scene contract: one JSON file that says which scene types exist, which fields each one
 * fills, which visual styles may use it, and the icons, poses, sounds and music moods the LLM may
 * pick. Written to contracts/scene-catalog.json at the repository root.
 *
 * The backend is the source (SceneCatalog, SoundEffects, ScriptSchema). The render worker and the
 * frontend have tests that read the file and fail when their own lists differ from it, so a name
 * that changes in one place can no longer break videos silently.
 *
 * The output is deterministic (sorted keys and lists, fixed layout), so the file only changes in
 * git when the contract really changes.
 */
public final class SceneContract {

    /** Path of the file, from the repository root. */
    public static final String FILE = "contracts/scene-catalog.json";

    /** Lists up to this long are written on one line. */
    private static final int SHORT_LIST = 8;

    private SceneContract() {
    }

    /** The contract as ordered maps and lists (also what a future /api endpoint can return). */
    public static Map<String, Object> build() {
        Map<String, Object> types = new LinkedHashMap<>();
        for (String type : new TreeSet<>(Visual.TYPES)) {
            List<String> styles = new ArrayList<>();
            for (Template t : Template.values()) {
                if (SceneCatalog.types(t).contains(type)) {
                    styles.add(t.name());
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fields", SceneCatalog.FIELDS.getOrDefault(type, List.of()));
            entry.put("styles", styles);
            types.put(type, entry);
        }
        Map<String, Object> styles = new LinkedHashMap<>();
        for (Template t : Template.values()) {
            styles.put(t.name(), sorted(SceneCatalog.types(t)));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", SceneCatalog.CONTRACT_VERSION);
        root.put("generatedBy", "backend SceneContract (run SceneContractTests to update)");
        root.put("types", types);
        root.put("styles", styles);
        root.put("icons", sorted(SceneCatalog.ICONS));
        root.put("poses", sorted(SceneCatalog.POSES));
        root.put("sounds", sorted(SoundEffects.SOUNDS));
        root.put("musicMoods", sorted(ScriptSchema.MUSIC_MOODS));
        return root;
    }

    /** The file's text: 2-space indent, short lists on one line, long ones one value per line. */
    public static String json() {
        StringBuilder sb = new StringBuilder();
        write(sb, build(), 0);
        return sb.append('\n').toString();
    }

    private static List<String> sorted(List<String> list) {
        return List.copyOf(new TreeSet<>(list));
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                pad(sb, indent + 2).append(quote(e.getKey())).append(": ");
                write(sb, e.getValue(), indent + 2);
                sb.append(++i < map.size() ? ",\n" : "\n");
            }
            pad(sb, indent).append('}');
        } else if (value instanceof List<?> list) {
            if (list.size() <= SHORT_LIST) {
                // short lists stay on one line
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(", ");
                    write(sb, list.get(i), indent);
                }
                sb.append(']');
            } else {
                // long lists (icons, sounds): one value per line, so a diff shows exactly what changed
                sb.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    pad(sb, indent + 2);
                    write(sb, list.get(i), indent + 2);
                    sb.append(i + 1 < list.size() ? ",\n" : "\n");
                }
                pad(sb, indent).append(']');
            }
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append(quote(String.valueOf(value)));
        }
    }

    private static StringBuilder pad(StringBuilder sb, int n) {
        return sb.append(" ".repeat(n));
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
