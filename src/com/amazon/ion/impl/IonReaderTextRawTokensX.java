package com.amazon.ion.impl;

import com.amazon.ion.IonException;
import com.amazon.ion.IonType;
import com.amazon.ion.UnexpectedEofException;
import com.amazon.ion.impl.UnifiedSavePointManagerX.SavePoint;
import com.amazon.ion.util.IonTextUtils;
import java.io.IOException;

/**
 * Tokenizer for the Ion text parser in IonTextIterator. This
 * reads bytes and returns the interesting tokens it recognizes
 * or an error.  While, currently, this does UTF-8 decoding
 * as it goes that is unnecessary.  The main entry point is
 * lookahead(n) which gets the token type n tokens ahead (0
 * is the next token).  The tokens type, its starting offset
 * in the input stream and its ending offset in the input stream
 * are cached, so lookahead() can be called repeatedly with
 * little overhead.  This supports a 7 token lookahead and requires
 * a "recompile" to change this limit.  (this could be "fixed"
 * but seems unnecessary at this time - the limit is in
 * IonTextTokenizer._token_lookahead_size which is 1 larger than
 * the size of the lookahead allowed)  Tokens are consumed by
 * a call to consumeToken, or the helper consumeTokenAsString.
 * The informational interfaces - getValueStart(), getValueEnd()
 * getValueAsString() can be used to get the contents of the
 * value once the caller has decided how to use it.
 *
 *  This is a copy and paste from IonTextTokenize on the introduction of
 *  the new input abstraction IonInputStream as the source of characters
 *  and bytes for the reader.
 *
 *  This variation does NOT make local copies of the tokens.  It does
 *  start "marking" at the beginning of the token and the end.  The stream
 *  will buffer the input until the mark is released.
 *
 *  The result is that only the most recent token is available to the
 *  calling reader.
 *
 */
public class IonReaderTextRawTokensX
{
//////////////////////////////////////////////////////////////////////////debug
    static final boolean _debug = false;
//    static final boolean _token_counter_on = true;
//    int _tokens_read = 0;
//    int _tokens_consumed = 0;

    static final int   BASE64_EOF = 128; // still a byte, not -1, none of the low 6 bits on
    static final int[] BASE64_CHAR_TO_BIN = Base64Encoder.Base64EncodingCharToInt;
    static final int   BASE64_TERMINATOR_CHAR = Base64Encoder.Base64EncodingTerminator;

    private UnifiedInputStreamX  _stream = null;
    private int                 _token = -1;
    private boolean             _unfinished_token;  // are we at the beginning of this token (false == done with it)
    private long                _line_count;
    private long                _line_starting_position;
    private boolean             _line_count_has_cached = false;
    private long                _line_count_cached;
    private long                _line_offset_cached;
    private int                 _base64_prefetch_count; // number of base64 decoded bytes in the stack, used to decode base64
    private int                 _base64_prefetch_stack; // since this "stack" will only 0-2 bytes deep, we'll just shift them into an int
    private int                 _utf8_pretch_byte_count;
    private int                 _utf8_pretch_bytes;


    /**
     * IonTokenReader constructor requires a UnifiedInputStream
     * as the source of bytes/chars that serve as the basic input
     *
     * @param iis wrapped input stream
     */
    public IonReaderTextRawTokensX(UnifiedInputStreamX iis) {
        _stream = iis;
        _line_count = 1;
    }

    public int  getToken()      { return _token; }
    public long getLineNumber() { return _line_count; }
    public long getLineOffset() { return _stream.getPosition() - _line_starting_position; }

    protected String input_position() {
        String s = " at line "
                + getLineNumber()
                + " offset "
                + getLineOffset();
        return s;
    }
    public final boolean isUnfishedToken() { return  _unfinished_token; }

    public final void tokenIsFinished() {
        _unfinished_token = false;
        _utf8_pretch_byte_count = 0;
        _base64_prefetch_count = 0;
    }

    //
    //  character routines to fetch characters and
    //  handle look ahead and line counting and such
    //
    protected final int read_char() throws IOException
    {
        int c;

        c = _stream.read();
        // start if inline of _r.read() ...
        //if (_r._pos >= _r._limit) {
        //    c = _r.read_helper();
        //}
        //else {
        //    c = (_r._is_byte_data) ? (_r._bytes[_r._pos++] & 0xff) : _r._chars[_r._pos++];
        //}
        // end of in-lined _r.read()

        if (c == '\r' || c == '\n' || c == '\\') {  // the c == '\\' clause will cause us to eat ALL slash-newlines
            c = line_count(c);
        }
        return c;
    }

    protected final void unread_char(int c)
    {
        if (c == IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE || c == '\n') {
            c = line_count_unread(c);
        }
        _stream.unread(c);
    }
    private final int line_count_unread(int c) {
        assert( c == IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE || c == '\n' );
        if (c == IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE ) {
            _stream.unread('\n');
            c = '\\';
        }
        if (_line_count_has_cached) {
            _line_count = _line_count_cached;
            _line_starting_position = _line_offset_cached;
            _line_count_has_cached = false;
        }
        return c;
    }
    private final int line_count(int c) throws IOException
    {
        // check for the slash new line case (and we'l
        // consume both here it that's what we find
        if (c == '\\') {
            int c2 = _stream.read();
            if (c2 == '\r') {  // DOS <cr><lf>  or old Mac <cr>
                int c3 = _stream.read();
                if (c3 != '\n') {
                    unread_char(c3);
                }
                c = IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE;
            }
            else if (c2 == '\n') { // Unix and new Mac (also Unix) <lf>
                c = IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE;
            }
            else {
                // not a slash new line, so we'll just return the slash
                // leave it to be handled elsewhere
                unread_char(c2);
                return c;
            }
        }
        else if (c == '\r') {
            // convert '\r' or '\r\n' into '\n'
            int c2 = _stream.read();
            if (c2 != '\n') {
                unread_char(c2);
            }
            c = '\n';
        }
        // before we adjust the line count we save it so that
        // we can recover from a unread of a line terminator
        // note that we can only recover from a single line
        // terminator unread, but that should be enough.  We
        // only unread whitespace if it's a delimiter, and
        // then we only have to unread a single instance.
        _line_count_cached = _line_count;
        _line_offset_cached = _line_starting_position;
        _line_count_has_cached = true;

        // anything else (and that should only be either a new line
        // of IonTokenConsts.EMPTY_ESCAPE_SEQUENCE passed in) we will
        // return the char unchanged and line count

        _line_count++;
        _line_starting_position = _stream.getPosition();
        return c;
    }

    /**
     * peeks into the input stream to see if the next token
     * would be a double colon.  If indeed this is the case
     * it skips the two colons and returns true.  If not
     * it unreads the 1 or 2 real characters it read and
     * return false.
     * It always consumes any preceding whitespace.
     * @return true if the next token is a double colon, false otherwise
     * @throws IOException
     */
    public final boolean skipDoubleColon() throws IOException {
        int c = skip_over_whitespace();
        if (c != ':') {
            unread_char(c);
            return false;
        }
        c = read_char();
        if (c != ':') {
            unread_char(c);
            unread_char(':');
            return false;
        }
        return true;
    }
    public final boolean isSingleQuote() throws IOException {
        int c = read_char();
        unread_char(c);
        return (c == '\'');
    }

    /**
     * this first peeks forward to the next punctuation
     * character.  If that is a dot '.' it consumes
     * the dot and return true.  Otherwise is unreads
     * whatever character it encountered and returns
     * false.
     * It consuming any leading whitespace whether
     * a dot was consumed or some other non-whitespace
     * character was encountered (and not skipped).
     * @return true if a dot was skipped, false if the next non-whitespace character was not a dot (and was, therefore, not consumed)
     */
    public final boolean skipDot() throws IOException {
        int c = skip_over_whitespace();
        if (c != '.') {
            unread_char(c);
            return false;
        }
        return true;
    }

    /**
     * peeks into the input stream to see if we have an
     * unquoted symbol that resolves to one of the ion
     * types.  If it does it consumes the input and
     * returns the type keyword id.  If not is unreads
     * the non-whitespace characters and the dot, which
     * the input argument 'c' should be.
     */
    public final int peekNullTypeSymbol() throws IOException {
        int c = skip_over_whitespace();
        if (c != '.') {
            unread_char(c);
            return IonTokenConstsX.TOKEN_ERROR;
        }
        // we have a dot, start reading through the following non-whitespace
        // and we'll collect it so that we can unread it in the event
        // we don't actually see a type name
        int[] read_ahead = new int[IonTokenConstsX.TN_MAX_NAME_LENGTH + 1];
        int read_count = 0;
        int possible_names = IonTokenConstsX.KW_ALL_BITS;

        while (read_count < IonTokenConstsX.TN_MAX_NAME_LENGTH + 1) {
            c = read_char();
            read_ahead[read_count++] = c;
            int letter_idx = IonTokenConstsX.typeNameLetterIdx(c);
            if (letter_idx < 1) {
                if (IonTokenConstsX.isValidTerminatingCharForInf(c)) {
                    // it's not a letter we care about but it is
                    // a valid end of const, so maybe we have a keyword now
                    // we always exit the loop here since we look
                    // too far so any letter is invalid at pos 10
                    break;
                }
                return peekNullTypeSymbolUndo(read_ahead, read_count);
            }
            int mask = IonTokenConstsX.typeNamePossibilityMask(read_count - 1, letter_idx);
            possible_names &= mask;
            if (possible_names == 0) {
                // in this case it can't be a valid keyword since
                // it has identifier chars (letters) at 1 past the
                // last possible end (at least)
                return peekNullTypeSymbolUndo(read_ahead, read_count);
            }
        }
        // now lets get the keyword value from our bit mask
        // at this point we can fail since we may have hit
        // a valid terminator before we're done with all key
        // words.  We even have to check the length.
        // for example "in)" matches both letters to the
        // typename int and terminates validly - but isn't
        // long enough, but with length we have enough to be sure
        // with the actual type names we're using in 1.0
        int kw = IonTokenConstsX.typeNameKeyWordFromMask(possible_names, read_count-1);
        if (kw == IonTokenConstsX.KEYWORD_unrecognized) {
            peekNullTypeSymbolUndo(read_ahead, read_count);
        }
        else {
            // since we're accepting the rest we aren't unreading anything
            // else - but we still have to unread the character that stopped us
            unread_char(c);
        }
        return kw;
    }
    private final int peekNullTypeSymbolUndo(int[] read_ahead, int read_count)
    {
        int ii = read_count;
        while (ii > 0) {
            ii--;
            unread_char(read_ahead[ii]);
        }
        unread_char('.'); // because we don't need the dot either (but it's what got us here)
        String message = "invalid type name on a typed null value";

        error(message); // this throws so we won't actually return
        return IonTokenConstsX.KEYWORD_unrecognized;
    }

    /**
     * peeks into the input stream to see what non-whitespace
     * character is coming up.  If it is a double quote or
     * a triple quote this returns true as either distinguished
     * the contents of a lob as distinctly a clob.  Otherwise
     * it returns false.
     * In either case it unreads whatever non-whitespace it read
     * to decide.
     * @return true if the next token is a double or triple quote, false otherwise
     * @throws IOException
     */
    public final int peekLobStartPunctuation() throws IOException
    {
        int c = skip_over_lob_whitespace();
        if (c == '"') {
            //unread_char(c);
            return IonTokenConstsX.TOKEN_STRING_DOUBLE_QUOTE;
        }
        if (c != '\'') {
            unread_char(c);
            return IonTokenConstsX.TOKEN_ERROR;
        }
        c = read_char();
        if (c != '\'') {
            unread_char(c);
            unread_char('\'');
            return IonTokenConstsX.TOKEN_ERROR;
        }
        c = read_char();
        if (c != '\'') {
            unread_char(c);
            unread_char('\'');
            unread_char('\'');
            return IonTokenConstsX.TOKEN_ERROR;
        }
        return IonTokenConstsX.TOKEN_STRING_TRIPLE_QUOTE;
    }

    protected final void skip_lob_close_punctuation(int lobToken) throws IOException {
        switch (lobToken) {
        case IonTokenConstsX.TOKEN_STRING_DOUBLE_QUOTE:
        case IonTokenConstsX.TOKEN_STRING_TRIPLE_QUOTE:
            break;
        default:
            return;
        }

        int c = skip_over_whitespace();
        if (c == '}') {
            c = read_char();
            if (c == '}') {
                return;
            }
            unread_char(c);
            c = '}';
        }
        unread_char(c);
        error("invalid closing puctuation for CLOB");
    }


    protected final void finish_token(SavePoint sp) throws IOException
    {
        if (_unfinished_token) {
            int c = skip_to_end(sp);
            unread_char(c);
            _unfinished_token = false;
        }
    }

    private final int skip_to_end(SavePoint sp)  throws IOException
    {
        int c;

        switch (_token) {
        case IonTokenConstsX.TOKEN_UNKNOWN_NUMERIC:
            c = skip_over_number(sp);
            break;
        case IonTokenConstsX.TOKEN_INT:
            c = skip_over_int(sp);
            break;
        case IonTokenConstsX.TOKEN_HEX:
            skip_over_hex(sp);
            c = skip_over_whitespace();
            break;
        case IonTokenConstsX.TOKEN_DECIMAL:
            c = skip_over_decimal(sp);
            break;
        case IonTokenConstsX.TOKEN_FLOAT:
            c = skip_over_float(sp);
            break;
        case IonTokenConstsX.TOKEN_TIMESTAMP:
            c = skip_over_timestamp(sp);
            break;
        case IonTokenConstsX.TOKEN_SYMBOL_BASIC:
            skip_over_symbol(sp);
            c = skip_over_whitespace();
            break;
        case IonTokenConstsX.TOKEN_SYMBOL_QUOTED:
            assert(!is_2_single_quotes_helper());
            skip_single_quoted_string(sp);
            c = skip_over_whitespace();
            break;
        case IonTokenConstsX.TOKEN_SYMBOL_OPERATOR:
            skip_over_symbol_operator(sp);
            c = skip_over_whitespace();
            break;
        case IonTokenConstsX.TOKEN_STRING_DOUBLE_QUOTE:
            skip_double_quoted_string_helper();
            c = skip_over_whitespace();
            break;
        case IonTokenConstsX.TOKEN_STRING_TRIPLE_QUOTE:
            skip_triple_quoted_string(sp);
            c = skip_over_whitespace();
            break;

        case IonTokenConstsX.TOKEN_OPEN_DOUBLE_BRACE:
            // works just like a pair of nested structs
            // since "skip_over" doesn't care about formal
            // syntax (like requiring field names);
            skip_over_blob(sp);
            c = read_char();
            break;
        case IonTokenConstsX.TOKEN_OPEN_BRACE:
            assert( sp == null ); // you can't save point a scanned struct (right now anyway)
            skip_over_struct();
            c = read_char();
            break;
        case IonTokenConstsX.TOKEN_OPEN_PAREN:
            skip_over_sexp(); // you can't save point a scanned sexp (right now anyway)
            c = read_char();
            break;
        case IonTokenConstsX.TOKEN_OPEN_SQUARE:
            skip_over_list();  // you can't save point a scanned list (right now anyway)
            c = read_char();
            break;
        case IonTokenConstsX.TOKEN_DOT:
        case IonTokenConstsX.TOKEN_COMMA:
        case IonTokenConstsX.TOKEN_COLON:
        case IonTokenConstsX.TOKEN_DOUBLE_COLON:
        case IonTokenConstsX.TOKEN_CLOSE_PAREN:
        case IonTokenConstsX.TOKEN_CLOSE_BRACE:
        case IonTokenConstsX.TOKEN_CLOSE_SQUARE:
        case IonTokenConstsX.TOKEN_CLOSE_DOUBLE_BRACE:
        case IonTokenConstsX.TOKEN_ERROR:
        case IonTokenConstsX.TOKEN_EOF:
        default:
            c = -1; // makes eclipse happy
            error("token "+IonTokenConstsX.getTokenName(_token)+" unexpectedly encounterd as \"unfinished\"");
            break;
        }
        if (IonTokenConstsX.isWhitespace(c)) {
            c = skip_over_whitespace();
        }
        _unfinished_token = false;
        return c;
    }

    public final int nextToken() throws IOException
    {
        int t = -1;
        int c, c2;

        if (_unfinished_token) {
            c = skip_to_end(null);
        }
        else {
            c = skip_over_whitespace();
        }
        _unfinished_token = true;

        switch (c) {
        case -1:
            return next_token_finish(IonTokenConstsX.TOKEN_EOF, true);
        case '/':
            unread_char(c);
            return next_token_finish(IonTokenConstsX.TOKEN_SYMBOL_OPERATOR, true);
        case ':':
            c2 = read_char();
            if (c2 != ':') {
                unread_char(c2);
                return next_token_finish(IonTokenConstsX.TOKEN_COLON, true);
            }
            return next_token_finish(IonTokenConstsX.TOKEN_DOUBLE_COLON, true);
        case '{':
            c2 = read_char();
            if (c2 != '{') {
                unread_char(c2);
                return next_token_finish(IonTokenConstsX.TOKEN_OPEN_BRACE, true);
            }
            return next_token_finish(IonTokenConstsX.TOKEN_OPEN_DOUBLE_BRACE, true);
        case '}':
            // detection of double closing braces is done
            // in the parser in the blob and clob handling
            // state - it's otherwise ambiguous with closing
            // two structs together. see tryForDoubleBrace() below
            return next_token_finish(IonTokenConstsX.TOKEN_CLOSE_BRACE, false);
        case '[':
            return next_token_finish(IonTokenConstsX.TOKEN_OPEN_SQUARE, false);
        case ']':
            return next_token_finish(IonTokenConstsX.TOKEN_CLOSE_SQUARE, false);
        case '(':
            return next_token_finish(IonTokenConstsX.TOKEN_OPEN_PAREN, false);
        case ')':
            return next_token_finish(IonTokenConstsX.TOKEN_CLOSE_PAREN, false);
        case ',':
            return next_token_finish(IonTokenConstsX.TOKEN_COMMA, false);
        case '.':
            c2 = read_char();
            unread_char(c2);
            if (IonTokenConstsX.isValidExtendedSymbolCharacter(c2)) {
                unread_char('.');
                return next_token_finish(IonTokenConstsX.TOKEN_SYMBOL_OPERATOR, true);
            }
            return next_token_finish(IonTokenConstsX.TOKEN_DOT, false);
        case '\'':
            if (is_2_single_quotes_helper()) {
                return next_token_finish(IonTokenConstsX.TOKEN_STRING_TRIPLE_QUOTE, true);
            }
            // unread_char(c);
            return next_token_finish(IonTokenConstsX.TOKEN_SYMBOL_QUOTED, true);
        case '+':
            if (peek_inf_helper(c)) // this will consume the inf if it succeeds
            {
                return next_token_finish(IonTokenConstsX.TOKEN_FLOAT_INF, false);
            }
            unread_char(c);
            return next_token_finish(IonTokenConstsX.TOKEN_SYMBOL_OPERATOR, true);
        case '#':
        case '<': case '>': case '*': case '=': case '^': case '&': case '|':
        case '~': case ';': case '!': case '?': case '@': case '%': case '`':
            unread_char(c);
            return next_token_finish(IonTokenConstsX.TOKEN_SYMBOL_OPERATOR, true);
        case '"':
            return next_token_finish(IonTokenConstsX.TOKEN_STRING_DOUBLE_QUOTE, true);
        case 'i':
            if (peek_inf_helper(c)) // this will consume the inf if it succeeds
            {
                return next_token_finish(IonTokenConstsX.TOKEN_FLOAT_INF, false);
            }
            unread_char(c);
            return next_token_finish(IonTokenConstsX.TOKEN_SYMBOL_BASIC, true);
        case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
        case 'g': case 'h': case 'j':           case 'k': case 'l':
        case 'm': case 'n': case 'o': case 'p': case 'q': case 'r':
        case 's': case 't': case 'u': case 'v': case 'w': case 'x':
        case 'y': case 'z':
        case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
        case 'G': case 'H': case 'J': case 'I': case 'K': case 'L':
        case 'M': case 'N': case 'O': case 'P': case 'Q': case 'R':
        case 'S': case 'T': case 'U': case 'V': case 'W': case 'X':
        case 'Y': case 'Z':
        case '$': case '_':
            unread_char(c);
            return next_token_finish(IonTokenConstsX.TOKEN_SYMBOL_BASIC, true);
        case '0': case '1': case '2': case '3': case '4':
        case '5': case '6': case '7': case '8': case '9':
            t = scan_for_numeric_type(c);
            unread_char(c);
            return next_token_finish(t, true);
        case '-':
            // see if we have a number or what might be an extended symbol
            c2 = read_char();
            unread_char(c2);
            if (Character.isDigit(c2)) {
                t = scan_negative_for_numeric_type(c);
                unread_char(c);
                return next_token_finish(t, true);
            }
            else if (peek_inf_helper(c)) // this will consume the inf if it succeeds
            {
                return next_token_finish(IonTokenConstsX.TOKEN_FLOAT_MINUS_INF, false);
            }
            else {
                unread_char(c);
                return next_token_finish(IonTokenConstsX.TOKEN_SYMBOL_OPERATOR, true);
            }
        default:
            bad_token_start(c); // throws
        }
        throw new IonException("invalid state: next token switch shouldn't exit");
    }
    private int next_token_finish(int token, boolean content_is_waiting) {
        _token = token;
        _unfinished_token = content_is_waiting;
        return _token;
    }

    private final int skip_over_whitespace() throws IOException
    {
        int c, c2;

        for (;;) {
            c = read_char();
            switch (c) {
            case -1:
                unread_char(c);
                return c;
            case IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE:
            case ' ':
            case '\t':
            case '\r':
            case '\n': // new line normalization and counting is handled in read_char
                break;
            case '/':
                switch(c2 = read_char()) {
                case '/':
                    skip_single_line_comment();
                    break;
                case '*':
                    skip_block_comment();
                    break;
                default:
                    unread_char(c2);
                    return c;
                }
                break;
            default:
                return c;
            }
        }
    }

    private final int skip_over_lob_whitespace() throws IOException
    {
        int c;

        for (;;) {
            c = read_char();
            switch (c) {
            case -1:
                unread_char(c);
                return c;
            case ' ':
            case '\t':
            case '\r':
            case '\n': // new line normalization is handled in read_char
                break;
            default:
                return c;
            }
        }
    }

    private final void skip_single_line_comment() throws IOException
    {
        for (;;) {
            int c = read_char();
            switch (c) {
            case '\n': return;
            case -1:   return;
            default:   break;
            }
        }
    }

    private final void skip_block_comment() throws IOException
    {
        int c;
        for (;;) {
            c = this.read_char();
            switch (c) {
                case '*':
                    // read back to back '*'s until you hit a '/' and terminate the comment
                    // or you see a non-'*'; in which case you go back to the outer loop.
                    // this just avoids the read-unread pattern on every '*' in a line of '*'
                    // commonly found at the top and bottom of block comments
                    for (;;) {
                        c = this.read_char();
                        if (c == '/') return;
                        if (c != '*') break;
                    }
                    break;
                case -1:
                    bad_token_start(c);
                default:
                    break;
            }
        }
    }

    /**
     * this peeks ahead to see if the next two characters
     * are single quotes. this would finish off a triple
     * quote when the first quote has been read.
     * if it succeeds it "consumes" the two quotes
     * it reads.
     * if it fails it unreads
     * @return true if the next two characters are single quotes
     * @throws IOException
     */
    private final boolean is_2_single_quotes_helper() throws IOException
    {
        int c = read_char();
        if (c != '\'') {
            unread_char(c);
            return false;
        }
        c = read_char();
        if (c != '\'') {
            unread_char(c);
            unread_char('\'');
            return false;
        }
        return true;
    }
    protected final boolean is_triple_quote(int c) throws IOException
    {
        if (c != '\'') {
            return false;
        }
        c = read_char();
        if (c != '\'') {
            unread_char(c);
            return false;
        }
        c = read_char();
        if (c != '\'') {
            unread_char(c);
            unread_char('\'');
            return false;
        }
        return true;
    }
    private final boolean peek_inf_helper(int c) throws IOException
    {
        if (c != '+' && c != '-') return false;
        c = read_char();
        if (c == 'i') {
            c = read_char();
            if (c == 'n') {
                c = read_char();
                if (c == 'f') {
                    c = read_char();
                    if (is_value_terminating_character(c)) {
                        unread_char(c);
                        return true;
                    }
                    unread_char(c);
                    c = 'f';
                }
                unread_char(c);
                c = 'n';
            }
            unread_char(c);
            c = 'i';
        }
        unread_char(c);
        return false;
    }

    /**
     * we encountered a character that starts a number,
     * a digit or a dash (minus).  Now we'll scan a little
     * ways ahead to spot some of the numeric types.
     *
     * this only looks far enough (2 or 6 chars) to identify
     * hex and timestamps
     * it might encounter a decimal or a 'd' or an 'e' and
     * decide this token is float or decimal (or int if we
     * hit a non-numeric char) but it may return TOKEN_UNKNOWN_NUMERIC;
     *
     * if will unread everything it's read, and the character
     * passed in as the first digit encountered
     *
     * @param c first char of number read by caller
     * @return numeric token type
     * @throws IOException
     */
    private final int scan_for_numeric_type(int c1) throws IOException
    {
        int   t = IonTokenConstsX.TOKEN_UNKNOWN_NUMERIC;
        int[] read_chars = new int[6];
        int   read_char_count = 0;
        int   c;

        assert(IonTokenConstsX.isDigit(c1));

        // the caller needs to unread this if they want to: read_chars[read_char_count++] = c1;

        c = read_char();
        read_chars[read_char_count++] = c;

        if (c1 == '0') {
            // check for hex
            switch(c) {
            case 'x':
            case 'X':
                t = IonTokenConstsX.TOKEN_HEX;
                break;
            case 'd':
            case 'D':
                t = IonTokenConstsX.TOKEN_DECIMAL;
                break;
            case 'e':
            case 'E':
                t = IonTokenConstsX.TOKEN_FLOAT;
                break;
            case '.':  // the decimal might have an 'e' somewhere down the line so we don't really know the type here
            default:
                if (is_value_terminating_character(c)) {
                    t = IonTokenConstsX.TOKEN_INT;
                }
                break;
            }
        }
        if (t == IonTokenConstsX.TOKEN_UNKNOWN_NUMERIC) { // oh for goto :(
            if (IonTokenConstsX.isDigit(c)) { // 2nd digit
                // it might be a timestamp if we have 4 digits, a dash, and a digit
                c = read_char();
                read_chars[read_char_count++] = c;
                if (IonTokenConstsX.isDigit(c)) { // digit 3
                    c = read_char();
                    read_chars[read_char_count++] = c;
                    if (IonTokenConstsX.isDigit(c)) { // last digit of possible year
                        c = read_char();
                        read_chars[read_char_count++] = c;
                        if (c == '-' || c =='T') { // we have dddd- or ddddT looks like a timestamp (or invalid input)
                            t = IonTokenConstsX.TOKEN_TIMESTAMP;
                        }
                    }
                }
            }
        }

        // unread whatever we read, including the passed in char
        do {
            read_char_count--;
            c = read_chars[read_char_count];
            unread_char(c);
        } while (read_char_count > 0);

        return t;
    }

    private final boolean is_value_terminating_character(int c) throws IOException
    {
        boolean isTerminator;

        if (c == '/') {
            // this is terminating only if it starts a comment of some sort
            c = read_char();
            unread_char(c);  // we never "keep" this character
            isTerminator = (c == '/' || c == '*');
        }
        else {
            isTerminator = IonTextUtils.isNumericStop(c);
        }

        return isTerminator;
    }

    /**
     * variant of scan_numeric_type where the passed in
     * start character was preceded by a minus sign.
     * this will also unread the minus sign.
     *
     * @param c first char of number read by caller
     * @return numeric token type
     * @throws IOException
     */
    private final int scan_negative_for_numeric_type(int c) throws IOException
    {
        assert(c == '-');
        c = read_char();
        int t = scan_for_numeric_type(c);
        if (t == IonTokenConstsX.TOKEN_TIMESTAMP) {
            bad_token(c);
        }
        unread_char(c); // and the caller need to unread the '-'
        return t;
    }

    // TODO: need new test cases since stepping out over values
    //       (or next-ing over them) is quite different from
    //       fully parsing them.  It is generally more lenient
    //       and that may not be best.

    /**
     * this is used to load a previously marked set of bytes
     * into the StringBuilder without escaping.  It expects
     * the caller to have set a save point so that the EOF
     * will stop us at the right time.
     * This does handle UTF8 decoding and surrogate encoding
     * as the bytes are transfered.
     */
    protected void load_raw_characters(StringBuilder sb) throws IOException
    {
        int c = read_char();
        for (;;) {
            c = read_char();
            switch (c) {
            case IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE:
                continue;
            case -1:
                return;
            default:
                if (!IonTokenConstsX.is7bitValue(c)) {
                    c = read_utf8_sequence(c);
                }
            }
            if (IonUTF8.needsSurrogateEncoding(c)) {
                sb.append(IonUTF8.highSurrogate(c));
                c = IonUTF8.lowSurrogate(c);
            }
            sb.append((char)c);
        }
    }

    protected void skip_over_struct() throws IOException
    {
        skip_over_container('}');
    }
    protected void skip_over_list() throws IOException
    {
        skip_over_container(']');
    }
    protected void skip_over_sexp() throws IOException
    {
        skip_over_container(')');
    }
    private void skip_over_container(int terminator) throws IOException
    {
        assert( terminator == '}' || terminator == ']' || terminator == ')' );
        int c;

        for (;;) {
            c = skip_over_whitespace();
            switch (c) {
            case -1:
                unexpected_eof();
            case '}':
            case ']':
            case ')':
                if (c == terminator) { // no point is checking this on every char
                    return;
                }
                break;
            case '"':
                skip_double_quoted_string_helper();
                break;
            case '\'':
                if (is_2_single_quotes_helper()) {
                    skip_triple_quoted_string(null);
                }
                else {
                    skip_single_quoted_string(null);
                }
                break;
            case '(':
                skip_over_container(')');
                break;
            case '[':
                skip_over_container(']');
                break;
            case '{':
                // this consumes lobs as well since the double
                // braces count correctly and the contents
                // of either clobs or blobs will be just content
                c = read_char();
                if (c == '{') {
                    // 2nd '{' - it's a lob of some sort - let's find out what sort
                    c = skip_over_lob_whitespace();
                    if (c == '"') {
                        // clob, double quoted
                        skip_double_quoted_string(null);
                    }
                    else if (c == '\'') {
                     // clob, triple quoted - or error
                        if (!is_2_single_quotes_helper()) {
                            error("invalid single quote in lob content");
                        }
                        skip_triple_quoted_string(null);
                    }
                    else if (c == '}') {
                        // blob, empty (closed immediately) - or error
                        c = read_char();
                        if (c != '}') {
                            error("missing blob close");
                        }
                    }
                }
                else if (c == '}') {
                    // do nothing, we just opened and closed an empty struct
                    // move on, there's nothing to see here ...
                }
                else {
                    skip_over_container('}');
                }
                break;
            default:
                break;
            }
        }
    }

    private int skip_over_number(SavePoint sp) throws IOException
    {
        int c = read_char();

        // first consume any leading 0 to get it out of the way
        if (c == '-') {
            c = read_char();
        }
        // could be a long int, a decimal, a float
        // it cannot be a hex or a valid timestamp
        // so scan digits - if decimal can more digits
        // if d or e eat possible sign
        // scan to end of digits
        c = skip_over_digits(c);
        if (c == '.') {
            c = read_char();
            c = skip_over_digits(c);
        }
        if (c == 'd' || c == 'D' || c == 'e' || c == 'E') {
            c = read_char();
            if (c == '-' || c == '+') {
                c = read_char();
            }
            c = skip_over_digits(c);
        }
        if (sp != null) {
            sp.markEnd(-1);
         }
        return c;
    }
    private int skip_over_int(SavePoint sp) throws IOException
    {
        int c = read_char();
        if (c == '-') {
            c = read_char();
        }
        c = skip_over_digits(c);
        if (sp != null) {
            sp.markEnd(-1);
        }
        return c;
    }
    private int skip_over_digits(int c) throws IOException
    {
        while (IonTokenConstsX.isDigit(c)) {
            c = read_char();
        }
        return c;
    }
    private int skip_over_hex(SavePoint sp) throws IOException
    {
        int c;

        // we probably shouldn't bother to unread the 0x or -0x header
        c = read_char();
        if (c == '-') {
            c = read_char();
        }
        assert(c == '0');
        c = read_char();
        assert(c == 'x' || c == 'X');

        do {
            c = read_char();
        } while (IonTokenConstsX.isHexDigit(c));

        if (sp != null) {
            sp.markEnd(-1);
        }

        return c;
    }
    private int skip_over_decimal(SavePoint sp) throws IOException
    {
        int c = skip_over_number(sp);
        return c;
    }
    private int skip_over_float(SavePoint sp) throws IOException
    {
        int c = skip_over_number(sp);
        return c;
    }
    private int skip_over_timestamp(SavePoint sp) throws IOException
    {
        int c = read_char();

        // we know we have dddd- or ddddT we don't know what follows
        // is should be dddd-mm
        skip_timestamp_past_digits(4);
        if (c == 'T') {
            // yyyyT
            if (sp != null) {
                sp.markEnd(0);
             }
            return skip_over_whitespace(); // prefetch
        }
        if (c != '-') {
            error("invalid timestamp encountered");
        }
        // yyyy-mmT
        // yyyy-mm-ddT
        // yyyy-mm-ddT+hh:mm
        // yyyy-mm-ddThh+hh:mm
        // yyyy-mm-ddThh:mm+hh:mm
        // yyyy-mm-ddThh:mm:ss+hh:mm
        // yyyy-mm-ddThh:mm:ss.dddd+hh:mm
        // yyyy-mm-ddThhZ
        // yyyy-mm-ddThh:mmZ
        // yyyy-mm-ddThh:mm:ssZ
        // yyyy-mm-ddThh:mm:ss.ddddZ
        // yyyy-.
        c = skip_timestamp_past_digits(2);
        if (c == 'T') {
            // yyyy-mmT
            if (sp != null) {
                sp.markEnd(0);
             }
            return skip_over_whitespace(); // prefetch
        }
        skip_timestamp_validate(c, '-');
        c = skip_timestamp_past_digits(2);
        c = read_char();
        if (!IonTokenConstsX.isDigit(c)) {
            // yyyy-mm-ddT?
            return skip_timestamp_offset(c, sp);
        }
        c = skip_timestamp_past_digits(1);
        c = read_char();
        if (c != ':') {
            // yyyy-mm-ddThh?
            return skip_timestamp_offset(c, sp);
        }
        c = skip_timestamp_past_digits(2);
        if (c != ':') {
            // yyyy-mm-ddThh:mm?
            return skip_timestamp_offset(c, sp);
        }
        c = skip_timestamp_past_digits(2);
        if (c != '.') {
            // yyyy-mm-ddThh:mm:ss?
            return skip_timestamp_offset(c, sp);
        }
        if (IonTokenConstsX.isDigit(c)) {
            c = skip_over_digits(c);
        }
        // yyyy-mm-ddThh:mm:ss.ddd?

        return skip_timestamp_offset(c, sp);
    }
    private int skip_timestamp_offset(int c, SavePoint sp) throws IOException
    {
        if (c == '-' || c == '+') {
            c = skip_timestamp_past_digits(2);
            if (c == ':') {
                c = skip_timestamp_past_digits(2);
            }
        }
        else if (c == 'Z' || c == 'z') {
            c = read_char();
        }
        if (sp != null) {
            sp.markEnd(-1);
        }
        return c;
    }
    private final void skip_timestamp_validate(int c, int expected) {
        if (c != expected) {
            error("invalid character '"+(char)c+"' encountered in timestamp (when '"+(char)expected+"' was expected");
        }
    }
    private final int skip_timestamp_past_digits(int len) throws IOException
    {
        int c;

        while (len > 0) {
            c = read_char();
            if (!IonTokenConstsX.isDigit(c)) {
                error("invalid character '"+(char)c+"' encountered in timestamp");
            }
            len--;
        }
        c = read_char();
        return c;
    }
    protected IonType load_number(StringBuilder sb) throws IOException
    {
        boolean has_sign = false;
        long    start_pos;
        int     t, c;

        // this reads int, float, decimal and timestamp strings
        // anything staring with a +, a - or a digit
        //case '0': case '1': case '2': case '3': case '4':
        //case '5': case '6': case '7': case '8': case '9':
        //case '-': case '+':

        start_pos = _stream.getPosition();
        c = read_char();
        has_sign = ((c == '-') || (c == '+'));
        if (has_sign) {
            // if there is a sign character, we just consume it
            // here and get whatever is next in line
            sb.append((char)c);
            c = read_char();
        }

        // first leading digit - to look for hex and
        // to make sure that there is at least 1 digit (or
        // this isn't really a number
        if (!IonTokenConstsX.isDigit(c)) {
            // if it's not a digit, this isn't a number
            // the only non-digit it could have been was a
            // sign character, and we'll have read past that
            // by now
            bad_token(c);
        }

        // the first digit is a special case
        boolean starts_with_zero = (c == '0');
        if (starts_with_zero) {
            // if it's a leading 0 check for a hex value
            int c2 = read_char();
            if (c2 == 'x' || c2 == 'X') {
                sb.append((char)c);
                c = load_hex_value(sb, has_sign, c2);
                return load_finish_number(c, IonTokenConstsX.TOKEN_HEX);
            }
            // not a next value, back up and try again
            unread_char(c2);
        }

        // leading digits
        c = load_digits(sb, c);

        if (c == '-' || c == 'T') {
            // this better be a timestamp and it starts with a 4 digit
            // year followed by a dash and no leading sign
            if (has_sign) bad_token(c);
            long pos = _stream.getPosition();
            long len = pos - start_pos;
            if (len != 5) bad_token(c);
            IonType tt = load_timestamp(sb, c);
            return tt;
        }

        // numbers aren't allowed to have excess leading '0'
        // (zeros) so we have to check here
        if (starts_with_zero) {
            long pos = _stream.getPosition();
            long len = pos - start_pos;
            if (len == 2) {
                if (has_sign) bad_token(c);
            }
            else if (len == 3) {
                if (!has_sign) bad_token(c);
            }
            else {
                bad_token(c);
            }
        }
        if (c == '.') {
            // so if it's a float of some sort
            // mark it as at least a DECIMAL
            // and read the "fraction" digits
            sb.append((char)c);
            c = read_char();
            c = load_digits(sb, c);
            t = IonTokenConstsX.TOKEN_DECIMAL;
        }
        else {
            t = IonTokenConstsX.TOKEN_INT;
        }

        // see if we have an exponential as in 2d+3
        if (c == 'e' || c == 'E') {
            t = IonTokenConstsX.TOKEN_FLOAT;
            sb.append((char)c);
            c = load_exponent(sb);  // the unused lookahead char
        }
        else if (c == 'd' || c == 'D') {
            t = IonTokenConstsX.TOKEN_DECIMAL;
            sb.append((char)c);
            c = load_exponent(sb);
        }
        return load_finish_number(c, t);
    }
    private final IonType load_finish_number(int c, int t)
    {
        // all forms of numeric need to stop someplace rational
        if (!IonTextUtils.isNumericStop(c)) bad_token(c);

        // we read off the end of the number, so put back
        // what we don't want, but what ever we have is an int
        unread_char(c);
        IonType it = IonTokenConstsX.ion_type_of_scalar(t);
        return it;
    }
    // this returns the lookahead character it didn't use so the caller
    // can unread it
    private final int load_exponent(StringBuilder sb) throws IOException
    {
        int c = read_char();
        if (c == '-' || c == '+') {
            sb.append((char)c);
            c = read_char();
        }
        c = load_digits(sb, c);

        if (c == '.') {
            sb.append((char)c);
            c = read_char();
            c = load_digits(sb, c);
        }
        return c;
    }
    private final int load_digits(StringBuilder sb, int c) throws IOException
    {
        while (Character.isDigit(c)) {
            sb.append((char)c);
            c = read_char();
        }
        return c;
    }
    private final void load_fixed_digits(StringBuilder sb, int len) throws IOException
    {
        int c;

        switch (len) {
        default:
            while (len > 4) {
                c = read_char();
                if (!Character.isDigit(c)) bad_token(c);
                sb.append((char)c);
                len--;
            }
            // fall through
        case 4:
            c = read_char();
            if (!Character.isDigit(c)) bad_token(c);
            sb.append((char)c);
            // fall through
        case 3:
            c = read_char();
            if (!Character.isDigit(c)) bad_token(c);
            sb.append((char)c);
            // fall through
        case 2:
            c = read_char();
            if (!Character.isDigit(c)) bad_token(c);
            sb.append((char)c);
            // fall through
        case 1:
            c = read_char();
            if (!Character.isDigit(c)) bad_token(c);
            sb.append((char)c);
            break;
        }

        return;
    }
    private final IonType load_timestamp(StringBuilder sb, int c) throws IOException
    {
        // we read the year in our caller, we should only be
        // here is we read 4 digits and then a dash or a 'T'
        assert (c == '-' || c == 'T');

        sb.append((char)c);

        // if it's 'T' we done: yyyyT
        if (c == 'T') {
            c = read_char(); // because we'll unread it before we return
            return load_finish_number(c, IonTokenConstsX.TOKEN_TIMESTAMP);
        }

        // read month
        load_fixed_digits(sb, 2);

        c = read_char();
        if (c == 'T') {
            sb.append((char)c);
            c = read_char(); // because we'll unread it before we return
            return load_finish_number(c, IonTokenConstsX.TOKEN_TIMESTAMP);
        }
        if (c != '-') bad_token(c);

        // read day
        sb.append((char)c);
        load_fixed_digits(sb, 2);

        // look for the 'T', otherwise we're done (and happy about it)
        c = read_char();
        if (c != 'T') {
            return load_finish_number(c, IonTokenConstsX.TOKEN_TIMESTAMP);
        }

        // so either we're done or we must at least hours and minutes
        // hour
        sb.append((char)c);
        c = read_char();
        if (!Character.isDigit(c)) {
            return load_finish_number(c, IonTokenConstsX.TOKEN_TIMESTAMP);
        }
        sb.append((char)c);
        load_fixed_digits(sb,1); // we already read the first digit
        c = read_char();
        if (c != ':') bad_token(c);

        // minutes
        sb.append((char)c);
        load_fixed_digits(sb, 2);
        c = read_char();
        if (c == ':') {
            // seconds are optional
            // and first we'll have the whole seconds
            sb.append((char)c);
            load_fixed_digits(sb, 2);
            c = read_char();
            if (c == '.') {
                sb.append((char)c);
                c = read_char();
                c = load_digits(sb,c);
            }
        }

        // since we have a time, we have to have a timezone of some sort
        // the timezone offset starts with a '+' '-' 'Z' or 'z'
        if (c == 'z' || c == 'Z') {
            sb.append((char)c);
            c = read_char(); // read ahead since we'll check for a valid ending in a bit
        }
        else if (c == '+' || c == '-') {
            // then ... hours of time offset
            sb.append((char)c);
            load_fixed_digits(sb, 2);
            c = read_char();
            if (c != ':') {
                // those hours need their minutes if it wasn't a 'z'
                // (above) then it has to be a +/- hours { : minutes }
                bad_token(c);
            }
            // and finally the *not* optional minutes of time offset
            sb.append((char)c);
            load_fixed_digits(sb, 2);
            c = read_char();
        }
        else {
            // some sort of offset is required with a time value
            // if it wasn't a 'z' (above) then it has to be a +/- hours { : minutes }
            bad_token(c);
        }
        return load_finish_number(c, IonTokenConstsX.TOKEN_TIMESTAMP);
    }
    private final int load_hex_value(StringBuilder sb, boolean has_sign, int c2) throws IOException
    {
        int c = read_char();

        assert(c2 == 'x' || c2 == 'X');
        sb.append((char)c2);

        // read the hex digits
        do {
            sb.append((char)c);
            c = read_char();
        } while(IonTokenConstsX.isHexDigit(c));

        // we have to do this later because of the optional
        // sign _start += has_sign ? 1 : 2; //  skip over the
        // "0x" (they're ASCII so 2 is correct)
        return c;
    }

    private final void skip_over_symbol(SavePoint sp) throws IOException
    {
        int c = read_char();

        while(IonTokenConstsX.isValidSymbolCharacter(c)) {
            c = read_char();
        }

        unread_char(c);
        if (sp != null) {
            sp.markEnd(0);
         }
        return;
    }
    protected void load_symbol(StringBuilder sb) throws IOException
    {
        int c = read_char();
        if (c == '$') {
            // since this *might* be a '$'<number> symbol it get special treatment
            sb.append((char)c);
            load_symbol_as_possible_id(sb);
        }
        else {
            // as long as this isn't a '$'<number> symbol just load it
            while(IonTokenConstsX.isValidSymbolCharacter(c)) {
                sb.append((char)c);
                c = read_char();
            }
            unread_char(c);
        }
        return;
    }
    private void load_symbol_as_possible_id(StringBuilder sb) throws IOException
    {
        // note the first character was a '$'
        assert(sb.length() == 1 && sb.charAt(0) == '$');

        boolean all_numeric = true;

        int c = read_char();
        while(IonTokenConstsX.isValidSymbolCharacter(c)) {
            sb.append((char)c);
            if (!IonTokenConstsX.isDigit(c)) {
                all_numeric = false;
            }
            c = read_char();
        }
        unread_char(c);

        if (all_numeric) {
            // here we have to normalize (that is remove
            // any leading '0's) the int value
            // this should be an unusual case so the cost
            // here isn't a major issue
            while(sb.length() > 2) {
                c = sb.charAt(1); // right after the '$'
                if (c != '0') {
                    break;
                }
                sb.deleteCharAt(1);
            }
        }

        return;
    }

    private void skip_over_symbol_operator(SavePoint sp) throws IOException
    {
        //int token_type;
        int c = read_char();

        // lookahead for +inf and -inf
        if (peek_inf_helper(c)) // this will consume the inf if it succeeds
        {
            // do nothing, peek_inf did all the work for us
            // (such as it is)
        }
        else {
            assert(IonTokenConstsX.isValidExtendedSymbolCharacter(c));

            // if it's not +/- inf then we'll just read the characters normally
            while (IonTokenConstsX.isValidExtendedSymbolCharacter(c)) {
                c = read_char();
            }
            unread_char(c);
        }
        if (sp != null) {
            sp.markEnd(0);
        }
        return;
    }
    protected void load_symbol_operator(StringBuilder sb) throws IOException
    {
        int c = read_char();

        // lookahead for +inf and -inf
        if ((c == '+' || c == '-') && peek_inf_helper(c)) { // this will consume the inf if it succeeds
            sb.append((char)c);
            sb.append("inf");
        }
        else {
            assert(IonTokenConstsX.isValidExtendedSymbolCharacter(c));

            // if it's not +/- inf then we'll just read the characters normally
            while (IonTokenConstsX.isValidExtendedSymbolCharacter(c)) {
                sb.append((char)c);
                c = read_char();
            }
            unread_char(c);
        }

        return;
    }
    private final void skip_single_quoted_string(SavePoint sp) throws IOException
    {
        int c;

        // the position should always be correct here
        // since there's no reason to lookahead into a
        // quoted symbol

        for (;;) {
            c = read_char();
            switch (c) {
            case -1: unexpected_eof();
            case '\'':
                if (sp != null) {
                   sp.markEnd(-1);
                }
                return;
            case '\\':
                c = read_char();
                if (c == '\\') {
                    c = read_char();
                }
                break;
            }
        }
    }
    protected int load_single_quoted_string(StringBuilder sb, boolean is_clob) throws IOException
    {
        int c;

        for (;;) {
            c = read_char();
            switch (c) {
            case IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE:
                continue;
            case -1:
            case '\'':
                return c;
            case '\n':
                bad_token(c);
            case '\\':
                c = read_char();
                c = read_escaped_char_content_helper(c, is_clob);
                break;
            default:
                if (!is_clob && !IonTokenConstsX.is7bitValue(c)) {
                    c = read_utf8_sequence(c);
                }
            }

            if (!is_clob) {
                if (IonUTF8.needsSurrogateEncoding(c)) {
                    sb.append(IonUTF8.highSurrogate(c));
                    c = IonUTF8.lowSurrogate(c);
                }
            }
            else if (IonTokenConstsX.is8bitValue(c)) {
                bad_token(c);
            }
            sb.append((char)c);
        }
    }

    private void skip_double_quoted_string(SavePoint sp) throws IOException
    {
        skip_double_quoted_string_helper();
        if (sp != null) {
            sp.markEnd(-1);
        }
    }
    private final void skip_double_quoted_string_helper() throws IOException
    {
        int c;
        for (;;) {
            c = read_char();
            switch (c) {
            case -1:
                unexpected_eof(); // throws
            case '\n':
                bad_token(c); // throws
            case '"':
                return;
            case '\\':
                c = read_char();
                if (c == '\\') {
                    // we don't want the escaped slash to be re-escaped
                    c = read_char();
                }
                break;
            }
        }
    }

    protected int load_double_quoted_string(StringBuilder sb, boolean is_clob) throws IOException
    {
        int c;

        for (;;) {
            c = read_char();
            switch (c) {
            case IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE:
                continue;
            case -1:
            case '"':
                return c;
            case '\r':
            case '\n':
                bad_token(c);
            case '\\':
                c = read_char_escaped(c, is_clob);
                break;
            default:
                if (!is_clob && !IonTokenConstsX.is7bitValue(c)) {
                    c = read_utf8_sequence(c);
                }
                break;
            }

            if (!is_clob) {
                if (IonUTF8.needsSurrogateEncoding(c)) {
                    sb.append(IonUTF8.highSurrogate(c));
                    c = IonUTF8.lowSurrogate(c);
                }
            }
            sb.append((char)c);
        }
    }
    protected int read_double_quoted_char(boolean is_clob) throws IOException
    {
        int c = read_char();

        switch (c) {
        case '"':
            unread_char(c);
            c = IonTokenConstsX.STRING_TERMINATOR;
            break;
        case -1:
            break;
        case '\\':
            c = read_char_escaped(c, is_clob);
            break;
        default:
            if (!is_clob && !IonTokenConstsX.is7bitValue(c)) {
                c = read_utf8_sequence(c);
            }
            break;
        }

        return c;
    }

    private void skip_triple_quoted_string(SavePoint sp) throws IOException
    {
        // starts AFTER the 3 quotes have been consumed
        int c;
        for (;;) {
            c = read_char();
            switch (c) {
            case -1:
                unexpected_eof();
            case '\'':
                c = read_char();
                if (c == '\'') { // 2nd quote
                    c = read_char(); // possibly the 3rd
                    if (sp != null) {
                        sp.markEnd(-3);
                    }
                    if (c == '\'') { // it is the 3rd quote - end of this segment
                        c = skip_over_whitespace();
                        if (c == '\'' && is_2_single_quotes_helper()) {
                            // there's another segment so read the next segment as well
                            break;
                        }
                        // end of last segment
                        unread_char(c);
                        return;
                    }
                }
                break;
            case '\\':
                c = read_char();
                if (c == '\\') {
                    // we don't want the escaped slash to be re-escaped
                    c = read_char();
                }
                break;
            }
        }
    }
    protected int load_triple_quoted_string(StringBuilder sb, boolean is_clob) throws IOException {
        int c;

        for (;;) {
            c = read_triple_quoted_char(is_clob);
            switch(c) {
            case IonTokenConstsX.STRING_TERMINATOR:
            case UnifiedInputStreamX.EOF:
                return c;
            case IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE:
                continue;
            default:
                break;
            }
            // if this isn't a clob we need to decode UTF8 and
            // handle surrogate encoding (otherwise we don't care)
            if (!is_clob) {
                if (IonUTF8.needsSurrogateEncoding(c)) {
                    sb.append(IonUTF8.highSurrogate(c));
                    c = IonUTF8.lowSurrogate(c);
                }
            }
            sb.append((char)c);
        }
    }
    protected int load_triple_quoted_string(byte[] buffer, int offset, int len, boolean is_clob) throws IOException {
        int c;
        int orig_offset = offset;
        int limit = offset + len;

        while (_utf8_pretch_byte_count > 0 && offset < limit) {
            _utf8_pretch_byte_count--;
            buffer[offset++] = (byte)((_utf8_pretch_bytes >> (_utf8_pretch_byte_count*8)) & 0xff);
        }
        while (offset < limit) {
            c = read_triple_quoted_char(is_clob);
            if (c < 0) {
                if (c == -1) break;
                if (c == IonTokenConstsX.STRING_TERMINATOR) {
                    break;
                }
                if (c == IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE) {
                    continue;
                }
                error("unexpected escape return ["+c+"] loading long string");
            }
            // end of loop - append whatever character we read
            offset = load_quoted_char_in_bytes(c, buffer, offset, limit, is_clob);
        }
        return offset - orig_offset;
    }
    protected int read_triple_quoted_char(boolean is_clob) throws IOException
    {
        int c = read_char();
        switch (c) {
        case '\'':
            if (is_2_single_quotes_helper()) {
                c = skip_over_whitespace();
                if (c == '\'' && is_2_single_quotes_helper()) {
                    // there's another segment so read the next segment as well
                    // since we're now just before char 1 of the next segment
                    // loop again, but don't append this char
                    return IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE;
                }
                // end of last segment - we're done (although we read a bit too far)
                if (c == IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE) {
                    // empty escape is really a two character sequence
                    // so we'll unread the 2nd character
                    unread_char('\n');
                    // and fake us up the 1st character
                    c = '\\';
                }
                unread_char(c);
                c = IonTokenConstsX.STRING_TERMINATOR;
            }
            break;
        case '\r':
            // in triple quoted strings \r\n is really just \n
            // we do this before escape decoding to avoid removing
            // an escaped '\r'
            int c2 = read_char();
            if (c2 != '\n') {
                unread_char(c2);
            }
            else {
                c = c2;
            }
            break;
        case '\\':
            c = read_char_escaped(c, is_clob);
            break;
        case IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE:
            break;
        case -1:
            break;
        default:
            if (!is_clob && !IonTokenConstsX.is7bitValue(c)) {
                c = read_utf8_sequence(c);
            }
            break;
        }

        return c;
    }
    private final int load_quoted_char_in_bytes(int c, byte[] buffer, int offset, int limit, boolean is_clob) {

        if (is_clob) {
            if (!IonTokenConstsX.is8bitValue(c)) {
                String message = "invalid character ["
                                + IonTextUtils.printCodePointAsString(c)
                                + "] encounterd in CLOB";
                error(message);
            }
        }
        else {
            int len = IonUTF8.getUTF8ByteCount(c);
            if (len > 1) {
                _utf8_pretch_bytes =  IonUTF8.packBytesAfter1(c, len);
                _utf8_pretch_byte_count = len - 1;
                c = IonUTF8.getByte1Of2(c);
            }
        }
        buffer[offset++] = (byte)(c & 0xff);
        return offset;
    }

    protected void skip_over_lob(int lobToken, SavePoint sp) throws IOException {
        switch(lobToken) {
        case IonTokenConstsX.TOKEN_STRING_DOUBLE_QUOTE:
            skip_double_quoted_string(sp);
            break;
        case IonTokenConstsX.TOKEN_STRING_TRIPLE_QUOTE:
            skip_triple_quoted_string(sp);
            break;
        case IonTokenConstsX.TOKEN_OPEN_DOUBLE_BRACE:
            skip_over_blob(sp);
            break;
        default:
            error("unexpected token "+IonTokenConstsX.getTokenName(getToken())+" encountered for lob content");
        }
    }
    protected void load_clob(int lobToken, StringBuilder sb) throws IOException
    {
        switch(lobToken) {
        case IonTokenConstsX.TOKEN_STRING_DOUBLE_QUOTE:
            load_double_quoted_string(sb, true);
            break;
        case IonTokenConstsX.TOKEN_STRING_TRIPLE_QUOTE:
            load_triple_quoted_string(sb, true);
            break;
        case IonTokenConstsX.TOKEN_OPEN_DOUBLE_BRACE:
            load_blob(sb);
            break;
        default:
            error("unexpected token "+IonTokenConstsX.getTokenName(getToken())+" encountered for lob content");
        }
    }
    private final int read_char_escaped(int c, boolean is_clob) throws IOException
    {
        for (;;) {
            if (c == IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE) {
                // loop again, we don't want empty escape chars
                c = read_char();
                continue;
            }
            if (c == '\\') {
                c = read_char();
                c = read_escaped_char_content_helper(c, is_clob);
                if (c == IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE) {
                    // loop again, we don't want empty escape chars
                    c = read_char();
                    continue;
                }
                if (c == IonTokenConstsX.ESCAPE_NOT_DEFINED) bad_escape_sequence();
            }
            else if (!is_clob && !IonTokenConstsX.is7bitValue(c)) {
                c = read_utf8_sequence(c);
            }
            break; // at this point we have a post-escaped character to return to the caller
        }
        if (c == -1) return c;
        if (is_clob && !IonTokenConstsX.is8bitValue(c)) {
            error("invalid character ["+ IonTextUtils.printCodePointAsString(c)+"] in CLOB");
        }
        return c;
    }
    private final int read_utf8_sequence(int c) throws IOException
    {
        assert(!IonTokenConstsX.is7bitValue(c)); // this should have the high order bit set
        int len = IonUTF8.getUTF8LengthFromFirstByte(c);
        int b2, b3, b4;
        switch (len) {
        case 1:
            break;
        case 2:
            b2 = read_char();
            c = IonUTF8.twoByteScalar(c, b2);
            break;
        case 3:
            b2 = read_char();
            b3 = read_char();
            c = IonUTF8.threeByteScalar(c, b2, b3);
            break;
        case 4:
            b2 = read_char();
            b3 = read_char();
            b4 = read_char();
            c = IonUTF8.fourByteScalar(c, b2, b3, b4);
            break;
        default:
            error("invalid UTF8 starting byte");
        }
        return c;
    }

    private void skip_over_blob(SavePoint sp) throws IOException
    {
        int c = skip_over_lob_whitespace();
        for (;;) {
            if (c == UnifiedInputStreamX.EOF) break;
            if (c == '}') break;
            c = skip_over_lob_whitespace();
        }
        if (sp != null) {
            // we don't care about these last 2 closing curly braces
            // but we may have seen one of them already
            int offset = (c == '}') ? -1 : 0;
            sp.markEnd(offset);
        }
        // did we hit EOF or the first '}' ?
        if (c != '}') unexpected_eof();
        c = read_char();
        if (c != '}') {
            String message = "improperly closed BLOB, "
                           + IonTextUtils.printCodePointAsString(c)
                           + " encountered when '}' was expected";
            error(message);
        }
        if (sp != null) {
            sp.markEnd();
        }
        return;
    }
    protected void load_blob(StringBuilder sb) throws IOException {
        int c;

        for (;;) {
            c = read_base64_byte();
            if (c == UnifiedInputStreamX.EOF) {
                break;
            }
            sb.append(c);
        }
        // did we hit EOF or the first '}' ?
        if (_stream.isEOF()) unexpected_eof();

        c = read_char();
        if (c != '}') {
            String message = "improperly closed BLOB, "
                           + IonTextUtils.printCodePointAsString(c)
                           + " encountered when '}' was expected";
            error(message);
        }
        return;
    }

    private final int read_escaped_char_content_helper(int c1, boolean is_clob) throws IOException
    {
        if (!IonTokenConstsX.isValueEscapeStart(c1)) {
            bad_escape_sequence(c1);
        }
        int c2 = IonTokenConstsX.escapeReplacementCharacter(c1);
        switch (c2) {
        case IonTokenConstsX.ESCAPE_NOT_DEFINED:
            assert(("invalid escape start characters (line "+((char)c1)+" should have been removed by isValid").length() < 0);
            break;
        case IonTokenConstsX.ESCAPE_REMOVES_NEWLINE2:
            int c3 = read_char();
            if (c3 != '\n') {
                unread_char(c3);
                break;
            }
            // fall through to regular newline
        case IonTokenConstsX.ESCAPE_REMOVES_NEWLINE:
            c2 = IonTokenConstsX.EMPTY_ESCAPE_SEQUENCE;
            break;
        case IonTokenConstsX.ESCAPE_LITTLE_U:
            if (is_clob) {
                bad_escape_sequence(c2);
            }
            c2 = read_hex_escape_sequence_value(4);
            break;
        case IonTokenConstsX.ESCAPE_BIG_U:
            if (is_clob) {
                bad_escape_sequence(c2);
            }
            c2 = read_hex_escape_sequence_value(8);
            break;
        case IonTokenConstsX.ESCAPE_HEX:
            c2 = read_hex_escape_sequence_value(2);
            break;
        }
        return c2;
    }
    private final int read_hex_escape_sequence_value(int len) throws IOException
    {
        int hexchar = 0;
        while (len > 0) {
            len--;
            int c = read_char();
            int d = IonTokenConstsX.hexDigitValue(c);
            if (d < 0) return -1;
            hexchar = (hexchar << 4) + d;
        }
        if (len > 0) {
            String message = "invalid hex digit ["
                + IonTextUtils.printCodePointAsString(hexchar)
                + "] in escape sequence";
            error(message);
        }
        return hexchar;
    }

    public final int read_base64_byte() throws IOException
    {
        int b;
        if (_base64_prefetch_count < 1) {
            b = read_base64_byte_helper();
        }
        else {
            b = (_base64_prefetch_stack & 0xff);
            _base64_prefetch_stack >>= 8;
            _base64_prefetch_count--;
        }
        return b;
    }
    private final int read_base64_byte_helper() throws IOException
    {
        // if there's any data left to read (the normal case)
        // we'll read 4 characters off the input source and
        // generate 1-3 bytes to return to the user.  That
        // will be 1 byte returned immediately and 0-2 bytes
        // put on the _binhex_stack to return later

        int c = skip_over_lob_whitespace();
        if (c == UnifiedInputStreamX.EOF || c == '}') {
            // we'll figure how which is which by check the stream for eof
            return UnifiedInputStreamX.EOF;
        }

        int c1 = read_base64_getchar_helper(c);
        int c2 = read_base64_getchar_helper();
        int c3 = read_base64_getchar_helper();
        int c4 = read_base64_getchar_helper();

        int b1, len = decode_base64_length(c1, c2, c3, c4);

        _base64_prefetch_stack = 0;
        _base64_prefetch_count = len - 1;
        switch (len) {
        default:
            throw new IonReaderTextTokenException("invalid binhex sequence encountered at offset"+input_position());
        case 3:
            int b3  = decode_base64_byte3(c1, c2, c3, c4);
            _base64_prefetch_stack = (b3 << 8) & 0xff00;
            // fall through
        case 2:
            int b2  = decode_base64_byte2(c1, c2, c3, c4);
            _base64_prefetch_stack |= (b2 & 0xff);
            // fall through
        case 1:
            b1 = decode_base64_byte1(c1, c2, c3, c4);
            // fall through
        }
        return b1;
    }
    private final int read_base64_getchar_helper(int c) throws IOException {
        assert( ! (c == UnifiedInputStreamX.EOF || c == '}') );

        if (c == UnifiedInputStreamX.EOF || c == '}') {
            return UnifiedInputStreamX.EOF;
        }
        if (c == BASE64_TERMINATOR_CHAR) {
            error("invalid base64 image - excess terminator characters ['=']");
        }
        return read_base64_getchar_helper2(c);
    }
    private final int read_base64_getchar_helper() throws IOException {
        int c = skip_over_lob_whitespace();
        if (c == UnifiedInputStreamX.EOF || c == '}') {
            error("invalid base64 image - too short");
        }
        return read_base64_getchar_helper2(c);
    }
    private final int read_base64_getchar_helper2(int c) throws IOException {
        assert( ! (c == UnifiedInputStreamX.EOF || c == '}') );

        if (c == BASE64_TERMINATOR_CHAR) {
            // we're using a new EOF here since the '=' is in range
            // of 0-63 (6 bits) and we don't want to confuse it with
            // the normal EOF
            return BASE64_EOF;
        }
        int b = BASE64_CHAR_TO_BIN[c & 0xff];
        if (b == UnifiedInputStreamX.EOF || !IonTokenConstsX.is8bitValue(c)) {
            String message = "invalid character "
                           + Character.toString((char)c)
                           + " encountered in base64 value at "
                           + input_position();
            throw new IonReaderTextTokenException(message);
        }
        return b;
    }
    private final static int decode_base64_length(int c1, int c2, int c3, int c4) {
        int len = 3;
        if (c4 != BASE64_EOF)      len = 3;
        else if (c3 != BASE64_EOF) len = 2;
        else                       len = 1;
        return len;
    }
    private final static int decode_base64_byte1(int c1, int c2, int c3, int c4) {
        //extracted from Base64Encoder.java:
        // convert =  c1 << 18;    [6:1] + 18 => [24:19]
        // convert |= (c2 << 12);  [6:1] + 12 => [18:13]
        // b1 = (char)((convert & 0x00FF0000) >> 16);  [32:1] & 0x00FF0000 => [24:17] - 16 => [8:1]
        // byte1 uses the 6 bits in char1 + 2 highest bits (out of 6) from char2
        if (_debug) assert(decode_base64_length(c1, c2, c3, c4) >= 1);
        int b1 = (((c1 << 2) & 0xfc) | ((c2 >> 4) & 0x03));
        return b1;
    }
    private final static int decode_base64_byte2(int c1, int c2, int c3, int c4) {
        //convert |= (c2 << 12);  [6:1]+12 => [18:13]
        //convert |= (c3 << 6);   [6:1]+6  => [12:7]
        //b2 = (char)((convert & 0x0000FF00) >> 8); [32:1] & 0x0000FF00 => [16:9] - 8 => [8:1]
        // [18:13] - 8 -> [10:5] or [6:5] from c2
        // [12:7] - 8 -> [4:-1] or [6:3] - 2 from c3
        //byte2 uses 4 low bits from c2 and 4 high bits from c3
        if (_debug) assert(decode_base64_length(c1, c2, c3, c4) >= 2);
        int b2 = (((c2 << 4) & 0xf0) | ((c3 >> 2) & 0x0f)) & 0xff;
        return b2;
    }
    private final static int decode_base64_byte3(int c1, int c2, int c3, int c4) {
        // convert |= (c3 << 6); [6:1]+6  => [12:7]
        // convert |= (c4 << 0); [6:1]+9  => [6:1]
        // b3 = (char)((convert & 0x000000FF) >> 0);
        // b3 uses low 2 bits from c3 and all 6 bits of c4
        if (_debug) assert(decode_base64_length(c1, c2, c3, c4) >= 3);
        int b3 = (((c3 & 0x03) << 6) | (c4 & 0x3f)) & 0xff;
        return b3;
    }

    protected void save_point_start(SavePoint sp) throws IOException
    {
        assert(sp != null && sp.isClear());
        long line_number = _line_count;
        long line_start = _line_starting_position;
        sp.start(line_number, line_start);
    }
    protected void save_point_activate(SavePoint sp) throws IOException
    {
        assert(sp != null && sp.isDefined());
        long line_number = _line_count;
        long line_start  = _line_starting_position;
        // this will set the "restore" (aka prev) line and start offset so
        // that when we pop the save point we'll get the correct line & char
        _stream._save_points.savePointPushActive(sp, line_number, line_start);
        _line_count = sp.getStartLineNumber();
        _line_starting_position = sp.getStartLineStart();
    }
    protected void save_point_deactivate(SavePoint sp) throws IOException
    {
        assert(sp != null && sp.isActive());

        _stream._save_points.savePointPopActive(sp);
        _line_count = sp.getPrevLineNumber();
        _line_starting_position = sp.getPrevLineStart();
    }

    private final void error(String message)
    {
        String message2 = message + input_position();
        throw new IonReaderTextTokenException(message2);
    }
    private final void unexpected_eof()
    {
        String message = "unexpected EOF encountered "+input_position();
        throw new UnexpectedEofException(message);
    }
    private final void bad_escape_sequence()
    {
        String message = "bad escape character encountered "+input_position();
        throw new IonReaderTextTokenException(message);
    }
    private final void bad_escape_sequence(int c)
    {
        String message = "bad escape character '"+IonTextUtils.printCodePointAsString(c)+"' encountered "+input_position();
        throw new IonReaderTextTokenException(message);
    }
    private final void bad_token_start(int c)
    {
        String message = "bad character ["+c+", "+IonTextUtils.printCodePointAsString(c)+"] encountered where a token was supposed to start "+input_position();
        throw new IonReaderTextTokenException(message);
    }
    private final void bad_token(int c)
    {
        String charStr = IonTextUtils.printCodePointAsString(c);
        String message = "a bad character " + charStr + " was encountered "+input_position();
        throw new IonReaderTextTokenException(message);
    }
    static public class IonReaderTextTokenException extends IonException {
        private static final long serialVersionUID = 1L;
        IonReaderTextTokenException(String msg) {
            super(msg);
        }
    }
}