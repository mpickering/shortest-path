package shortestpath.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

class TransportTsvParser {
    private static final String DELIM_COLUMN = "\t";
    private static final String PREFIX_COMMENT = "#";

    void parse(String contents, RowConsumer rowConsumer) {
        try (BufferedReader reader = new BufferedReader(new StringReader(contents))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return;
            }

            String[] headers = normalizeHeaderLine(headerLine).split(DELIM_COLUMN, -1);
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.startsWith(PREFIX_COMMENT) || line.isBlank()) {
                    continue;
                }

                String[] fields = line.split(DELIM_COLUMN, -1);
                Map<String, String> fieldMap = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    if (i < fields.length) {
                        fieldMap.put(headers[i], fields[i]);
                    }
                }
                rowConsumer.accept(lineNumber, fieldMap);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse transport TSV contents", e);
        }
    }

    private String normalizeHeaderLine(String headerLine) {
        String normalized = headerLine;
        if (normalized.startsWith(PREFIX_COMMENT + " ")) {
            normalized = normalized.replace(PREFIX_COMMENT + " ", PREFIX_COMMENT);
        }
        if (normalized.startsWith(PREFIX_COMMENT)) {
            normalized = normalized.replace(PREFIX_COMMENT, "");
        }
        return normalized;
    }

    interface RowConsumer {
        void accept(int lineNumber, Map<String, String> fieldMap);
    }
}
