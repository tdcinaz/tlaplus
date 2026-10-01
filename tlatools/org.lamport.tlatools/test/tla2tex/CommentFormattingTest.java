package tla2tex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for how comment text is classified into English prose versus TLA
 * tokens, and for the test harness's own isolation between specs.
 */
public class CommentFormattingTest {

    private Path workDir;

    @Before
    public void reset() throws IOException {
        TLA2TexTestSupport.resetStatics();
        workDir = TLA2TexTestSupport.createTempDir("tla2tex-comments-");
    }

    @After
    public void cleanUp() throws IOException {
        TLA2TexTestSupport.deleteRecursively(workDir);
    }

    private String body(String spec) throws IOException {
        return TLA2TexTestSupport.documentBody(TLA2TexTestSupport.latexWithoutAlignment(spec, workDir));
    }

    private static final String PROSE_SPEC =
        "---- MODULE Prose ----\n"
        + "\\* This sentence is ordinary written prose about the variable x.\n"
        + "VARIABLE x\n"
        + "====\n";

    @Test
    public void englishWordsInCommentsStayText() throws IOException {
        String tex = body(PROSE_SPEC);
        assertTrue(tex, tex.contains(" This sentence is ordinary written prose"));
        assertFalse(tex, tex.contains("\\ensuremath{This}"));
        // A word that is also a spec identifier is typeset as a TLA token.
        assertTrue(tex, tex.contains("\\ensuremath{x}"));
    }

    @Test
    public void unbalancedQuoteInOneSpecDoesNotLeakIntoTheNext() throws IOException {
        // A single ` opens a quoted-TLA region that this comment never closes with '.
        body("---- MODULE Leaky ----\n"
             + "\\* identifiers such as `alpha are written in math mode\n"
             + "VARIABLE alpha\n"
             + "====\n");
        TLA2TexTestSupport.resetStatics();
        String tex = body(PROSE_SPEC);
        assertFalse("quote state leaked from the previous spec:\n" + tex, tex.contains("\\ensuremath{This}"));
        assertTrue(tex, tex.contains(" This sentence is ordinary written prose"));
    }
}
