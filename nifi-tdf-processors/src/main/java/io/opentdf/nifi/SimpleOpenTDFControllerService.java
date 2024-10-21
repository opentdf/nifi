package io.opentdf.nifi;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.InitializationException;

import java.util.Arrays;
import java.util.List;

/**
 * Provides an implementation of the OpenTDFControllerService API for OpenTDF SDK Configuration Parameters.
 */
@Tags({"TDF", "ZTDF", "OpenTDF", "Configuration"})
@CapabilityDescription("Provides An implementation of the OpenTDFControllerService API for OpenTDF SDK Configuration Parameters")
public class SimpleOpenTDFControllerService extends AbstractControllerService implements OpenTDFControllerService {

    /**
     * The endpoint of the OpenTDF platform in GRPC compatible format, excluding the protocol prefix.
     * This is a required property and supports expression language within the scope of the variable registry.
     */
    public static final PropertyDescriptor PLATFORM_ENDPOINT = new PropertyDescriptor.Builder()
            .name("platform-endpoint")
            .displayName("OpenTDF Platform ENDPOINT")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(false)
            .description("OpenTDF Platform ENDPOINT in GRPC compatible format (no protocol prefix)")
            .build();

    /**
     * The client secret used for authentication with the OpenTDF Platform.
     * <p>
     * This property is required and must be configured with a valid client secret.
     * It supports expression language within the scope of the variable registry, ensuring that the client secret
     * can be dynamically set based on external configurations.
     * The value of this property is sensitive and will be handled securely.
     */
    public static final PropertyDescriptor CLIENT_SECRET = new PropertyDescriptor.Builder()
            .name("clientSecret")
            .displayName("Client Secret")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .description("OpenTDF Platform Authentication Client Secret")
            .build();

    /**
     * Property for specifying the Client ID used for authentication against the OpenTDF Platform.
     * This is a required field, and it must be non-empty. Expression language is supported for
     * variable registry scope.
     */
    public static final PropertyDescriptor CLIENT_ID = new PropertyDescriptor.Builder()
            .name("clientId")
            .displayName("Client ID")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(false)
            .description("OpenTDF Platform Authentication Client ID")
            .build();


    /**
     * Configuration property that determines whether the connection to the OpenTDF Platform should use plaintext.
     * This is a required property and can have a default value of "false".
     * The acceptable values for this property are "true" and "false". It is not marked as sensitive.
     * A non-empty validator is applied to ensure the property is not left blank.
     */
    public static final PropertyDescriptor USE_PLAINTEXT = new PropertyDescriptor.Builder()
            .name("usePlaintext")
            .displayName("Platform Use Plaintext Connection")
            .required(true)
            .defaultValue("false")
            .allowableValues("true", "false")
            .sensitive(false)
            .description("OpenTDF Platform Authentication Client ID")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    Config config = null;

    /**
     * Returns a list of property descriptors that are supported by this controller service.
     *
     * @return a list of PropertyDescriptor objects
     */
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return Arrays.asList(PLATFORM_ENDPOINT, CLIENT_ID, CLIENT_SECRET, USE_PLAINTEXT);
    }

    /**
     * Initializes the controller service with the provided configuration context.
     *
     * @param configurationContext the context containing configuration properties to be applied during service enablement
     * @throws InitializationException if any required configuration property is missing or invalid
     */
    @OnEnabled
    public void enabled(final ConfigurationContext configurationContext) throws InitializationException {
        config = new Config();

        PropertyValue clientIdValue = getPropertyValue(configurationContext.getProperty(CLIENT_ID));
        PropertyValue clientSecretValue = getPropertyValue(configurationContext.getProperty(CLIENT_SECRET));
        PropertyValue platformEndpointValue = getPropertyValue(configurationContext.getProperty(PLATFORM_ENDPOINT));
        PropertyValue usePlainTextValue = getPropertyValue(configurationContext.getProperty(USE_PLAINTEXT));

        // Ensure that no required property is null
        if (clientIdValue.getValue() == null ||
                clientSecretValue.getValue() == null ||
                platformEndpointValue.getValue() == null ||
                usePlainTextValue == null) {
            throw new InitializationException("One or more required properties are not configured properly.");
        }

        config.setClientId(clientIdValue.getValue());
        config.setClientSecret(clientSecretValue.getValue());
        config.setPlatformEndpoint(platformEndpointValue.getValue());

        Boolean usePlainText = usePlainTextValue.asBoolean();
        if (usePlainText == null) {
            throw new InitializationException("The 'usePlaintext' property must be either 'true' or 'false'.");
        }
        config.setUsePlainText(usePlainText);
    }

    /**
     * Evaluates the provided PropertyValue and returns the result of the evaluation if expression language is present.
     * Otherwise, it returns the original PropertyValue.
     *
     * @param propertyValue the PropertyValue object to be evaluated
     * @return the evaluated PropertyValue if expression language is present, otherwise the original PropertyValue
     */
    PropertyValue getPropertyValue(PropertyValue propertyValue) {
        return propertyValue.isExpressionLanguagePresent() ? propertyValue.evaluateAttributeExpressions() : propertyValue;
    }

    /**
     * Retrieves the current configuration settings for the OpenTDF controller service.
     *
     * @return the current Config object containing configuration properties
     * @throws ProcessException if an error occurs while retrieving the configuration
     */
    @Override
    public Config getConfig() throws ProcessException {
        return config;
    }
}
