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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * Common helper processor
 */
public abstract class AbstractTDFProcessor extends AbstractProcessor {

    /**
     * Configuration property representing the limit on the number of FlowFiles
     * that can be pulled from the FlowFile queue at a time.
     * It supports expression language through the variable registry and has a default value of 10.
     */
    public static final PropertyDescriptor FLOWFILE_PULL_SIZE = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("FlowFile queue pull limit")
            .description("FlowFile queue pull size limit")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .defaultValue("10")
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .build();

    /**
     * Property descriptor representing an optional SSL Context Service.
     * This descriptor defines a property that can be used to configure
     * an SSLContextService, which is optional for the processor. This
     * service provides the SSL/TLS context needed for secure communication.
     */
    public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .description("Optional SSL Context Service")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .build();

    /**
     * Represents a property descriptor for the OpenTDF Config Service.
     * <p>
     * This descriptor specifies that the property is required and identifies
     * a controller service of type {@link OpenTDFControllerService}. The controller service
     * provides the necessary configuration for the OpenTDF platform.
     */
    public static final PropertyDescriptor OPENTDF_CONFIG_SERVICE = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("OpenTDF Config Service")
            .description("Controller Service providing OpenTDF Platform Configuration")
            .required(true)
            .identifiesControllerService(OpenTDFControllerService.class)
            .build();

    /**
     * Defines a successful relationship for the NiFi processor. This relationship is used to route flow files
     * that have been successfully processed. Flow files sent to this relationship indicate that the processor
     * completed its intended action without errors.
     * <p>
     * This relationship is commonly used as an output route for data that has passed all validation, transformation,
     * and processing steps.
     */
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("")
            .build();

    /**
     * Relationship representing a failure in processing flow files.
     * <p>
     * This relationship should be used to route flow files that could not
     * be processed successfully by the processor. The reasons for failure
     * can vary widely and may include issues like invalid data, processing
     * errors, or configuration issues.
     */
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("")
            .build();

    /**
     * Evaluates the provided PropertyValue if expression language is present,
     * otherwise returns the original PropertyValue.
     *
     * @param propertyValue The PropertyValue to evaluate or return.
     * @return The evaluated PropertyValue if expression language is present,
     *         otherwise the original PropertyValue.
     */
    PropertyValue getPropertyValue(PropertyValue propertyValue) {
        return propertyValue.isExpressionLanguagePresent() ? propertyValue.evaluateAttributeExpressions() : propertyValue;
    }

    /**
     * Retrieves the value of the specified property from the given process context.
     *
     * @param processContext The context from which to retrieve the property value.
     * @return An Optional containing the PropertyValue if it is set, or an empty Optional otherwise.
     */
    Optional<PropertyValue> getPropertyValue(ProcessContext processContext) {
        PropertyValue propertyValue = null;
        if(processContext.getProperty(ConvertToZTDF.SIGN_ASSERTIONS).isSet()){
            propertyValue = getPropertyValue(processContext.getProperty(ConvertToZTDF.SIGN_ASSERTIONS));
        }
        return Optional.ofNullable(propertyValue);
    }

    private SDK sdk;

    /**
     * Retrieves an instance of the TDF SDK, initializing it if it is not already created.
     *
     * @param processContext the NiFi ProcessContext providing necessary configuration and controller services.
     * @return an instance of the initialized SDK.
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

    /**
     * Creates and returns a new instance of TDF.
     *
     * @return A new instance of TDF.
     */
    TDF getTDF() {
        return new TDF();
    }

    /**
     * Creates and returns a new instance of NanoTDF.
     *
     * @return A new instance of NanoTDF.
     */
    NanoTDF getNanoTDF(){
        return new NanoTDF();
    }

    /**
     * Retrieves the list of property descriptors that are supported by this processor.
     *
     * @return A list containing the supported property descriptors.
     */
    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return List.of(SSL_CONTEXT_SERVICE, OPENTDF_CONFIG_SERVICE, FLOWFILE_PULL_SIZE);
    }
}
