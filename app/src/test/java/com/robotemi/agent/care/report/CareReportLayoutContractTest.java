package com.robotemi.agent.care.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

public class CareReportLayoutContractTest {
    private static final String ANDROID_NS =
            "http://schemas.android.com/apk/res/android";

    @Test
    public void reportOverlayIsScrollableAndRaisedAboveGeneralControls()
            throws Exception {
        Document layout = parseLayout();
        Element overlay = findById(layout, "@+id/careReportOverlay");
        assertNotNull(overlay);
        assertEquals("true", androidAttribute(overlay, "clickable"));
        assertEquals("16dp", androidAttribute(overlay, "elevation"));

        NodeList scrollViews = overlay.getElementsByTagName("ScrollView");
        assertEquals(1, scrollViews.getLength());
        Element scrollView = (Element) scrollViews.item(0);
        assertEquals("true", androidAttribute(scrollView, "fillViewport"));
        assertEquals("vertical", androidAttribute(scrollView, "scrollbars"));
    }

    private static Document parseLayout() throws Exception {
        Path layoutPath = locateProjectFile(
                "src/main/res/layout/activity_main.xml");
        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder().parse(layoutPath.toFile());
    }

    private static Element findById(Document document, String id) {
        NodeList elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(androidAttribute(element, "id"))) {
                return element;
            }
        }
        return null;
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
        throw new IllegalStateException("care_report_layout_not_found");
    }
}
