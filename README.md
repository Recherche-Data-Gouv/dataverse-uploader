# dataverse-uploader

DVUploader, a Command-line Bulk Uploader for Dataverse and SEAD/Clowder (see link below)

Motivation:

Dataverse supports file uploads through its web interface. However, that interface has a limit of 1000 files per upload session and, since it displays uploaded files in a single long list, it becomes difficult to use with fewer files than that. The web interface's support for unzipping zip files is one way to simplify the process - files can be pre-zipped for upload as one larger zip file - but the interface still shows a long list of the included files. 

The Dataverse community has a number of initiatives underway to support upload of larger files (greater than a few GB) and/or large numbers of files. Many of these involve configuring external storage and/or data transfer software. One, whose development was supported by TDL, is a relatively simple application (DVUploader) that can be downloaded by users. It uses the existing Dataverse application programming interface (API) to upload files from a specified directory into a specified Dataset. It can be a useful alternative to the web interface when:

  * there are hundreds or thousands of files to upload,
  * when automatic verification of error-free and complete upload of files is desired,
  * when new files are being generated/added to a directory and Dataverse needs to be updated with just the new files,
  * uploading of files needs to be automated, e.g. added to an instrument or analysis script or program.

The DVUploader does need to be installed and, as a command-line tool, may not be as intuitive as the Dataverse web interface. However, unlike other bulk tools being developed, it will work with any Dataverse installation without any server-side changes. (Since it does upload and store data via Dataverse, it shares the basic performance and performance limitations of Dataverse's web interface. Other tools bypass Dataverse to handle larger data or do not move data from their remote locations and simply reference it in a Dataverse Dataset.) DVUploader can thus be a useful tool for individuals and for Dataverse installations interested in supporting larger numbers of files.

For Clowder/SEAD-specific information, see https://opensource.ncsa.illinois.edu/confluence/display/SEAD/SEADUploader%3A+Using+SEAD%27s+API+for+bulk+uploads+and+application+integration

Build:

Run the following command to build the project and create the executable JAR:

```bash
mvn clean package -DskipTests
```

This will produce:
- `target/DVUploader-1.4.0.jar`: Standard library JAR.
- `target/DVUploader-v1.4.0.jar`: Executable "fat" JAR containing all dependencies.

Testing:

Basic functionality tests for Dataverse can be run using Maven. These tests require a live Dataverse instance and valid credentials.

1. Copy `test.properties.example` to `test.properties`.
2. Edit `test.properties` and provide your Dataverse server URL, API key, and a test Dataset PID (DOI).
3. Run the tests:
```bash
mvn test
```

Alternatively, you can provide configuration via system properties or environment variables:
```bash
mvn test -Ddataverse.server=... -Ddataverse.api_key=... -Ddataverse.dataset_pid=...
```

Usage: See wiki: https://github.com/GlobalDataverseCommunityConsortium/dataverse-uploader/wiki/DVUploader,-a-Command-line-Bulk-Uploader-for-Dataverse
