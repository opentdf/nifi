package io.opentdf.nifi;

import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.Config.TDFConfig;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.TDF;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@CapabilityDescription("Transforms flow file content into a TDF")
@Tags({"TDF", "OpenTDF", "Encrypt", "Data Centric Security"})
@ReadsAttributes(value = {
        @ReadsAttribute(attribute = "kas_url", description = "The Key Access Server (KAS) URL used TDF Creation. This overrides " +
                "the KAS URL property of this processor."),
        @ReadsAttribute(attribute = "tdf_attribute", description = "A comma separated list of data attributes added " +
                "to created TDF Data Policy. e.g. http://example.org/attr/foo/value/bar,http://example.org/attr/foo/value/bar2")
})
public class ConvertToTDF extends AbstractTDFProcessor {
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

    @Override
    public void onTrigger(ProcessContext processContext, ProcessSession processSession) throws ProcessException {
        List<FlowFile> flowFiles = processSession.get(processContext.getProperty(FLOWFILE_PULL_SIZE).asInteger());
        if (!flowFiles.isEmpty()) {
            SDK sdk = getTDFSDK(processContext);
            for (final FlowFile flowFile : flowFiles) {
                try {
                    //check kas url
                    String kasUrlAttribute = flowFile.getAttribute(KAS_URL_ATTRIBUTE);
                    if (!processContext.getProperty(KAS_URL).isSet() && kasUrlAttribute == null) {
                        throw new Exception("no " + KAS_URL_ATTRIBUTE + " flowfile attribute and no default KAS URL configured");
                    } else {
                        String kasUrl = kasUrlAttribute != null ? kasUrlAttribute : getPropertyValue(processContext.getProperty(KAS_URL)).getValue();
                        var kasInfo = new Config.KASInfo();
                        kasInfo.URL = kasUrl;
                        Set<String> dataAttributes = Arrays.stream((flowFile.getAttribute(TDF_ATTRIBUTE) == null ? "" :
                                flowFile.getAttribute(TDF_ATTRIBUTE)).split(",")).filter(x -> !x.isEmpty()).collect(Collectors.toSet());
                        if (dataAttributes.isEmpty()) {
                            throw new Exception("no data attributes provided via " + TDF_ATTRIBUTE + " flowfile attribute");
                        } else {
                            TDFConfig config = Config.newTDFConfig(Config.withKasInformation(kasInfo),
                                    Config.withDataAttributes(dataAttributes.toArray(new String[0])));
                            //write tdf to flowfile
                            final long size = flowFile.getSize();
                            FlowFile updatedFlowFile = processSession.write(flowFile, new StreamCallback() {
                                        @Override
                                        public void process(InputStream inputStream, OutputStream outputStream) throws IOException {
                                            try {
                                                getTDF().createTDF(inputStream, outputStream, config, sdk.getServices().kas());
                                            } catch (Exception e) {
                                                getLogger().error("error creating tdf", e);
                                                throw new IOException(e);
                                            }
                                        }
                                    }
                            );
                            processSession.transfer(updatedFlowFile, REL_SUCCESS);
                        }
                    }
                } catch (Exception e) {
                    getLogger().error(flowFile.getId() + ": error converting plain text to TDF", e);
                    processSession.transfer(flowFile, REL_FAILURE);
                }
            }
        }
    }

    TDF getTDF() {
        return new TDF();
    }
}
