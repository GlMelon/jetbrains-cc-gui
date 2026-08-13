package com.github.claudecodegui.provider;

import com.github.claudecodegui.session.runtime.RuntimeType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ProviderDescriptorLoader 配置解析与容错测试。
 */
public class ProviderDescriptorLoaderTest {

    @Test
    public void parsesValidCustomProvider() {
        JsonObject acme = new JsonObject();
        acme.addProperty("id", "acme");
        acme.addProperty("label", "Acme");
        acme.addProperty("cliCommand", "acme");
        acme.addProperty("cliCommandWindows", "acme.cmd");
        acme.add("capabilities", strArray("CLI_SESSION", "STREAMING", "HISTORY"));
        acme.add("runtimes", strArray("CLI"));

        List<ProviderDescriptor> result = ProviderDescriptorLoader.fromJsonArray(arr(acme));

        assertEquals(1, result.size());
        ProviderDescriptor d = result.get(0);
        assertEquals("acme", d.providerId());
        assertEquals("Acme", d.displayLabel());
        assertEquals("acme", d.cliCommand());
        assertEquals("acme.cmd", d.cliCommandWindows());
        assertEquals(EnumSet.of(ProviderCapability.CLI_SESSION, ProviderCapability.STREAMING, ProviderCapability.HISTORY),
                d.capabilities());
        assertTrue(d.supports(RuntimeType.CLI));
    }

    @Test
    public void appliesDefaultsForMissingOptionalFields() {
        JsonObject minimal = new JsonObject();
        minimal.addProperty("id", "mistral");

        List<ProviderDescriptor> result = ProviderDescriptorLoader.fromJsonArray(arr(minimal));

        assertEquals(1, result.size());
        ProviderDescriptor d = result.get(0);
        assertEquals("mistral", d.displayLabel()); // label 缺省回退 id
        assertEquals("mistral", d.cliCommand()); // cliCommand 缺省回退 id
        assertEquals("mistral.cmd", d.cliCommandWindows()); // 缺省 cliCommand + ".cmd"
        assertTrue(d.capabilities().isEmpty());
        assertTrue(d.supportedRuntimes().isEmpty());
    }

    @Test
    public void skipsEntriesMissingId() {
        JsonObject noId = new JsonObject();
        noId.addProperty("label", "NoId");

        List<ProviderDescriptor> result = ProviderDescriptorLoader.fromJsonArray(arr(noId));

        assertEquals(0, result.size());
    }

    @Test
    public void skipsEntriesWithBlankId() {
        JsonObject blankId = new JsonObject();
        blankId.addProperty("id", "   ");

        assertEquals(0, ProviderDescriptorLoader.fromJsonArray(arr(blankId)).size());
    }

    @Test
    public void ignoresUnknownCapabilityAndRuntimeValues() {
        JsonObject d = new JsonObject();
        d.addProperty("id", "weird");
        JsonArray caps = strArray("CLI_SESSION", "NOT_A_REAL_CAPABILITY");
        JsonArray runtimes = strArray("CLI", "NOT_A_RUNTIME");
        d.add("capabilities", caps);
        d.add("runtimes", runtimes);

        List<ProviderDescriptor> result = ProviderDescriptorLoader.fromJsonArray(arr(d));

        assertEquals(1, result.size());
        assertEquals(EnumSet.of(ProviderCapability.CLI_SESSION), result.get(0).capabilities());
        assertEquals(EnumSet.of(RuntimeType.CLI), result.get(0).supportedRuntimes());
    }

    @Test
    public void skipsNonObjectArrayElements() {
        JsonArray array = new JsonArray();
        array.add("not-an-object");
        array.add(42);

        assertEquals(0, ProviderDescriptorLoader.fromJsonArray(array).size());
    }

    @Test
    public void nullArrayReturnsEmpty() {
        assertTrue(ProviderDescriptorLoader.fromJsonArray(null).isEmpty());
    }

    @Test
    public void mixedValidAndInvalidEntries() {
        JsonObject valid = new JsonObject();
        valid.addProperty("id", "valid-one");
        JsonObject invalid = new JsonObject(); // 缺 id
        JsonObject valid2 = new JsonObject();
        valid2.addProperty("id", "valid-two");

        List<ProviderDescriptor> result = ProviderDescriptorLoader.fromJsonArray(arr(valid, invalid, valid2));

        assertEquals(2, result.size());
        assertEquals("valid-one", result.get(0).providerId());
        assertEquals("valid-two", result.get(1).providerId());
    }

    private static JsonArray arr(JsonObject... objects) {
        JsonArray array = new JsonArray();
        for (JsonObject o : objects) {
            array.add(o);
        }
        return array;
    }

    private static JsonArray strArray(String... values) {
        JsonArray array = new JsonArray();
        for (String v : values) {
            array.add(v);
        }
        return array;
    }
}
