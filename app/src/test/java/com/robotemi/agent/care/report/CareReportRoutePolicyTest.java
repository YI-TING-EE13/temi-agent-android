package com.robotemi.agent.care.report;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CareReportRoutePolicyTest {
    private CareReportStateHolder holder;
    private String valid;

    @Before
    public void setUp() throws Exception {
        holder = new CareReportStateHolder(new CareReportParser(), true);
        holder.syncIdentity(CareReportStateHolderTest.identity("father"));
        valid = CareReportFixtures.direct("complete_report.json");
    }

    @Test
    public void identityUnknownHidesOverlayAndSensitiveEntry() throws Exception {
        holder.accept(valid, false);
        JsonObject unknown = CareReportFixtures.completeObject();
        unknown.add("resident_id", JsonNull.INSTANCE);
        unknown.addProperty("display_name", "unknown");
        unknown.addProperty("status", "identity_unknown");
        unknown.getAsJsonObject("data_completeness")
                .addProperty("status", "identity_unknown");
        unknown.addProperty("error_code", "unknown_resident");
        unknown.addProperty("error_message", "Synthetic unknown.");

        CareReportStateHolder.Update update =
                holder.accept(unknown.toString(), false);
        CareReportRoutePolicy.Decision route = CareReportRoutePolicy.decide(
                update, true, holder.isEntryAuthorized());

        assertTrue(route.hideOverlay);
        assertFalse(route.showEntry);
        assertFalse(route.renderOverlay);
    }

    @Test
    public void mismatchAndInvalidHideOverlayButExposeSafeErrorEntry()
            throws Exception {
        holder.accept(valid, false);
        CareReportStateHolder.Update mismatch = holder.accept(
                CareReportFixtures.wrappedReport("wrong_resident_report.json"), false);
        CareReportRoutePolicy.Decision mismatchRoute =
                CareReportRoutePolicy.decide(
                        mismatch, true, holder.isEntryAuthorized());
        assertTrue(mismatchRoute.hideOverlay);
        assertTrue(mismatchRoute.showEntry);

        CareReportStateHolder.Update invalid =
                holder.accept("{invalid", false);
        CareReportRoutePolicy.Decision invalidRoute =
                CareReportRoutePolicy.decide(
                        invalid, true, holder.isEntryAuthorized());
        assertTrue(invalidRoute.hideOverlay);
        assertTrue(invalidRoute.showEntry);
    }

    @Test
    public void retainedRejectDoesNotMutateOrHideCurrentReport() throws Exception {
        holder.accept(valid, false);
        CareReportStateHolder.Update retained =
                holder.accept("{invalid", true);
        CareReportRoutePolicy.Decision route = CareReportRoutePolicy.decide(
                retained, true, holder.isEntryAuthorized());

        assertFalse(route.hideOverlay);
        assertFalse(route.renderOverlay);
        assertTrue(route.showEntry);
        assertTrue(retained.state.report != null);
    }

    @Test
    public void unsupportedEnvelopeRendersSafeCompatibilityState()
            throws Exception {
        JsonObject unsupported = CareReportFixtures.completeObject();
        unsupported.addProperty("status", "unsupported_schema_version");
        unsupported.getAsJsonObject("data_completeness")
                .addProperty("status", "unsupported_schema_version");
        unsupported.getAsJsonObject("data_completeness")
                .addProperty("requested_schema_version", "2.0");
        unsupported.addProperty("error_code", "unsupported_schema_version");
        unsupported.addProperty("error_message", "Synthetic unsupported version.");

        CareReportStateHolder.Update update =
                holder.accept(unsupported.toString(), false);
        CareReportRoutePolicy.Decision route = CareReportRoutePolicy.decide(
                update, true, holder.isEntryAuthorized());

        assertFalse(route.hideOverlay);
        assertTrue(route.renderOverlay);
        assertTrue(route.recordVisibleReport);
        assertTrue(route.showEntry);
    }
}
