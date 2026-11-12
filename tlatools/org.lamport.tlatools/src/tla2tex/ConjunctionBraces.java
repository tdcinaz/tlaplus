package tla2tex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import tla2sany.parser.SyntaxTreeNode;
import tla2sany.st.Location;
import tla2sany.st.SyntaxTreeConstants;
import util.ToolIO;

/**
 * Typesets a definition whose body is a bulleted conjunction list like a
 * piecewise function, when the {@code brace conjunctions} directive is given:
 *
 * <pre>
 *   Inv == /\ x \in Nat             Inv ==
 *          /\ y \in Nat      or       /\ x \in Nat
 *                                     /\ y \in Nat
 * </pre>
 *
 * both print as {@code Inv} and the {@code \defeq} followed by a tall left
 * brace and the conjuncts without their bullets, the left-hand side being
 * centered vertically on the list.
 *
 * <p>SANY locates the definitions and their bullets ({@link #find}). After
 * alignment, {@link #finish} keeps a list only if it can be set in one box:
 * its first bullet is on the line of the {@code ==} or on the next line, every
 * token of its lines from the first bullet on is in or right of the bullet
 * column, its only comments are one-line comments, nothing but comments
 * follows it on its last line, it has at least two conjuncts and at most
 * {@link #MAX_LINES} lines, and it is not in a PlusCal algorithm. Otherwise it
 * is typeset as usual; {@code -debug} says why. {@link LaTeXOutput} writes a
 * kept list as an {@code \@bx} command (defined in {@link #STYLE_FILE}), whose
 * box lines are positioned relative to the bullet column and whose bullets
 * are printed invisibly so the brace can take their place.
 */
final class ConjunctionBraces
{
    /**
     * Longest list, in source lines, that is typeset with a brace. The box
     * cannot be broken across pages, so a longer list is typeset as usual.
     */
    static final int MAX_LINES = 30;

    /** A definition whose conjunction-list body may be typeset with a brace. */
    static final class Region
    {
        /** The {@code ==} of the definition. */
        final Token define;
        /** The bullets of the list, in order. */
        final Token[] bullets;
        /** The last token of the list. */
        final Token last;
        /** Names the definition in messages. */
        final String what;

        /** Set by {@link #finish}: positions in the spec of the tokens above. */
        int defLine, defItem, firstLine, firstItem, lastLine, lastItem;

        /** Set by {@link #finish}: true if the first bullet follows the {@code ==}. */
        boolean sameLine;

        Region(Token define, Token[] bullets, Token last, String what)
        {
            this.define = define;
            this.bullets = bullets;
            this.last = last;
            this.what = what;
        }
    }

    private ConjunctionBraces()
    {
    }

    /**
     * The resource file defining {@code \@bx}, which LaTeXOutput copies into
     * the preamble when the brace directive is given. It is not part of
     * {@code tlatex.sty}, so the output of other specs is unchanged.
     */
    static final String STYLE_FILE = "tlatexbraces.sty";

    /**
     * Attaches a {@link Region} to the {@code ==} of each definition in
     * {@code spec} whose body is a conjunction list, using {@code root}, the
     * result of {@link MacroSpans#parse} for {@code source}. If {@code root} is
     * null, warns with {@code failure} instead. Does nothing unless the brace
     * directive was given. {@code spec} must still have one row per source line.
     */
    static void find(SyntaxTreeNode root, String failure, byte[] source, Token[][] spec, Directives directives)
    {
        if (!directives.braceConjunctions())
        {
            return;
        }
        if (root == null)
        {
            directives.warn("brace directive not applied because SANY could not parse the spec: "
                            + MacroSpans.firstLine(failure));
            return;
        }
        MacroSpans.SourceColumns columns = new MacroSpans.SourceColumns(source, spec);
        Deque<SyntaxTreeNode> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty())
        {
            SyntaxTreeNode node = todo.pop();
            if (node.isKind(SyntaxTreeConstants.N_OperatorDefinition)
                || node.isKind(SyntaxTreeConstants.N_FunctionDefinition))
            {
                SyntaxTreeNode[] heirs = node.getHeirs();
                if (heirs.length >= 3 && heirs[heirs.length - 1].isKind(SyntaxTreeConstants.N_ConjList))
                {
                    consider(heirs[heirs.length - 2], heirs[heirs.length - 1], spec, columns);
                }
            }
            SyntaxTreeNode[] heirs = node.getHeirs();
            for (int i = heirs.length - 1; i >= 0; i--)
            {
                todo.push(heirs[i]);
            }
        }
    }

    private static void consider(SyntaxTreeNode define, SyntaxTreeNode list, Token[][] spec,
                                 MacroSpans.SourceColumns columns)
    {
        String what = "definition at line " + define.getLocation().beginLine();
        Token defTok = tokenStartingAt(define.getLocation(), spec, columns);
        SyntaxTreeNode[] items = list.getHeirs();
        Token[] bullets = new Token[items.length];
        for (int k = 0; k < items.length; k++)
        {
            SyntaxTreeNode[] parts = items[k].getHeirs();
            bullets[k] = parts.length == 0 ? null : tokenStartingAt(parts[0].getLocation(), spec, columns);
        }
        Location end = list.getLocation();
        Token last = null;
        int line = end.endLine() - 1;
        if (line >= 0 && line < spec.length)
        {
            int item = MacroSpans.endingAt(spec[line], columns.of(line), end.endColumn() - 1);
            last = item < 0 ? null : spec[line][item];
        }
        if (defTok == null || last == null || !defTok.string.equals("=="))
        {
            decline(what, "its tokens do not match SANY's location");
            return;
        }
        for (Token b : bullets)
        {
            if (b == null || !b.string.equals("/\\"))
            {
                decline(what, "its bullets do not match SANY's location");
                return;
            }
        }
        defTok.braceRegion = new Region(defTok, bullets, last, what);
    }

    private static Token tokenStartingAt(Location loc, Token[][] spec, MacroSpans.SourceColumns columns)
    {
        int line = loc.beginLine() - 1;
        if (line < 0 || line >= spec.length)
        {
            return null;
        }
        int item = MacroSpans.startingAt(spec[line], columns.of(line), loc.beginColumn() - 1);
        return item < 0 ? null : spec[line][item];
    }

    /**
     * Run after FindAlignments: drops every region that cannot be typeset in
     * one box, and marks the bullets of the others. Also makes the first
     * bullet of a list that follows its {@code ==} an alignment point, so the
     * alignment run measures where the box starts.
     */
    static void finish(Token[][] spec)
    {
        List<Token> defines = new ArrayList<>();
        for (Token[] row : spec)
        {
            for (Token tok : row)
            {
                if (tok.braceRegion != null)
                {
                    defines.add(tok);
                }
            }
        }
        if (defines.isEmpty())
        {
            return;
        }
        Map<Token, int[]> where = new IdentityHashMap<>();
        for (int line = 0; line < spec.length; line++)
        {
            for (int item = 0; item < spec[line].length; item++)
            {
                where.put(spec[line][item], new int[] { line, item });
            }
        }
        for (Token define : defines)
        {
            Region r = define.braceRegion;
            String why = check(r, spec, where);
            if (why != null)
            {
                decline(r.what, why);
                define.braceRegion = null;
                continue;
            }
            for (Token b : r.bullets)
            {
                b.bracedBullet = true;
            }
            if (r.sameLine)
            {
                r.bullets[0].isAlignmentPoint = true;
            }
        }
    }

    /** Why {@code r} cannot be typeset with a brace, or null if it can; sets its positions. */
    private static String check(Region r, Token[][] spec, Map<Token, int[]> where)
    {
        if (Parameters.PrintLineNumbers)
        {
            return "line numbers are printed (-number)";
        }
        int[] def = where.get(r.define);
        int[] first = where.get(r.bullets[0]);
        int[] last = where.get(r.last);
        if (def == null || first == null || last == null)
        {
            return "its tokens were removed from the spec";
        }
        r.defLine = def[0];
        r.defItem = def[1];
        r.firstLine = first[0];
        r.firstItem = first[1];
        r.lastLine = last[0];
        r.lastItem = last[1];
        if (r.bullets.length < 2)
        {
            return "the list has only one conjunct";
        }
        r.sameLine = r.firstLine == r.defLine && r.firstItem == r.defItem + 1;
        boolean nextLine = r.firstLine == r.defLine + 1 && r.firstItem == 0
                           && r.defItem == spec[r.defLine].length - 1;
        if (!r.sameLine && !nextLine)
        {
            return "the first /\\ is neither right after the == nor first on the next line";
        }
        for (int item = 0; item < r.defItem; item++)
        {
            if (spec[r.defLine][item].type == Token.COMMENT)
            {
                return "a comment precedes the == on its line";
            }
        }
        if (r.lastLine - r.firstLine + 1 > MAX_LINES)
        {
            return "the list has more than " + MAX_LINES + " lines";
        }
        if (TokenizeSpec.hasPcal && r.lastLine >= TokenizeSpec.pcalStart.line
            && r.defLine <= TokenizeSpec.pcalEnd.line)
        {
            return "it overlaps a PlusCal algorithm";
        }
        int column = r.bullets[0].column;
        for (int line = r.firstLine; line <= r.lastLine; line++)
        {
            int from = line == r.firstLine ? r.firstItem : 0;
            for (int item = from; item < spec[line].length; item++)
            {
                Token tok = spec[line][item];
                boolean after = line == r.lastLine && item > r.lastItem;
                if (tok.type == Token.COMMENT)
                {
                    if (((CommentToken) tok).subtype != CommentToken.ONE_LINE)
                    {
                        return "line " + (line + 1) + " has a multi-line comment";
                    }
                } else if (after)
                {
                    return "something other than a comment follows it on line " + (line + 1);
                } else if (!TokenRenderer.handles(tok))
                {
                    return "line " + (line + 1) + " has a token that is not part of an expression";
                }
                if (tok.column < column)
                {
                    return "line " + (line + 1) + " has a token left of the /\\ column";
                }
            }
        }
        return null;
    }

    /**
     * For each line of {@code spec}, the distance from the margin of the
     * bullet column of the innermost braced list whose box holds the line as
     * one of its own lines, or NaN if there is none. The line of a list's
     * {@code ==} is a box line only if the first bullet is on the next line.
     * Called by LaTeXOutput after the dimensions are set; {@code totalIndent}
     * gives the distance from the margin of the token at a position.
     */
    static float[] origins(Token[][] spec, ToDoubleFunction<Position> totalIndent)
    {
        float[] origin = new float[spec.length];
        Arrays.fill(origin, Float.NaN);
        for (Region r : regions(spec))
        {
            float x = (float) totalIndent.applyAsDouble(new Position(r.firstLine, r.firstItem));
            for (int line = r.sameLine ? r.firstLine + 1 : r.firstLine; line <= r.lastLine; line++)
            {
                origin[line] = x;
            }
        }
        return origin;
    }

    /** For each line of {@code spec}, the number of braced lists that end on it. */
    static int[] closings(Token[][] spec)
    {
        int[] count = new int[spec.length];
        for (Region r : regions(spec))
        {
            count[r.lastLine]++;
        }
        return count;
    }

    /** The regions kept by {@link #finish}, outermost first. */
    private static List<Region> regions(Token[][] spec)
    {
        List<Region> result = new ArrayList<>();
        for (Token[] row : spec)
        {
            for (Token tok : row)
            {
                if (tok.braceRegion != null)
                {
                    result.add(tok.braceRegion);
                }
            }
        }
        return result;
    }

    private static void decline(String what, String why)
    {
        if (Parameters.Debug)
        {
            ToolIO.out.println("TLATeX brace for the " + what + " not used: " + why);
        }
    }
}
