package org.sead.uploader.dataverse;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.sead.uploader.util.UploaderException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for DVUploader.
 * These tests require a live Dataverse instance and valid credentials.
 * Configure via test.properties file or environment variables.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DVUploaderTest {

    private String server;
    private String apiKey;
    private String datasetPID;
    private DVUploader uploader;

    @BeforeAll
    public void setup() throws IOException {
        Properties props = new Properties();
        File propFile = new File("test.properties");
        if (propFile.exists()) {
            try (FileInputStream fis = new FileInputStream(propFile)) {
                props.load(fis);
            }
        }

        server = System.getProperty("dataverse.server", props.getProperty("dataverse.server", System.getenv("DATAVERSE_SERVER")));
        apiKey = System.getProperty("dataverse.api_key", props.getProperty("dataverse.api_key", System.getenv("DATAVERSE_API_KEY")));
        datasetPID = System.getProperty("dataverse.dataset_pid", props.getProperty("dataverse.dataset_pid", System.getenv("DATAVERSE_DATASET_PID")));

        // Skip tests if configuration is missing
        Assumptions.assumeTrue(server != null && apiKey != null && datasetPID != null,
                "Test configuration missing. Provide dataverse.server, dataverse.api_key, and dataverse.dataset_pid " +
                "via test.properties, system properties, or environment variables.");

        uploader = new DVUploader();
        DVUploader.setUploader(uploader);
        
        // Initial setup of credentials
        uploader.parseArgs(new String[]{"-server=" + server, "-key=" + apiKey, "-did=" + datasetPID});
    }

    @BeforeEach
    public void resetUploader() {
        uploader.clearRequests();
        // Re-apply common args to ensure they are set
        uploader.parseArgs(new String[]{"-server=" + server, "-key=" + apiKey, "-did=" + datasetPID});
    }

    @AfterAll
    public void cleanup() throws IOException {
        if (uploader == null) return;

        System.out.println("Cleaning up uploaded files...");
        CloseableHttpClient httpClient = uploader.getSharedHttpClient();
        
        // Get all files in the dataset to find their IDs
        String url = server + "/api/datasets/:persistentId/versions/:latest/files?key=" + apiKey + "&persistentId=" + datasetPID;
        HttpGet get = new HttpGet(url);
        
        try (CloseableHttpResponse response = httpClient.execute(get, uploader.getLocalContext())) {
            if (response.getStatusLine().getStatusCode() == 200) {
                String res = EntityUtils.toString(response.getEntity());
                JSONArray data = new JSONObject(res).getJSONArray("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject fileEntry = data.getJSONObject(i);
                    JSONObject dataFile = fileEntry.getJSONObject("dataFile");
                    String filename = dataFile.getString("filename");
                    
                    // Cleanup any files starting with our test prefixes
                    if (filename.startsWith("dvuploader-test") || filename.startsWith("dvuploader-large")) {
                        long id = dataFile.getLong("id");
                        deleteFile(httpClient, id);
                    }
                }
            }
        }
    }

    private void deleteFile(CloseableHttpClient httpClient, long id) throws IOException {
        String url = server + "/api/files/" + id + "?key=" + apiKey;
        HttpDelete delete = new HttpDelete(url);
        try (CloseableHttpResponse response = httpClient.execute(delete, uploader.getLocalContext())) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 204 || statusCode == 200) {
                System.out.println("Deleted file ID: " + id + " (Status: " + statusCode + ")");
            } else {
                System.err.println("Failed to delete file ID: " + id + " Status: " + statusCode);
            }
            EntityUtils.consumeQuietly(response.getEntity());
        }
    }

    @Test
    public void testSimpleUpload() throws IOException, UploaderException {
        Path tempFile = Files.createTempFile("dvuploader-test", ".txt");
        String filename = tempFile.getFileName().toString();
        Files.writeString(tempFile, "Hello Dataverse!");
        
        try {
            uploader.parseArgs(new String[]{tempFile.toAbsolutePath().toString()});
            uploader.processRequests();
            
            assertTrue(isFileInDataset(filename), "File " + filename + " should be in dataset after upload");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testDuplicateUpload() throws IOException, UploaderException {
        Path tempFile = Files.createTempFile("dvuploader-test-dup", ".txt");
        String filename = tempFile.getFileName().toString();
        Files.writeString(tempFile, "Duplicate Content");
        
        try {
            // First upload
            uploader.parseArgs(new String[]{tempFile.toAbsolutePath().toString()});
            uploader.processRequests();
            assertTrue(isFileInDataset(filename), "File " + filename + " should be in dataset after first upload");
            
            // Clear requests and re-add the same file
            uploader.clearRequests();
            uploader.parseArgs(new String[]{tempFile.toAbsolutePath().toString()});
            
            // Second upload - should see it exists and not re-upload (this is handled internally by DVUploader)
            uploader.processRequests(); 
            
            // Verify it's still there and there's only one (by name)
            assertEquals(1, countFileInDataset(filename), "There should be exactly one file named " + filename + " in the dataset");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testLargeFileUpload() throws IOException, UploaderException {
        // Create a > 5MB file to trigger multipart upload
        Path largeFile = Files.createTempFile("dvuploader-large", ".bin");
        String filename = largeFile.getFileName().toString();
        byte[] data = new byte[6 * 1024 * 1024]; // 6 MB
        Files.write(largeFile, data);
        
        try {
            uploader.parseArgs(new String[]{largeFile.toAbsolutePath().toString()});
            uploader.processRequests();
            assertTrue(isFileInDataset(filename), "Large file " + filename + " should be in dataset after upload");
        } finally {
            Files.deleteIfExists(largeFile);
        }
    }

    private boolean isFileInDataset(String filename) throws IOException {
        return countFileInDataset(filename) > 0;
    }

    private int countFileInDataset(String filename) throws IOException {
        CloseableHttpClient httpClient = uploader.getSharedHttpClient();
        String url = server + "/api/datasets/:persistentId/versions/:latest/files?key=" + apiKey + "&persistentId=" + datasetPID;
        HttpGet get = new HttpGet(url);
        int count = 0;
        try (CloseableHttpResponse response = httpClient.execute(get, uploader.getLocalContext())) {
            if (response.getStatusLine().getStatusCode() == 200) {
                String res = EntityUtils.toString(response.getEntity());
                JSONArray data = new JSONObject(res).getJSONArray("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject fileEntry = data.getJSONObject(i);
                    if (fileEntry.getJSONObject("dataFile").getString("filename").equals(filename)) {
                        count++;
                    }
                }
            }
            EntityUtils.consumeQuietly(response.getEntity());
        }
        return count;
    }
}
