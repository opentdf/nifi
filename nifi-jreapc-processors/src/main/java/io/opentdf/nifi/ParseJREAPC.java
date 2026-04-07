package io.opentdf.nifi;

import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses binary JREAP-C (Joint Range Extension Applications Protocol Category C)
 * messages and extracts policy-relevant header fields as NiFi flow file attributes.
 *
 * JREAP-C Header Format (32 bytes):
 *   [0-1]  uint16 BE  J-series word type
 *   [2]    uint8      Security classification (0=U, 1=CUI, 2=S, 3=TS)
 *   [3]    uint8      Flags (bit0=exercise, bit1=simulation)
 *   [4-7]  uint32 BE  Sequence number
 *   [8-15] 8 bytes    Source address
 *   [16-23] 8 bytes   Destination address
 *   [24-27] uint32 BE Timestamp (Unix seconds)
 *   [28-29] uint16 BE Track number
 *   [30-31] 2 bytes   Reserved
 *   [32+]              Payload
 *
 * The flow file content is passed through unmodified. Only attributes are added.
 */
@CapabilityDescription("Parses JREAP-C binary message headers and extracts classification, " +
        "source/destination, track number, and word type as flow file attributes for downstream " +
        "ABAC policy enforcement. The payload bytes are passed through unchanged.")
@Tags({"JREAP-C", "JREAP", "Link16", "TDL", "tactical", "ABAC", "classification", "parse", "DSP"})
@WritesAttributes({
    @WritesAttribute(attribute = "jreapc.word_type",         description = "J-series word type label (e.g. J3.0)"),
    @WritesAttribute(attribute = "jreapc.word_type_code",    description = "J-series word type hex code"),
    @WritesAttribute(attribute = "jreapc.classification",    description = "Security classification label (UNCLASSIFIED, CUI, SECRET, TOP SECRET)"),
    @WritesAttribute(attribute = "jreapc.classification_code", description = "Raw classification byte value (0-3)"),
    @WritesAttribute(attribute = "jreapc.exercise",          description = "true if exercise flag is set"),
    @WritesAttribute(attribute = "jreapc.simulation",        description = "true if simulation flag is set"),
    @WritesAttribute(attribute = "jreapc.sequence_number",   description = "Message sequence number"),
    @WritesAttribute(attribute = "jreapc.source_address",    description = "Source node address (hex)"),
    @WritesAttribute(attribute = "jreapc.destination_address", description = "Destination node address (hex)"),
    @WritesAttribute(attribute = "jreapc.timestamp",         description = "Message timestamp (ISO-8601 UTC)"),
    @WritesAttribute(attribute = "jreapc.track_number",      description = "Track number"),
    @WritesAttribute(attribute = "jreapc.payload_size",      description = "Payload size in bytes (after 32-byte header)"),
})
public class ParseJREAPC extends AbstractProcessor {

    static final int HEADER_SIZE = 32;

    /** Map of J-series word type codes to human-readable labels. */
    private static final Map<Integer, String> WORD_TYPE_LABELS;
    static {
        Map<Integer, String> m = new HashMap<>();
        m.put(0x0300, "J3.0");   // Track Data
        m.put(0x0304, "J3.4");   // EW Track Data
        m.put(0x0500, "J5.0");   // Air Track
        m.put(0x0504, "J5.4");   // Air Posture
        m.put(0x0700, "J7.0");   // Surface Track
        m.put(0x0900, "J9.0");   // Point of Interest
        m.put(0x0C00, "J12.0");  // Mission Assignment
        m.put(0x0D00, "J13.0");  // C2 Message
        m.put(0x1100, "J17.0");  // Joint Engagement Sequence
        m.put(0x1200, "J18.0");  // Correlation
        m.put(0x1F00, "J31.0");  // Identification
        WORD_TYPE_LABELS = Collections.unmodifiableMap(m);
    }

    private static final String[] CLASSIFICATION_LABELS = {
        "UNCLASSIFIED", "CUI", "SECRET", "TOP SECRET"
    };

    // ─── Relationships ────────────────────────────────────────────────────────

    static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Flow file with parsed JREAP-C attributes")
            .build();

    static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("Flow file that could not be parsed (too short, invalid header)")
            .build();

    // ─── Properties ──────────────────────────────────────────────────────────

    static final PropertyDescriptor CLASSIFICATION_ATTRIBUTE_NAMESPACE = new PropertyDescriptor.Builder()
            .name("Classification Attribute Namespace")
            .displayName("Classification Attribute Namespace")
            .description("DSP attribute namespace FQN for classification mapping. " +
                    "When set, a 'tdf_attribute' flow file attribute is populated with the " +
                    "corresponding attribute value FQN so downstream ABACEnforcement can use it directly. " +
                    "Example: https://classification.example.org/attr/level")
            .required(false)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    static final PropertyDescriptor FLOWFILE_PULL_SIZE = new PropertyDescriptor.Builder()
            .name("FlowFile queue pull limit")
            .description("FlowFile queue pull size limit")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .defaultValue("10")
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .build();

    @Override
    public Set<Relationship> getRelationships() {
        return new HashSet<>(Arrays.asList(REL_SUCCESS, REL_FAILURE));
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return Arrays.asList(CLASSIFICATION_ATTRIBUTE_NAMESPACE, FLOWFILE_PULL_SIZE);
    }

    @Override
    public void onTrigger(ProcessContext ctx, ProcessSession session) throws ProcessException {
        List<FlowFile> flowFiles = session.get(ctx.getProperty(FLOWFILE_PULL_SIZE).evaluateAttributeExpressions().asInteger());
        if (flowFiles.isEmpty()) return;

        String classificationNs = ctx.getProperty(CLASSIFICATION_ATTRIBUTE_NAMESPACE).isSet()
                ? ctx.getProperty(CLASSIFICATION_ATTRIBUTE_NAMESPACE).evaluateAttributeExpressions().getValue()
                : null;

        for (FlowFile flowFile : flowFiles) {
            if (flowFile.getSize() < HEADER_SIZE) {
                getLogger().warn("FlowFile {} is too small ({} bytes) to be a valid JREAP-C message",
                        flowFile.getId(), flowFile.getSize());
                session.transfer(flowFile, REL_FAILURE);
                continue;
            }

            final byte[] header = new byte[HEADER_SIZE];
            session.read(flowFile, in -> in.read(header, 0, HEADER_SIZE));

            try {
                Map<String, String> attrs = parseHeader(header, (int) flowFile.getSize() - HEADER_SIZE,
                        classificationNs);
                flowFile = session.putAllAttributes(flowFile, attrs);
                session.transfer(flowFile, REL_SUCCESS);
            } catch (Exception e) {
                getLogger().error("Failed to parse JREAP-C header for FlowFile {}", flowFile.getId(), e);
                session.transfer(flowFile, REL_FAILURE);
            }
        }
    }

    // ─── Package-private for testing ─────────────────────────────────────────

    Map<String, String> parseHeader(byte[] header, int payloadSize, String classificationNs) {
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);

        int wordTypeCode   = buf.getShort(0) & 0xFFFF;
        int classCode      = buf.get(2) & 0xFF;
        int flags          = buf.get(3) & 0xFF;
        long seqNumber     = buf.getInt(4) & 0xFFFFFFFFL;
        byte[] srcAddr     = Arrays.copyOfRange(header, 8, 16);
        byte[] dstAddr     = Arrays.copyOfRange(header, 16, 24);
        long timestamp     = buf.getInt(24) & 0xFFFFFFFFL;
        int trackNumber    = buf.getShort(28) & 0xFFFF;

        boolean exercise   = (flags & 0x01) != 0;
        boolean simulation = (flags & 0x02) != 0;

        String wordTypeLabel = WORD_TYPE_LABELS.getOrDefault(wordTypeCode,
                String.format("J-UNKNOWN(0x%04X)", wordTypeCode));
        String classLabel    = classCode < CLASSIFICATION_LABELS.length
                ? CLASSIFICATION_LABELS[classCode]
                : "UNKNOWN(" + classCode + ")";
        String isoTimestamp  = Instant.ofEpochSecond(timestamp)
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, String> attrs = new HashMap<>();
        attrs.put("jreapc.word_type",            wordTypeLabel);
        attrs.put("jreapc.word_type_code",        String.format("0x%04X", wordTypeCode));
        attrs.put("jreapc.classification",        classLabel);
        attrs.put("jreapc.classification_code",   String.valueOf(classCode));
        attrs.put("jreapc.exercise",              String.valueOf(exercise));
        attrs.put("jreapc.simulation",            String.valueOf(simulation));
        attrs.put("jreapc.sequence_number",       String.valueOf(seqNumber));
        attrs.put("jreapc.source_address",        bytesToHex(srcAddr));
        attrs.put("jreapc.destination_address",   bytesToHex(dstAddr));
        attrs.put("jreapc.timestamp",             isoTimestamp);
        attrs.put("jreapc.track_number",          String.valueOf(trackNumber));
        attrs.put("jreapc.payload_size",          String.valueOf(payloadSize));

        // Auto-populate tdf_attribute from classification if namespace configured
        if (classificationNs != null && !classificationNs.isBlank()) {
            String valueSuffix = classLabel.toLowerCase().replace(" ", "_");
            attrs.put("tdf_attribute", classificationNs + "/value/" + valueSuffix);
        }

        return attrs;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
