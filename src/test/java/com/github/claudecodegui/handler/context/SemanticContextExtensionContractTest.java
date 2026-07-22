package com.github.claudecodegui.handler.context;

import com.github.claudecodegui.handler.file.ClassNavigationProvider;
import com.github.claudecodegui.handler.file.JavaClassNavigationSupport;
import com.github.claudecodegui.handler.file.OpenClassHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.junit.Test;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Guards the optional language-provider extension point and its classloader boundary. */
public class SemanticContextExtensionContractTest {

    private static final String PLUGIN_ID = "com.github.idea-claude-code-gui";
    private static final String SEMANTIC_EP_NAME = "semanticContextProvider";
    private static final String SEMANTIC_EP_QUALIFIED_NAME = PLUGIN_ID + "." + SEMANTIC_EP_NAME;
    private static final String CLASS_NAVIGATION_EP_NAME = "classNavigationProvider";
    private static final String CLASS_NAVIGATION_EP_QUALIFIED_NAME =
            PLUGIN_ID + "." + CLASS_NAVIGATION_EP_NAME;
    private static final String JAVA_PLUGIN_ID = "com.intellij.java";
    private static final String PYTHON_PLUGIN_ID = "com.intellij.modules.python";
    private static final String JAVA_DESCRIPTOR = "java-features.xml";
    private static final String PYTHON_DESCRIPTOR = "python-features.xml";
    private static final String JAVA_COLLECTOR = JavaContextCollector.class.getName();
    private static final String PYTHON_COLLECTOR = PythonContextCollector.class.getName();
    private static final String JAVA_NAVIGATION_PROVIDER = JavaClassNavigationSupport.class.getName();

    @Test
    public void coreDescriptorDeclaresDynamicSemanticContextExtensionPoint() throws Exception {
        org.w3c.dom.Document plugin = parseDescriptor("plugin.xml");
        Element extensionPoint = findElementByAttribute(plugin, "extensionPoint", "name", SEMANTIC_EP_NAME);

        assertNotNull("semantic context extension point must be declared", extensionPoint);
        assertEquals(SemanticContextProvider.class.getName(), extensionPoint.getAttribute("interface"));
        assertEquals("true", extensionPoint.getAttribute("dynamic"));
        assertEquals(SEMANTIC_EP_QUALIFIED_NAME, SemanticContextProvider.EP_NAME.getName());

        String coreDescriptor = readDescriptor("plugin.xml");
        assertFalse("core descriptor must not load the Java collector", coreDescriptor.contains(JAVA_COLLECTOR));
        assertFalse("core descriptor must not load the Python collector", coreDescriptor.contains(PYTHON_COLLECTOR));
    }

    @Test
    public void coreDescriptorDeclaresDynamicClassNavigationExtensionPoint() throws Exception {
        org.w3c.dom.Document plugin = parseDescriptor("plugin.xml");
        Element extensionPoint = findElementByAttribute(
                plugin,
                "extensionPoint",
                "name",
                CLASS_NAVIGATION_EP_NAME
        );

        assertNotNull("class navigation extension point must be declared", extensionPoint);
        assertEquals(ClassNavigationProvider.class.getName(), extensionPoint.getAttribute("interface"));
        assertEquals("true", extensionPoint.getAttribute("dynamic"));
        assertEquals(CLASS_NAVIGATION_EP_QUALIFIED_NAME, ClassNavigationProvider.EP_NAME.getName());

        String coreDescriptor = readDescriptor("plugin.xml");
        assertFalse(
                "core descriptor must not load the Java navigation provider",
                coreDescriptor.contains(JAVA_NAVIGATION_PROVIDER)
        );
    }

    @Test
    public void optionalDependenciesOwnOnlyTheirMatchingCollector() throws Exception {
        org.w3c.dom.Document plugin = parseDescriptor("plugin.xml");
        assertOptionalDependency(plugin, JAVA_PLUGIN_ID, JAVA_DESCRIPTOR);
        assertOptionalDependency(plugin, PYTHON_PLUGIN_ID, PYTHON_DESCRIPTOR);

        assertCollectorRegistration(JAVA_DESCRIPTOR, JAVA_COLLECTOR, PYTHON_COLLECTOR);
        assertCollectorRegistration(PYTHON_DESCRIPTOR, PYTHON_COLLECTOR, JAVA_COLLECTOR);
    }

    @Test
    public void javaOptionalDescriptorOwnsClassNavigationProvider() throws Exception {
        org.w3c.dom.Document javaDescriptor = parseDescriptor(JAVA_DESCRIPTOR);
        NodeList registrations = javaDescriptor.getElementsByTagName(CLASS_NAVIGATION_EP_NAME);
        List<String> implementations = new ArrayList<>();
        for (int i = 0; i < registrations.getLength(); i++) {
            implementations.add(((Element) registrations.item(i)).getAttribute("implementation"));
        }

        assertEquals(List.of(JAVA_NAVIGATION_PROVIDER), implementations);
        assertFalse(readDescriptor(PYTHON_DESCRIPTOR).contains(JAVA_NAVIGATION_PROVIDER));
    }

    @Test
    public void classNavigationContractPreservesOptionalJavaBoundary() throws Exception {
        ClassNavigationProvider.class.getDeclaredMethod(
                "navigate",
                Project.class,
                String.class,
                Consumer.class
        );
        assertTrue(ClassNavigationProvider.class.isAssignableFrom(JavaClassNavigationSupport.class));

        String providerSource = Files.readString(
                Path.of("src/main/java/com/github/claudecodegui/handler/file/ClassNavigationProvider.java"),
                StandardCharsets.UTF_8
        );
        assertFalse(providerSource.contains("com.intellij.psi."));

        String handlerSource = Files.readString(
                Path.of("src/main/java/com/github/claudecodegui/handler/file/OpenClassHandler.java"),
                StandardCharsets.UTF_8
        );
        assertFalse(handlerSource.contains("Class.forName"));
        assertFalse(handlerSource.contains("JavaClassNavigationSupport"));
        assertTrue(handlerSource.contains("ClassNavigationProvider.EP_NAME.getExtensionList()"));
    }

    @Test
    public void providerContractDoesNotDependOnLanguagePluginTypes() throws Exception {
        SemanticContextProvider.class.getDeclaredMethod(
                "collectSemanticContext",
                JsonObject.class,
                Editor.class,
                Project.class,
                PsiFile.class,
                Document.class
        );
        assertTrue(SemanticContextProvider.class.isAssignableFrom(JavaContextCollector.class));
        assertTrue(SemanticContextProvider.class.isAssignableFrom(PythonContextCollector.class));

        String source = Files.readString(
                Path.of("src/main/java/com/github/claudecodegui/handler/context/SemanticContextProvider.java"),
                StandardCharsets.UTF_8
        );
        assertFalse(source.contains("com.intellij.psi.impl.source.tree.java"));
        assertFalse(source.contains("com.jetbrains.python"));
    }

    @Test
    public void pluginVerifierCoversOptionalLanguageIsolationMatrix() throws Exception {
        String buildScript = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);

        assertTrue(buildScript.contains("ide('IC', '2024.3.1')"));
        assertTrue(buildScript.contains("ide('PC', '2024.3')"));
        assertTrue(buildScript.contains("ide('WS', '2024.3')"));
        assertTrue(buildScript.contains("ide('IU', '262.6228.19')"));
        assertTrue(buildScript.contains("externalPrefixes = ['com.jetbrains.python']"));
    }

    @Test
    public void providerLookupRemainsDynamicAndFailuresStayIsolated() throws Exception {
        for (Field field : ContextCollector.class.getDeclaredFields()) {
            boolean staticList = Modifier.isStatic(field.getModifiers()) && List.class.equals(field.getType());
            assertFalse("dynamic EP implementations must not be retained in a static list", staticList);
        }

        String source = Files.readString(
                Path.of("src/main/java/com/github/claudecodegui/handler/context/ContextCollector.java"),
                StandardCharsets.UTF_8
        );
        assertTrue("each collection must use the current dynamic EP snapshot",
                source.contains("EP_NAME.getExtensionList()"));

        ContextCollector collector = new ContextCollector();
        JsonObject target = new JsonObject();
        SemanticContextProvider failing = (json, editor, project, file, document) -> {
            throw new IllegalStateException("expected test failure");
        };
        SemanticContextProvider succeeding = (json, editor, project, file, document) ->
                json.addProperty("providerReached", true);

        collector.collectProviderContext(
                List.of(failing, succeeding),
                target,
                null,
                null,
                null,
                null
        );
        assertTrue(target.get("providerReached").getAsBoolean());

        JsonObject fallbackTarget = new JsonObject();
        collector.collectProviderContext(List.of(), fallbackTarget, null, null, null, null);
        assertEquals(0, fallbackTarget.size());
    }

    private void assertOptionalDependency(
            org.w3c.dom.Document plugin,
            String pluginId,
            String descriptor) {
        NodeList dependencies = plugin.getElementsByTagName("depends");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            if (pluginId.equals(dependency.getTextContent().trim())) {
                assertEquals("true", dependency.getAttribute("optional"));
                assertEquals(descriptor, dependency.getAttribute("config-file"));
                return;
            }
        }
        throw new AssertionError("Optional dependency not found: " + pluginId);
    }

    private void assertCollectorRegistration(
            String descriptor,
            String expectedCollector,
            String forbiddenCollector) throws Exception {
        org.w3c.dom.Document document = parseDescriptor(descriptor);
        NodeList registrations = document.getElementsByTagName(SEMANTIC_EP_NAME);
        List<String> implementations = new ArrayList<>();
        for (int i = 0; i < registrations.getLength(); i++) {
            implementations.add(((Element) registrations.item(i)).getAttribute("implementation"));
        }

        assertEquals(List.of(expectedCollector), implementations);
        assertFalse(readDescriptor(descriptor).contains(forbiddenCollector));
    }

    private Element findElementByAttribute(
            org.w3c.dom.Document document,
            String tagName,
            String attribute,
            String value) {
        NodeList nodes = document.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (value.equals(element.getAttribute(attribute))) {
                return element;
            }
        }
        return null;
    }

    private org.w3c.dom.Document parseDescriptor(String name) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        try (InputStream input = Files.newInputStream(descriptorPath(name))) {
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(input);
            DocumentType doctype = document.getDoctype();
            assertEquals(null, doctype);
            return document;
        }
    }

    private String readDescriptor(String name) throws Exception {
        return Files.readString(descriptorPath(name), StandardCharsets.UTF_8);
    }

    private Path descriptorPath(String name) {
        return Path.of("src/main/resources/META-INF").resolve(name);
    }
}
