package com.github.claudecodegui.settings;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the one-way Facade -> domain service -> ConfigStore ownership boundary. */
public class DomainSettingsOwnershipContractTest {

    private static final Class<?>[] DOMAIN_SERVICES = {
            AppearanceSettingsService.class,
            AiFeatureToggleSettingsService.class,
            CodexSandboxModeSettingsService.class,
            ModelRegistrySettingsService.class,
            McpSettingsService.class,
            ProviderSettingsService.class
    };

    @Test
    public void domainServicesDependOnConfigStoreInsteadOfFacade() throws Exception {
        for (Class<?> service : DOMAIN_SERVICES) {
            for (Field field : service.getDeclaredFields()) {
                assertFalse(service.getSimpleName() + " must not retain the Facade",
                        field.getType().equals(CodemossSettingsService.class));
            }
            assertTrue(service.getSimpleName() + " constructor must accept ConfigStore",
                    Arrays.stream(service.getConstructors()).anyMatch(this::acceptsConfigStore));

            String source = readSource(service);
            assertFalse(service.getSimpleName() + " must not reference the Facade implementation",
                    source.contains("CodemossSettingsService"));
            assertFalse(service.getSimpleName() + " must not call legacy readConfig",
                    source.contains(".readConfig()"));
            assertFalse(service.getSimpleName() + " must not call legacy writeConfig",
                    source.contains(".writeConfig("));
        }
    }

    @Test
    public void configRepositoryIsTheOnlyProductionConfigStoreImplementation() throws Exception {
        assertTrue(ConfigStore.class.isAssignableFrom(ConfigRepository.class));

        Path settingsDir = Path.of("src/main/java/com/github/claudecodegui/settings");
        try (Stream<Path> files = Files.walk(settingsDir)) {
            Set<Path> implementors = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> readUnchecked(path).contains("implements ConfigStore"))
                    .map(Path::normalize)
                    .collect(Collectors.toSet());
            assertEquals(Set.of(settingsDir.resolve("ConfigRepository.java").normalize()), implementors);
        }
    }

    @Test
    public void facadeRetainsAllDomainServiceMethodNames() {
        Set<String> facadeMethods = Arrays.stream(CodemossSettingsService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        for (Class<?> service : DOMAIN_SERVICES) {
            for (Method method : service.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    assertTrue("Facade missing domain API " + service.getSimpleName() + "." + method.getName(),
                            facadeMethods.contains(method.getName()));
                }
            }
        }
    }

    private boolean acceptsConfigStore(Constructor<?> constructor) {
        return Arrays.asList(constructor.getParameterTypes()).contains(ConfigStore.class);
    }

    private String readSource(Class<?> type) throws IOException {
        return Files.readString(
                Path.of("src/main/java")
                        .resolve(type.getName().replace('.', '/') + ".java"),
                StandardCharsets.UTF_8
        );
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
