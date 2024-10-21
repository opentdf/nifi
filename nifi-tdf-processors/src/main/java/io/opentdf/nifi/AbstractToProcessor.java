package io.opentdf.nifi;

import io.opentdf.platform.sdk.Config;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Common utilities for a processor converting content to one of the TDF formats
 */
public abstract class AbstractToProcessor extends AbstractTDFProcessor{
    static final String KAS_URL_ATTRIBUTE = "kas_url";
    static final String TDF_ATTRIBUTE = "tdf_attribute";
    static final String TDF_ASSERTION_PREFIX = "tdf_assertion_";

    public static final PropertyDescriptor KAS_URL = new org.apache.nifi.components.PropertyDescriptor.Builder()
            .name("KAS URL")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .description("The KAS Url to use for encryption; this is a default if the kas_url attribute is not present in the flow file")
            .required(false)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .build();

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return Collections.unmodifiableList(Arrays.asList(SSL_CONTEXT_SERVICE, OPENTDF_CONFIG_SERVICE, FLOWFILE_PULL_SIZE, KAS_URL));
    }

    /**
     * Retrieves a list of KAS (Key Access Service) URLs either from the flow file attributes or from the process context.
     * If the KAS URL is not provided through the flow file attribute and is not set in the process context, an exception is thrown.
     *
     * @param flowFile       the flow file from which KAS URL attributes are retrieved.
     * @param processContext the process context to get the default KAS URL if not available in the flow file.
     * @return a list of KAS URLs.
     * @throws Exception if no KAS URL is provided via the flow file or the default in the process context, or if the KAS URLs provided are empty.
     */
    List<String> getKasUrl(FlowFile flowFile, ProcessContext processContext) throws Exception {
        String kasUrlAttribute = flowFile.getAttribute(KAS_URL_ATTRIBUTE);
        // Check kas url
        if (!processContext.getProperty(KAS_URL).isSet() && kasUrlAttribute == null) {
            throw new Exception("no " + KAS_URL_ATTRIBUTE + " flowfile attribute and no default KAS URL configured");
        }
        String kasUrlValues = kasUrlAttribute != null ? kasUrlAttribute : getPropertyValue(processContext.getProperty(KAS_URL)).getValue();
        List<String> kasUrls = Arrays.stream(kasUrlValues.split(","))
                .filter(x -> !x.isEmpty())
                .toList(); // Use Stream.toList() for an unmodifiable list
        if (kasUrlValues.isEmpty()) {
            throw new Exception("no KAS Urls provided");
        }
        return kasUrls;
    }

    /**
     * Converts a list of KAS (Key Access Service) URLs into a list of Config.KASInfo objects.
     *
     * @param kasUrls a list of strings representing the KAS URLs
     * @return a list of Config.KASInfo objects with each object's URL field set to the corresponding string from the input list
     */
    List<Config.KASInfo> getKASInfoFromKASURLs(List<String> kasUrls){
        return kasUrls.stream().map(x -> {
            var ki = new Config.KASInfo();
            ki.URL = x;
            return ki;
        }).toList();
    }

    /**
     * Extracts and returns a set of data attributes from the given FlowFile's attribute specified by TDF_ATTRIBUTE.
     * The attributes are split by commas and filtered to remove empty strings.
     *
     * @param flowFile the FlowFile from which to retrieve the data attributes.
     * @return a set of data attributes extracted from the given FlowFile.
     * @throws Exception if no data attributes are provided via the TDF_ATTRIBUTE FlowFile attribute.
     */
    Set<String> getDataAttributes(FlowFile flowFile) throws Exception{
        Set<String> dataAttributes = Arrays.stream((flowFile.getAttribute(TDF_ATTRIBUTE) == null ? "" :
                flowFile.getAttribute(TDF_ATTRIBUTE)).split(",")).filter(x -> !x.isEmpty()).collect(Collectors.toSet());
        if (dataAttributes.isEmpty()) {
            throw new Exception("no data attributes provided via " + TDF_ATTRIBUTE + " flowfile attribute");
        }
        return dataAttributes;
    }
}
