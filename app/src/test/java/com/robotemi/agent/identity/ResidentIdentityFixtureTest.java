package com.robotemi.agent.identity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

/** Executes the synthetic canonical consumer scenarios included with the tests. */
public class ResidentIdentityFixtureTest {
    @Test
    public void canonicalFixturesDriveExpectedConsumerOutcomes() throws Exception {
        assertFixture("father_valid.json",
                ResidentIdentityUiState.Kind.FATHER,
                ResidentIdentityStateHolder.Disposition.ACCEPTED);
        assertFixture("mother_valid.json",
                ResidentIdentityUiState.Kind.MOTHER,
                ResidentIdentityStateHolder.Disposition.ACCEPTED);
        assertFixture("unknown_valid.json",
                ResidentIdentityUiState.Kind.UNKNOWN,
                ResidentIdentityStateHolder.Disposition.ACCEPTED_UNKNOWN);
        assertFixture("stale.json",
                ResidentIdentityUiState.Kind.UNKNOWN,
                ResidentIdentityStateHolder.Disposition.STALE);
        assertFixture("out_of_order.json",
                ResidentIdentityUiState.Kind.MOTHER,
                ResidentIdentityStateHolder.Disposition.OUT_OF_ORDER);
        assertFixture("duplicate.json",
                ResidentIdentityUiState.Kind.FATHER,
                ResidentIdentityStateHolder.Disposition.DUPLICATE);
        assertFixture("equal_timestamp_conflict.json",
                ResidentIdentityUiState.Kind.UNKNOWN,
                ResidentIdentityStateHolder.Disposition.CONFLICT);
        assertFixture("invalid.json",
                ResidentIdentityUiState.Kind.UNKNOWN,
                ResidentIdentityStateHolder.Disposition.INVALID);
    }

    private static void assertFixture(
            String filename,
            ResidentIdentityUiState.Kind expectedState,
            ResidentIdentityStateHolder.Disposition expectedLastDisposition
    ) throws Exception {
        JsonObject fixture = JsonParser.parseString(readFixture(filename)).getAsJsonObject();
        String receivedAt = fixture.get("received_at").getAsString();
        long wall = new ResidentIdentityParser().parse(
                ResidentIdentityParserTest.unknown("fixture-clock").replace(
                        "2026-07-27T12:00:00Z", receivedAt)).timestampMillis;
        FixtureClock clock = new FixtureClock(wall, 50_000L);
        ResidentIdentityViewModel viewModel = new ResidentIdentityViewModel(
                new ResidentIdentityParser(), clock, true);
        ResidentIdentityStateHolder.Update last = null;
        JsonArray messages = fixture.getAsJsonArray("messages");
        for (int index = 0; index < messages.size(); index++) {
            last = viewModel.acceptPayload(messages.get(index).toString());
        }

        assertEquals(filename, expectedState, viewModel.state().kind);
        assertEquals(filename, expectedLastDisposition, last.disposition);
    }

    private static String readFixture(String filename) throws Exception {
        try (InputStream input = ResidentIdentityFixtureTest.class.getClassLoader()
                .getResourceAsStream("resident-identity/" + filename)) {
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
        return new String(Files.readAllBytes(fixtureRoot().resolve(filename)),
                StandardCharsets.UTF_8);
    }

    private static Path fixtureRoot() {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve(
                    "contracts/ai6-resident-identity-v1/fixtures");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("resident_identity_fixture_root_not_found");
    }

    private static final class FixtureClock implements IdentityClock {
        private final long wall;
        private final long monotonic;

        FixtureClock(long wall, long monotonic) {
            this.wall = wall;
            this.monotonic = monotonic;
        }

        @Override
        public long wallTimeMillis() {
            return wall;
        }

        @Override
        public long monotonicTimeMillis() {
            return monotonic;
        }
    }
}
