package io.opentdf.nifi;

import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.TDF;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

import static io.opentdf.nifi.AbstractTDFProcessor.OPENTDF_CONFIG_SERVICE;
import static io.opentdf.nifi.SimpleOpenTDFControllerService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConvertToZTDFTest {

    SDK mockSDK;
    TDF mockTDF;

    @BeforeEach
    void setup() {
        mockSDK = mock(SDK.class);
        mockTDF = mock(TDF.class);
    }

    @Test
    public void testToTDF() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(MockRunner.class);
        ((MockRunner) runner.getProcessor()).mockSDK = mockSDK;
        ((MockRunner) runner.getProcessor()).mockTDF = mockTDF;
        runner.setProperty(ConvertToZTDF.KAS_URL, "https://kas1");
        Utils.setupTDFControllerService(runner);
        runner.assertValid();

        SDK.Services mockServices = mock(SDK.Services.class);
        SDK.KAS mockKAS = mock(SDK.KAS.class);
        when(mockSDK.getServices()).thenReturn(mockServices);
        when(mockServices.kas()).thenReturn(mockKAS);

        ArgumentCaptor<InputStream> inputStreamArgumentCaptor = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<OutputStream> outputStreamArgumentCaptor = ArgumentCaptor.forClass(OutputStream.class);
        ArgumentCaptor<SDK.KAS> kasArgumentCaptor = ArgumentCaptor.forClass(SDK.KAS.class);
        ArgumentCaptor<Config.TDFConfig> configArgumentCaptor = ArgumentCaptor.forClass(Config.TDFConfig.class);

        doAnswer(invocationOnMock -> {
            InputStream inputStream = invocationOnMock.getArgument(0);
            OutputStream outputStream = invocationOnMock.getArgument(1);
            Config.TDFConfig config = invocationOnMock.getArgument(2);
            SDK.KAS kas = invocationOnMock.getArgument(3);
            byte[] b = IOUtils.toByteArray(inputStream);
            outputStream.write(("TDF:" + new String(b)).getBytes());
            assertNotNull(kas, "KAS is not null");
            assertSame(mockKAS, kas, "Expected KAS passed in");
            if (new String(b).equals("message two")) {
                assertEquals(2, config.attributes.size());
                assertTrue(config.attributes.containsAll(Arrays.asList("https://example.org/attr/one/value/a", "https://example.org/attr/one/value/b")));
            } else {
                assertEquals(1, config.attributes.size());
                assertTrue(config.attributes.contains("https://example.org/attr/one/value/c"));
            }
            return null;
        }).when(mockTDF).createTDF(inputStreamArgumentCaptor.capture(),
                outputStreamArgumentCaptor.capture(),
                configArgumentCaptor.capture(),
                kasArgumentCaptor.capture());

        //message one has no attribute
        MockFlowFile messageOne = runner.enqueue("message one".getBytes());
        //message two has attributes
        MockFlowFile messageTwo = runner.enqueue("message two".getBytes(), Map.of(ConvertToZTDF.TDF_ATTRIBUTE,
                "https://example.org/attr/one/value/a,https://example.org/attr/one/value/b"));
        //message three has attributes and kas url override
        MockFlowFile messageThree = runner.enqueue("message three".getBytes(), Map.of(ConvertToZTDF.TDF_ATTRIBUTE,
                "https://example.org/attr/one/value/c", ConvertToZTDF.KAS_URL_ATTRIBUTE, "https://kas2"));
        runner.run(1);
        List<MockFlowFile> flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromZTDF.REL_SUCCESS);
        assertEquals(Set.of("application/ztdf+zip"), flowFileList.stream().map(x->x.getAttribute("mime.type")).collect(Collectors.toSet()));
        assertEquals(2, flowFileList.size(), "Two flowfiles for success relationship");
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageTwo.getAttribute("filename")))
                .filter(x -> x.getContent().equals("TDF:message two")).count());
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageThree.getAttribute("filename")))
                .filter(x -> x.getContent().equals("TDF:message three")).count());


        flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromZTDF.REL_FAILURE);
        assertEquals(1, flowFileList.size(), "One flowfile for failure relationship");
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageOne.getAttribute("filename")))
                .filter(x -> x.getContent().equals("message one")).count());
    }

    public static class MockRunner extends ConvertToZTDF {
        SDK mockSDK;
        TDF mockTDF;

        @Override
        SDK getTDFSDK(ProcessContext processContext) {
            return mockSDK;
        }

        @Override
        TDF getTDF() {
            return mockTDF;
        }
    }


}