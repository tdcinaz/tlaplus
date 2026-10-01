package tla2tex;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helpers shared by the tla2tex JUnit tests.
 *
 * <p>TLATeX keeps all of its state in static fields and normally runs once per
 * JVM. These helpers make it usable from a test suite that runs many specs in
 * one JVM:
 * <ul>
 * <li>{@link #resetStatics()} restores every option in {@link Parameters} to
 * its default and clears the tokenizer's per-spec tables. Call it from a
 * {@code @Before} method.</li>
 * <li>{@link #tokenize(String)} and {@link #analyze(String)} run the front
 * half of the pipeline on a spec given as a string.</li>
 * <li>{@link #latexWithoutAlignment(String, Path)} runs the pipeline through
 * {@code LaTeXOutput.WriteLaTeXFile} <em>without</em> running LaTeX, so no TeX
 * installation is needed. All alignment spaces are zero in that output.</li>
 * <li>{@link #runCli(Path, String...)} runs {@code tla2tex.TLA} in a child JVM
 * with a chosen working directory, for end-to-end tests that do run LaTeX.</li>
 * </ul>
 *
 * <p>Input specs live in {@code test-model/tla2tex/} and are located through
 * the {@code basedir} system property that the Ant {@code test-set} target
 * passes, like the SANY and TLC suites do.
 */
public final class TLA2TexTestSupport {

    /** Root of the org.lamport.tlatools project, from Ant or the current directory. */
    public static final String BASE_DIR =
        System.getProperty("basedir", System.getProperty("user.dir", "."));

    /** Directory holding the tla2tex test specs and their golden files. */
    public static final Path TEST_MODEL_DIR = Paths.get(BASE_DIR, "test-model", "tla2tex");

    /** Set this environment variable to {@code 1} to rewrite golden files instead of comparing. */
    public static final String UPDATE_GOLDEN_ENV = "TLA2TEX_UPDATE_GOLDEN";

    private static final Map<Field, Object> PARAMETER_DEFAULTS = snapshotParameters();

    private TLA2TexTestSupport() {
    }

    // ------------------------------------------------------------------ state

    /**
     * Restores every public static non-final field of {@link Parameters} to the
     * value it had when this class was loaded (its default), clears the
     * tokenizer's identifier tables, and resets the comment tokenizer's
     * quote state.
     */
    public static void resetStatics() {
        for (Map.Entry<Field, Object> entry : PARAMETER_DEFAULTS.entrySet()) {
            try {
                entry.getKey().set(null, entry.getValue());
            } catch (IllegalAccessException e) {
                throw new AssertionError("Cannot reset Parameters." + entry.getKey().getName(), e);
            }
        }
        TokenizeSpec.reset();
        TokenizeComment.reset();
    }

    private static Map<Field, Object> snapshotParameters() {
        Map<Field, Object> defaults = new LinkedHashMap<>();
        for (Field field : Parameters.class.getFields()) {
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) && !Modifier.isFinal(mods)) {
                try {
                    defaults.put(field, field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("Cannot read Parameters." + field.getName(), e);
                }
            }
        }
        return defaults;
    }

    // --------------------------------------------------------------- pipeline

    /** Tokenizes a complete module (prolog, module, epilog) the way {@code TLA.runTranslation} does. */
    public static Token[][] tokenize(String specText) {
        BuiltInSymbols.Initialize();
        Token[][] spec = TokenizeSpec.Tokenize(new VectorCharReader(toLines(specText), 0), TokenizeSpec.MODULE);
        Token.FindPfStepTokens(spec);
        TokenizeSpec.FixPlusCal(spec, false);
        return spec;
    }

    /** {@link #tokenize(String)} followed by comment processing and alignment, i.e. everything before LaTeX runs. */
    public static Token[][] analyze(String specText) {
        Token[][] spec = tokenize(specText);
        CommentToken.ProcessComments(spec);
        FormatComments.Initialize();
        FindAlignments.FindAlignments(spec);
        return spec;
    }

    /**
     * Runs the whole pipeline except the two LaTeX runs and returns the text of
     * the generated {@code .tex} file. Because {@code LaTeXOutput.SetDimensions}
     * is skipped, every alignment space is zero; the output is deterministic and
     * needs no TeX installation.
     *
     * @param specText the module source
     * @param workDir  an existing directory to write {@code out.tex} into
     */
    public static String latexWithoutAlignment(String specText, Path workDir) throws IOException {
        Token[][] spec = analyze(specText);
        String root = workDir.resolve("out").toString();
        Parameters.LaTeXOutputFile = root;
        Parameters.LaTeXAlignmentFile = root;
        LaTeXOutput.WriteLaTeXFile(spec);
        return readFile(Paths.get(root + ".tex"));
    }

    /**
     * The lines strictly between {@code \begin{document}} and {@code \end{document}}.
     * The preamble is the inlined {@code tlatex.sty}, which is the same for every
     * spec and is better covered by the LaTeX run than by golden files.
     */
    public static String documentBody(String tex) {
        String[] lines = tex.split("\n", -1);
        int begin = indexOf(lines, "\\begin{document}");
        int end = indexOf(lines, "\\end{document}");
        if (begin < 0 || end < 0 || end <= begin) {
            throw new AssertionError("Output has no \\begin{document} ... \\end{document}");
        }
        return String.join("\n", Arrays.copyOfRange(lines, begin + 1, end)) + "\n";
    }

    private static int indexOf(String[] lines, String exact) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(exact)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ files

    /** All {@code *.tla} files under {@link #TEST_MODEL_DIR}, sorted by name. */
    public static List<Path> specsInTestModel() throws IOException {
        if (!Files.isDirectory(TEST_MODEL_DIR)) {
            throw new AssertionError("Missing " + TEST_MODEL_DIR + " (is the basedir property set?)");
        }
        try (Stream<Path> files = Files.list(TEST_MODEL_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".tla"))
                        .sorted()
                        .collect(Collectors.toList());
        }
    }

    /** Creates a fresh temporary directory; pair with {@link #deleteRecursively(Path)} in {@code @After}. */
    public static Path createTempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path path : paths) {
                Files.delete(path);
            }
        }
    }

    public static String readFile(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    public static void writeFile(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Normalizes line endings so goldens compare equal on every platform. */
    public static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n");
    }

    public static boolean updateGoldenRequested() {
        String value = System.getenv(UPDATE_GOLDEN_ENV);
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
    }

    private static Vector<String> toLines(String text) {
        return new Vector<>(Arrays.asList(normalizeNewlines(text).split("\n", -1)));
    }

    // ------------------------------------------------------------ external

    /** True when {@code command} resolves to an executable on {@code PATH}. */
    public static boolean isOnPath(String command) {
        String pathVar = System.getenv("PATH");
        if (pathVar == null) {
            return false;
        }
        for (String dir : pathVar.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Paths.get(dir, command);
            if (Files.isExecutable(candidate) || Files.isExecutable(Paths.get(dir, command + ".exe"))) {
                return true;
            }
        }
        return false;
    }

    /** Exit code and combined stdout/stderr of a child {@code tla2tex.TLA} run. */
    public static final class CliResult {
        public final int exitCode;
        public final String output;

        CliResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * Runs {@code tla2tex.TLA args...} in a child JVM whose working directory is
     * {@code workDir}, using this JVM's class path. TLATeX resolves the input
     * file and runs LaTeX relative to the working directory, which a test
     * cannot change in-process.
     */
    public static CliResult runCli(Path workDir, String... args) throws IOException, InterruptedException {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("tla2tex.TLA");
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("tla2tex.TLA did not finish within 5 minutes:\n" + output);
        }
        return new CliResult(process.exitValue(), output);
    }

    /** Number of error lines (those starting with {@code !}) in a LaTeX log. */
    public static int countLatexErrors(Path log) throws IOException {
        int count = 0;
        for (String line : Files.readAllLines(log, StandardCharsets.ISO_8859_1)) {
            if (line.startsWith("!")) {
                count++;
            }
        }
        return count;
    }
}
