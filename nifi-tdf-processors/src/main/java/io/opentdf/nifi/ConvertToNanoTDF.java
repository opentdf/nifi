package io.opentdf.nifi;

import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.SDK;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.stream.io.StreamUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@CapabilityDescription("Transforms flow file content into a NanoTDF")
@Tags({"NanoTDF", "OpenTDF", "Encrypt", "Data Centric Security"})
@ReadsAttributes(value = {
        @ReadsAttribute(attribute = "kas_url", description = "The Key Access Server (KAS) URL used TDF Creation. This overrides " +
                "the KAS URL property of this processor."),
        @ReadsAttribute(attribute = "tdf_attribute", description = "A comma separated list of data attributes added " +
                "to created TDF Data Policy. e.g. http://example.org/attr/foo/value/bar,http://example.org/attr/foo/value/bar2")
})
public class ConvertToNanoTDF extends AbstractToProcessor {

    public static final Relationship REL_FLOWFILE_EXCEEDS_NANO_SIZE = new Relationship.Builder()
            .name("exceeds_size_limit")
            .description("NanoTDF creation failed due to the content size exceeding the max NanoTDF size threshold")
            .build();

    static final long MAX_SIZE = 16777218;

    @Override
    public Set<Relationship> getRelationships() {
        return new HashSet<>(Arrays.asList(REL_SUCCESS, REL_FAILURE, REL_FLOWFILE_EXCEEDS_NANO_SIZE));
    }


    @Override
    public void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException {
        SDK sdk = getTDFSDK(processContext);
        for (final FlowFile flowFile : flowFiles) {
            try {
                var kasInfoList = getKASInfoFromKASURLs(getKasUrl(flowFile, processContext));
                Set<String> dataAttributes = getDataAttributes(flowFile);
                Config.NanoTDFConfig config = Config.newNanoTDFConfig(
                        Config.withNanoKasInformation(kasInfoList.toArray(new Config.KASInfo[0])),
                        Config.witDataAttributes(dataAttributes.toArray(new String[0]))
                );

                if (flowFile.getSize() >MAX_SIZE){
                    getLogger().error(flowFile.getId() + ": error converting plain text to NanoTDF; content length of " + flowFile.getSize() + " > " + MAX_SIZE);
                    processSession.transfer(flowFile, REL_FLOWFILE_EXCEEDS_NANO_SIZE);
                }else {

                    //write NanoTDF to FlowFile
                    FlowFile updatedFlowFile = processSession.write(flowFile, (inputStream, outputStream) -> {
                                try {
                                    byte[] bytes = new byte[(int) flowFile.getSize()];
                                    StreamUtils.fillBuffer(inputStream, bytes);
                                    ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
                                    getNanoTDF().createNanoTDF(byteBuffer, outputStream, config, sdk.getServices().kas());
                                } catch (Exception e) {
                                    getLogger().error("error creating NanoTDF", e);
                                    throw new IOException(e);
                                }
                            }
                    );
                    processSession.transfer(updatedFlowFile, REL_SUCCESS);
                }
            } catch (Exception e) {
                getLogger().error(flowFile.getId() + ": error converting plain text to NanoTDF", e);
                processSession.transfer(flowFile, REL_FAILURE);
            }
        }
    }

}
