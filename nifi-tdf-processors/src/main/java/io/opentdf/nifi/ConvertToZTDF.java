package io.opentdf.nifi;

import com.google.gson.Gson;
import com.nimbusds.jose.jwk.RSAKey;
import io.opentdf.platform.sdk.Assertion;
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
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
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


    /**
     * Build an assertion from the attribute
     * @return
     * @throws Exception throw exception when assertion is not valid
     */
    Assertion buildAssertion(FlowFile flowFile, String flowFileAttributeName) throws Exception{
        String assertionJson = flowFile.getAttribute(flowFileAttributeName);
        Map<?,?> assertionMap = gson.fromJson(assertionJson, Map.class);
        Assertion assertion = new Assertion();
        assertion.id = assertionMap.containsKey("id") ? (String)assertionMap.get("id") : null;
        assertion.type = assertionMap.containsKey("type") ? (String)assertionMap.get("type") : null;
        assertion.scope =assertionMap.containsKey("scope") ? (String)assertionMap.get("scope") : null;
        assertion.appliesToState = assertionMap.containsKey("appliesToState") ? (String)assertionMap.get("appliesToState"): null;
        assertion.statement = new Assertion.Statement();
        Map<?,?> statementMap = (Map<?,?>)assertionMap.get("statement");
        if(statementMap!=null) {
            assertion.statement.format = statementMap.containsKey("format") ? (String)statementMap.get("format") : null;
            assertion.statement.value = (String)statementMap.get("value");
        }
        if(assertion.scope == null){
            throw new Exception("assertion scope is required");
        }
        if(assertion.statement == null){
            throw new Exception("assertion statement is required");
        }
        if(assertion.statement.format == null){
            throw new Exception("assertion statement format is required");
        }
        if(assertion.appliesToState == null){
            throw new Exception("assertion appliesToState is required");
        }
        if(assertion.type == null){
            throw new Exception("assertion type is required");
        }
        return assertion;
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
                    configurationOptions.add(Config.WithAssertion(buildAssertion(flowFile, nifiAssertionAttributeKey)));
                }
                configurationOptions.add(Config.withAssertionConfig(buildAssertionConfig(processContext, nifiAssertionAttributeKeys)));
                TDFConfig config = Config.newTDFConfig(configurationOptions.toArray(new Consumer[0]));

                //write ZTDF to FlowFile
                FlowFile updatedFlowFile = processSession.write(flowFile, (inputStream, outputStream) -> {
                            try {
                                getTDF().createTDF(inputStream, outputStream, config, sdk.getServices().kas());
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

    private Config.AssertionConfig buildAssertionConfig(ProcessContext processContext, List<String> nifiAssertionAttributeKeys) throws NoSuchAlgorithmException, InvalidKeySpecException {
        Config.AssertionConfig assertionConfig = new Config.AssertionConfig();
        Optional<PropertyValue> signAssertions = getPropertyValue(SIGN_ASSERTIONS, processContext);
        //populate assertion signing config only when sign assertions property is true and assertions exist
        if (!nifiAssertionAttributeKeys.isEmpty() && signAssertions.isPresent() && signAssertions.get().asBoolean()) {
            getLogger().debug("signed assertions is active");
            PrivateKeyService privateKeyService = getPrivateKeyService(processContext);
            if (privateKeyService != null) {
                getLogger().debug("adding signing configuration for assertion");
                //TODO assumes RSA256 signing key
                PrivateKey privateKey = privateKeyService.getPrivateKey();
                RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey) privateKey;
                RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent());
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                RSAPublicKey rsaPublicKey = (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
                assertionConfig.keyType = Config.AssertionConfig.KeyType.RS256;
                assertionConfig.rs256PrivateKeyForSigning = new RSAKey.Builder(rsaPublicKey).privateKey(rsaPrivateKey).build();
                assertionConfig.rs256PublicKeyForVerifying = new RSAKey.Builder(rsaPublicKey).build();
            }
        }
        return assertionConfig;
    }
}
