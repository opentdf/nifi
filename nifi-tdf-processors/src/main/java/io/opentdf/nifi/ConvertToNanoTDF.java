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

/**
 * Processor for converting the content of a FlowFile into a NanoTDF (Trusted Data Format).
 * <p>
 * This class extends AbstractToProcessor and handles the encryption of FlowFile content into NanoTDF,
 * applying specified KAS (Key Access Service) URLs and data attributes as defined in the flow file attributes
 * or processor properties.
 * <p>
 * Relationships:
 * - REL_SUCCESS: When the conversion to NanoTDF is successful.
 * - REL_FAILURE: When the conversion to NanoTDF fails.
 * - REL_FLOWFILE_EXCEEDS_NANO_SIZE: When the content size exceeds the maximum allowed size for NanoTDF.
 * <p>
 * Property Descriptors:
 * - Inherited from AbstractToProcessor (e.g., KAS URL, SSL_CONTEXT_SERVICE, OPENTDF_CONFIG_SERVICE, etc.)
 * <p>
 * Reads Attributes:
 * - kas_url: The Key Access Server (KAS) URL used for TDF creation. Overrides the default KAS URL property.
 * - tdf_attribute: A comma-separated list of data attributes added to the created TDF Data Policy.
 */
@CapabilityDescription("Transforms flow file content into a NanoTDF")
@Tags({"NanoTDF", "OpenTDF", "Encrypt", "Data Centric Security"})
@ReadsAttributes(value = {
        @ReadsAttribute(attribute = "kas_url", description = "The Key Access Server (KAS) URL used TDF Creation. This overrides " +
                "the KAS URL property of this processor."),
        @ReadsAttribute(attribute = "tdf_attribute", description = "A comma separated list of data attributes added " +
                "to created TDF Data Policy. e.g. http://example.org/attr/foo/value/bar,http://example.org/attr/foo/value/bar2")
})
public class ConvertToNanoTDF extends AbstractToProcessor {

    /**
     * Default constructor for ConvertToNanoTDF.
     */
    public ConvertToNanoTDF() {
        super();
    }

    /**
     * Defines a relationship indicating that NanoTDF creation has failed
     * due to the content size exceeding the maximum allowed NanoTDF size threshold.
     */
    public static final Relationship REL_FLOWFILE_EXCEEDS_NANO_SIZE = new Relationship.Builder()
            .name("exceeds_size_limit")
            .description("NanoTDF creation failed due to the content size exceeding the max NanoTDF size threshold")
            .build();

    /**
     * Represents the maximum allowable size for processing a flow file in nano TDF conversion.
     * Value is set to 16 MB.
     */
    static final long MAX_SIZE = 16777218;

    /**
     * Retrieves all the relationships defined in the ConvertToNanoTDF processor.
     *
     * @return a Set of Relationship objects representing the different relationships for the processor.
     */
    @Override
    public Set<Relationship> getRelationships() {
        return new HashSet<>(Arrays.asList(REL_SUCCESS, REL_FAILURE, REL_FLOWFILE_EXCEEDS_NANO_SIZE));
    }


    /**
     * Processes a list of FlowFiles to convert them to NanoTDF format.
     * If a FlowFile's size exceeds the maximum allowed size, it is routed to a specific relationship.
     * Otherwise, it attempts to convert the FlowFile's content and transfer it to a success relationship.
     * In case of an error during processing, the FlowFile is routed to a failure relationship.
     *
     * @param processContext the NiFi ProcessContext providing necessary configuration and controller services.
     * @param processSession the NiFi ProcessSession representing a transaction context for the processing of FlowFiles.
     * @param flowFiles a list of FlowFiles to be processed.
     * @throws ProcessException if an error occurs during the processing of the FlowFiles.
     */
    @Override
    public void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException {
        SDK sdk = getTDFSDK(processContext);
        for (final FlowFile flowFile : flowFiles) {
            try {
                var kasInfoList = getKASInfoFromKASURLs(getKasUrl(flowFile, processContext));
                Set<String> dataAttributes = getDataAttributes(flowFile);
                // Config.newNanoTDFConfig is correctly handling the varargs
                @SuppressWarnings("unchecked")
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
