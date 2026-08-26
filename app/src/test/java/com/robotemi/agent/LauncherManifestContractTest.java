package com.robotemi.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

public class LauncherManifestContractTest {
    private static final String ANDROID_NS =
            "http://schemas.android.com/apk/res/android";

    @Test
    public void declaresOneTemiLauncherEntryWithLabelAndIcon() throws Exception {
        Document manifest = parseManifest();
        Element application = (Element) manifest
                .getElementsByTagName("application").item(0);

        assertNotNull(application);
        assertEquals("@string/app_name", androidAttribute(application, "label"));
        assertEquals("@drawable/ic_temi_agent",
                androidAttribute(application, "icon"));
        assertEquals("@drawable/ic_temi_agent",
                androidAttribute(application, "roundIcon"));
        assertEquals("@string/app_name", metadataValue(
                application, "com.robotemi.sdk.metadata.SKILL"));

        NodeList activities = application.getElementsByTagName("activity");
        int launcherEntries = 0;
        for (int index = 0; index < activities.getLength(); index++) {
            Element activity = (Element) activities.item(index);
            NodeList filters = activity.getElementsByTagName("intent-filter");
            for (int filterIndex = 0;
                    filterIndex < filters.getLength(); filterIndex++) {
                Element filter = (Element) filters.item(filterIndex);
                if (hasIntentValue(filter, "action",
                        "android.intent.action.MAIN")
                        && hasIntentValue(filter, "category",
                        "android.intent.category.LAUNCHER")) {
                    launcherEntries++;
                    assertEquals(".MainActivity",
                            androidAttribute(activity, "name"));
                    assertEquals("true",
                            androidAttribute(activity, "exported"));
                }
            }
        }
        assertEquals(1, launcherEntries);
    }

    @Test
    public void declaresTemiFullscreenUiMode() throws Exception {
        Element application = (Element) parseManifest()
                .getElementsByTagName("application").item(0);

        assertEquals("1", metadataValue(
                application, "com.robotemi.sdk.metadata.UI_MODE"));
        assertNull(metadataValue(
                application, "com.robotemi.sdk.metadata.UI_FLAG"));
    }

    private static Document parseManifest() throws Exception {
        Path manifestPath = locateProjectFile(
                "src/main/AndroidManifest.xml");
        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder().parse(manifestPath.toFile());
    }

    private static String metadataValue(
            Element application, String metadataName) {
        NodeList metadata = application.getElementsByTagName("meta-data");
        for (int index = 0; index < metadata.getLength(); index++) {
            Element item = (Element) metadata.item(index);
            if (metadataName.equals(androidAttribute(item, "name"))) {
                return androidAttribute(item, "value");
            }
        }
        return null;
    }

    private static boolean hasIntentValue(
            Element filter, String elementName, String expected) {
        NodeList items = filter.getElementsByTagName(elementName);
        for (int index = 0; index < items.getLength(); index++) {
            if (expected.equals(androidAttribute(
                    (Element) items.item(index), "name"))) {
                return true;
            }
        }
        return false;
    }

    private static String androidAttribute(Element element, String name) {
        return element.getAttributeNS(ANDROID_NS, name);
    }

    private static Path locateProjectFile(String relativePath) {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            candidate = current.resolve("TemiAgent/app").resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("android_manifest_not_found");
    }
}
