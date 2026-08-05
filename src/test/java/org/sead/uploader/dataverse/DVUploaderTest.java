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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

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
    private long partSize;
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
        
        String partSizeStr = System.getProperty("dataverse.part_size", props.getProperty("dataverse.part_size", System.getenv("DATAVERSE_PART_SIZE")));
        if (partSizeStr != null) {
            partSize = Long.parseLong(partSizeStr);
        } else {
            partSize = 5 * 1024 * 1024; // Default 5MB
        }

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
        System.out.println("Using part size: " + partSize);
        // Create a file slightly larger than partSize to trigger multipart upload
        long fileSize = partSize + (1024 * 1024); // partSize + 1 MB
        
        Path largeFile = Files.createTempFile("dvuploader-large", ".bin");
        String filename = largeFile.getFileName().toString();
        
        System.out.println("Creating " + fileSize + " bytes temp file: " + filename);
        try (OutputStream os = Files.newOutputStream(largeFile)) {
            byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
            long written = 0;
            while (written < fileSize) {
                int toWrite = (int) Math.min(buffer.length, fileSize - written);
                os.write(buffer, 0, toWrite);
                written += toWrite;
            }
        }
        
        try {
            uploader.parseArgs(new String[]{largeFile.toAbsolutePath().toString()});
            uploader.processRequests();
            assertTrue(isFileInDataset(filename), "Large file " + filename + " should be in dataset after upload");
        } finally {
            Files.deleteIfExists(largeFile);
        }
    }

    @Test
    public void testDirectoryTreeUploadWithLimit() throws IOException, UploaderException {
        Path tempDir = Files.createTempDirectory("dvuploader-test-tree");
        try {
            Path file1 = Files.createFile(tempDir.resolve("dvuploader-test-tree1.txt"));
            Files.writeString(file1, "File 1 content");
            Path subDir = Files.createDirectory(tempDir.resolve("subdir"));
            Path file2 = Files.createFile(subDir.resolve("dvuploader-test-tree2.txt"));
            Files.writeString(file2, "File 2 content");
            Path file3 = Files.createFile(subDir.resolve("dvuploader-test-tree3.txt"));
            Files.writeString(file3, "File 3 content");

            String[] filenames = {
                file1.getFileName().toString(),
                file2.getFileName().toString(),
                file3.getFileName().toString()
            };

            // Run 1: limit = 1, recurse
            System.out.println("Run 1: limit=1");
            uploader.parseArgs(new String[]{"-limit=1", "-recurse", tempDir.toAbsolutePath().toString()});
            uploader.processRequests();
            
            int count = countFilesFromSet(filenames);
            assertEquals(1, count, "Should have exactly 1 file uploaded in first run");

            // Run 2: no limit, recurse
            System.out.println("Run 2: full upload");
            uploader.clearRequests();
            uploader.parseArgs(new String[]{"-server=" + server, "-key=" + apiKey, "-did=" + datasetPID}); // Re-add common args
            uploader.parseArgs(new String[]{"-recurse", tempDir.toAbsolutePath().toString()});
            uploader.processRequests();

            count = countFilesFromSet(filenames);
            assertEquals(3, count, "Should have all 3 files uploaded after second run");

        } finally {
            deleteDirectory(tempDir);
        }
    }


    private int countFilesFromSet(String[] filenames) throws IOException {
        int count = 0;
        for (String f : filenames) {
            if (isFileInDataset(f)) count++;
        }
        return count;
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                entries.forEach(p -> {
                    try {
                        deleteDirectory(p);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
        Files.deleteIfExists(path);
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
