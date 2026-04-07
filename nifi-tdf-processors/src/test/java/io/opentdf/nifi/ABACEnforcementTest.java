package io.opentdf.nifi;

import com.google.common.util.concurrent.Futures;
import io.opentdf.platform.authorization.AuthorizationServiceGrpc;
import io.opentdf.platform.authorization.DecisionResponse;
import io.opentdf.platform.authorization.GetDecisionsRequest;
import io.opentdf.platform.authorization.GetDecisionsResponse;
import io.opentdf.platform.sdk.SDK;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ABACEnforcementTest {

    private static final String ENTITY_ID_VALUE   = "nifi-service-account";
    private static final String TDF_ATTR_FQN      = "https://ns.example.mil/attr/classification/value/secret";
    private static final byte[] PAYLOAD           = "tactical message content".getBytes();

    // ─── Mock inner class ─────────────────────────────────────────────────────

    static class MockABACEnforcement extends ABACEnforcement {
        SDK mockSDK;
        @Override
        SDK getTDFSDK(ProcessContext ctx) { return mockSDK; }
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────────

    private MockABACEnforcement processor;
    private TestRunner runner;
    private AuthorizationServiceGrpc.AuthorizationServiceFutureStub mockAuthStub;

    @BeforeEach
    void setup() throws Exception {
        processor = new MockABACEnforcement();

        SDK mockSDK = mock(SDK.class);
        SDK.Services mockServices = mock(SDK.Services.class);
        mockAuthStub = mock(AuthorizationServiceGrpc.AuthorizationServiceFutureStub.class);
        when(mockSDK.getServices()).thenReturn(mockServices);
        when(mockServices.authorization()).thenReturn(mockAuthStub);
        processor.mockSDK = mockSDK;

        runner = TestRunners.newTestRunner(processor);
        Utils.setupTDFControllerService(runner);
        runner.setProperty(ABACEnforcement.ENTITY_ID, ENTITY_ID_VALUE);
        runner.setProperty(ABACEnforcement.ENTITY_TYPE, "CLIENT_ID");
    }

    // ─── PERMIT / DENY ───────────────────────────────────────────────────────

    @Test
    void permit_routesToPermitWithDecisionAttribute() {
        stubAuthResponse(DecisionResponse.Decision.DECISION_PERMIT);

        runner.enqueue(PAYLOAD, Map.of("tdf_attribute", TDF_ATTR_FQN));
        runner.run(1);

        List<MockFlowFile> permitted = runner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT);
        assertEquals(1, permitted.size());
        assertEquals(0, runner.getFlowFilesForRelationship(ABACEnforcement.REL_DENY).size());
        assertEquals(0, runner.getFlowFilesForRelationship(ABACEnforcement.REL_FAILURE).size());

        MockFlowFile ff = permitted.get(0);
        assertEquals("PERMIT",          ff.getAttribute("abac.decision"));
        assertEquals(ENTITY_ID_VALUE,   ff.getAttribute("abac.entity_id"));
        assertEquals(TDF_ATTR_FQN,      ff.getAttribute("abac.resource_attributes"));
        assertNotNull(ff.getAttribute("abac.processing_time_ms"));
        // Binary content must pass through unchanged
        assertArrayEquals(PAYLOAD, ff.toByteArray());
    }

    @Test
    void deny_routesToDenyWithDecisionAttribute() {
        stubAuthResponse(DecisionResponse.Decision.DECISION_DENY);

        runner.enqueue(PAYLOAD, Map.of("tdf_attribute", TDF_ATTR_FQN));
        runner.run(1);

        List<MockFlowFile> denied = runner.getFlowFilesForRelationship(ABACEnforcement.REL_DENY);
        assertEquals(1, denied.size());
        assertEquals(0, runner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT).size());
        assertEquals("DENY", denied.get(0).getAttribute("abac.decision"));
        assertArrayEquals(PAYLOAD, denied.get(0).toByteArray());
    }

    @Test
    void anyDenyInMultipleDecisions_overallDeny() {
        when(mockAuthStub.getDecisions(any(GetDecisionsRequest.class)))
                .thenReturn(Futures.immediateFuture(GetDecisionsResponse.newBuilder()
                        .addDecisionResponses(decisionOf(DecisionResponse.Decision.DECISION_PERMIT))
                        .addDecisionResponses(decisionOf(DecisionResponse.Decision.DECISION_DENY))
                        .build()));

        runner.enqueue(PAYLOAD, Map.of("tdf_attribute", TDF_ATTR_FQN));
        runner.run(1);

        assertEquals(1, runner.getFlowFilesForRelationship(ABACEnforcement.REL_DENY).size());
    }

    // ─── Validation failures (fail-closed regardless of Fail Open setting) ───

    @Test
    void missingTdfAttribute_noDefault_routesToFailure() {
        // No tdf_attribute on flow file, no Default Resource Attribute FQNs configured
        runner.enqueue(PAYLOAD);
        runner.run(1);

        assertEquals(1, runner.getFlowFilesForRelationship(ABACEnforcement.REL_FAILURE).size());
        assertEquals(0, runner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT).size());
        verifyNoInteractions(mockAuthStub);
    }

    @Test
    void blankTdfAttribute_noDefault_routesToFailure() {
        runner.enqueue(PAYLOAD, Map.of("tdf_attribute", "   "));
        runner.run(1);

        assertEquals(1, runner.getFlowFilesForRelationship(ABACEnforcement.REL_FAILURE).size());
        verifyNoInteractions(mockAuthStub);
    }

    @Test
    void allBlankFqnsAfterSplit_routesToFailure() {
        // All entries are blank after splitting — empty FQN list must never default-permit
        runner.enqueue(PAYLOAD, Map.of("tdf_attribute", " , , "));
        runner.run(1);

        assertEquals(1, runner.getFlowFilesForRelationship(ABACEnforcement.REL_FAILURE).size());
        verifyNoInteractions(mockAuthStub);
    }

    @Test
    void emptyDecisionList_routesToFailure() {
        // Auth service returns a response with no decisions — ambiguous, treat as failure
        when(mockAuthStub.getDecisions(any(GetDecisionsRequest.class)))
                .thenReturn(Futures.immediateFuture(GetDecisionsResponse.newBuilder().build()));

        runner.enqueue(PAYLOAD, Map.of("tdf_attribute", TDF_ATTR_FQN));
        runner.run(1);

        assertEquals(1, runner.getFlowFilesForRelationship(ABACEnforcement.REL_FAILURE).size());
    }

    // ─── Default resource attributes ─────────────────────────────────────────

    @Test
    void defaultResourceAttributes_usedWhenTdfAttributeAbsent() {
        stubAuthResponse(DecisionResponse.Decision.DECISION_PERMIT);
        runner.setProperty(ABACEnforcement.DEFAULT_RESOURCE_ATTRIBUTES, TDF_ATTR_FQN);

        // Flow file has no tdf_attribute — should fall back to the default
        runner.enqueue(PAYLOAD);
        runner.run(1);

        assertEquals(1, runner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT).size());
        assertEquals(TDF_ATTR_FQN,
                runner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT)
                      .get(0).getAttribute("abac.resource_attributes"));
    }

    @Test
    void tdfAttributeOverridesDefault_whenBothSet() {
        stubAuthResponse(DecisionResponse.Decision.DECISION_PERMIT);
        String defaultAttr = "https://ns.example.mil/attr/classification/value/unclassified";
        runner.setProperty(ABACEnforcement.DEFAULT_RESOURCE_ATTRIBUTES, defaultAttr);

        String flowFileAttr = TDF_ATTR_FQN;
        runner.enqueue(PAYLOAD, Map.of("tdf_attribute", flowFileAttr));
        runner.run(1);

        MockFlowFile ff = runner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT).get(0);
        assertEquals(flowFileAttr, ff.getAttribute("abac.resource_attributes"),
                "Flow file tdf_attribute must take precedence over default");
    }

    // ─── Remote call failure / Fail Open ─────────────────────────────────────

    @Test
    void authServiceException_failClosedDefault_routesToFailure() {
        when(mockAuthStub.getDecisions(any(GetDecisionsRequest.class)))
                .thenThrow(new RuntimeException("gRPC channel closed"));

        runner.enqueue(PAYLOAD, Map.of("tdf_attribute", TDF_ATTR_FQN));
        runner.run(1);

        assertEquals(1, runner.getFlowFilesForRelationship(ABACEnforcement.REL_FAILURE).size());
        assertEquals(0, runner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT).size());
    }

    @Test
    void authServiceTimeout_failOpen_routesToPermit() {
        when(mockAuthStub.getDecisions(any(GetDecisionsRequest.class)))
                .thenReturn(Futures.immediateFailedFuture(new TimeoutException("upstream timeout")));
        runner.setProperty(ABACEnforcement.FAIL_OPEN, "true");

        runner.enqueue(PAYLOAD, Map.of("tdf_attribute", TDF_ATTR_FQN));
        runner.run(1);

        List<MockFlowFile> permitted = runner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT);
        assertEquals(1, permitted.size());
        assertEquals("PERMIT", permitted.get(0).getAttribute("abac.decision"));
        assertNotNull(permitted.get(0).getAttribute("abac.error"),
                "abac.error must be set on fail-open permit");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void stubAuthResponse(DecisionResponse.Decision decision) {
        when(mockAuthStub.getDecisions(any(GetDecisionsRequest.class)))
                .thenReturn(Futures.immediateFuture(GetDecisionsResponse.newBuilder()
                        .addDecisionResponses(decisionOf(decision))
                        .build()));
    }

    private static DecisionResponse decisionOf(DecisionResponse.Decision decision) {
        return DecisionResponse.newBuilder().setDecision(decision).build();
    }
}
