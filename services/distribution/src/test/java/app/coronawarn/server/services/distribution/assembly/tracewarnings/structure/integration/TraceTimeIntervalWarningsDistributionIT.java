package app.coronawarn.server.services.distribution.assembly.tracewarnings.structure.integration;

import app.coronawarn.server.common.persistence.service.TraceTimeIntervalWarningService;
import app.coronawarn.server.common.persistence.service.utils.checkins.CheckinsDateSpecification;
import app.coronawarn.server.common.protocols.internal.SubmissionPayload.SubmissionType;
import app.coronawarn.server.common.protocols.internal.pt.CheckIn;
import app.coronawarn.server.services.distribution.Application;
import app.coronawarn.server.services.distribution.assembly.component.OutputDirectoryProvider;
import app.coronawarn.server.services.distribution.assembly.component.TraceTimeIntervalWarningsStructureProvider;
import app.coronawarn.server.services.distribution.assembly.structure.WritableOnDisk;
import app.coronawarn.server.services.distribution.assembly.structure.directory.Directory;
import app.coronawarn.server.services.distribution.assembly.structure.directory.DirectoryOnDisk;
import app.coronawarn.server.services.distribution.assembly.structure.util.ImmutableStack;
import app.coronawarn.server.services.distribution.assembly.structure.util.TimeUtils;
import app.coronawarn.server.services.distribution.common.Helpers;
import app.coronawarn.server.services.distribution.objectstore.ObjectStoreAccess;
import org.apache.commons.lang3.StringUtils;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.rules.TemporaryFolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.vault.config.VaultAutoConfiguration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ContextConfiguration(classes = {Application.class}, initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration(exclude = VaultAutoConfiguration.class)
class TraceTimeIntervalWarningsDistributionIT {

  @Autowired
  private TraceTimeIntervalWarningService traceTimeIntervalWarningService;

  @Autowired
  private TraceTimeIntervalWarningsStructureProvider traceTimeIntervalWarningsStructureProvider;

  @MockBean
  private ObjectStoreAccess objectStoreAccess;

  @MockBean
  private OutputDirectoryProvider outputDirectoryProvider;

  @Rule
  private TemporaryFolder tempFolder = new TemporaryFolder();

  private static final String SEPARATOR = File.separator;
  private static final String PARENT_DIRECTORY = "parent";

  @BeforeEach
  public void setup() throws Exception {
    tempFolder.create();
    File outputDirectory = tempFolder.newFolder(PARENT_DIRECTORY);
    Directory<WritableOnDisk> testDirectory = new DirectoryOnDisk(outputDirectory);
    when(outputDirectoryProvider.getDirectory()).thenReturn(testDirectory);
  }

  @Test
  void testIndicesAreOldestAndLatestForMultipleSubmissions() throws Exception {
    //given
    LocalDateTime utcHour = TimeUtils.getCurrentUtcHour();
    Integer excludedCurrentHour = CheckinsDateSpecification.HOUR_SINCE_EPOCH_DERIVATION
        .apply(utcHour.toEpochSecond(ZoneOffset.UTC));
    Integer oldestHour = CheckinsDateSpecification.HOUR_SINCE_EPOCH_DERIVATION
        .apply(utcHour.minusHours(10).toEpochSecond(ZoneOffset.UTC));
    Integer latestHour = CheckinsDateSpecification.HOUR_SINCE_EPOCH_DERIVATION
        .apply(utcHour.minusHours(1).toEpochSecond(ZoneOffset.UTC));

    List<CheckIn> checkIns = Helpers.buildCheckIns(5, 10, 30);
    List<CheckIn> additionalCheckIns = Helpers.buildCheckIns(5, 10, 30);
    List<CheckIn> anotherCheckIns = Helpers.buildCheckIns(5, 10, 30);

    traceTimeIntervalWarningService
        .saveCheckins(checkIns, excludedCurrentHour, SubmissionType.SUBMISSION_TYPE_PCR_TEST);
    traceTimeIntervalWarningService
        .saveCheckins(additionalCheckIns, oldestHour, SubmissionType.SUBMISSION_TYPE_PCR_TEST);
    traceTimeIntervalWarningService
        .saveCheckins(anotherCheckIns, latestHour, SubmissionType.SUBMISSION_TYPE_PCR_TEST);

    //when
    final Directory<WritableOnDisk> traceWarningsDirectory = traceTimeIntervalWarningsStructureProvider
        .getTraceWarningsDirectory();
    final Directory<WritableOnDisk> directory = outputDirectoryProvider.getDirectory();
    directory.addWritable(traceWarningsDirectory);
    directory.prepare(new ImmutableStack<>());
    directory.write();

    //then
    Set<String> expectedPaths = new java.util.HashSet<>(Set.of(
        PARENT_DIRECTORY,
        StringUtils.joinWith(SEPARATOR, PARENT_DIRECTORY, "twp"),
        StringUtils.joinWith(SEPARATOR, PARENT_DIRECTORY, "twp", "country"),
        StringUtils.joinWith(SEPARATOR, PARENT_DIRECTORY, "twp", "country", "DE"),
        StringUtils.joinWith(SEPARATOR, PARENT_DIRECTORY, "twp", "country", "DE", "hour"),
        StringUtils.joinWith(SEPARATOR, PARENT_DIRECTORY, "twp", "country", "index"),
        StringUtils.joinWith(SEPARATOR, PARENT_DIRECTORY, "twp", "country", "index.checksum"),
        StringUtils.joinWith(SEPARATOR, PARENT_DIRECTORY, "twp", "country", "DE", "hour", "index"),
        StringUtils.joinWith(SEPARATOR, PARENT_DIRECTORY, "twp", "country", "DE", "hour", "index.checksum")));
    IntStream.range(oldestHour, latestHour + 1).forEach(hour -> {
      expectedPaths.add(StringUtils.joinWith(SEPARATOR, PARENT_DIRECTORY, "twp", "country", "DE", "hour",
          hour));
    });
    Set<String> actualFiles = Helpers.getSubFoldersPaths(tempFolder.getRoot().getAbsolutePath(), PARENT_DIRECTORY);
    actualFiles.addAll(Helpers.getFilePaths(tempFolder.getRoot(), tempFolder.getRoot().getAbsolutePath()));
    final List<Integer> excludedCurrentHourEmptyList = actualFiles.stream()
        .filter(path -> hasSuffix(path, excludedCurrentHour))
        .map(this::extractSubmissionHour)
        .collect(Collectors.toList());
    List<Integer> oldestAndLatestTimeStamps = actualFiles.stream()
        .filter(path -> hasSuffix(path, latestHour, oldestHour))
        .map(this::extractSubmissionHour).collect(Collectors.toList());

    assertThat(excludedCurrentHourEmptyList).isEmpty();
    assertThat(oldestAndLatestTimeStamps).containsExactlyInAnyOrder(latestHour, oldestHour);
    assertThat(actualFiles).containsAll(expectedPaths);
  }

  private int extractSubmissionHour(String path) {
    final String[] split = path.split("/");
    return Integer.parseInt(split[split.length - 1]);
  }

  private boolean hasSuffix(String path, Integer... submissionHours) {
    return Arrays.stream(submissionHours)
        .map(it -> path
            .endsWith(it.toString())).reduce((a, b) -> a || b).orElse(Boolean.FALSE);
  }
}