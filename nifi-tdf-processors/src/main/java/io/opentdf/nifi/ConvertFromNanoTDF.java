package io.opentdf.nifi;

import io.opentdf.platform.sdk.SDK;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * A processor for decrypting NanoTDF flow file content using the OpenTDF framework.
 * <p>
 * This processor reads encrypted NanoTDF data from incoming flow files and decrypts
 * it using the associated SDK. The decrypted content is then written back into the
 * flow file and routed to the success relationship. If decryption fails, the flow file
 * is routed to the failure relationship.
 */
@CapabilityDescription("Decrypts NanoTDF flow file content")
@Tags({"NanoTDF", "OpenTDF", "Decrypt", "Data Centric Security"})
public class ConvertFromNanoTDF extends AbstractTDFProcessor {

    /**
     * Default constructor for ConvertFromNanoTDF.
     */
    public ConvertFromNanoTDF() {
        super();
    }

    /**
     * Processes the provided list of flow files by decrypting their content using the NanoTDF protocol.
     * If decryption succeeds, the flow file is routed to the success relationship; otherwise, it is routed to the failure relationship.
     *
     * @param processContext the NiFi ProcessContext which provides configuration and controller services
     * @param processSession the ProcessSession which provides mechanisms for reading, writing, transferring, and penalizing flow files
     * @param flowFiles the list of FlowFile objects to be processed
     * @throws ProcessException if any error occurs during the processing of flow files
     */
    @Override
    public void processFlowFiles(ProcessContext processContext, ProcessSession processSession, List<FlowFile> flowFiles) throws ProcessException {
        SDK sdk = getTDFSDK(processContext);
        for (FlowFile flowFile : flowFiles) {
            try {
                byte[] nanoTDFBytes = readEntireFlowFile(flowFile, processSession);
                FlowFile updatedFlowFile = processSession.write(flowFile, outputStream -> {
                    try {
                        getNanoTDF().readNanoTDF(ByteBuffer.wrap(nanoTDFBytes), outputStream, sdk.getServices().kas());
                    } catch (Exception e) {
                        getLogger().error("error decrypting NanoTDF", e);
                        throw new IOException(e);
                    }
                });
                processSession.transfer(updatedFlowFile, REL_SUCCESS);
            } catch (Exception e) {
                getLogger().error(flowFile.getId() + ": error decrypting flowfile", e);
                processSession.transfer(flowFile, REL_FAILURE);
            }
        }
    }
}