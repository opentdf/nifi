package io.opentdf.nifi;

import com.google.common.util.concurrent.Futures;
import io.opentdf.platform.authorization.AuthorizationServiceGrpc;
import io.opentdf.platform.authorization.DecisionResponse;
import io.opentdf.platform.authorization.GetDecisionsRequest;
import io.opentdf.platform.authorization.GetDecisionsResponse;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.TDF;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end pipeline test: ParseJREAPC → ABACEnforcement → ConvertToZTDF.
 *
 * <p>Verifies that JREAP-C binary content is preserved byte-for-byte through
 * the full classification-tagging and ABAC enforcement pipeline before the TDF
 * wrapping step receives it. Two scenarios are covered:
 * <ul>
 *   <li>PERMIT — message flows through all three stages; TDF receives original bytes.</li>
 *   <li>DENY  — message is stopped at ABACEnforcement and never reaches ConvertToZTDF.</li>
 * </ul>
 */
class JREAPCPipelineTest {

    // ─── JREAP-C binary builder (32-byte big-endian header + payload) ─────────

    private static final int HEADER_SIZE = 32;

    /**
     * Builds a synthetic JREAP-C message with the given word-type, classification
     * code, and arbitrary payload bytes appended after the fixed header.
     *
     * <p>Header layout (big-endian):
     * <pre>
     *  offset  len  field
     *   0       2   word type
     *   2       1   classification code  (0=UNCLASSIFIED, 1=CUI, 2=SECRET, 3=TOP SECRET)
     *   3       1   flags                (bit0=exercise, bit1=simulation)
     *   4       4   sequence number
     *   8       8   source address
     *  16       8   destination address
     *  24       4   timestamp (UNIX, 32-bit)
     *  28       2   track number
     *  30       2   reserved
     * </pre>
     */
    static byte[] buildJreapCMessage(int wordType, int classCode, int seqNum,
                                     long timestamp, int trackNumber, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length)
                                   .order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) wordType);
        buf.put((byte) classCode);
        buf.put((byte) 0x00);               // flags
        buf.putInt(seqNum);
        buf.put(new byte[8]);               // source address (zeroed)
        buf.put(new byte[8]);               // dest address (zeroed)
        buf.putInt((int) timestamp);
        buf.putShort((short) trackNumber);
        buf.putShort((short) 0);            // reserved
        buf.put(payload);
        return buf.array();
    }

    // ─── Mock inner classes ───────────────────────────────────────────────────

    static class MockABACEnforcement extends ABACEnforcement {
        SDK mockSDK;
        @Override
        SDK getTDFSDK(ProcessContext ctx) { return mockSDK; }
    }

    static class MockConvertToZTDF extends ConvertToZTDF {
        SDK mockSDK;
        TDF mockTDF;
        @Override
        SDK getTDFSDK(ProcessContext ctx) { return mockSDK; }
        @Override
        TDF getTDF() { return mockTDF; }
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    /**
     * Happy-path: SECRET JREAP-C message is parsed, ABAC returns PERMIT,
     * and ConvertToZTDF receives the original binary unchanged.
     */
    @Test
    void secretMessage_permitDecision_binaryPreservedThroughFullPipeline() throws Exception {
        // Build a realistic JREAP-C message: J5.0 word type, SECRET, with a short payload
        byte[] tacticalPayload = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        byte[] jreapMsg = buildJreapCMessage(
                0x0500,          // J5.0 word type
                2,               // classification code 2 = SECRET
                42,              // sequence number
                1700000000L,     // timestamp
                7,               // track number
                tacticalPayload
        );

        // ── Stage 1: ParseJREAPC ─────────────────────────────────────────────
        ParseJREAPC parseProcessor = new ParseJREAPC();
        TestRunner parseRunner = TestRunners.newTestRunner(parseProcessor);
        parseRunner.setProperty(ParseJREAPC.CLASSIFICATION_ATTRIBUTE_NAMESPACE,
                "https://classification.example.mil/attr/level");

        parseRunner.enqueue(jreapMsg);
        parseRunner.run(1);

        List<MockFlowFile> parsedFiles = parseRunner.getFlowFilesForRelationship(ParseJREAPC.REL_SUCCESS);
        assertEquals(1, parsedFiles.size(), "ParseJREAPC must route to success");
        MockFlowFile parsedFF = parsedFiles.get(0);

        // Verify classification was extracted and tdf_attribute was set
        assertEquals("SECRET", parsedFF.getAttribute("jreapc.classification"));
        assertEquals("https://classification.example.mil/attr/level/value/secret",
                parsedFF.getAttribute("tdf_attribute"));
        assertEquals("J5.0", parsedFF.getAttribute("jreapc.word_type"));
        assertEquals("42", parsedFF.getAttribute("jreapc.sequence_number"));

        // Binary must be unchanged at this stage
        assertArrayEquals(jreapMsg, parsedFF.toByteArray(),
                "ParseJREAPC must not modify binary content");

        // ── Stage 2: ABACEnforcement (mocked: PERMIT) ────────────────────────
        MockABACEnforcement abacProcessor = new MockABACEnforcement();

        SDK mockAbacSDK = mock(SDK.class);
        SDK.Services mockAbacServices = mock(SDK.Services.class);
        AuthorizationServiceGrpc.AuthorizationServiceFutureStub mockAuthStub =
                mock(AuthorizationServiceGrpc.AuthorizationServiceFutureStub.class);
        when(mockAbacSDK.getServices()).thenReturn(mockAbacServices);
        when(mockAbacServices.authorization()).thenReturn(mockAuthStub);
        when(mockAuthStub.getDecisions(any(GetDecisionsRequest.class)))
                .thenReturn(Futures.immediateFuture(GetDecisionsResponse.newBuilder()
                        .addDecisionResponses(DecisionResponse.newBuilder()
                                .setDecision(DecisionResponse.Decision.DECISION_PERMIT))
                        .build()));
        abacProcessor.mockSDK = mockAbacSDK;

        TestRunner abacRunner = TestRunners.newTestRunner(abacProcessor);
        Utils.setupTDFControllerService(abacRunner);
        abacRunner.setProperty(ABACEnforcement.ENTITY_ID, "nifi-pipeline-service");
        abacRunner.setProperty(ABACEnforcement.ENTITY_TYPE, "CLIENT_ID");

        // Carry all attributes and content forward from ParseJREAPC output
        Map<String, String> parsedAttrs = new HashMap<>(parsedFF.getAttributes());
        abacRunner.enqueue(parsedFF.toByteArray(), parsedAttrs);
        abacRunner.run(1);

        List<MockFlowFile> permittedFiles = abacRunner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT);
        assertEquals(1, permittedFiles.size(), "ABACEnforcement must route SECRET to permit");
        assertEquals(0, abacRunner.getFlowFilesForRelationship(ABACEnforcement.REL_DENY).size());

        MockFlowFile permittedFF = permittedFiles.get(0);
        assertEquals("PERMIT", permittedFF.getAttribute("abac.decision"));
        assertEquals("https://classification.example.mil/attr/level/value/secret",
                permittedFF.getAttribute("abac.resource_attributes"));

        // Binary still unchanged after ABAC decision
        assertArrayEquals(jreapMsg, permittedFF.toByteArray(),
                "ABACEnforcement must not modify binary content");

        // ── Stage 3: ConvertToZTDF (mocked: capture bytes fed to TDF) ────────
        MockConvertToZTDF tdfProcessor = new MockConvertToZTDF();

        SDK mockTdfSDK = mock(SDK.class);
        SDK.Services mockTdfServices = mock(SDK.Services.class);
        SDK.KAS mockKAS = mock(SDK.KAS.class);
        TDF mockTDF = mock(TDF.class);
        when(mockTdfSDK.getServices()).thenReturn(mockTdfServices);
        when(mockTdfServices.kas()).thenReturn(mockKAS);
        tdfProcessor.mockSDK = mockTdfSDK;
        tdfProcessor.mockTDF = mockTDF;

        // Capture the exact bytes ConvertToZTDF passes to TDF.createTDF()
        final byte[][] capturedInputBytes = {null};
        doAnswer(inv -> {
            java.io.InputStream is = inv.getArgument(0);
            java.io.OutputStream os = inv.getArgument(1);
            capturedInputBytes[0] = is.readAllBytes();
            os.write(("WRAPPED:" + capturedInputBytes[0].length + "b").getBytes());
            return null;
        }).when(mockTDF).createTDF(any(), any(), any(), any(), any());

        TestRunner tdfRunner = TestRunners.newTestRunner(tdfProcessor);
        Utils.setupTDFControllerService(tdfRunner);
        tdfRunner.setProperty(ConvertToZTDF.KAS_URL, "https://kas.example.mil");

        Map<String, String> permittedAttrs = new HashMap<>(permittedFF.getAttributes());
        tdfRunner.enqueue(permittedFF.toByteArray(), permittedAttrs);
        tdfRunner.run(1);

        assertEquals(1, tdfRunner.getFlowFilesForRelationship(ConvertToZTDF.REL_SUCCESS).size(),
                "ConvertToZTDF must succeed");

        // ── Key assertion: TDF receives the original JREAP-C binary, byte-for-byte ──
        assertNotNull(capturedInputBytes[0], "TDF.createTDF must have been called");
        assertArrayEquals(jreapMsg, capturedInputBytes[0],
                "ConvertToZTDF must feed the original JREAP-C binary to the TDF library unchanged");
    }

    /**
     * Deny-path: SECRET JREAP-C message is parsed but ABAC returns DENY —
     * the message must be stopped before reaching ConvertToZTDF.
     */
    @Test
    void secretMessage_denyDecision_neverReachesTdf() throws Exception {
        byte[] jreapMsg = buildJreapCMessage(0x0500, 2, 1, 1700000000L, 3,
                "sensitive tactical data".getBytes());

        // Stage 1: ParseJREAPC
        ParseJREAPC parseProcessor = new ParseJREAPC();
        TestRunner parseRunner = TestRunners.newTestRunner(parseProcessor);
        parseRunner.setProperty(ParseJREAPC.CLASSIFICATION_ATTRIBUTE_NAMESPACE,
                "https://classification.example.mil/attr/level");
        parseRunner.enqueue(jreapMsg);
        parseRunner.run(1);
        MockFlowFile parsedFF = parseRunner.getFlowFilesForRelationship(ParseJREAPC.REL_SUCCESS).get(0);

        // Stage 2: ABACEnforcement — DENY
        MockABACEnforcement abacProcessor = new MockABACEnforcement();
        SDK mockAbacSDK = mock(SDK.class);
        SDK.Services mockAbacServices = mock(SDK.Services.class);
        AuthorizationServiceGrpc.AuthorizationServiceFutureStub mockAuthStub =
                mock(AuthorizationServiceGrpc.AuthorizationServiceFutureStub.class);
        when(mockAbacSDK.getServices()).thenReturn(mockAbacServices);
        when(mockAbacServices.authorization()).thenReturn(mockAuthStub);
        when(mockAuthStub.getDecisions(any(GetDecisionsRequest.class)))
                .thenReturn(Futures.immediateFuture(GetDecisionsResponse.newBuilder()
                        .addDecisionResponses(DecisionResponse.newBuilder()
                                .setDecision(DecisionResponse.Decision.DECISION_DENY))
                        .build()));
        abacProcessor.mockSDK = mockAbacSDK;

        TestRunner abacRunner = TestRunners.newTestRunner(abacProcessor);
        Utils.setupTDFControllerService(abacRunner);
        abacRunner.setProperty(ABACEnforcement.ENTITY_ID, "unauthorized-client");
        abacRunner.setProperty(ABACEnforcement.ENTITY_TYPE, "CLIENT_ID");

        abacRunner.enqueue(parsedFF.toByteArray(), new HashMap<>(parsedFF.getAttributes()));
        abacRunner.run(1);

        // Message must be on the deny relationship — not permit, not failure
        assertEquals(1, abacRunner.getFlowFilesForRelationship(ABACEnforcement.REL_DENY).size());
        assertEquals(0, abacRunner.getFlowFilesForRelationship(ABACEnforcement.REL_PERMIT).size());
        assertEquals(0, abacRunner.getFlowFilesForRelationship(ABACEnforcement.REL_FAILURE).size());

        MockFlowFile deniedFF = abacRunner.getFlowFilesForRelationship(ABACEnforcement.REL_DENY).get(0);
        assertEquals("DENY", deniedFF.getAttribute("abac.decision"));

        // The denied flow file would NOT be fed to ConvertToZTDF — no wrapping stage reached.
        // Binary content is still intact on the deny branch (for audit/quarantine downstream).
        assertArrayEquals(jreapMsg, deniedFF.toByteArray(),
                "Binary content must be intact on the deny branch");
    }

    /**
     * Top-secret message: classification code 3 → tdf_attribute slug is top_secret.
     */
    @Test
    void topSecretMessage_tdfAttributeSlug_isTopSecret() throws Exception {
        byte[] jreapMsg = buildJreapCMessage(0x0300, 3, 1, 1700000000L, 0, new byte[]{0x55});

        ParseJREAPC parseProcessor = new ParseJREAPC();
        TestRunner parseRunner = TestRunners.newTestRunner(parseProcessor);
        parseRunner.setProperty(ParseJREAPC.CLASSIFICATION_ATTRIBUTE_NAMESPACE,
                "https://classification.example.mil/attr/level");
        parseRunner.enqueue(jreapMsg);
        parseRunner.run(1);

        MockFlowFile parsedFF = parseRunner.getFlowFilesForRelationship(ParseJREAPC.REL_SUCCESS).get(0);
        assertEquals("TOP SECRET", parsedFF.getAttribute("jreapc.classification"));
        assertEquals("https://classification.example.mil/attr/level/value/top_secret",
                parsedFF.getAttribute("tdf_attribute"));
        assertArrayEquals(jreapMsg, parsedFF.toByteArray());
    }
}
