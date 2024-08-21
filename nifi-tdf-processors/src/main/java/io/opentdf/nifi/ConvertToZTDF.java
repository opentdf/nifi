package io.opentdf.nifi;

import com.google.gson.Gson;
import io.opentdf.platform.sdk.AssertionConfig;
import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.Config.TDFConfig;
import io.opentdf.platform.sdk.SDK;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.key.service.api.PrivateKeyService;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.function.Consumer;

@CapabilityDescription("Transforms flow file content into a ZTDF")
@Tags({"ZTDF", "OpenTDF", "Zero Trust Data Format", "Encrypt", "Data Centric Security"})
@ReadsAttributes(value = {
        @ReadsAttribute(attribute = "kas_url", description = "The Key Access Server (KAS) URL used TDF Creation. This overrides " +
                "the KAS URL property of this processor."),
        @ReadsAttribute(attribute = "tdf_attribute", description = "A comma separated list of data attributes added " +
                "to created TDF Data Policy. e.g. http://example.org/attr/foo/value/bar,http://example.org/attr/foo/value/bar2"),
        @ReadsAttribute(attribute = "tdf_assertion_<id>", description = """
                A single assertion with a JSON payload reflecting the assertion schema :
                {\
                "type":<>,
                "scope":<>
                ,\
                "appliesToState":<>
                "statement": {
                 "value":<>,
                 "format":<>,
                }\s
                }; more than one \
                assertion supported through the "tdf_assertion_" attribute name prefix. e.g. tdf_assertion_1, tdf_assertion_2""")
})
public class ConvertToZTDF extends AbstractToProcessor {

    public static final PropertyDescriptor SIGN_ASSERTIONS = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("Sign Assertions")
            .description("sign assertions")
            .required(false)
            .defaultValue("false")
            .allowableValues("true", "false")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor PRIVATE_KEY_CONTROLLER_SERVICE = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("Private Key Controller Service")
            .description("Optional Private Key Service; this is need for assertion signing")
            .required(true)
            .identifiesControllerService(PrivateKeyService.class)
            .dependsOn(SIGN_ASSERTIONS, new AllowableValue("true"))
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();


    PrivateKeyService getPrivateKeyService(ProcessContext processContext) {
        return processContext.getProperty(PRIVATE_KEY_CONTROLLER_SERVICE).isSet() ?
                processContext.getProperty(PRIVATE_KEY_CONTROLLER_SERVICE)
                        .asControllerService(PrivateKeyService.class) : null;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        List<PropertyDescriptor> propertyDescriptors = new ArrayList<>(super.getSupportedPropertyDescriptors());
        propertyDescriptors.add(PRIVATE_KEY_CONTROLLER_SERVICE);
        propertyDescriptors.add(SIGN_ASSERTIONS);
        return Collections.unmodifiableList(propertyDescriptors);
    }

    Gson gson = new Gson();

    Map<String, AssertionConfig.Type> assertionTypeMap = Map.of("handling", AssertionConfig.Type.HandlingAssertion,
            "base", AssertionConfig.Type.BaseAssertion);
    Map<String, AssertionConfig.Scope> assertionScopeMap = Map.of("tdo", AssertionConfig.Scope.TrustedDataObj,
            "payload", AssertionConfig.Scope.Payload);
    Map<String, AssertionConfig.AppliesToState> assertionAppliesToStateMap = Map.of("encrypted", AssertionConfig.AppliesToState.Encrypted,
            "unencrypted", AssertionConfig.AppliesToState.Unencrypted);
    /**
     * Build an assertion config from the flow file attribute
     * @return
     * @throws Exception throw exception when assertion is not valid
     */
    AssertionConfig buildAssertion(ProcessContext processContext, FlowFile flowFile, String flowFileAttributeName) throws Exception{
        String assertionJson = flowFile.getAttribute(flowFileAttributeName);
        Map<?,?> assertionMap = gson.fromJson(assertionJson, Map.class);
        AssertionConfig assertionConfig = new AssertionConfig();
        assertionConfig.id = assertionMap.containsKey("id") ? (String)assertionMap.get("id") : null;
        assertionConfig.type = assertionMap.containsKey("type") ? assertionTypeMap.get(assertionMap.get("type")) : null;
        assertionConfig.scope =assertionMap.containsKey("scope") ? assertionScopeMap.get(assertionMap.get("scope")) : null;
        assertionConfig.appliesToState = assertionMap.containsKey("appliesToState") ? assertionAppliesToStateMap.get(assertionMap.get("appliesToState")): null;
        assertionConfig.statement = new AssertionConfig.Statement();

        Map<?,?> statementMap = (Map<?,?>)assertionMap.get("statement");
        if(statementMap!=null) {
            assertionConfig.statement.format = statementMap.containsKey("format") ? (String)statementMap.get("format") : null;
            assertionConfig.statement.value = (String)statementMap.get("value");
        }
        addSigningInfoToAssertionConfig(processContext, assertionConfig);
        if(assertionConfig.scope == null){
            throw new Exception("assertion scope is required");
        }
        if(assertionConfig.statement == null){
            throw new Exception("assertion statement is required");
        }
        if(assertionConfig.statement.format == null){
            throw new Exception("assertion statement format is required");
        }
        if(assertionConfig.appliesToState == null){
            throw new Exception("assertion appliesToState is required");
        }
        if(assertionConfig.type == null){
            throw new Exception("assertion type is required");
        }
        return assertionConfig;
    }

    @Override
    void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException {
        SDK sdk = getTDFSDK(processContext);
        for (final FlowFile flowFile : flowFiles) {
            try {
                var kasInfoList = getKASInfoFromKASURLs(getKasUrl(flowFile, processContext));
                Set<String> dataAttributes = getDataAttributes(flowFile);
                //build baseline TDF Config options
                List<Consumer<TDFConfig>> configurationOptions = new ArrayList<>(Arrays.asList(Config.withKasInformation(kasInfoList.toArray(new Config.KASInfo[0])),
                        Config.withDataAttributes(dataAttributes.toArray(new String[0]))));
                List<String> nifiAssertionAttributeKeys = flowFile.getAttributes().keySet().stream().filter(x->x.startsWith(TDF_ASSERTION_PREFIX)).toList();
                for(String nifiAssertionAttributeKey: nifiAssertionAttributeKeys) {
                    getLogger().debug(String.format("Adding assertion for NiFi attribute = %s", nifiAssertionAttributeKey));
                    configurationOptions.add(Config.withAssertionConfig(buildAssertion(processContext, flowFile, nifiAssertionAttributeKey)));
                }
                TDFConfig config = Config.newTDFConfig(configurationOptions.toArray(new Consumer[0]));

                //write ZTDF to FlowFile
                FlowFile updatedFlowFile = processSession.write(flowFile, (inputStream, outputStream) -> {
                            try {
                                getTDF().createTDF(inputStream, outputStream, config, sdk.getServices().kas(), sdk.getServices().attributes());
                            } catch (Exception e) {
                                getLogger().error("error creating ZTDF", e);
                                throw new IOException(e);
                            }
                        }
                );
                updatedFlowFile = processSession.putAttribute(updatedFlowFile, "mime.type", "application/ztdf+zip");
                processSession.transfer(updatedFlowFile, REL_SUCCESS);
            } catch (Exception e) {
                getLogger().error(flowFile.getId() + ": error converting plain text to ZTDF", e);
                processSession.transfer(flowFile, REL_FAILURE);
            }
        }
    }

    private void addSigningInfoToAssertionConfig(ProcessContext processContext, AssertionConfig assertionConfig) throws NoSuchAlgorithmException, InvalidKeySpecException {
        Optional<PropertyValue> signAssertions = getPropertyValue(SIGN_ASSERTIONS, processContext);
        //populate assertion signing config only when sign assertions property is true and assertions exist
        if (signAssertions.isPresent() && signAssertions.get().asBoolean()) {
            getLogger().debug("signed assertions is active");
            PrivateKeyService privateKeyService = getPrivateKeyService(processContext);
            if (privateKeyService != null) {
                getLogger().debug("adding signing configuration for assertion");
                //TODO assumes RSA256 signing key
                PrivateKey privateKey = privateKeyService.getPrivateKey();
                assertionConfig.assertionKey = new AssertionConfig.AssertionKey(AssertionConfig.AssertionKeyAlg.RS256, privateKey);
            }
        }
    }
}
