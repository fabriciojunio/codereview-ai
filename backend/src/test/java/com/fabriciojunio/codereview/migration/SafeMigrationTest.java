package com.fabriciojunio.codereview.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build on a migration that breaks the previous version of the code.
 *
 * <h2>The problem</h2>
 * A rolling deploy runs both versions of the code at the same time, if only for
 * thirty seconds. If the migration drops a column the old version still reads,
 * that window turns into errors for whoever is using the system at that moment.
 * The same goes for renaming a column and for changing its type.
 *
 * <p>This system has an extra twist the generic advice does not cover: analysis
 * is asynchronous. A review request goes onto the RabbitMQ queue and the
 * consumer writes the result minutes later, so the old instance is not merely
 * answering requests during the swap — it is halfway through work it already
 * accepted. A column that disappears in that window does not return an error to
 * whoever clicked: it loses a review that was already running, and the user is
 * left with a request that stays pending forever.
 *
 * <h2>The process this enforces</h2>
 * The strategy is expand and contract, across three separate deploys:
 *
 * <ol>
 *   <li><b>Expand.</b> The new column lands beside the old one, nullable. The
 *       code writes to both and still reads from the old one. Nothing breaks,
 *       because nothing was taken away.</li>
 *   <li><b>Migrate.</b> Old data is copied into the new column and the code
 *       starts reading from it. The old column stays, still written, which is
 *       what makes rolling back possible without losing what came in
 *       meanwhile.</li>
 *   <li><b>Contract.</b> Only once the previous version is gone from every
 *       environment does the old column go. This is where the destructive
 *       statement belongs, and the only place it is safe.</li>
 * </ol>
 *
 * <p>This test does not block step three. It blocks step three happening
 * without someone having written down, in the file itself, that they know what
 * they are doing. The marker is a {@code -- contract:} line with a reason, and
 * it exists to force the question during review rather than at 3am.
 */
@DisplayName("Migration that does not break the previous version")
class SafeMigrationTest {

    /**
     * The marker that releases a destructive statement, with the reason beside
     * it.
     *
     * <p>Demanding the reason, and not just the marker, is what separates a
     * decision from a comment pasted to get the build green.
     */
    private static final Pattern CONTRACT_MARKER =
            // The reason has to sit on the same line as the marker. With \s the
            // pattern crosses the newline and swallows the statement below it
            // as if that were the justification, so an empty marker would let
            // anything through.
            Pattern.compile("--[^\\S\\n]*contract:[^\\S\\n]*\\S+", Pattern.CASE_INSENSITIVE);

    /**
     * Statements that break the previous version of the code while it still
     * runs.
     *
     * <p>{@code drop table} is deliberately absent: a whole table disappearing
     * is too large to slip past a review, and this list exists to catch what
     * does slip past.
     */
    private static final List<Pattern> DESTRUCTIVE = List.of(
            Pattern.compile("\\balter\\s+table\\s+\\S+\\s+drop\\s+column\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balter\\s+table\\s+\\S+\\s+rename\\s+column\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balter\\s+column\\s+\\S+\\s+type\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balter\\s+column\\s+\\S+\\s+set\\s+not\\s+null\\b", Pattern.CASE_INSENSITIVE));

    @Test
    @DisplayName("no migration breaks the previous version without saying it is on purpose")
    void noDestructiveMigrationWithoutMarker() throws IOException {
        List<String> problems = migrations()
                .flatMap(SafeMigrationTest::problemsIn)
                .toList();

        assertThat(problems)
                .as("""
                        Destructive migration found. A rolling deploy runs both versions of the \
                        code at the same time, and dropping or renaming a column the previous \
                        version still reads breaks whoever is using the system at that moment.

                        Do it in three steps: add the new column beside the old one, migrate the \
                        data, and only then drop. If this file ALREADY is the third step, write \
                        this line at the top of it:

                            -- contract: <why the previous version no longer exists>
                        """)
                .isEmpty();
    }

    @Test
    @DisplayName("there is at least one migration to check, otherwise this test proves nothing")
    void thereAreMigrationsToCheck() throws IOException {
        assertThat(migrations())
                .as("a test that finds no files always passes, and is worth nothing")
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alter table reviews drop column commit_hash;",
            "ALTER TABLE REVIEWS DROP COLUMN COMMIT_HASH;",
            "alter table reviews rename column status to state;",
            "alter table analysis_metrics alter column duration_ms type bigint;",
            "alter table review_results alter column summary set not null;",
    })
    @DisplayName("recognises the breaking shapes, in any case")
    void recognisesTheDestructiveOnes(String statement) {
        assertThat(problemsIn("test.sql", statement))
                .as("a statement that breaks the previous version has to be caught")
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alter table reviews add column commit_hash varchar(80);",
            "create index idx_reviews_status on reviews (status);",
            "update reviews set status = 'PENDING' where status is null;",
            "alter table review_results alter column summary drop not null;",
    })
    @DisplayName("lets through what stays compatible with the previous version")
    void letsThroughTheCompatibleOnes(String statement) {
        assertThat(problemsIn("test.sql", statement))
                .as("adding a column, creating an index and loosening a constraint break nobody")
                .isEmpty();
    }

    @Test
    @DisplayName("the contract marker releases, and is what documents the decision")
    void markerReleases() {
        String markedFile = """
                -- contract: version 1.4 left every environment on 2026-09-02
                alter table reviews drop column commit_hash;
                """;

        assertThat(problemsIn("V9__contract.sql", markedFile)).isEmpty();
    }

    @Test
    @DisplayName("a marker with no reason does not release, or it becomes a paste to get the build green")
    void markerWithoutReasonDoesNotRelease() {
        String noReason = """
                -- contract:
                alter table reviews drop column commit_hash;
                """;

        assertThat(problemsIn("V9__contract.sql", noReason)).isNotEmpty();
    }

    private static Stream<Path> migrations() throws IOException {
        Path root = Path.of("src", "main", "resources", "db").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".sql"))
                    .toList()
                    .stream();
        }
    }

    private static Stream<String> problemsIn(Path file) {
        try {
            return problemsIn(file.getFileName().toString(),
                    Files.readString(file, StandardCharsets.UTF_8)).stream();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
    }

    private static List<String> problemsIn(String name, String content) {
        if (CONTRACT_MARKER.matcher(content).find()) {
            return List.of();
        }
        String withoutComments = content.lines()
                .map(line -> line.replaceAll("--.*$", ""))
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);

        return DESTRUCTIVE.stream()
                .filter(destructive -> destructive.matcher(withoutComments).find())
                .map(destructive -> name + " contains " + destructive.pattern())
                .toList();
    }
}
