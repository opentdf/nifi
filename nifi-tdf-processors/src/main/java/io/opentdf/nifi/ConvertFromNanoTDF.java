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

@CapabilityDescription("Decrypts NanoTDF flow file content")
@Tags({"NanoTDF", "OpenTDF", "Decrypt", "Data Centric Security"})
public class ConvertFromNanoTDF extends AbstractTDFProcessor {

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