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

@Tags({"TDF", "ZTDF", "OpenTDF", "Configuration"})
@CapabilityDescription("Provides An implementation of the OpenTDFControllerService API for OpenTDF SDK Configuration Parameters")
public class SimpleOpenTDFControllerService extends AbstractControllerService implements OpenTDFControllerService {

    public static final PropertyDescriptor PLATFORM_ENDPOINT = new PropertyDescriptor.Builder()
            .name("platform-endpoint")
            .displayName("OpenTDF Platform ENDPOINT")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(false)
            .description("OpenTDF Platform ENDPOINT")
            .build();

    public static final PropertyDescriptor CLIENT_SECRET = new PropertyDescriptor.Builder()
            .name("clientSecret")
            .displayName("Client Secret")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .description("OpenTDF Platform Authentication Client Secret")
            .build();

    public static final PropertyDescriptor CLIENT_ID = new PropertyDescriptor.Builder()
            .name("clientId")
            .displayName("Client ID")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(false)
            .description("OpenTDF Platform Authentication Client ID")
            .build();


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

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return Arrays.asList(PLATFORM_ENDPOINT, CLIENT_ID, CLIENT_SECRET, USE_PLAINTEXT);
    }

    @OnEnabled
    public void enabled(final ConfigurationContext configurationContext) throws InitializationException {
        config = new Config();
        config.setClientId(getPropertyValue(configurationContext.getProperty(CLIENT_ID)).getValue());
        config.setClientSecret(getPropertyValue(configurationContext.getProperty(CLIENT_SECRET)).getValue());
        config.setPlatformEndpoint(getPropertyValue(configurationContext.getProperty(PLATFORM_ENDPOINT)).getValue());
        config.setUsePlainText(getPropertyValue(configurationContext.getProperty(USE_PLAINTEXT)).asBoolean());
    }

    PropertyValue getPropertyValue(PropertyValue propertyValue) {
        return propertyValue.isExpressionLanguagePresent() ? propertyValue.evaluateAttributeExpressions() : propertyValue;
    }

    @Override
    public Config getConfig() throws ProcessException {
        return config;
    }
}
