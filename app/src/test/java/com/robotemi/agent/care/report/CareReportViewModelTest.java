package com.robotemi.agent.care.report;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CareReportViewModelTest {
    private CareReportInteractionCoordinatorTest.InMemoryPersistence persistence;
    private CareReportViewModel viewModel;

    @Before
    public void setUp() throws Exception {
        persistence = new CareReportInteractionCoordinatorTest.InMemoryPersistence();
        viewModel = newViewModel(persistence);
        viewModel.syncIdentity(CareReportStateHolderTest.identity("father"));
        viewModel.acceptReport(
                CareReportFixtures.direct("complete_report.json"), false);
    }

    @Test
    public void activityRecreationDoesNotCreateSecondViewedInteraction() {
        assertEquals(CareReportInteractionCoordinator.Disposition.ENQUEUED,
                viewModel.reportVisible("endpoint-a").disposition);

        // The same ViewModel instance is retained across Activity recreation.
        assertEquals(CareReportInteractionCoordinator.Disposition.INVALID,
                viewModel.reportVisible("endpoint-a").disposition);
        assertEquals(1, viewModel.pendingInteractions().size());
    }

    @Test
    public void explicitAcknowledgeIsIndependentFromVisibleAction() {
        assertEquals(CareReportInteractionCoordinator.Disposition.ENQUEUED,
                viewModel.reportVisible("endpoint-a").disposition);
        assertEquals(CareReportInteractionCoordinator.Disposition.ENQUEUED,
                viewModel.acknowledge("endpoint-a").disposition);
        assertEquals("viewed", viewModel.pendingInteractions().get(0).action);
        assertEquals("acknowledged", viewModel.pendingInteractions().get(1).action);
    }

    @Test
    public void processRestartRestoresOutboxButNotReportBody() {
        viewModel.reportVisible("endpoint-a");

        CareReportViewModel restarted = newViewModel(persistence);

        assertNull(restarted.state().report);
        assertEquals(1, restarted.pendingInteractions().size());
        assertEquals(CareReportInteractionCoordinator.Disposition.INVALID,
                restarted.acknowledge("endpoint-a").disposition);
    }

    @Test
    public void identitySwitchImmediatelyClearsReportAuthorization() throws Exception {
        viewModel.reportVisible("endpoint-a");
        viewModel.syncIdentity(CareReportStateHolderTest.identity("mother"));

        assertNull(viewModel.state().report);
        assertEquals(CareReportInteractionCoordinator.Disposition.INVALID,
                viewModel.reportVisible("endpoint-a").disposition);

        viewModel.syncIdentity(CareReportStateHolderTest.identity("father"));
        viewModel.acceptReport(
                CareReportFixtures.direct("complete_report.json"), false);
        assertEquals(CareReportInteractionCoordinator.Disposition.ENQUEUED,
                viewModel.reportVisible("endpoint-a").disposition);
    }

    @Test
    public void exactReportRecoveryDoesNotReplayViewedOrAcknowledged()
            throws Exception {
        viewModel.reportVisible("endpoint-a");
        viewModel.acknowledge("endpoint-a");
        assertEquals(2, viewModel.pendingInteractions().size());

        viewModel.acceptReport("{invalid", false);
        assertEquals(CareReportStateHolder.Disposition.ACCEPTED,
                viewModel.acceptReport(
                        CareReportFixtures.direct("complete_report.json"), false)
                        .disposition);

        assertEquals(CareReportInteractionCoordinator.Disposition.INVALID,
                viewModel.reportVisible("endpoint-a").disposition);
        assertEquals(CareReportInteractionCoordinator.Disposition.INVALID,
                viewModel.acknowledge("endpoint-a").disposition);
        assertEquals(2, viewModel.pendingInteractions().size());
    }

    private static CareReportViewModel newViewModel(
            CareInteractionPersistence persistence) {
        final int[] sequence = {0};
        CareReportInteractionCoordinator coordinator =
                new CareReportInteractionCoordinator(
                        persistence,
                        new CareReportInteractionCoordinator.Clock() {
                            @Override public long nowMs() { return 1L; }
                            @Override public String nowTimestamp() {
                                return "2026-07-27T20:05:00Z";
                            }
                        },
                        () -> "generated-request-" + sequence[0]++);
        return new CareReportViewModel(
                new CareReportStateHolder(new CareReportParser(), true),
                coordinator);
    }
}
