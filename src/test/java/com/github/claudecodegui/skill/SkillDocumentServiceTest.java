package com.github.claudecodegui.skill;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.HashingUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillDocumentServiceTest {

    @Test
    public void readReturnsProviderDeclaredSchemaAndRevision() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());

        JsonObject result = service.read(fixture.provider(), fixture.identity(), null);

        assertTrue(result.get("success").getAsBoolean());
        assertTrue(result.get("editable").getAsBoolean());
        assertEquals(7, result.getAsJsonArray("fields").size());
        assertEquals("claude", result.get("provider").getAsString());
        assertEquals(64, result.get("revision").getAsString().length());
        assertEquals("\n# Body\n", result.get("body").getAsString());
    }

    @Test
    public void savePreservesUnknownYamlAndCreatesBackup() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        JsonObject changes = new JsonObject();
        changes.addProperty("description", "Updated description");
        JsonArray paths = new JsonArray();
        paths.add("src/**");
        changes.add("paths", paths);

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), changes, "\n# Updated Body\n");

        assertTrue(result.get("success").getAsBoolean());
        assertTrue(result.get("changed").getAsBoolean());
        String persisted = Files.readString(fixture.file(), StandardCharsets.UTF_8);
        assertTrue(persisted.contains("x-provider-option: keep-me"));
        assertTrue(persisted.contains("description: \"Updated description\""));
        assertTrue(persisted.contains("paths:\n  - \"src/**\""));
        assertTrue(persisted.endsWith("\n# Updated Body\n"));
        Path backup = Path.of(result.get("backupPath").getAsString());
        assertTrue(Files.isRegularFile(backup));
        assertEquals(validSource(), Files.readString(backup, StandardCharsets.UTF_8));
    }

    @Test
    public void saveRejectsExternalEditConflictWithoutOverwrite() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        String externallyEdited = validSource().replace("Old description", "External description");
        Files.writeString(fixture.file(), externallyEdited, StandardCharsets.UTF_8);
        JsonObject changes = new JsonObject();
        changes.addProperty("description", "Editor description");

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), changes, "\n# Body\n");

        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("conflict").getAsBoolean());
        assertEquals(externallyEdited, Files.readString(fixture.file(), StandardCharsets.UTF_8));
    }

    @Test
    public void invalidYamlIsNeverOverwritten() throws Exception {
        String invalid = "---\nname: [broken\n---\nBody\n";
        Fixture fixture = fixture(invalid);
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        JsonObject changes = new JsonObject();
        changes.addProperty("name", "fixed-name");

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                revision(invalid), changes, "Body\n");

        assertFalse(read.get("success").getAsBoolean());
        assertTrue(read.get("parseError").getAsBoolean());
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(invalid, Files.readString(fixture.file(), StandardCharsets.UTF_8));
    }

    @Test
    public void postWriteVerificationFailureRestoresBackup() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new CorruptingWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        JsonObject changes = new JsonObject();
        changes.addProperty("description", "Updated description");

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), changes, "\n# Body\n");

        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("rolledBack").getAsBoolean());
        assertEquals(validSource(), Files.readString(fixture.file(), StandardCharsets.UTF_8));
    }

    @Test
    public void saveRejectsProviderUnsupportedFieldWithoutOverwrite() throws Exception {
        Fixture fixture = fixture(validSource(), SkillDocumentSchema.agentSkills());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        JsonObject changes = new JsonObject();
        changes.addProperty("user-invocable", false);

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), changes, read.get("body").getAsString());

        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("Unsupported frontmatter field"));
        assertEquals(validSource(), Files.readString(fixture.file(), StandardCharsets.UTF_8));
    }

    @Test
    public void saveRejectsBlankRequiredFieldWithoutOverwrite() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        JsonObject changes = new JsonObject();
        changes.addProperty("description", "   ");

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), changes, read.get("body").getAsString());

        assertFalse(result.get("success").getAsBoolean());
        assertEquals("description is required", result.get("error").getAsString());
        assertEquals(validSource(), Files.readString(fixture.file(), StandardCharsets.UTF_8));
    }

    @Test
    public void saveRejectsBodyOverCapacityWithoutOverwrite() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), new JsonObject(), "x".repeat(1_048_577));

        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("maximum length"));
        assertEquals(validSource(), Files.readString(fixture.file(), StandardCharsets.UTF_8));
    }

    @Test
    public void saveRejectsTooManyPathsWithoutOverwrite() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        JsonArray paths = new JsonArray();
        for (int index = 0; index < 257; index++) {
            paths.add("path-" + index);
        }
        JsonObject changes = new JsonObject();
        changes.add("paths", paths);

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), changes, read.get("body").getAsString());

        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("too many entries"));
        assertEquals(validSource(), Files.readString(fixture.file(), StandardCharsets.UTF_8));
    }

    @Test
    public void saveNoOpDoesNotCreateBackup() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), new JsonObject(),
                read.get("body").getAsString());

        assertTrue(result.get("success").getAsBoolean());
        assertFalse(result.get("changed").getAsBoolean());
        assertFalse(result.has("backupPath"));
        assertEquals(read.get("revision").getAsString(), result.get("revision").getAsString());
        assertEquals(validSource(), Files.readString(fixture.file(), StandardCharsets.UTF_8));
    }

    @Test
    public void savePreservesCrLfWhenBrowserNormalizesBodyLineEndings() throws Exception {
        String source = "---\r\n"
                + "name: sample\r\n"
                + "description: Old description\r\n"
                + "---\r\n"
                + "\r\n# Body\r\n";
        Fixture fixture = fixture(source);
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        JsonObject changes = new JsonObject();
        changes.addProperty("description", "Updated description");

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), changes, "\n# Body\n");

        assertTrue(result.get("success").getAsBoolean());
        String persisted = Files.readString(fixture.file(), StandardCharsets.UTF_8);
        assertEquals("---\r\n"
                + "name: sample\r\n"
                + "description: \"Updated description\"\r\n"
                + "---\r\n"
                + "\r\n# Body\r\n", persisted);
    }

    @Test
    public void saveWithMissingBodyPreservesExistingBody() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        JsonObject changes = new JsonObject();
        changes.addProperty("description", "Updated description");

        JsonObject result = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), changes, null);

        assertTrue(result.get("success").getAsBoolean());
        assertTrue(Files.readString(fixture.file(), StandardCharsets.UTF_8)
                .endsWith("\n# Body\n"));
    }

    @Test
    public void saveRejectsNonStringTextAndListEntries() throws Exception {
        Fixture fixture = fixture(validSource());
        SkillDocumentService service = new SkillDocumentService(new AtomicSkillDocumentWriter());
        JsonObject read = service.read(fixture.provider(), fixture.identity(), null);
        JsonObject textChanges = new JsonObject();
        textChanges.addProperty("description", 42);

        JsonObject textResult = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), textChanges, read.get("body").getAsString());

        assertFalse(textResult.get("success").getAsBoolean());
        assertEquals(validSource(), Files.readString(fixture.file(), StandardCharsets.UTF_8));

        JsonArray paths = new JsonArray();
        paths.add(42);
        JsonObject listChanges = new JsonObject();
        listChanges.add("paths", paths);
        JsonObject listResult = service.save(fixture.provider(), fixture.identity(), null,
                read.get("revision").getAsString(), listChanges, read.get("body").getAsString());

        assertFalse(listResult.get("success").getAsBoolean());
        assertEquals(validSource(), Files.readString(fixture.file(), StandardCharsets.UTF_8));
    }

    private Fixture fixture(String content) throws IOException {
        return fixture(content, SkillDocumentSchema.full());
    }

    private Fixture fixture(String content, SkillDocumentSchema schema) throws IOException {
        Path root = Files.createTempDirectory("skill-document-root");
        Path directory = Files.createDirectories(root.resolve("sample"));
        Path file = Files.writeString(directory.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
        SkillDocumentIdentity identity = new SkillDocumentIdentity(
                SkillScopeType.GLOBAL.value(), "sample", directory.toString(), file.toString(), true);
        return new Fixture(file, identity, new TestProvider(root, schema));
    }

    private String revision(String content) {
        return HashingUtil.sha256Hex(content);
    }

    private String validSource() {
        return "---\n"
                + "name: sample\n"
                + "description: Old description\n"
                + "x-provider-option: keep-me\n"
                + "---\n"
                + "\n# Body\n";
    }

    private record Fixture(Path file, SkillDocumentIdentity identity, UnifiedSkillService provider) {
    }

    private static final class TestProvider implements UnifiedSkillService {
        private final Path root;
        private final SkillDocumentSchema schema;

        private TestProvider(Path root, SkillDocumentSchema schema) {
            this.root = root;
            this.schema = schema;
        }

        @Override
        public ProviderType provider() {
            return ProviderType.CLAUDE;
        }

        @Override
        public JsonObject getAllSkills(String cwd) {
            return new JsonObject();
        }

        @Override
        public SkillDocumentSchema skillDocumentSchema() {
            return schema;
        }

        @Override
        public SkillDocumentTarget resolveSkillDocument(SkillDocumentIdentity identity, String cwd)
                throws SkillDocumentAccessException {
            return SkillDocumentPathPolicy.resolve(Path.of(identity.skillPath()), List.of(root));
        }

        @Override
        public JsonObject importSkills(List<String> sourcePaths, String scope, String cwd) {
            return new JsonObject();
        }

        @Override
        public JsonObject deleteSkill(SkillId id, boolean enabled, String cwd) {
            return new JsonObject();
        }

        @Override
        public JsonObject toggleSkill(SkillId id, boolean currentEnabled, String cwd) {
            return new JsonObject();
        }
    }

    private static final class CorruptingWriter implements SkillDocumentWriter {
        @Override
        public Path write(Path target, String content) throws IOException {
            Path backup = target.resolveSibling(target.getFileName() + ".test.bak");
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(target, "---\nname: [corrupt\n---\n", StandardCharsets.UTF_8);
            return backup;
        }

        @Override
        public void restore(Path target, Path backup) throws IOException {
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
