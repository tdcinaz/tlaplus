package tla2tex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Golden-file test: for every {@code test-model/tla2tex/NAME.tla}, the body of
 * the generated LaTeX (without running LaTeX, so with zero alignment spaces)
 * must equal {@code NAME.golden.tex}.
 *
 * <p>To (re)generate the goldens after an intended output change, run the
 * tests with the environment variable {@code TLA2TEX_UPDATE_GOLDEN=1}
 * ({@code scripts/tlatex-dev.sh golden} in the extension repo) and review the
 * resulting diff.
 */
@RunWith(Parameterized.class)
public class GoldenLaTeXTest {

    @Parameters(name = "{0}")
    public static Collection<Object[]> specs() throws IOException {
        List<Object[]> params = new ArrayList<>();
        for (Path spec : TLA2TexTestSupport.specsInTestModel()) {
            String name = spec.getFileName().toString();
            params.add(new Object[] { name.substring(0, name.length() - ".tla".length()), spec });
        }
        return params;
    }

    private final String name;
    private final Path spec;

    public GoldenLaTeXTest(String name, Path spec) {
        this.name = name;
        this.spec = spec;
    }

    private Path workDir;

    @Before
    public void reset() throws IOException {
        TLA2TexTestSupport.resetStatics();
        workDir = TLA2TexTestSupport.createTempDir("tla2tex-golden-");
    }

    @After
    public void cleanUp() throws IOException {
        TLA2TexTestSupport.deleteRecursively(workDir);
    }

    @Test
    public void latexBodyMatchesGolden() throws IOException {
        String tex = TLA2TexTestSupport.latexWithoutAlignment(
            TLA2TexTestSupport.readFile(spec), workDir);
        String body = TLA2TexTestSupport.normalizeNewlines(TLA2TexTestSupport.documentBody(tex));
        Path golden = spec.resolveSibling(name + ".golden.tex");

        if (TLA2TexTestSupport.updateGoldenRequested()) {
            TLA2TexTestSupport.writeFile(golden, body);
            return;
        }
        assertTrue("Missing golden file " + golden + ". Generate it with "
                   + TLA2TexTestSupport.UPDATE_GOLDEN_ENV + "=1 and review it.",
                   Files.exists(golden));
        String expected = TLA2TexTestSupport.normalizeNewlines(TLA2TexTestSupport.readFile(golden));
        assertEquals("LaTeX body for " + name + " differs from " + golden.getFileName()
                     + ". If the change is intended, regenerate with "
                     + TLA2TexTestSupport.UPDATE_GOLDEN_ENV + "=1.",
                     expected, body);
    }
}
