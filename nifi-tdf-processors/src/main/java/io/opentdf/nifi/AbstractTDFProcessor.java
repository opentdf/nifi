package io.opentdf.nifi;

import io.opentdf.platform.sdk.NanoTDF;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.SDKBuilder;
import io.opentdf.platform.sdk.TDF;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.stream.io.StreamUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.util.*;

/**
 * Common helper processor
 */
public abstract class AbstractTDFProcessor extends AbstractProcessor {

    public static final PropertyDescriptor FLOWFILE_PULL_SIZE = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("FlowFile queue pull limit")
            .description("FlowFile queue pull size limit")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .defaultValue("10")
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .description("Optional SSL Context Service")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .build();

    public static final PropertyDescriptor OPENTDF_CONFIG_SERVICE = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("OpenTDF Config Service")
            .description("Controller Service providing OpenTDF Platform Configuration")
            .required(true)
            .identifiesControllerService(OpenTDFControllerService.class)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("")
            .build();

    /**
     * Get a property value by evaluating attribute expressions if present.
     *
     * @param propertyValue
     * @return
     */
    PropertyValue getPropertyValue(PropertyValue propertyValue) {
        return propertyValue.isExpressionLanguagePresent() ? propertyValue.evaluateAttributeExpressions() : propertyValue;
    }

    private SDK sdk;

    /**
     * Create a new TDF SDK using the OpenTDFController Service as a source of configuration
     *
     * @param processContext
     * @return
     */
    SDK getTDFSDK(ProcessContext processContext) {
        if (sdk == null) {
            getLogger().info("SDK - create");
            OpenTDFControllerService openTDFControllerService = processContext.getProperty(OPENTDF_CONFIG_SERVICE)
                    .asControllerService(OpenTDFControllerService.class);
            Config config = openTDFControllerService.getConfig();

            SDKBuilder sdkBuilder = createSDKBuilder().platformEndpoint(config.getPlatformEndpoint())
                    .clientSecret(config.getClientId(), config.getClientSecret());
            if (processContext.getProperty(SSL_CONTEXT_SERVICE).isSet()) {
                getLogger().info("SDK - use SSLFactory from SSL Context Service truststore");
                SSLContextService sslContextService = processContext.getProperty(SSL_CONTEXT_SERVICE)
                        .asControllerService(SSLContextService.class);
                sdkBuilder = sdkBuilder.sslFactoryFromKeyStore(sslContextService.getTrustStoreFile(), sslContextService.getTrustStorePassword());
            }
            if (config.isUsePlainText()) {
                getLogger().info("SDK - use plaintext connection");
                sdkBuilder = sdkBuilder.useInsecurePlaintextConnection(true);
            }
            sdk = sdkBuilder.build();
        }
        return this.sdk;
    }

    //this is really here to allow for easier mocking for testing
    SDKBuilder createSDKBuilder() {
        return SDKBuilder.newBuilder();
    }

    @Override
    public void onPropertyModified(PropertyDescriptor descriptor, String oldValue, String newValue) {
        this.sdk = null;
    }


    @Override
    public Set<Relationship> getRelationships() {
        return new HashSet<>(Arrays.asList(REL_SUCCESS, REL_FAILURE));
    }

    FlowFile writeContent(FlowFile flowFile, ProcessSession session, InputStream payload) {
        return session.write(flowFile, new OutputStreamCallback() {
            @Override
            public void process(OutputStream outputStream) throws IOException {
                IOUtils.copy(payload, outputStream);
            }
        });
    }

    byte[] readEntireFlowFile(FlowFile flowFile, ProcessSession processSession) {
        final byte[] buffer = new byte[(int) flowFile.getSize()];
        processSession.read(flowFile, in -> StreamUtils.fillBuffer(in, buffer));
        return buffer;
    }

    @Override
    public void onTrigger(ProcessContext processContext, ProcessSession processSession) throws ProcessException {
        List<FlowFile> flowFiles = processSession.get(processContext.getProperty(FLOWFILE_PULL_SIZE).asInteger());
        if (!flowFiles.isEmpty()) {
            processFlowFiles(processContext, processSession, flowFiles);
        }
    }

    /**
     * Process the flow files pulled using pull size
     * @param processContext NiFi process context
     * @param processSession Nifi process session
     * @param flowFiles List of FlowFile from the process session up to pull size limit
     * @throws ProcessException Processing Exception
     */
    abstract void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException;

    TDF getTDF() {
        return new TDF();
    }

    NanoTDF getNanoTDF(){
        return new NanoTDF();
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return Collections.unmodifiableList(Arrays.asList(SSL_CONTEXT_SERVICE, OPENTDF_CONFIG_SERVICE, FLOWFILE_PULL_SIZE));
    }

}
