/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.verifier.framework;

import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.parser.SqlParserOptions;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.tests.StandaloneQueryRunner;
import com.facebook.presto.verifier.checksum.ChecksumValidator;
import com.facebook.presto.verifier.event.DeterminismAnalysisRun;
import com.facebook.presto.verifier.event.VerifierQueryEvent;
import com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus;
import com.facebook.presto.verifier.prestoaction.JdbcPrestoAction;
import com.facebook.presto.verifier.prestoaction.PrestoAction;
import com.facebook.presto.verifier.prestoaction.PrestoClusterConfig;
import com.facebook.presto.verifier.prestoaction.PrestoExceptionClassifier;
import com.facebook.presto.verifier.resolver.ChecksumExceededTimeLimitFailureResolver;
import com.facebook.presto.verifier.resolver.ExceededGlobalMemoryLimitFailureResolver;
import com.facebook.presto.verifier.resolver.ExceededTimeLimitFailureResolver;
import com.facebook.presto.verifier.resolver.FailureResolverManager;
import com.facebook.presto.verifier.resolver.VerifierLimitationFailureResolver;
import com.facebook.presto.verifier.retry.RetryConfig;
import com.facebook.presto.verifier.rewrite.QueryRewriter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.facebook.presto.sql.parser.IdentifierSymbol.AT_SIGN;
import static com.facebook.presto.sql.parser.IdentifierSymbol.COLON;
import static com.facebook.presto.verifier.VerifierTestUtil.CATALOG;
import static com.facebook.presto.verifier.VerifierTestUtil.SCHEMA;
import static com.facebook.presto.verifier.VerifierTestUtil.createChecksumValidator;
import static com.facebook.presto.verifier.VerifierTestUtil.createTypeManager;
import static com.facebook.presto.verifier.VerifierTestUtil.setupPresto;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.FAILED;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.FAILED_RESOLVED;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.SKIPPED;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.SUCCEEDED;
import static com.facebook.presto.verifier.framework.ClusterType.CONTROL;
import static com.facebook.presto.verifier.framework.ClusterType.TEST;
import static com.facebook.presto.verifier.framework.DeterminismAnalysis.DETERMINISTIC;
import static com.facebook.presto.verifier.framework.DeterminismAnalysis.NON_DETERMINISTIC_COLUMNS;
import static com.facebook.presto.verifier.framework.SkippedReason.CONTROL_SETUP_QUERY_FAILED;
import static com.facebook.presto.verifier.framework.SkippedReason.FAILED_BEFORE_CONTROL_QUERY;
import static com.facebook.presto.verifier.framework.SkippedReason.NON_DETERMINISTIC;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.regex.Pattern.DOTALL;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.stream.Collectors.joining;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestDataVerification
{
    private static final String SUITE = "test-suite";
    private static final String NAME = "test-query";
    private static final String TEST_ID = "test-id";

    private static StandaloneQueryRunner queryRunner;

    @BeforeClass
    public void setupClass()
            throws Exception
    {
        queryRunner = setupPresto();
    }

    private DataVerification createVerification(String controlQuery, String testQuery)
    {
        return createVerification(controlQuery, testQuery, new DeterminismAnalyzerConfig());
    }

    private DataVerification createVerification(String controlQuery, String testQuery, DeterminismAnalyzerConfig determinismAnalyzerConfig)
    {
        QueryConfiguration configuration = new QueryConfiguration(CATALOG, SCHEMA, Optional.of("user"), Optional.empty(), Optional.empty());
        VerificationContext verificationContext = new VerificationContext();
        VerifierConfig verifierConfig = new VerifierConfig().setTestId(TEST_ID);
        RetryConfig retryConfig = new RetryConfig();
        TypeManager typeManager = createTypeManager();
        PrestoAction prestoAction = new JdbcPrestoAction(
                PrestoExceptionClassifier.createDefault(),
                configuration,
                verificationContext,
                new PrestoClusterConfig()
                        .setHost(queryRunner.getServer().getAddress().getHost())
                        .setJdbcPort(queryRunner.getServer().getAddress().getPort()),
                retryConfig,
                retryConfig);
        QueryRewriter queryRewriter = new QueryRewriter(
                new SqlParser(new SqlParserOptions().allowIdentifierSymbol(COLON, AT_SIGN)),
                typeManager,
                prestoAction,
                ImmutableList.of(),
                ImmutableMap.of(CONTROL, QualifiedName.of("tmp_verifier_c"), TEST, QualifiedName.of("tmp_verifier_t")));
        ChecksumValidator checksumValidator = createChecksumValidator(verifierConfig);
        SourceQuery sourceQuery = new SourceQuery(SUITE, NAME, controlQuery, testQuery, configuration, configuration);
        return new DataVerification(
                (verification, e) -> false,
                prestoAction,
                sourceQuery,
                queryRewriter,
                new DeterminismAnalyzer(
                        sourceQuery,
                        prestoAction,
                        queryRewriter,
                        checksumValidator,
                        typeManager,
                        verificationContext,
                        determinismAnalyzerConfig),
                new FailureResolverManager(ImmutableSet.of(
                        new ExceededGlobalMemoryLimitFailureResolver(),
                        new ExceededTimeLimitFailureResolver(),
                        new ChecksumExceededTimeLimitFailureResolver(),
                        new VerifierLimitationFailureResolver())),
                verificationContext,
                verifierConfig,
                typeManager,
                checksumValidator);
    }

    @Test
    public void testSuccess()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT 1.0", "SELECT 1.00001").run();
        assertTrue(event.isPresent());
        assertEvent(event.get(), SUCCEEDED, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    public void testSchemaMismatch()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT 1", "SELECT 1.00001").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                FAILED,
                Optional.empty(),
                Optional.of("SCHEMA_MISMATCH"),
                Optional.of("Test state SUCCEEDED, Control state SUCCEEDED.\n\n" +
                        "SCHEMA MISMATCH\n"));
    }

    @Test
    public void testRowCountMismatch()
    {
        Optional<VerifierQueryEvent> event = createVerification(
                "SELECT 1 x",
                "SELECT 1 x UNION ALL SELECT 1 x").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                FAILED,
                Optional.of(DETERMINISTIC),
                Optional.of("ROW_COUNT_MISMATCH"),
                Optional.of("Test state SUCCEEDED, Control state SUCCEEDED.\n\n" +
                        "ROW COUNT MISMATCH\n" +
                        "Control 1 rows, Test 2 rows\n"));
    }

    @Test
    public void testColumnMismatch()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT 1.0", "SELECT 1.001").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                FAILED,
                Optional.of(DETERMINISTIC),
                Optional.of("COLUMN_MISMATCH"),
                Optional.of("Test state SUCCEEDED, Control state SUCCEEDED.\n\n" +
                        "COLUMN MISMATCH\n" +
                        "Control 1 rows, Test 1 rows\n" +
                        "Mismatched Columns:\n" +
                        "  _col0 \\(double\\): control\\(sum: 1.0\\) test\\(sum: 1.001\\) relative error: 9.995002498749525E-4\n"));
    }

    @Test
    public void testParsingFailed()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT", "SELECT 1").run();
        assertFalse(event.isPresent());
    }

    @Test
    public void testRewriteFailed()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT * FROM test", "SELECT 1").run();
        assertTrue(event.isPresent());
        assertEquals(event.get().getSkippedReason(), FAILED_BEFORE_CONTROL_QUERY.name());
        assertEvent(
                event.get(),
                SKIPPED,
                Optional.empty(),
                Optional.of("PRESTO(SYNTAX_ERROR)"),
                Optional.of("Test state NOT_RUN, Control state NOT_RUN.\n\n" +
                        "REWRITE query failed on CONTROL cluster:\n.*"));
    }

    @Test
    public void testControlFailed()
    {
        Optional<VerifierQueryEvent> event = createVerification("INSERT INTO dest SELECT * FROM test", "SELECT 1").run();
        assertTrue(event.isPresent());
        assertEquals(event.get().getSkippedReason(), CONTROL_SETUP_QUERY_FAILED.name());
        assertEvent(
                event.get(),
                SKIPPED,
                Optional.empty(),
                Optional.of("PRESTO(SYNTAX_ERROR)"),
                Optional.of("Test state NOT_RUN, Control state FAILED_TO_SETUP.\n\n" +
                        "CONTROL SETUP query failed on CONTROL cluster:\n.*"));
    }

    @Test
    public void testNonDeterministic()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT rand()", "SELECT 2.0").run();
        assertTrue(event.isPresent());
        assertEquals(event.get().getSkippedReason(), NON_DETERMINISTIC.name());
        assertEvent(
                event.get(),
                SKIPPED,
                Optional.of(NON_DETERMINISTIC_COLUMNS),
                Optional.of("COLUMN_MISMATCH"),
                Optional.of("Test state SUCCEEDED, Control state SUCCEEDED.\n\n" +
                        "COLUMN MISMATCH\n" +
                        "Control 1 rows, Test 1 rows\n" +
                        "Mismatched Columns:\n" +
                        "  _col0 \\(double\\): control\\(sum: .*\\) test\\(sum: 2.0\\) relative error: .*\n"));

        List<DeterminismAnalysisRun> runs = event.get().getDeterminismAnalysisDetails().getRuns();
        assertEquals(runs.size(), 1);
        assertDeterminismAnalysisRun(runs.get(0));
    }

    @Test
    public void testArrayOfRow()
    {
        Optional<VerifierQueryEvent> event = createVerification(
                "SELECT ARRAY[ROW(1, 'a'), ROW(2, null)]", "SELECT ARRAY[ROW(1, 'a'), ROW(2, null)]",
                new DeterminismAnalyzerConfig().setMaxAnalysisRuns(3)).run();
        assertTrue(event.isPresent());
        assertEvent(event.get(), SUCCEEDED, Optional.empty(), Optional.empty(), Optional.empty());

        event = createVerification("SELECT ARRAY[ROW(1, 'a'), ROW(2, 'b')]", "SELECT ARRAY[ROW(1, 'a'), ROW(2, null)]").run();
        assertTrue(event.isPresent());
        assertEvent(
                event.get(),
                FAILED,
                Optional.of(DETERMINISTIC),
                Optional.of("COLUMN_MISMATCH"),
                Optional.of("Test state SUCCEEDED, Control state SUCCEEDED.\n\n" +
                        "COLUMN MISMATCH\n" +
                        "Control 1 rows, Test 1 rows\n" +
                        "Mismatched Columns:\n" +
                        "  _col0 \\(array\\(row\\(integer, varchar\\(1\\)\\)\\)\\):" +
                        " control\\(checksum: 71 b5 2f 7f 1e 9b a6 a4, cardinality_sum: 2\\)" +
                        " test\\(checksum: b4 3c 7d 02 2b 14 77 12, cardinality_sum: 2\\)\n"));

        List<DeterminismAnalysisRun> runs = event.get().getDeterminismAnalysisDetails().getRuns();
        assertEquals(runs.size(), 2);
        assertDeterminismAnalysisRun(runs.get(0));
        assertDeterminismAnalysisRun(runs.get(1));
    }

    @Test
    public void testSelectDate()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT date '2020-01-01', date(now()) today", "SELECT date '2020-01-01', date(now()) today").run();
        assertTrue(event.isPresent());
        assertEvent(event.get(), SUCCEEDED, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    public void testSelectUnknown()
    {
        Optional<VerifierQueryEvent> event = createVerification("SELECT null, null unknown", "SELECT null, null unknown").run();
        assertTrue(event.isPresent());
        assertEvent(event.get(), SUCCEEDED, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    public void testSelectNonStorableStructuredColumns()
    {
        String query = "SELECT\n" +
                "    ARRAY[DATE '2020-01-01'],\n" +
                "    ARRAY[NULL],\n" +
                "    MAP(\n" +
                "        ARRAY[DATE '2020-01-01'], ARRAY[\n" +
                "            CAST(ROW(1, 'a', DATE '2020-01-01') AS ROW(x int, y VARCHAR, z date))\n" +
                "        ]\n" +
                "    ),\n" +
                "    ROW(NULL)";
        Optional<VerifierQueryEvent> event = createVerification(query, query).run();
        assertTrue(event.isPresent());
        assertEvent(event.get(), SUCCEEDED, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    public void testChecksumQueryCompilerError()
    {
        List<String> columns = IntStream.range(0, 1000).mapToObj(i -> "c" + i).collect(toImmutableList());
        queryRunner.execute(format("CREATE TABLE checksum_test (%s)", columns.stream().map(column -> column + " double").collect(joining(","))));

        String query = format("SELECT %s FROM checksum_test", Joiner.on(",").join(columns));
        Optional<VerifierQueryEvent> event = createVerification(query, query).run();

        assertTrue(event.isPresent());
        assertEquals(event.get().getStatus(), FAILED_RESOLVED.name());
        assertEquals(event.get().getErrorCode(), "PRESTO(COMPILER_ERROR)");
        assertNotNull(event.get().getControlQueryInfo().getChecksumQuery());
        assertNotNull(event.get().getControlQueryInfo().getChecksumQueryId());
        assertNotNull(event.get().getTestQueryInfo().getChecksumQuery());
    }

    private void assertEvent(
            VerifierQueryEvent event,
            EventStatus expectedStatus,
            Optional<DeterminismAnalysis> expectedDeterminismAnalysis,
            Optional<String> expectedErrorCode,
            Optional<String> expectedErrorMessageRegex)
    {
        assertEquals(event.getSuite(), SUITE);
        assertEquals(event.getTestId(), TEST_ID);
        assertEquals(event.getName(), NAME);
        assertEquals(event.getStatus(), expectedStatus.name());
        assertEquals(event.getDeterminismAnalysis(), expectedDeterminismAnalysis.map(DeterminismAnalysis::name).orElse(null));
        assertEquals(event.getErrorCode(), expectedErrorCode.orElse(null));
        if (event.getErrorMessage() == null) {
            assertFalse(expectedErrorMessageRegex.isPresent());
        }
        else {
            assertTrue(expectedErrorMessageRegex.isPresent());
            assertTrue(Pattern.compile(expectedErrorMessageRegex.get(), MULTILINE + DOTALL).matcher(event.getErrorMessage()).matches());
        }
    }

    private void assertDeterminismAnalysisRun(DeterminismAnalysisRun run)
    {
        assertNotNull(run.getTableName());
        assertNotNull(run.getQueryId());
        assertNotNull(run.getChecksumQueryId());
    }
}
