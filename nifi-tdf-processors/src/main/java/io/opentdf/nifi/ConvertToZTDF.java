package io.opentdf.nifi;

import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.Config.TDFConfig;
import io.opentdf.platform.sdk.SDK;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@CapabilityDescription("Transforms flow file content into a ZTDF")
@Tags({"ZTDF", "OpenTDF", "Zero Trust Data Format", "Encrypt", "Data Centric Security"})
@ReadsAttributes(value = {
        @ReadsAttribute(attribute = "kas_url", description = "The Key Access Server (KAS) URL used TDF Creation. This overrides " +
                "the KAS URL property of this processor."),
        @ReadsAttribute(attribute = "tdf_attribute", description = "A comma separated list of data attributes added " +
                "to created TDF Data Policy. e.g. http://example.org/attr/foo/value/bar,http://example.org/attr/foo/value/bar2")
})
public class ConvertToZTDF extends AbstractToProcessor {

    @Override
    void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException {
        SDK sdk = getTDFSDK(processContext);
        for (final FlowFile flowFile : flowFiles) {
            try {
                var kasInfoList = getKASInfoFromKASURLs( getKasUrl(flowFile, processContext));
                Set<String> dataAttributes = getDataAttributes(flowFile);
                TDFConfig config = Config.newTDFConfig(Config.withKasInformation(kasInfoList.toArray(new Config.KASInfo[0])),
                        Config.withDataAttributes(dataAttributes.toArray(new String[0])));
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
}
