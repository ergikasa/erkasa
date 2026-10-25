package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.erkasa.backend.features.promptvideo.script.SceneCatalog;
import com.erkasa.backend.features.promptvideo.script.SceneContract;
import com.erkasa.backend.features.promptvideo.script.ScriptSchema;
import com.erkasa.backend.features.promptvideo.script.Visual;
import com.erkasa.backend.jobs.VideoOptions.Template;

/**
 * The scene contract (contracts/scene-catalog.json) must match the backend's catalog.
 *
 * When it does not, the test fails and says how to update it. To write the file:
 *   PowerShell:  $env:CONTRACT_UPDATE="1"; .\mvnw.cmd test -Dtest=SceneContractTests; Remove-Item Env:CONTRACT_UPDATE
 * then commit contracts/scene-catalog.json. The render worker and frontend tests then check their
 * own lists against the new file.
 */
class SceneContractTests {

    /** The repository root: tests run in backend/ (Maven) or in the root (some IDE setups). */
    private static Path contractFile() {
        Path here = Path.of("").toAbsolutePath();
        Path root = Files.exists(here.resolve("backend")) ? here : here.getParent();
        return root.resolve(SceneContract.FILE);
    }

    private static boolean updateRequested() {
        return "1".equals(System.getenv("CONTRACT_UPDATE")) || Boolean.getBoolean("contract.update");
    }

    @Test
    void theContractFileMatchesTheCatalog() throws IOException {
        Path file = contractFile();
        String expected = SceneContract.json();

        if (updateRequested()) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, expected, StandardCharsets.UTF_8);
        }

        assertThat(file).as("""
                %s is missing. Create it with:
                  $env:CONTRACT_UPDATE="1"; .\\mvnw.cmd test -Dtest=SceneContractTests; Remove-Item Env:CONTRACT_UPDATE
                """.formatted(SceneContract.FILE)).exists();
        // compare without caring about Windows line endings (git may check the file out with CRLF)
        String actual = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual).as("""
                %s is out of date: the scene catalog changed. Update it with:
                  $env:CONTRACT_UPDATE="1"; .\\mvnw.cmd test -Dtest=SceneContractTests; Remove-Item Env:CONTRACT_UPDATE
                then commit the file (and raise SceneCatalog.CONTRACT_VERSION if a name changed).
                """.formatted(SceneContract.FILE)).isEqualTo(expected);
    }

    @Test
    void everyTypeDeclaresItsFieldsAndTheyExist() {
        assertThat(SceneCatalog.FIELDS.keySet()).as("SceneCatalog.FIELDS covers every Visual type")
                .containsExactlyInAnyOrderElementsOf(Visual.TYPES);

        Set<String> recordFields = Arrays.stream(Visual.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        Set<String> schemaFields = visualSchemaFields();
        SceneCatalog.FIELDS.forEach((type, fields) -> {
            assertThat(recordFields).as("fields of %s exist on Visual", type).containsAll(fields);
            assertThat(schemaFields).as("fields of %s are in the LLM schema", type).containsAll(fields);
        });
    }

    @Test
    void everyStyleUsesOnlyKnownTypesAndEveryTypeHasAStyle() {
        for (Template t : Template.values()) {
            assertThat(Visual.TYPES).as("types of %s", t).containsAll(SceneCatalog.types(t));
        }
        Set<String> used = Arrays.stream(Template.values())
                .flatMap(t -> SceneCatalog.types(t).stream()).collect(Collectors.toSet());
        assertThat(used).as("every type can be chosen in at least one style").containsAll(Visual.TYPES);
    }

    @Test
    void theJsonIsStableAndSorted() {
        String json = SceneContract.json();
        assertThat(json).isEqualTo(SceneContract.json());
        assertThat(json).startsWith("{\n  \"version\": " + SceneCatalog.CONTRACT_VERSION + ",")
                .endsWith("}\n")
                .contains("\"exchange\": {", "\"ANIMATED_2D\": [", "\"musicMoods\": [");
        List<String> icons = SceneCatalog.ICONS.stream().sorted().toList();
        assertThat(json.indexOf("\"" + icons.get(0) + "\"")).isLessThan(json.indexOf("\"" + icons.get(icons.size() - 1) + "\""));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> visualSchemaFields() {
        Map<String, Object> scenes = (Map<String, Object>) ((Map<String, Object>) ScriptSchema.schema(Template.ANIMATED_2D)
                .get("properties")).get("scenes");
        Map<String, Object> scene = (Map<String, Object>) scenes.get("items");
        Map<String, Object> visual = (Map<String, Object>) ((Map<String, Object>) scene.get("properties")).get("visual");
        return ((Map<String, Object>) visual.get("properties")).keySet();
    }
}
