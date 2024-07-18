package io.opentdf.nifi;

import com.nimbusds.jose.jwk.RSAKey;
import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.TDF;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.io.FileUtils;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;


import java.io.File;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;


@CapabilityDescription("Decrypts ZTDF flow file content")
@Tags({"ZTDF", "Zero Trust Data Format", "OpenTDF", "Decrypt", "Data Centric Security"})
public class ConvertFromZTDF extends AbstractTDFProcessor {

    public static final PropertyDescriptor VERIFY_ASSERTIONS = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("Verify Assertions")
            .description("Verify assertions")
            .required(false)
            .defaultValue("false")
            .allowableValues("true", "false")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor ASSERTION_PUBLIC_KEY = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("Assertion Verification Key")
            .description("path to the public key for verifying assertions - assumes X509 Public Key in PEM format")
            .required(true)
            .dependsOn(VERIFY_ASSERTIONS, new AllowableValue("true"))
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();


    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        List<PropertyDescriptor> propertyDescriptors = new ArrayList<>(super.getSupportedPropertyDescriptors());
        propertyDescriptors.add(VERIFY_ASSERTIONS);
        propertyDescriptors.add(ASSERTION_PUBLIC_KEY);
        return Collections.unmodifiableList(propertyDescriptors);
    }

    private Config.AssertionConfig buildAssertionConfig(ProcessContext processContext) throws ProcessException{
        try {
            Config.AssertionConfig assertionConfig = new Config.AssertionConfig();
            Optional<PropertyValue> verifyAssertionValue = getPropertyValue(VERIFY_ASSERTIONS, processContext);
            if (verifyAssertionValue.isPresent() && verifyAssertionValue.get().asBoolean()) {
                String verificationKeyPath = processContext.getProperty(ASSERTION_PUBLIC_KEY).getValue();
                assertionConfig.keyType = Config.AssertionConfig.KeyType.RS256;
                String pem = FileUtils.readFileToString(new File(verificationKeyPath), Charset.defaultCharset());
                assertionConfig.rs256PublicKeyForVerifying = RSAKey.parseFromPEMEncodedX509Cert(pem).toRSAKey();
            }
            return assertionConfig;
        }catch (Exception e){
            throw new ProcessException(e);
        }
    }

    @Override
    void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException {
        SDK sdk = getTDFSDK(processContext);
        Config.AssertionConfig assertionConfig = buildAssertionConfig(processContext);
        for (FlowFile flowFile : flowFiles) {
            try {
                try (SeekableByteChannel seekableByteChannel = new SeekableInMemoryByteChannel(readEntireFlowFile(flowFile, processSession))) {
                    FlowFile updatedFlowFile = processSession.write(flowFile, outputStream -> {
                        try {
                            TDF.Reader reader = getTDF().loadTDF(seekableByteChannel, assertionConfig, sdk.getServices().kas());
                            reader.readPayload(outputStream);
                        } catch (Exception e) {
                            getLogger().error("error decrypting ZTDF", e);
                            throw new IOException(e);
                        }
                    });
                    processSession.transfer(updatedFlowFile, REL_SUCCESS);
                }
            } catch (Exception e) {
                getLogger().error(flowFile.getId() + ": error decrypting flowfile", e);
                processSession.transfer(flowFile, REL_FAILURE);
            }
        }
    }
}
