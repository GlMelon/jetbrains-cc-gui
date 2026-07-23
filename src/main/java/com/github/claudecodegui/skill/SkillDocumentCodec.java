package com.github.claudecodegui.skill;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loss-minimizing SKILL.md frontmatter parser and editor. */
final class SkillDocumentCodec {

    private static final Gson GSON = GsonHolder.GSON;
    private static final String DELIMITER = "---";
    private static final String UTF8_BOM = "\uFEFF";
    private static final String CRLF = "\r\n";
    private static final String LF = "\n";
    private static final Pattern TOP_LEVEL_KEY =
            Pattern.compile("^([A-Za-z0-9_-]+)[ \\t]*:.*$");
    private static final int FRONTMATTER_CODE_POINT_LIMIT = 65_536;

    ParsedDocument parse(String content) throws SkillDocumentFormatException {
        if (content == null) {
            throw new SkillDocumentFormatException("Skill document content is missing");
        }
        String lineSeparator = content.contains(CRLF) ? CRLF : LF;
        int openingEnd = findLineEnd(content, 0);
        if (openingEnd < 0) {
            throw new SkillDocumentFormatException("SKILL.md must start with YAML frontmatter");
        }
        String openingLine = stripLineEnding(content.substring(0, openingEnd));
        if (openingLine.startsWith(UTF8_BOM)) {
            openingLine = openingLine.substring(UTF8_BOM.length());
        }
        if (!DELIMITER.equals(openingLine.trim())) {
            throw new SkillDocumentFormatException("SKILL.md must start with YAML frontmatter");
        }

        int closingStart = findClosingDelimiter(content, openingEnd);
        if (closingStart < 0) {
            throw new SkillDocumentFormatException("SKILL.md frontmatter has no closing delimiter");
        }
        int closingEnd = findLineEnd(content, closingStart);
        if (closingEnd < 0) {
            closingEnd = content.length();
        }

        String prefix = content.substring(0, openingEnd);
        String rawYaml = content.substring(openingEnd, closingStart);
        String yaml = removeTrailingLineEnding(rawYaml);
        String closing = content.substring(closingStart, closingEnd);
        String body = content.substring(closingEnd);
        rejectDuplicateEditableFields(yaml);
        Map<String, Object> yamlMap = parseYamlMap(yaml);
        return new ParsedDocument(content, lineSeparator, prefix, yaml, closing, body, yamlMap);
    }

    String render(ParsedDocument document, Map<SkillFrontmatterField, Object> changes, String body)
            throws SkillDocumentFormatException {
        String updatedYaml = applyChanges(document.yaml(), document.lineSeparator(), changes);
        rejectDuplicateEditableFields(updatedYaml);
        parseYamlMap(updatedYaml);
        String updatedBody = body == null
                ? document.body() : normalizeLineSeparators(body, document.lineSeparator());
        return document.prefix() + updatedYaml + document.lineSeparator()
                + document.closing() + updatedBody;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYamlMap(String yaml) throws SkillDocumentFormatException {
        try {
            LoadSettings settings = LoadSettings.builder()
                    .setMaxAliasesForCollections(0)
                    .setCodePointLimit(FRONTMATTER_CODE_POINT_LIMIT)
                    .build();
            Object parsed = new Load(settings).loadFromString(yaml);
            if (!(parsed instanceof Map<?, ?> parsedMap)) {
                throw new SkillDocumentFormatException("SKILL.md frontmatter must be a YAML mapping");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : parsedMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new SkillDocumentFormatException("SKILL.md frontmatter keys must be strings");
                }
                result.put(key, entry.getValue());
            }
            return result;
        } catch (SkillDocumentFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillDocumentFormatException("Invalid YAML frontmatter: " + e.getMessage(), e);
        }
    }

    private String applyChanges(String yaml, String lineSeparator,
                                Map<SkillFrontmatterField, Object> changes)
            throws SkillDocumentFormatException {
        if (changes.isEmpty()) {
            return yaml;
        }
        List<String> lines = splitLines(yaml);
        Map<String, FieldBlock> blocks = findFieldBlocks(lines);
        Map<Integer, Replacement> replacements = new HashMap<>();
        List<String> appended = new ArrayList<>();

        for (Map.Entry<SkillFrontmatterField, Object> entry : changes.entrySet()) {
            SkillFrontmatterField field = entry.getKey();
            List<String> rendered = renderField(field, entry.getValue());
            FieldBlock block = blocks.get(field.key());
            if (block == null) {
                appended.addAll(rendered);
            } else {
                replacements.put(block.start(), new Replacement(block.end(), rendered));
            }
        }

        List<String> output = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            Replacement replacement = replacements.get(index);
            if (replacement == null) {
                output.add(lines.get(index));
                index++;
            } else {
                output.addAll(replacement.lines());
                index = replacement.end();
            }
        }
        if (!appended.isEmpty()) {
            output.addAll(appended);
        }
        return String.join(lineSeparator, output);
    }

    private Map<String, FieldBlock> findFieldBlocks(List<String> lines)
            throws SkillDocumentFormatException {
        List<KeyLine> keys = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = TOP_LEVEL_KEY.matcher(lines.get(index));
            if (matcher.matches()) {
                keys.add(new KeyLine(matcher.group(1), index));
            }
        }

        Map<String, FieldBlock> blocks = new HashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            KeyLine current = keys.get(index);
            int nextStart = index + 1 < keys.size() ? keys.get(index + 1).line() : lines.size();
            int contentEnd = nextStart;
            while (contentEnd > current.line() + 1 && isDetachedTrivia(lines.get(contentEnd - 1))) {
                contentEnd--;
            }
            FieldBlock previous = blocks.put(current.key(), new FieldBlock(current.line(), contentEnd));
            if (previous != null && isEditableKey(current.key())) {
                throw new SkillDocumentFormatException(
                        "Duplicate editable frontmatter field: " + current.key());
            }
        }
        return blocks;
    }

    private void rejectDuplicateEditableFields(String yaml) throws SkillDocumentFormatException {
        findFieldBlocks(splitLines(yaml));
    }

    private List<String> renderField(SkillFrontmatterField field, Object value)
            throws SkillDocumentFormatException {
        if (isEmptyOptionalValue(field, value)) {
            return List.of();
        }
        return switch (field.control()) {
            case TEXT, TEXTAREA -> List.of(field.key() + ": " + GSON.toJson(value));
            case BOOLEAN -> List.of(field.key() + ": " + value);
            case STRING_LIST -> renderStringList(field, value);
        };
    }

    private List<String> renderStringList(SkillFrontmatterField field, Object value)
            throws SkillDocumentFormatException {
        if (!(value instanceof List<?> values)) {
            throw new SkillDocumentFormatException(field.key() + " must be a string list");
        }
        if (values.isEmpty()) {
            return List.of();
        }
        List<String> rendered = new ArrayList<>();
        rendered.add(field.key() + ":");
        for (Object item : values) {
            rendered.add("  - " + GSON.toJson(item));
        }
        return rendered;
    }

    private boolean isEmptyOptionalValue(SkillFrontmatterField field, Object value) {
        if (field.required()) {
            return false;
        }
        if (value == null) {
            return true;
        }
        if (value instanceof String stringValue) {
            return stringValue.isBlank();
        }
        return value instanceof List<?> listValue && listValue.isEmpty();
    }

    private boolean isEditableKey(String key) {
        for (SkillFrontmatterField field : SkillFrontmatterField.values()) {
            if (field.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDetachedTrivia(String line) {
        if (line.isBlank()) {
            return true;
        }
        return !Character.isWhitespace(line.charAt(0)) && line.startsWith("#");
    }

    private List<String> splitLines(String value) {
        String[] split = value.split("\\r?\\n", -1);
        List<String> lines = new ArrayList<>(List.of(split));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private int findClosingDelimiter(String content, int start) {
        int current = start;
        while (current < content.length()) {
            int end = findLineEnd(content, current);
            if (end < 0) {
                end = content.length();
            }
            String line = stripLineEnding(content.substring(current, end));
            if (DELIMITER.equals(line.trim())) {
                return current;
            }
            current = end;
        }
        return -1;
    }

    private int findLineEnd(String value, int start) {
        int newline = value.indexOf('\n', start);
        return newline < 0 ? -1 : newline + 1;
    }

    private String normalizeLineSeparators(String value, String lineSeparator) {
        String normalized = value.replace(CRLF, LF).replace('\r', '\n');
        return CRLF.equals(lineSeparator) ? normalized.replace(LF, CRLF) : normalized;
    }

    private String stripLineEnding(String value) {
        if (value.endsWith(CRLF)) {
            return value.substring(0, value.length() - CRLF.length());
        }
        if (value.endsWith(LF)) {
            return value.substring(0, value.length() - LF.length());
        }
        return value;
    }

    private String removeTrailingLineEnding(String value) {
        return stripLineEnding(value);
    }

    record ParsedDocument(
            String originalContent,
            String lineSeparator,
            String prefix,
            String yaml,
            String closing,
            String body,
            Map<String, Object> yamlMap
    ) {
        ParsedDocument {
            yamlMap = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(yamlMap));
        }
    }

    private record KeyLine(String key, int line) {
    }

    private record FieldBlock(int start, int end) {
    }

    private record Replacement(int end, List<String> lines) {
    }
}
