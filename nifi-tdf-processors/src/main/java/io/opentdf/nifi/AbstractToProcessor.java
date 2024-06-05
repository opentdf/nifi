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

    /**{
     * Get the kas urls from a flowfile attribute or if none present fallback to processor configuration KAS URL;
     * format is a comma separated list
     * @param flowFile
     * @param processContext
     * @return
     * @throws Exception
     */
    List<String> getKasUrl(FlowFile flowFile, ProcessContext processContext) throws Exception{
        String kasUrlAttribute = flowFile.getAttribute(KAS_URL_ATTRIBUTE);
        //check kas url
        if (!processContext.getProperty(KAS_URL).isSet() && kasUrlAttribute == null) {
            throw new Exception("no " + KAS_URL_ATTRIBUTE + " flowfile attribute and no default KAS URL configured");
        }
        String kasUrlValues = kasUrlAttribute != null ? kasUrlAttribute : getPropertyValue(processContext.getProperty(KAS_URL)).getValue();
        List<String> kasUrls = Arrays.stream(kasUrlValues.split(",")).filter(x->!x.isEmpty()).collect(Collectors.toList());
        if (kasUrlValues.isEmpty()){
            throw new Exception("no KAS Urls provided");
        }
        return kasUrls;
    }

    List<Config.KASInfo> getKASInfoFromKASURLs(List<String> kasUrls){
        return kasUrls.stream().map(x->{ var ki = new Config.KASInfo(); ki.URL=x; return ki;}).collect(Collectors.toList());
    }

    /**
     * Get data attributes on a FlowFile from attribute value
     * @param flowFile
     * @return
     * @throws Exception
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
