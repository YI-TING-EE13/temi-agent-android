package com.robotemi.agent.care.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class CareReportFixtures {
    private CareReportFixtures() {}

    static String direct(String name) throws Exception {
        return read(name);
    }

    static String wrappedReport(String name) throws Exception {
        return JsonParser.parseString(read(name)).getAsJsonObject()
                .getAsJsonObject("report").toString();
    }

    static JsonObject completeObject() throws Exception {
        return JsonParser.parseString(direct("complete_report.json")).getAsJsonObject();
    }

    private static String read(String name) throws Exception {
        try (InputStream input = CareReportFixtures.class.getClassLoader()
                .getResourceAsStream("care-report/" + name)) {
            if (input != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return new String(
                Files.readAllBytes(root().resolve(name)), StandardCharsets.UTF_8);
    }

    private static Path root() {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve("contracts/ai6-care-report-v1/fixtures");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("care_report_fixture_root_not_found");
    }
}
