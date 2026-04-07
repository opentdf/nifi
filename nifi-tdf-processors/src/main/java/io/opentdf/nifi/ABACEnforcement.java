package io.opentdf.nifi;

import io.opentdf.platform.authorization.AuthorizationServiceGrpc;
import io.opentdf.platform.authorization.DecisionRequest;
import io.opentdf.platform.authorization.DecisionResponse;
import io.opentdf.platform.authorization.Entity;
import io.opentdf.platform.authorization.EntityChain;
import io.opentdf.platform.authorization.GetDecisionsRequest;
import io.opentdf.platform.authorization.GetDecisionsResponse;
import io.opentdf.platform.authorization.ResourceAttribute;
import io.opentdf.platform.policy.Action;
import io.opentdf.platform.sdk.SDK;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Calls the OpenTDF Authorization Service (GetDecisions) to make an ABAC permit/deny
 * decision for a flow file. Routes to "permit" or "deny" based on the response.
 *
 * The processor reads the {@code tdf_attribute} flow file attribute as a
 * comma-separated list of OpenTDF resource attribute FQNs and submits them to the
 * authorization service. Any upstream processor that populates {@code tdf_attribute}
 * can feed into this processor — it is not tied to any specific protocol or format.
 *
 * Example flow:
 *   [Source] → [UpdateAttribute tdf_attribute=...] → [ABACEnforcement] → permit/deny/failure
 */
@CapabilityDescription("Calls the OpenTDF Authorization Service GetDecisions endpoint to make an " +
        "ABAC permit/deny decision for the flow file. Reads resource attribute FQNs from the " +
        "'tdf_attribute' flow file attribute and routes to 'permit', 'deny', or 'failure' " +
        "based on the response. Works with any flow that sets tdf_attribute upstream.")
@Tags({"ABAC", "authorization", "OpenTDF", "policy", "enforcement", "permit", "deny", "access control"})
@ReadsAttributes({
    @ReadsAttribute(attribute = "tdf_attribute",
            description = "Comma-separated OpenTDF resource attribute value FQNs used as the " +
                    "resource context for the authorization decision. Required — flow files " +
                    "missing this attribute are routed to failure."),
})
@WritesAttributes({
    @WritesAttribute(attribute = "abac.decision",
            description = "PERMIT or DENY"),
    @WritesAttribute(attribute = "abac.entity_id",
            description = "The entity ID used in the authorization request"),
    @WritesAttribute(attribute = "abac.resource_attributes",
            description = "Comma-separated resource attribute FQNs that were evaluated"),
    @WritesAttribute(attribute = "abac.processing_time_ms",
            description = "Time taken for the GetDecisions call in milliseconds"),
})
public class ABACEnforcement extends AbstractTDFProcessor {

    // ─── Relationships ────────────────────────────────────────────────────────

    static final Relationship REL_PERMIT = new Relationship.Builder()
            .name("permit")
            .description("Authorization service returned DECISION_PERMIT")
            .build();

    static final Relationship REL_DENY = new Relationship.Builder()
            .name("deny")
            .description("Authorization service returned DECISION_DENY")
            .build();

    @Override
    public Set<Relationship> getRelationships() {
        return new HashSet<>(Arrays.asList(REL_PERMIT, REL_DENY, REL_FAILURE));
    }

    // ─── Properties ──────────────────────────────────────────────────────────

    static final AllowableValue ENTITY_TYPE_CLIENT_ID   = new AllowableValue("CLIENT_ID", "Client ID");
    static final AllowableValue ENTITY_TYPE_EMAIL        = new AllowableValue("EMAIL", "Email Address");
    static final AllowableValue ENTITY_TYPE_USERNAME     = new AllowableValue("USERNAME", "Username");

    static final PropertyDescriptor ENTITY_ID = new PropertyDescriptor.Builder()
            .name("Entity ID")
            .displayName("Entity ID")
            .description("The entity (user, service account, or client) making the data access request. " +
                    "Used as the subject in the GetDecisions call. Supports Expression Language to " +
                    "read from flow file attributes (e.g. ${jwt.sub}).")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .build();

    static final PropertyDescriptor ENTITY_TYPE = new PropertyDescriptor.Builder()
            .name("Entity Type")
            .displayName("Entity Type")
            .description("How to interpret the Entity ID value.")
            .required(true)
            .allowableValues(ENTITY_TYPE_CLIENT_ID, ENTITY_TYPE_EMAIL, ENTITY_TYPE_USERNAME)
            .defaultValue("CLIENT_ID")
            .build();

    static final PropertyDescriptor DEFAULT_RESOURCE_ATTRIBUTES = new PropertyDescriptor.Builder()
            .name("Default Resource Attribute FQNs")
            .displayName("Default Resource Attribute FQNs")
            .description("Comma-separated list of attribute value FQNs to use when the 'tdf_attribute' " +
                    "flow file attribute is not set. Leave blank to require tdf_attribute on every message. " +
                    "Example: https://classification.example.org/attr/level/value/secret")
            .required(false)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    static final PropertyDescriptor DECISION_TIMEOUT_SECONDS = new PropertyDescriptor.Builder()
            .name("Decision Timeout (seconds)")
            .displayName("Decision Timeout (seconds)")
            .description("Maximum time to wait for a GetDecisions response from the authorization service.")
            .required(true)
            .defaultValue("5")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    static final PropertyDescriptor FAIL_OPEN = new PropertyDescriptor.Builder()
            .name("Fail Open")
            .displayName("Fail Open on Authorization Error")
            .description("When true, routes to 'permit' if the authorization service is unreachable " +
                    "or returns an error. When false (default), routes to 'failure'.")
            .required(true)
            .allowableValues("true", "false")
            .defaultValue("false")
            .build();

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        List<PropertyDescriptor> props = new java.util.ArrayList<>(super.getSupportedPropertyDescriptors());
        props.add(ENTITY_ID);
        props.add(ENTITY_TYPE);
        props.add(DEFAULT_RESOURCE_ATTRIBUTES);
        props.add(DECISION_TIMEOUT_SECONDS);
        props.add(FAIL_OPEN);
        return Collections.unmodifiableList(props);
    }

    // ─── Processor logic ─────────────────────────────────────────────────────

    @Override
    void processFlowFiles(ProcessContext ctx, ProcessSession session, List<FlowFile> flowFiles)
            throws ProcessException {

        SDK sdk = getTDFSDK(ctx);
        AuthorizationServiceGrpc.AuthorizationServiceFutureStub authStub =
                sdk.getServices().authorization();

        int timeoutSeconds = ctx.getProperty(DECISION_TIMEOUT_SECONDS).asInteger();
        Boolean failOpenVal = ctx.getProperty(FAIL_OPEN).asBoolean();
        if (failOpenVal == null) {
            throw new ProcessException("Fail Open property did not resolve to 'true' or 'false'");
        }
        boolean failOpen = failOpenVal;

        String defaultAttrFqns = ctx.getProperty(DEFAULT_RESOURCE_ATTRIBUTES).isSet()
                ? ctx.getProperty(DEFAULT_RESOURCE_ATTRIBUTES).evaluateAttributeExpressions().getValue()
                : null;

        for (FlowFile flowFile : flowFiles) {
            long startMs = System.currentTimeMillis();
            try {
                // Resolve entity ID
                String entityId = ctx.getProperty(ENTITY_ID)
                        .evaluateAttributeExpressions(flowFile).getValue();
                String entityType = ctx.getProperty(ENTITY_TYPE).getValue();

                // Resolve resource attributes from tdf_attribute or default
                String attrFqnsCsv = flowFile.getAttribute("tdf_attribute");
                if (attrFqnsCsv == null || attrFqnsCsv.isBlank()) {
                    attrFqnsCsv = defaultAttrFqns;
                }
                if (attrFqnsCsv == null || attrFqnsCsv.isBlank()) {
                    throw new ProcessException("No resource attributes: set tdf_attribute on flow file " +
                            "or configure 'Default Resource Attribute FQNs'");
                }

                // Build entity
                Entity.Builder entityBuilder = Entity.newBuilder().setId("entity-0");
                switch (entityType) {
                    case "EMAIL"    -> entityBuilder.setEmailAddress(entityId);
                    case "USERNAME" -> entityBuilder.setUserName(entityId);
                    default         -> entityBuilder.setClientId(entityId);
                }
                Entity entity = entityBuilder.build();

                // Build entity chain
                EntityChain entityChain = EntityChain.newBuilder()
                        .setId("ec-0")
                        .addEntities(entity)
                        .build();

                // Build resource attributes
                ResourceAttribute.Builder raBuilder = ResourceAttribute.newBuilder()
                        .setResourceAttributesId("ra-0");
                for (String fqn : attrFqnsCsv.split(",")) {
                    String trimmed = fqn.trim();
                    if (!trimmed.isEmpty()) raBuilder.addAttributeValueFqns(trimmed);
                }
                if (raBuilder.getAttributeValueFqnsCount() == 0) {
                    throw new ProcessException("Resource attribute FQN list is empty after parsing: " + attrFqnsCsv);
                }
                ResourceAttribute resourceAttribute = raBuilder.build();

                // Build action (TRANSMIT for data forwarding)
                Action action = Action.newBuilder()
                        .setStandard(Action.StandardAction.STANDARD_ACTION_TRANSMIT)
                        .build();

                // Build and fire GetDecisions request
                DecisionRequest decisionRequest = DecisionRequest.newBuilder()
                        .addActions(action)
                        .addEntityChains(entityChain)
                        .addResourceAttributes(resourceAttribute)
                        .build();

                GetDecisionsRequest request = GetDecisionsRequest.newBuilder()
                        .addDecisionRequests(decisionRequest)
                        .build();

                GetDecisionsResponse response = authStub.getDecisions(request)
                        .get(timeoutSeconds, TimeUnit.SECONDS);

                long elapsedMs = System.currentTimeMillis() - startMs;

                // Evaluate decision — empty response is not a permit
                if (response.getDecisionResponsesList().isEmpty()) {
                    throw new ProcessException("Authorization service returned no decisions");
                }
                DecisionResponse.Decision overallDecision = DecisionResponse.Decision.DECISION_PERMIT;
                for (DecisionResponse dr : response.getDecisionResponsesList()) {
                    if (dr.getDecision() != DecisionResponse.Decision.DECISION_PERMIT) {
                        overallDecision = DecisionResponse.Decision.DECISION_DENY;
                        break;
                    }
                }

                String decisionLabel = overallDecision == DecisionResponse.Decision.DECISION_PERMIT
                        ? "PERMIT" : "DENY";

                flowFile = session.putAttribute(flowFile, "abac.decision", decisionLabel);
                flowFile = session.putAttribute(flowFile, "abac.entity_id", entityId);
                flowFile = session.putAttribute(flowFile, "abac.resource_attributes", attrFqnsCsv);
                flowFile = session.putAttribute(flowFile, "abac.processing_time_ms",
                        String.valueOf(elapsedMs));

                getLogger().info("ABAC decision: {} | attrs={} | {}ms",
                        decisionLabel, attrFqnsCsv, elapsedMs);
                getLogger().debug("ABAC subject: {}", entityId);

                Relationship rel = overallDecision == DecisionResponse.Decision.DECISION_PERMIT
                        ? REL_PERMIT : REL_DENY;
                session.transfer(flowFile, rel);

            } catch (ProcessException pe) {
                // Local validation failures (missing attributes, bad config) are never
                // fail-open — unclassified or malformed flow files must not bypass policy.
                getLogger().error("ABAC request validation failed for FlowFile {}: {}",
                        flowFile.getId(), pe.getMessage());
                session.transfer(flowFile, REL_FAILURE);
            } catch (Exception e) {
                // Remote call failures (network, timeout, service unavailable) respect failOpen.
                getLogger().error("ABAC authorization call failed for FlowFile {}", flowFile.getId(), e);
                if (failOpen) {
                    flowFile = session.putAttribute(flowFile, "abac.decision", "PERMIT");
                    flowFile = session.putAttribute(flowFile, "abac.error", e.getMessage());
                    session.transfer(flowFile, REL_PERMIT);
                } else {
                    session.transfer(flowFile, REL_FAILURE);
                }
            }
        }
    }
}
