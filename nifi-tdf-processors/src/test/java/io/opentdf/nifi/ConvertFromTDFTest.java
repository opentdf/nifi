package io.opentdf.nifi;

import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.SDKBuilder;
import io.opentdf.platform.sdk.TDF;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.opentdf.nifi.AbstractTDFProcessor.OPENTDF_CONFIG_SERVICE;
import static io.opentdf.nifi.SimpleOpenTDFControllerService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;


class ConvertFromTDFTest {

    SDK mockSDK;
    TDF mockTDF;

    @BeforeEach
    void setup() {
        mockSDK = mock(SDK.class);
        mockTDF = mock(TDF.class);
    }

    void setupTDFControllerService(TestRunner runner) throws Exception {
        SimpleOpenTDFControllerService tdfControllerService = new SimpleOpenTDFControllerService();
        Map<String, String> controllerPropertyMap = new HashMap<>();
        controllerPropertyMap.put(PLATFORM_ENDPOINT.getName(), "http://platform");
        controllerPropertyMap.put(CLIENT_ID.getName(), "my-client");
        controllerPropertyMap.put(CLIENT_SECRET.getName(), "123-456");
        controllerPropertyMap.put(USE_PLAINTEXT.getName(), "false");
        runner.addControllerService(OPENTDF_CONFIG_SERVICE.getName(), tdfControllerService, controllerPropertyMap);
        runner.enableControllerService(tdfControllerService);
        runner.assertValid(tdfControllerService);
        runner.setProperty(OPENTDF_CONFIG_SERVICE.getName(), OPENTDF_CONFIG_SERVICE.getName());
    }

    @Test
    public void testConvertFromTDF() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(MockRunner.class);
        SDKBuilder mockSDKBuilder = mock(SDKBuilder.class);
        ((MockRunner) runner.getProcessor()).mockTDF = mockTDF;
        ((MockRunner) runner.getProcessor()).mockSDKBuilder = mockSDKBuilder;
        setupTDFControllerService(runner);
        runner.assertValid();

        SDK.Services mockServices = mock(SDK.Services.class);
        SDK.KAS mockKAS = mock(SDK.KAS.class);
        when(mockSDK.getServices()).thenReturn(mockServices);
        when(mockServices.kas()).thenReturn(mockKAS);
        when(mockSDKBuilder.platformEndpoint("http://platform")).thenReturn(mockSDKBuilder);
        when(mockSDKBuilder.clientSecret("my-client", "123-456")).thenReturn(mockSDKBuilder);
        when(mockSDKBuilder.build()).thenReturn(mockSDK);

        ArgumentCaptor<SeekableByteChannel> seekableByteChannelArgumentCaptor = ArgumentCaptor.forClass(SeekableByteChannel.class);
        ArgumentCaptor<OutputStream> outputStreamArgumentCaptor = ArgumentCaptor.forClass(OutputStream.class);
        ArgumentCaptor<SDK.KAS> kasArgumentCaptor = ArgumentCaptor.forClass(SDK.KAS.class);

        doAnswer(invocationOnMock -> {
            SeekableInMemoryByteChannel seekableByteChannel = invocationOnMock.getArgument(0);
            OutputStream outputStream = invocationOnMock.getArgument(1);
            SDK.KAS kas = invocationOnMock.getArgument(2);
            outputStream.write(("Decrypted:" + new String(seekableByteChannel.array())).getBytes());
            assertNotNull(kas, "KAS is not null");
            assertSame(mockKAS, kas, "Expected KAS passed in");
            return null;
        }).when(mockTDF).loadTDF(seekableByteChannelArgumentCaptor.capture(),
                outputStreamArgumentCaptor.capture(),
                kasArgumentCaptor.capture());
        MockFlowFile messageOne = runner.enqueue("message one".getBytes());
        MockFlowFile messageTwo = runner.enqueue("message two".getBytes());
        runner.run(1);
        List<MockFlowFile> flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromTDF.REL_SUCCESS);
        assertEquals(2, flowFileList.size(), "Two successful flow files");
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageOne.getAttribute("filename")))
                .filter(x -> x.getContent().equals("Decrypted:message one")).count());
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageTwo.getAttribute("filename")))
                .filter(x -> x.getContent().equals("Decrypted:message two")).count());

    }

    public static class MockRunner extends ConvertFromTDF {
        TDF mockTDF;
        SDKBuilder mockSDKBuilder;
        @Override
        SDKBuilder createSDKBuilder(){
            return mockSDKBuilder;
        }

       @Override
        TDF getTDF() {
            return mockTDF;
        }
    }
}