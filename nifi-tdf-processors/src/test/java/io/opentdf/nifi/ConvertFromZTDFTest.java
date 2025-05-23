package io.opentdf.nifi;

import com.nimbusds.jose.JOSEException;
import io.opentdf.platform.policy.kasregistry.KeyAccessServerRegistryServiceGrpc;
import io.opentdf.platform.sdk.*;
import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.TDF.Reader;
import nl.altindag.ssl.util.KeyStoreUtils;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


class ConvertFromZTDFTest {

    SDK mockSDK;
    TDF mockTDF;

    @BeforeEach
    void setup() {
        mockSDK = mock(SDK.class);
        mockTDF = mock(TDF.class);
    }

    @Test
    void testConvertFromTDF() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(MockRunner.class);
        SDKBuilder mockSDKBuilder = mock(SDKBuilder.class);
        ((MockRunner) runner.getProcessor()).mockTDF = mockTDF;
        ((MockRunner) runner.getProcessor()).mockSDKBuilder = mockSDKBuilder;
        Utils.setupTDFControllerService(runner);

        //add ssl context
        SSLContextService sslContextService = mock(SSLContextService.class);
        File trustStoreFile = Files.createTempFile("trust", "jks").toFile();
        final String TRUST_STORE_PATH = trustStoreFile.getAbsolutePath();
        final String TRUST_STORE_PASSWORD = "foo";
        when(sslContextService.validate(any())).thenReturn(Collections.emptyList());
        when(sslContextService.getTrustStoreFile()).thenReturn(TRUST_STORE_PATH);
        when(sslContextService.getTrustStorePassword()).thenReturn(TRUST_STORE_PASSWORD);

        try (FileOutputStream fos = new FileOutputStream(trustStoreFile)) {
            KeyStoreUtils.createKeyStore().store(fos, TRUST_STORE_PASSWORD.toCharArray());
        }
        when(sslContextService.getIdentifier()).thenReturn(AbstractTDFProcessor.SSL_CONTEXT_SERVICE.getName());
        runner.addControllerService(AbstractTDFProcessor.SSL_CONTEXT_SERVICE.getName(), sslContextService, new HashMap<>());
        runner.enableControllerService(sslContextService);
        runner.setProperty(AbstractTDFProcessor.SSL_CONTEXT_SERVICE, AbstractTDFProcessor.SSL_CONTEXT_SERVICE.getName());


        runner.assertValid();
        String platformEndpoint = "http://platform";
        SDK.Services mockServices = mock(SDK.Services.class);
        SDK.KAS mockKAS = mock(SDK.KAS.class);
        when(mockSDK.getServices()).thenReturn(mockServices);
        when(mockServices.kas()).thenReturn(mockKAS);
        when(mockSDK.getPlatformUrl()).thenReturn(platformEndpoint);
        when(mockSDKBuilder.platformEndpoint(platformEndpoint)).thenReturn(mockSDKBuilder);
        when(mockSDKBuilder.clientSecret("my-client", "123-456")).thenReturn(mockSDKBuilder);
        when(mockSDKBuilder.sslFactoryFromKeyStore(TRUST_STORE_PATH, TRUST_STORE_PASSWORD)).thenReturn(mockSDKBuilder);
        when(mockSDKBuilder.build()).thenReturn(mockSDK);

        ArgumentCaptor<SeekableByteChannel> seekableByteChannelArgumentCaptor = ArgumentCaptor.forClass(SeekableByteChannel.class);
        ArgumentCaptor<SDK.KAS> kasArgumentCaptor = ArgumentCaptor.forClass(SDK.KAS.class);
        ArgumentCaptor<Config.TDFReaderConfig> tdfReaderConfigArgumentCaptor = ArgumentCaptor.forClass(Config.TDFReaderConfig.class);
        ArgumentCaptor<KeyAccessServerRegistryServiceGrpc.KeyAccessServerRegistryServiceFutureStub> keyAccessServerRegistryServiceGrpcArgumentCaptor =
                ArgumentCaptor.forClass(KeyAccessServerRegistryServiceGrpc.KeyAccessServerRegistryServiceFutureStub.class);
        ArgumentCaptor<String> platformUrlCaptor = ArgumentCaptor.forClass(String.class);

        Reader mockReader = mock(Reader.class);

        ArgumentCaptor<OutputStream> outputStreamArgumentCaptor = ArgumentCaptor.forClass(OutputStream.class);
        List<String> messages = new ArrayList<>();

        final AtomicInteger ai = new AtomicInteger(0);
        doAnswer(invocationOnMock -> {
            OutputStream outputStream = invocationOnMock.getArgument(0);
            outputStream.write(("Decrypted: Decrypted message " + ai.incrementAndGet()).getBytes());
            return  null;
        }).when(mockReader).readPayload(outputStreamArgumentCaptor.capture());
        doAnswer(invocationOnMock -> {
            SeekableInMemoryByteChannel seekableByteChannel = invocationOnMock.getArgument(0);
            ByteBuffer bb = ByteBuffer.allocate((int)seekableByteChannel.size());
            seekableByteChannel.read(bb);
            messages.add(new String(bb.array()));
            SDK.KAS kas = invocationOnMock.getArgument(1);
            assertNotNull(kas, "KAS is not null");
            assertSame(mockKAS, kas, "Expected KAS passed in");
            return mockReader;
        }).when(mockTDF).loadTDF(seekableByteChannelArgumentCaptor.capture(),
                kasArgumentCaptor.capture(),
                tdfReaderConfigArgumentCaptor.capture(),
                keyAccessServerRegistryServiceGrpcArgumentCaptor.capture(),
                platformUrlCaptor.capture()
                );
        MockFlowFile messageOne = runner.enqueue("message one".getBytes());
        MockFlowFile messageTwo = runner.enqueue("message two".getBytes());
        runner.run(1);
        List<MockFlowFile> flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromZTDF.REL_SUCCESS);
        assertEquals(2, flowFileList.size(), "Two successful flow files");
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageOne.getAttribute("filename")))
                .filter(x -> x.getContent().equals("Decrypted: Decrypted message 1")).count());
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageTwo.getAttribute("filename")))
                .filter(x -> x.getContent().equals("Decrypted: Decrypted message 2")).count());
        assertEquals(2, seekableByteChannelArgumentCaptor.getAllValues().size());

        assertTrue(messages.contains("message one"));
        assertTrue(messages.contains("message two"));

        assertEquals(platformEndpoint, platformUrlCaptor.getValue());
        // disableAssertionVerification is a private field
        Field field = Config.TDFReaderConfig.class.getDeclaredField("disableAssertionVerification");
        field.setAccessible(true);
        boolean disableAssertionVerification = (boolean) field.get(tdfReaderConfigArgumentCaptor.getValue());
        assertTrue(disableAssertionVerification);
    }

    public static class MockRunner extends ConvertFromZTDF {
        TDF mockTDF;
        SDKBuilder mockSDKBuilder;

        @Override
        SDKBuilder createSDKBuilder() {
            return mockSDKBuilder;
        }

        @Override
        TDF getTDF() {
            return mockTDF;
        }
    }
}