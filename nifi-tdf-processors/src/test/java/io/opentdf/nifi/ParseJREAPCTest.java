package io.opentdf.nifi;

import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParseJREAPCTest {

    private ParseJREAPC processor;
    private TestRunner runner;

    @BeforeEach
    void setup() {
        processor = new ParseJREAPC();
        runner = TestRunners.newTestRunner(processor);
    }

    // ─── parseHeader unit tests ───────────────────────────────────────────────

    @Test
    void parseHeader_secretClassification() {
        byte[] header = buildHeader(0x0500, 2, 0x00, 42, new byte[8], new byte[8], 1700000000L, 99);
        Map<String, String> attrs = processor.parseHeader(header, 100, null);

        assertEquals("SECRET",  attrs.get("jreapc.classification"));
        assertEquals("2",       attrs.get("jreapc.classification_code"));
        assertEquals("J5.0",    attrs.get("jreapc.word_type"));
        assertEquals("0x0500",  attrs.get("jreapc.word_type_code"));
        assertEquals("42",      attrs.get("jreapc.sequence_number"));
        assertEquals("99",      attrs.get("jreapc.track_number"));
        assertEquals("100",     attrs.get("jreapc.payload_size"));
        assertFalse(Boolean.parseBoolean(attrs.get("jreapc.exercise")));
        assertFalse(Boolean.parseBoolean(attrs.get("jreapc.simulation")));
        assertNull(attrs.get("tdf_attribute"), "tdf_attribute not set when namespace is null");
    }

    @Test
    void parseHeader_allClassificationLabels() {
        String[] expected = {"UNCLASSIFIED", "CUI", "SECRET", "TOP SECRET"};
        for (int code = 0; code < 4; code++) {
            byte[] header = buildHeader(0x0300, code, 0x00, 0, new byte[8], new byte[8], 0L, 0);
            Map<String, String> attrs = processor.parseHeader(header, 0, null);
            assertEquals(expected[code], attrs.get("jreapc.classification"), "code=" + code);
        }
    }

    @Test
    void parseHeader_unknownClassificationCode() {
        byte[] header = buildHeader(0x0300, 9, 0x00, 0, new byte[8], new byte[8], 0L, 0);
        Map<String, String> attrs = processor.parseHeader(header, 0, null);
        assertTrue(attrs.get("jreapc.classification").startsWith("UNKNOWN"));
    }

    @Test
    void parseHeader_exerciseAndSimulationFlags() {
        byte[] header = buildHeader(0x0300, 0, 0x03, 0, new byte[8], new byte[8], 0L, 0);
        Map<String, String> attrs = processor.parseHeader(header, 0, null);
        assertTrue(Boolean.parseBoolean(attrs.get("jreapc.exercise")));
        assertTrue(Boolean.parseBoolean(attrs.get("jreapc.simulation")));
    }

    @Test
    void parseHeader_tdfAttributePopulatedWhenNamespaceSet() {
        byte[] header = buildHeader(0x0500, 2, 0x00, 0, new byte[8], new byte[8], 0L, 0);
        String ns = "https://classification.example.org/attr/level";
        Map<String, String> attrs = processor.parseHeader(header, 0, ns);
        assertEquals(ns + "/value/secret", attrs.get("tdf_attribute"));
    }

    @Test
    void parseHeader_topSecretTdfAttributeSlug() {
        byte[] header = buildHeader(0x0300, 3, 0x00, 0, new byte[8], new byte[8], 0L, 0);
        String ns = "https://ns.example/attr/level";
        Map<String, String> attrs = processor.parseHeader(header, 0, ns);
        assertEquals(ns + "/value/top_secret", attrs.get("tdf_attribute"));
    }

    @Test
    void parseHeader_unknownWordType() {
        byte[] header = buildHeader(0xABCD, 0, 0x00, 0, new byte[8], new byte[8], 0L, 0);
        Map<String, String> attrs = processor.parseHeader(header, 0, null);
        assertTrue(attrs.get("jreapc.word_type").startsWith("J-UNKNOWN"));
        assertEquals("0xABCD", attrs.get("jreapc.word_type_code"));
    }

    // ─── Processor integration tests ─────────────────────────────────────────

    @Test
    void onTrigger_tooShortGoesToFailure() {
        runner.enqueue(new byte[10]); // smaller than 32-byte header
        runner.run(1);

        assertEquals(0, runner.getFlowFilesForRelationship(ParseJREAPC.REL_SUCCESS).size());
        assertEquals(1, runner.getFlowFilesForRelationship(ParseJREAPC.REL_FAILURE).size());
    }

    @Test
    void onTrigger_validMessageGoesToSuccess() throws Exception {
        byte[] payload = "tactical payload".getBytes();
        byte[] message = buildMessage(0x0700, 1, 0x00, 7, new byte[8], new byte[8], 1700000000L, 5, payload);

        runner.enqueue(message);
        runner.run(1);

        List<MockFlowFile> success = runner.getFlowFilesForRelationship(ParseJREAPC.REL_SUCCESS);
        assertEquals(1, success.size());
        MockFlowFile ff = success.get(0);
        ff.assertAttributeEquals("jreapc.classification", "CUI");
        ff.assertAttributeEquals("jreapc.word_type", "J7.0");
        ff.assertAttributeEquals("jreapc.payload_size", String.valueOf(payload.length));
        // Content is passed through unchanged
        ff.assertContentEquals(message);
    }

    @Test
    void onTrigger_exactlyHeaderSize() {
        byte[] message = buildMessage(0x0300, 0, 0x00, 1, new byte[8], new byte[8], 0L, 0, new byte[0]);
        runner.enqueue(message);
        runner.run(1);

        assertEquals(1, runner.getFlowFilesForRelationship(ParseJREAPC.REL_SUCCESS).size());
        runner.getFlowFilesForRelationship(ParseJREAPC.REL_SUCCESS).get(0)
                .assertAttributeEquals("jreapc.payload_size", "0");
    }

    @Test
    void onTrigger_withNamespaceProperty() {
        runner.setProperty(ParseJREAPC.CLASSIFICATION_ATTRIBUTE_NAMESPACE,
                "https://classification.example.org/attr/level");
        byte[] message = buildMessage(0x0500, 2, 0x00, 1, new byte[8], new byte[8], 0L, 0, new byte[0]);
        runner.enqueue(message);
        runner.run(1);

        MockFlowFile ff = runner.getFlowFilesForRelationship(ParseJREAPC.REL_SUCCESS).get(0);
        ff.assertAttributeEquals("tdf_attribute",
                "https://classification.example.org/attr/level/value/secret");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Build a 32-byte JREAP-C header. */
    static byte[] buildHeader(int wordType, int classCode, int flags,
                              long seqNum, byte[] src, byte[] dst,
                              long timestamp, int trackNumber) {
        ByteBuffer buf = ByteBuffer.allocate(ParseJREAPC.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) wordType);
        buf.put((byte) classCode);
        buf.put((byte) flags);
        buf.putInt((int) seqNum);
        buf.put(src, 0, 8);
        buf.put(dst, 0, 8);
        buf.putInt((int) timestamp);
        buf.putShort((short) trackNumber);
        buf.putShort((short) 0); // reserved
        return buf.array();
    }

    /** Build a full JREAP-C message (header + payload). */
    static byte[] buildMessage(int wordType, int classCode, int flags,
                               long seqNum, byte[] src, byte[] dst,
                               long timestamp, int trackNumber, byte[] payload) {
        byte[] header = buildHeader(wordType, classCode, flags, seqNum, src, dst, timestamp, trackNumber);
        byte[] msg = new byte[header.length + payload.length];
        System.arraycopy(header, 0, msg, 0, header.length);
        System.arraycopy(payload, 0, msg, header.length, payload.length);
        return msg;
    }
}
