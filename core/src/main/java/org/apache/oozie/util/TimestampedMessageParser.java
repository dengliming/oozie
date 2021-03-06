/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oozie.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.oozie.service.Services;
import org.apache.oozie.service.XLogStreamingService;
import org.apache.oozie.util.LogLine.MATCHED_PATTERN;

  /**
 * Encapsulates the parsing and filtering of the log messages from a BufferedReader. It makes sure not to read in the entire log
 * into memory at the same time; at most, it will have two messages (which can be multi-line in the case of exception stack traces).
 * <p>
 * To use this class: Calling {@link TimestampedMessageParser#increment()} will tell the parser to read the next message from the
 * Reader. It will return true if there are more messages and false if not. Calling
 * {@link TimestampedMessageParser#getLastMessage()} and {@link TimestampedMessageParser#getLastTimestamp()} will return the last
 * message and timestamp, respectively, that were parsed when {@link TimestampedMessageParser#increment()} was called. Calling
 * {@link TimestampedMessageParser#processRemaining(java.io.Writer,org.apache.oozie.util.XLogStreamer)} will write the
 * remaining log messages to the given Writer.
 */
public class TimestampedMessageParser {

    static final String SYSTEM_LINE_SEPARATOR = System.getProperty("line.separator");
    protected BufferedReader reader;
    private LogLine nextLine = null;
    private String lastTimestamp = null;
    private XLogFilter filter;
    private boolean empty = false;
    private String lastMessage = null;
    private boolean patternMatched = false;
    public int count = 0;
    private Pattern splitPattern = null;

    /**
     * Creates a TimestampedMessageParser with the given BufferedReader and filter.
     *
     * @param reader The BufferedReader to get the log messages from
     * @param filter The filter
     */
    public TimestampedMessageParser(BufferedReader reader, XLogFilter filter) {
        this.reader = reader;
        this.filter = filter;
        if (filter == null) {
            filter = new XLogFilter();
        }
        filter.constructPattern();
        String regEx = XLogFilter.PREFIX_REGEX + filter.getFilterPattern().pattern();
        this.splitPattern = Pattern.compile(regEx);
    }


    /**
     * Causes the next message and timestamp to be parsed from the BufferedReader.
     *
     * @return true if there are more messages left; false if not
     * @throws IOException If there was a problem reading from the Reader
     */
    public boolean increment() throws IOException {
        if (empty) {
            return false;
        }

        StringBuilder message = new StringBuilder();
        if (nextLine == null) {     // first time only
            nextLine = parseNextLogLine();
            if (nextLine == null || nextLine.getLine() == null) {
                // reader finished
                empty = true;
                return false;
            }
        }
        lastTimestamp = parseTimestamp(nextLine);
        String nextTimestamp = null;
        while (nextTimestamp == null) {
            message.append(nextLine.getLine()).append(SYSTEM_LINE_SEPARATOR);
            nextLine = parseNextLogLine();
            if (nextLine != null && nextLine.getLine() != null) {
                // exit loop if we have a timestamp, continue if not
                nextTimestamp = parseTimestamp(nextLine);
            }
            else {                                          // reader finished
                empty = true;
                nextTimestamp = "";                         // exit loop
            }
        }

        lastMessage = message.toString();
        return true;
    }

    /**
     * Returns the timestamp from the last message that was parsed.
     *
     * @return the timestamp from the last message that was parsed
     */
    public String getLastTimestamp() {
        return lastTimestamp;
    }

    /**
     * Returns the message that was last parsed.
     *
     * @return the message that was last parsed
     */
    public String getLastMessage() {
        return lastMessage;
    }

    /**
     * Closes the Reader.
     *
     * @throws IOException if the reader can't be closed
     */
    public void closeReader() throws IOException {
        reader.close();
    }

    /**
     * Reads the next line from the Reader and checks if it matches the filter.
     * It can also handle multi-line messages (i.e. exception stack traces). If
     * it returns null, then there are no lines left in the Reader.
     *
     * @return LogLine
     * @throws IOException in case of an error in the Reader
     */
    protected LogLine parseNextLogLine() throws IOException {
        String line;
        LogLine logLine = new LogLine();
        while ((line = reader.readLine()) != null) {
            logLine.setLine(line);
            logLine.setLogParts(null);
            filter.splitLogMessage(logLine, splitPattern);
            // check the splits if logLine matches with the splitPattern
            // Otherwise, go with previous patternMatched value. This is needed
            // in parsing stack trace
            patternMatched = logLine.getMatchedPattern() == MATCHED_PATTERN.NONE ? patternMatched
                    : filter.splitsMatches(logLine);
            if (patternMatched) {
                if (filter.getLogLimit() != -1) {
                    if (logLine.getLogParts() != null) {
                        if (count >= filter.getLogLimit()) {
                            return null;
                        }
                        count++;
                    }
                }
                if (logLine.getLogParts() != null) {
                    if (filter.getEndDate() != null) {
                        // Ignore the milli second part
                        if (logLine.getLogParts().get(0).substring(0, 19).compareTo(filter.getFormattedEndDate()) > 0)
                            return null;
                    }
                }
                return logLine;
            }
        }
        logLine.setLine(null);
        return logLine;
    }

    /**
     * Parses the timestamp out of the passed in line. If there isn't one, it
     * returns null.
     *
     * @param logLine The LogLine to check
     * @return the timestamp of the line, or null
     */
    private String parseTimestamp(LogLine logLine) {
        String timestamp = null;
        if (logLine != null && logLine.getLogParts() != null && logLine.getLogParts().size() > 0) {
            timestamp = logLine.getLogParts().get(0);
        }
        return timestamp;
    }

    /**
     * Streams log messages to the passed in Writer, with zero bytes already
     * written
     *
     * @param writer the target writer
     * @param logStreamer the log streamer
     * @throws IOException in case of IO error
     */
    public void processRemaining(Writer writer, XLogStreamer logStreamer) throws IOException {
        while (increment()) {
            writer.write(StringEscapeUtils.escapeHtml4(lastMessage));
            if (logStreamer.shouldFlushOutput(lastMessage.length())) {
                writer.flush();
            }
        }
        writer.flush();
    }

    /**
     * Splits the log message into parts
     *
     * @param line the line to split
     * @return List of log parts
     */
    protected ArrayList<String> splitLogMessage(String line) {
        return filter.splitLogMessage(line);
    }


}
